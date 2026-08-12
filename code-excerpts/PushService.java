package mildo.push.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import mildo.push.entity.PushLog;
import mildo.push.repository.DeviceTokenRepository;
import mildo.push.repository.PushLogRepository;
import mildo.push.type.PushCategory;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 푸시 발송 진입점 — <b>발췌</b>: 카테고리별 수신 동의 필터 + 야간 광고 차단 + 발송 기록.
 * (Expo/FCM 멀티캐스트 플럼빙, 무효 토큰 비활성화 등은 생략)
 *
 * <p><b>판정은 발사 지점이 아니라 여기(그리고 토큰 조회 쿼리)에서 한다.</b>
 * 발사 지점 11곳이 각자 토글을 검사하면 하나만 빠져도 정책 구멍이다. 호출부는
 * {@code sendToUser(PushCategory.CHAT, ...)}처럼 카테고리만 선언하고, 수신 동의(마스터 AND 세부)는
 * 토큰 조회 쿼리({@code findActiveTokensForCategory})가 걸러낸다 —
 * 필터를 통과한 토큰이 없으면 발송 자체가 없던 일이 되고, 그 사실도 push_log에 남는다.</p>
 *
 * <p>MARKETING만 조회 조건이 다르다 — 서비스 알림은 "거부하지 않으면 발송"이지만
 * 광고는 <b>별도 옵트인</b>이 있어야 하고(정보통신망법 §50), 야간에는 옵트인이 있어도 막는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final PushLogRepository pushLogRepository;

    /** 야간 광고성 발송 금지 기준 시간대 — KST(정보통신망법 §50: 21시~익일 08시 별도 동의 필요). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int NIGHT_AD_START_HOUR = 21; // 21:00 KST부터
    private static final int NIGHT_AD_END_HOUR = 8;    // 08:00 KST 전까지 차단(08:00부터 허용)

    /**
     * 야간(21:00~08:00 KST) 광고성(MARKETING) 발송 차단 여부.
     * 서비스성(거래·알림)은 광고가 아니므로 24시간 발송 대상(항상 false).
     */
    private boolean isNightAdBlocked(PushCategory category) {
        if (category != PushCategory.MARKETING) {
            return false;
        }
        int hour = LocalTime.now(KST).getHour();
        return hour >= NIGHT_AD_START_HOUR || hour < NIGHT_AD_END_HOUR;
    }

    /**
     * 특정 사용자들에게 푸시 발송 (카테고리별 알림 설정 필터링).
     * sendToAll / sendToUser 도 같은 구조다 — 조회 쿼리의 대상 조건만 다르다.
     */
    @Async
    public void sendToUsers(PushCategory category, List<Long> userIds, String title, String body, Map<String, String> data) {
        if (isNightAdBlocked(category)) {
            log.info("[Push] 야간(21~08 KST) 광고성 발송 차단 — sendToUsers (category=MARKETING)");
            return;
        }
        List<String> tokens = (category == PushCategory.MARKETING)
                ? deviceTokenRepository.findActiveMarketingTokensByUserIds(userIds)
                : deviceTokenRepository.findActiveTokensForCategoryByUserIds(userIds, category.name());
        if (tokens.isEmpty()) {
            // 대상 0건도 기록한다 — "토글에 걸려 안 나갔다"가 로그로 증명 가능해야
            // 세분화 토글의 실수신 검증(requested=0)이 된다.
            log.info("[Push] No eligible tokens for users: {} (category={})", userIds, category);
            savePushLog("USERS", singleUserId(userIds), category.name(), title, body, emptyResult());
            return;
        }

        SendResult result = sendMulticast(tokens, title, body, data);
        savePushLog("USERS", singleUserId(userIds), category.name(), title, body, result);
    }

    // ========== 발송 플럼빙(생략) ==========
    // sendMulticast: Expo 토큰("ExponentPushToken[" 프리픽스)과 FCM 토큰을 분리해
    // 각각 배치 발송(100/500건)하고 성공/실패 카운트를 집계한다.
    // FCM UNREGISTERED/INVALID_ARGUMENT 응답을 받은 토큰은 즉시 비활성화한다.

    private SendResult sendMulticast(List<String> tokens, String title, String body, Map<String, String> data) {
        /* 생략 */
        return new SendResult();
    }

    // ========== push_log 기록 ==========

    /** 발송 결과 집계 홀더(내부용). */
    private static class SendResult {
        int requested;
        int success;
        int fail;
        String errorSummary;
    }

    private SendResult emptyResult() {
        return new SendResult(); // requested/success/fail=0 — 대상 0건 기록용
    }

    private Long singleUserId(List<Long> userIds) {
        return (userIds != null && userIds.size() == 1) ? userIds.get(0) : null;
    }

    /** 발송 기록 저장. 실패해도 발송 흐름에 영향 없도록 예외 삼킴. */
    private void savePushLog(String targetType, Long userId, String category,
                             String title, String body, SendResult r) {
        try {
            pushLogRepository.save(PushLog.builder()
                    .targetType(targetType)
                    .userId(userId)
                    .category(category)
                    .title(truncate(title, 200))
                    .body(truncate(body, 500))
                    .requestedCount(r.requested)
                    .successCount(r.success)
                    .failCount(r.fail)
                    .errorSummary(r.errorSummary)
                    .build());
        } catch (Exception e) {
            log.warn("[Push] push_log 저장 실패: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        // 4바이트 문자(이모지 등)는 DB 커넥션 charset(utf8mb3)에서 저장 실패를 유발하므로 로그에선 제거한다.
        // (실제 푸시 알림 본문에는 이모지가 그대로 나간다 — 여기 제거는 push_log 저장용일 뿐)
        String cleaned = s.replaceAll("[\\x{10000}-\\x{10FFFF}]", "");
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }
}
