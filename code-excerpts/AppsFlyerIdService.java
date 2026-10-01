package mildo.ad.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import mildo.ad.event.ConversionEvent;
import mildo.auth.entity.User;
import mildo.auth.repository.UserRepository;
import mildo.persona.entity.Persona;
import mildo.persona.repository.PersonaRepository;

import java.time.LocalDateTime;

/**
 * 디바이스 광고 식별자(AppsFlyer ID) 등록 + <b>늦게 들어온 회원의 가입 전환 보내기</b>.
 * 설명: docs/troubleshooting.md §25
 *
 * <p><b>문제.</b> 가입 전환({@code af_complete_registration})은 가입이 끝나는 순간(페르소나 생성) 서버가
 * 광고 측정 서비스로 보낸다(S2S). 보낼 대상 기기를 이 식별자로 지정하는데, 그 순간 식별자가 없으면
 * 건너뛰고 끝이었다. 앱은 식별자를 로그인·세션 복구 때만 넘기고, SDK 가 아직 값을 못 줬으면 조용히
 * 건너뛴다(재시도 없음, 다음 실행 때 다시 시도). 가입 도중엔 비어 있다가 다음 실행 때 들어오는 회원이 많아
 * 가입 완료 174명 중 식별자 보유가 81명이었다. 안드로이드·iOS 가 똑같이 절반쯤이라 iOS 추적 동의(ATT)와는 무관했다.</p>
 *
 * <p><b>규칙.</b> 식별자가 <b>처음</b> 채워진 요청에서, 그 회원이 이미 가입을 마쳤고 완료가
 * {@value #LATE_REGISTRATION_WINDOW_DAYS}일 안이면 그때 가입 전환을 보낸다.</p>
 * <pre>
 * 가입 완료 뒤 처음 들어옴 (7일 안)   → 보낸다
 * 가입 중(페르소나 없음)에 들어옴      → 안 보낸다 — 가입 완료 때 원래 경로가 보낸다
 * 이미 있던 식별자가 바뀜(재설치)      → 값만 바꾼다 — 가입 완료 때 이미 나갔다
 * 같은 요청이 동시에 두 번             → 조건부 UPDATE 를 이긴 한쪽만 보낸다
 * 가입 완료가 7일보다 오래됨           → 안 보낸다 — 오늘 가입한 것처럼 광고 성과에 섞이지 않게
 * </pre>
 *
 * <p><b>「처음」을 조회로 판정하지 않는 이유.</b> 「읽어서 비어 있으면 저장」은 같은 회원의 요청 두 개가 동시에
 * 비어 있음을 읽고 둘 다 전환을 보낼 수 있다. {@code UPDATE … WHERE appsflyer_id IS NULL} 한 문장은
 * DB 가 행 잠금으로 하나만 통과시키므로, 영향받은 행 수(1/0)가 곧 「내가 처음 채웠다」의 답이다.
 * 컬럼·플래그를 새로 두지 않고 기존 값의 전이(비어 있음 → 있음)를 한 번뿐인 사건으로 쓴다.</p>
 *
 * <p>전환 발송은 {@code AFTER_COMMIT} 리스너가 별도 스레드에서 한다 — 이 트랜잭션이 커밋돼 식별자가
 * 저장된 뒤에 읽으므로 대상 기기를 찾을 수 있고, 롤백되면 보내지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppsFlyerIdService {

    /** 늦은 가입 전환을 보내는 기한(가입 완료 = 페르소나 생성 시각 기준). */
    static final int LATE_REGISTRATION_WINDOW_DAYS = 7;

    static final String REGISTRATION_EVENT = "af_complete_registration";

    private final UserRepository userRepository;
    private final PersonaRepository personaRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void register(String email, String afId) {
        if (afId == null || afId.isBlank()) {
            return;
        }
        User user = userRepository.findByEmail(email).orElseThrow();
        if (afId.equals(user.getAppsflyerId())) {
            return;
        }
        if (user.getAppsflyerId() != null && !user.getAppsflyerId().isBlank()) {
            // 식별자 교체(재설치 등). 전환은 이미 처리된 회원이라 값만 바꾼다.
            user.setAppsflyerId(afId);
            return;
        }
        // 처음 채우는 경우 — 조건부 UPDATE 로 「처음」을 판정한다(동시 요청이면 한쪽만 1).
        if (userRepository.fillAppsflyerIdIfEmpty(user.getId(), afId) == 0) {
            return;
        }
        sendLateRegistrationIfDue(user.getId());
    }

    private void sendLateRegistrationIfDue(Long userId) {
        Persona persona = personaRepository.findActiveByUserId(userId).orElse(null);
        if (persona == null || persona.getCreatedAt() == null) {
            return;
        }
        if (persona.getCreatedAt().isBefore(LocalDateTime.now().minusDays(LATE_REGISTRATION_WINDOW_DAYS))) {
            log.info("[AdTracking] 늦은 가입 전환 기한 지남 — 보내지 않음 userId={}", userId);
            return;
        }
        eventPublisher.publishEvent(ConversionEvent.of(userId, REGISTRATION_EVENT));
        log.info("[AdTracking] 늦은 가입 전환 발행 userId={}", userId);
    }
}

/*
 * UserRepository 에 추가한 쿼리:
 *
 *   @Modifying
 *   @Query("UPDATE User u SET u.appsflyerId = :afId WHERE u.id = :userId "
 *        + "AND (u.appsflyerId IS NULL OR u.appsflyerId = '')")
 *   int fillAppsflyerIdIfEmpty(@Param("userId") Long userId, @Param("afId") String afId);
 */
