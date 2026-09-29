package mildo.push.service;

import com.google.firebase.messaging.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import mildo.notification.service.NotificationInboxService;
import mildo.push.entity.PushLog;
import mildo.push.repository.DeviceTokenRepository;
import mildo.push.repository.PushLogRepository;
import mildo.push.repository.UserToken;
import mildo.push.type.PushCategory;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 푸시 발송 진입점 — <b>발췌</b>: 카테고리별 수신 동의 필터 + 야간 광고 차단 + 앱 아이콘 배지 + 발송 기록.
 * (Expo 배치 발송, 무효 토큰 비활성화, 관리자 테스트 발송, sendToAll/sendToUser 는 생략)
 *
 * <p><b>판정은 발사 지점이 아니라 여기(그리고 토큰 조회 쿼리)에서 한다.</b>
 * 발사 지점 11곳이 각자 토글을 검사하면 하나만 빠져도 정책 구멍이다. 호출부는
 * {@code sendToUser(PushCategory.CHAT, ...)}처럼 카테고리만 선언하고, 수신 동의(마스터 AND 세부)는
 * 토큰 조회 쿼리({@code findActiveTokensForCategoryByUserIds})가 걸러낸다 —
 * 필터를 통과한 토큰이 없으면 발송 자체가 없던 일이 되고, 그 사실도 push_log에 남는다.</p>
 *
 * <p>MARKETING만 조회 조건이 다르다 — 서비스 알림은 "거부하지 않으면 발송"이지만
 * 광고는 <b>별도 옵트인</b>이 있어야 하고(정보통신망법 §50), 야간에는 옵트인이 있어도 막는다.</p>
 *
 * <p><b>배지(2026-09-29 추가).</b> 조회 결과가 토큰 문자열 목록에서 {@code (userId, token)} 쌍으로
 * 바뀌었다 — 배지 숫자는 <b>토큰 주인</b>의 안 읽은 알림 수라서, 어느 토큰이 누구 것인지 알아야
 * 토큰별 숫자를 붙일 수 있다. 숫자를 세는 곳은 알림함 화면과 같은 함수 하나뿐이다.
 * 이 서비스는 {@code @Async} 라 호출자의 트랜잭션이 커밋되기 전에 돌 수 있다 — 그래서 알림을 저장하는
 * 쪽이 푸시 호출을 커밋 뒤로 미룬다({@code NotificationDispatch.java} 참고).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final PushLogRepository pushLogRepository;
    private final NotificationInboxService notificationInboxService;

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
     * 특정 사용자들에게 푸시 발송 (카테고리별 알림 설정 필터링 + 토큰별 배지).
     * sendToUser 도 같은 구조다. sendToAll 은 조회 조건만 다르고 <b>배지를 싣지 않는다</b>
     * — 유저별로 세면 전원 조회가 되기 때문이다.
     */
    @Async
    public void sendToUsers(PushCategory category, List<Long> userIds, String title, String body, Map<String, String> data) {
        if (isNightAdBlocked(category)) {
            log.info("[Push] 야간(21~08 KST) 광고성 발송 차단 — sendToUsers (category=MARKETING)");
            return;
        }
        List<UserToken> targets = (category == PushCategory.MARKETING)
                ? deviceTokenRepository.findActiveMarketingTokensByUserIds(userIds)
                : deviceTokenRepository.findActiveTokensForCategoryByUserIds(userIds, category.name());
        if (targets.isEmpty()) {
            // 대상 0건도 기록한다 — "토글에 걸려 안 나갔다"가 로그로 증명 가능해야
            // 세분화 토글의 실수신 검증(requested=0)이 된다.
            log.info("[Push] No eligible tokens for users: {} (category={})", userIds, category);
            savePushLog("USERS", singleUserId(userIds), category.name(), title, body, emptyResult());
            return;
        }

        SendResult result = sendMulticast(tokensOf(targets), title, body, data, badgesOf(targets));
        savePushLog("USERS", singleUserId(userIds), category.name(), title, body, result);
    }

    private List<String> tokensOf(List<UserToken> targets) {
        return targets.stream().map(UserToken::token).toList();
    }

    // ========== 앱 아이콘 배지 ==========

    /**
     * 토큰별 앱 아이콘 배지 숫자 = 그 토큰 주인의 <b>알림함 안 읽은 개수</b>.
     *
     * <p>앱이 꺼져 있으면 JS 가 안 돌아 숫자를 못 맞추므로 푸시가 숫자를 들고 가야 한다. 채팅처럼 알림함에
     * 안 쌓이는 푸시도 같은 숫자를 싣는다 — iOS 는 배지 키가 없으면 이전 숫자를 그대로 두지만,
     * 어느 푸시든 같은 기준이면 아이콘이 항상 알림함과 일치한다.
     * 한 유저가 기기를 여러 대 가져도 한 번만 센다({@code byUser} 캐시).</p>
     *
     * <p><b>세다가 실패한 유저는 배지 없이 보낸다</b> — 숫자 하나 때문에 푸시가 안 가면 안 된다.
     * 맵에 없는 토큰은 아래 발송 단계에서 배지 키 자체를 싣지 않는다.</p>
     */
    private Map<String, Integer> badgesOf(List<UserToken> targets) {
        Map<Long, Integer> byUser = new HashMap<>();
        Map<String, Integer> byToken = new HashMap<>();
        for (UserToken t : targets) {
            Integer badge = byUser.computeIfAbsent(t.userId(), this::unreadBadge);
            if (badge != null) {
                byToken.put(t.token(), badge);
            }
        }
        return byToken;
    }

    /**
     * 알림함 화면의 unread-count 와 <b>같은 함수</b>(공지 + 개인 알림)를 부른다.
     * 따로 세면 아이콘 숫자와 앱 안 숫자가 갈라진다.
     */
    private Integer unreadBadge(Long userId) {
        try {
            return (int) Math.min(notificationInboxService.getUnreadCount(userId), Integer.MAX_VALUE);
        } catch (Exception e) {
            log.warn("[Push] 배지 계산 실패 — 배지 없이 발송: userId={}, {}", userId, e.getMessage());
            return null;
        }
    }

    // ========== 발송 ==========

    /**
     * 멀티캐스트 발송 (Expo + FCM 분리). 발송 결과 카운트를 집계해 반환한다(push_log 기록용).
     * {@code badges} 는 토큰별 배지 숫자 — 없는 토큰은 배지 키를 안 싣는다.
     * (Expo 쪽은 메시지가 토큰마다 따로라 토큰별로 {@code badge} 를 넣기만 하면 된다 — 생략)
     */
    private SendResult sendMulticast(List<String> tokens, String title, String body, Map<String, String> data,
                                     Map<String, Integer> badges) {
        /* Expo/FCM 토큰 분리 → 각각 발송 → 성공/실패 합산 (생략) */
        List<String> errors = new ArrayList<>();
        int[] f = sendFcmMulticast(tokens, title, body, data, badges, errors);
        SendResult result = new SendResult();
        result.requested = tokens.size();
        result.success = f[0];
        result.fail = f[1];
        result.errorSummary = errors.isEmpty() ? null : truncate(String.join(",", errors), 500);
        return result;
    }

    /**
     * FCM 발송 (최대 500개씩). <b>멀티캐스트 한 통은 배지 숫자가 하나</b>라 같은 숫자끼리 묶어 보낸다.
     *
     * <p>토큰마다 한 통씩 보내면 호출이 토큰 수만큼 늘어난다. 그런데 한 번에 보내는 대상의 안 읽은 개수는
     * 몇 가지 값뿐이라, 숫자로 묶으면 호출 수가 거의 늘지 않는다. 배지 없는 토큰(계산 실패)은 null 묶음이다.</p>
     */
    private int[] sendFcmMulticast(List<String> tokens, String title, String body, Map<String, String> data,
                                   Map<String, Integer> badges, List<String> errors) {
        Map<Integer, List<String>> byBadge = new HashMap<>();
        for (String token : tokens) {
            byBadge.computeIfAbsent(badges.get(token), k -> new ArrayList<>()).add(token);
        }

        int success = 0;
        int fail = 0;
        for (Map.Entry<Integer, List<String>> group : byBadge.entrySet()) {
            for (List<String> batch : partition(group.getValue(), 500)) {
                try {
                    MulticastMessage message = buildMulticast(batch, title, body, data, group.getKey());
                    BatchResponse response = FirebaseMessaging.getInstance().sendEachForMulticast(message);
                    success += response.getSuccessCount();
                    fail += response.getFailureCount();
                    /* 실패 토큰 비활성화(UNREGISTERED/INVALID_ARGUMENT), 에러 코드 수집 — 생략 */
                } catch (FirebaseMessagingException e) {
                    log.error("[FCM Push] Failed to send multicast: {}", e.getMessage());
                    fail += batch.size();
                    errors.add(e.getMessage());
                }
            }
        }
        return new int[]{success, fail};
    }

    /**
     * FCM 멀티캐스트 메시지 빌드. {@code badge} 가 있으면 iOS {@code aps.badge}·
     * Android {@code notification_count} 로 싣고, null 이면 키를 아예 넣지 않는다.
     *
     * <p><b>안드로이드는 서버 값이 곧 표시값이다.</b> 삼성 One UI 는 앱이 넣는 숫자를 무시하고
     * 푸시의 {@code notification_count} 로 아이콘을 그린다 — 서버가 안 실으면 숫자가 안 맞는다.</p>
     */
    private MulticastMessage buildMulticast(List<String> tokens, String title, String body, Map<String, String> data,
                                            Integer badge) {
        AndroidNotification.Builder android = AndroidNotification.builder().setChannelId("default");
        Aps.Builder aps = Aps.builder().setSound("default");
        if (badge != null) {
            android.setNotificationCount(badge);
            aps.setBadge(badge);
        }
        return MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putAllData(data != null ? data : Map.of())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(android.build())
                        .build())
                .setApnsConfig(ApnsConfig.builder().setAps(aps.build()).build())
                .build();
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return out;
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
