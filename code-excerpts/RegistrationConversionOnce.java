package mildo.ad;

// 발췌: 가입 전환을 회원당 한 번만 보내기. 설명: docs/ops/troubleshooting.md §27, docs/ops/pitfalls.md B-4
// 앞선 보완(§25, AppsFlyerIdService.java)은 "식별자의 비어 있음 → 있음 전이는 회원당 한 번"에 기댔는데,
// 다른 요청이 엔티티 전체를 저장하면서 식별자를 옛 값(null)으로 되돌려 그 전이가 반복됐다.

import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// ─── 1. 회원 엔티티: 바뀐 칼럼만 UPDATE ─────────────────────────────────────────────

/**
 * <p><b>{@code @DynamicUpdate}.</b> 저장할 때 바뀐 칼럼만 UPDATE 한다. 전에는 칼럼 전체를 다시 썼기 때문에,
 * 앱 시작 때 거의 동시에 오는 요청 둘 중 하나(푸시 토큰 등록 — 마지막 접속 플랫폼만 바꿈)가 먼저 읽어 둔 회원을 저장하면서
 * 다른 요청이 그 사이 채운 {@code appsflyer_id} 를 옛 값(null)으로 덮었다. 그래서 앱을 켤 때마다 식별자가 지워지고,
 * 다음 실행에서 「처음 채움」으로 판단해 가입 전환을 또 보냈다. 회원 행은 여러 경로가 각자 다른 칼럼을 고치므로,
 * 칼럼 단위로만 쓰는 게 서로의 값을 지우지 않는 기본값이다.</p>
 */
@Entity
@DynamicUpdate
@Table(name = "users")
class User {

    @Column(name = "appsflyer_id")
    private String appsflyerId;

    /**
     * 가입 전환을 보낸 시각. 회원당 한 번만 보내기 위한 표시 — 식별자 전이 같은 부수 효과가 아니라
     * 이 의미만 가진 칼럼에 근거를 둔다.
     */
    @Column(name = "af_registration_sent_at")
    private LocalDateTime afRegistrationSentAt;
}

// ─── 2. 보낼 자격을 조건부 UPDATE 로 "차지" ───────────────────────────────────────

interface UserRepository {

    /**
     * 가입 전환 발송 자격을 차지한다 — 아직 보낸 적 없을 때만 1. 같은 회원의 전환 두 건이 동시에 와도 한쪽만 1을 받는다.
     * 호출부가 비동기 스레드라 트랜잭션을 여기서 연다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.afRegistrationSentAt = :now WHERE u.id = :userId AND u.afRegistrationSentAt IS NULL")
    int claimRegistrationConversion(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}

// ─── 3. 발송 직전 ──────────────────────────────────────────────────────────────────

class AdTrackingService {

    static final String REGISTRATION_EVENT = "af_complete_registration";

    void onConversion(ConversionEvent e) {
        User user = userRepository.findById(e.userId()).orElse(null);
        if (user == null || user.getAppsflyerId() == null) {
            return;
        }
        // 가입 전환은 회원당 한 번 — 보내기 전에 자격을 조건부 UPDATE 로 차지한다.
        // "읽어서 비었으면 보냄"이 아니라 한 문장으로 판정하므로 동시 요청에도 한 번.
        if (REGISTRATION_EVENT.equals(e.eventName())
                && userRepository.claimRegistrationConversion(e.userId(), LocalDateTime.now()) == 0) {
            log.info("[AdTracking] skip {} userId={} (이미 보냄)", e.eventName(), e.userId());
            return;
        }
        send(user, e);
    }
}

// 배포 시점엔 af_registration_sent_at 이 전원 NULL 이라 이미 보낸 회원도 "안 보냄"으로 보인다.
// 운영 로그에서 발송 기록이 있는 회원을 찾아 첫 발송 시각으로 미리 채운 뒤 열었다(IS NULL 조건 — 다시 돌려도 같은 결과).
