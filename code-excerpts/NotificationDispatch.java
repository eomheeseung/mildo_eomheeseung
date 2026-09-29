package mildo.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import mildo.auth.entity.User;
import mildo.auth.repository.UserRepository;
import mildo.notification.entity.UserNotification;
import mildo.notification.repository.UserNotificationRepository;
import mildo.push.service.PushService;
import mildo.push.type.PushCategory;

import java.util.HashMap;
import java.util.Map;

/**
 * 알림 1건 저장 → 푸시 발송 — <b>발췌</b>: {@code UserNotificationService.dispatch} 의 저장·푸시 부분과
 * 커밋 후 실행 헬퍼만. (알림 타입별 생성 메서드들, SSE 발송, 피드 푸시 억제 판정, 카테고리 매핑은 생략)
 *
 * <p><b>문제.</b> 앱 아이콘 배지는 푸시가 "안 읽은 알림 개수"를 싣고 간다({@code PushService.java}).
 * 그런데 푸시는 {@code @Async} 라 <b>다른 스레드</b>에서 돈다. 알림을 저장한 호출자 트랜잭션은
 * 아직 커밋 전일 수 있고, 그 사이 푸시 스레드가 새 커넥션으로 안 읽은 개수를 세면
 * <b>방금 저장한 알림이 보이지 않는다</b>. 이 엔티티는 IDENTITY 전략이라 {@code save} 순간 INSERT 는
 * 이미 나갔지만(쓰기 지연 없이 id 를 받아 온다) <b>커밋 전 행은 다른 트랜잭션에서 안 보인다.</b>
 * "쿼리가 나갔다"와 "남이 볼 수 있다"는 다른 시점이다. 결과는 <b>배지가 하나 모자라게</b> 뜨는 것이었다.</p>
 *
 * <p><b>해결.</b> 푸시 호출 자체를 트랜잭션 동기화의 {@code afterCommit} 콜백으로 미룬다.
 * 커밋이 끝난 뒤에야 {@code @Async} 작업이 제출되므로, 푸시 스레드가 셀 때는 행이 이미 보인다.
 * 부수 효과로 <b>롤백되면 푸시도 안 나간다</b> — 예전에는 알림함에 없는 알림의 푸시가 나갈 수 있었다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private final UserNotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final PushService pushService;

    /** 알림 타입별 생성 메서드는 모두 이런 모양이다 — 트랜잭션 안에서 dispatch 를 부른다. */
    @Transactional
    public void createMatchNotification(Long userId, Long matchId, String partnerName) {
        /* 문구·페이로드 구성 생략 */
        dispatch(userId, /* spec */ null);
    }

    private void dispatch(Long userId, NotificationSpec spec) {
        User user = userRepository.findById(userId).orElseThrow();

        UserNotification notification = UserNotification.builder()
                .user(user)
                .type(spec.type())
                .title(spec.title())
                .content(spec.content())
                /* actor·reference·피드 필드 생략 */
                .build();
        notificationRepository.save(notification);

        // 저장 후 부여된 알림 id 를 페이로드에 싣는다 (앱이 푸시를 눌러 읽음 처리할 때 쓴다).
        // FCM data 는 문자열만 허용(null 불가) → 없는 값은 키 자체를 넣지 않는다.
        Map<String, String> pushData = new HashMap<>(spec.pushData());
        pushData.put("notificationId", String.valueOf(notification.getId()));

        PushCategory category = pushCategoryOf(spec.type());
        // ★ 바로 부르지 않는다 — 커밋 뒤로 미룬다. (아래 afterCommit 주석)
        afterCommit(() -> pushService.sendToUser(category, userId, spec.title(), spec.content(), pushData));

        /* SSE 실시간 발송 — 생략 (같은 JVM 의 열린 연결로 보내는 것이라 배지 계산과 무관) */
    }

    /**
     * 알림 저장이 <b>커밋된 뒤에</b> 푸시를 보낸다(2026-09-29, 앱 아이콘 배지).
     *
     * <p>푸시는 @Async 라 저장 트랜잭션이 끝나기 전에 돌 수 있다. 그러면 배지로 세는 안 읽은 개수에
     * 방금 넣은 알림이 빠져 아이콘이 하나 모자라게 뜬다. 롤백되면 알림함에 없는 알림 푸시도 안 나간다.</p>
     *
     * <p>트랜잭션 밖에서 불리면(동기화 비활성) 기다릴 커밋이 없으므로 즉시 실행한다.</p>
     *
     * <p>⚠️ 다른 트랜잭션의 AFTER_COMMIT 리스너에서 <b>새 트랜잭션 없이</b> 여기로 들어오면 등록한 후처리가
     * 영영 안 돈다. 그 시점은 이미 바깥 트랜잭션의 커밋 후처리 단계라 동기화는 아직 활성으로 보이지만
     * 다시 afterCommit 을 부를 커밋이 오지 않는다. 지금 그 경로의 리스너는 전부 REQUIRES_NEW 라
     * 자기 트랜잭션의 커밋에서 정상 실행된다 — 새 리스너를 붙일 때 지켜야 할 조건이다.</p>
     */
    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** 알림 타입 → 푸시 세부 토글 카테고리. 매핑에 없는 타입은 TRANSACTIONAL(마스터 토글만 따름) — 생략 */
    private static PushCategory pushCategoryOf(String type) {
        return PushCategory.TRANSACTIONAL;
    }

    /** 단일 알림 생성+발송에 필요한 데이터 묶음 (필드 일부 생략). */
    private record NotificationSpec(String type, String title, String content, Map<String, String> pushData) {
    }
}
