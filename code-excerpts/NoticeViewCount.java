package mildo.notification.service;

// 발췌: 공지 읽음 처리 + 조회수 증가. 설명: docs/ops/troubleshooting.md §26, docs/ops/pitfalls.md C-6

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class NotificationInboxService {

    /**
     * 공지사항 읽음 처리 + 조회수 증가
     */
    @Transactional
    public void markNoticeAsRead(String email, Long noticeId) {
        User user = getUserByEmail(email);
        Notice notice = noticeRepository.findById(noticeId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTICE_NOT_FOUND));

        if (noticeReadRepository.existsByNoticeIdAndUserId(noticeId, user.getId())) {
            return;
        }

        // notice_reads.notice_id → notices FK. InnoDB 는 FK 검사로 notices 행에 공유 잠금(S)을 걸고,
        // 이 트랜잭션이 끝날 때까지 쥐고 있다.
        noticeReadRepository.save(NoticeRead.builder().notice(notice).user(user).build());

        incrementViewCountSafely(noticeId);
    }

    /**
     * 조회수 증가를 <b>읽음 처리가 커밋된 뒤</b> 독립 트랜잭션으로 호출하고 실패는 격리 (읽음 처리 롤백 방지).
     *
     * <p><b>커밋 뒤로 미루는 이유.</b> 같은 요청 안에서 바로 REQUIRES_NEW 로 그 행의 조회수를 UPDATE 하면
     * 새 트랜잭션은 배타 잠금(X)이 필요해 바깥 트랜잭션의 S 잠금을 기다리고, 바깥은 이 호출이 끝나야 끝난다.
     * DB 의 교착 탐지는 바깥을 붙잡은 게 애플리케이션 스레드라 이걸 못 잡고, 잠금 대기 한도(50초)까지 멈췄다가
     * 조회수 증가만 실패하고 풀렸다. 그동안 앱은 응답을 못 받아 같은 요청을 14번 다시 보냈다.
     * 커밋 뒤에는 잠금이 없어 바로 끝나고, 중복 INSERT 로 롤백된 요청은 조회수도 올리지 않는다.</p>
     */
    private void incrementViewCountSafely(Long noticeId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    incrementViewCountNow(noticeId);
                }
            });
            return;
        }
        incrementViewCountNow(noticeId);
    }

    private void incrementViewCountNow(Long noticeId) {
        try {
            noticeViewCountService.increment(noticeId);
        } catch (Exception e) {
            log.warn("공지 조회수 증가 실패 (읽음 처리는 계속): noticeId={}", noticeId, e);
        }
    }
}

/**
 * 공지 조회수 증가 전용 서비스 — 별도 빈으로 둬야 프록시를 거쳐 REQUIRES_NEW 가 적용된다(함정 C-1).
 */
class NoticeViewCountService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void increment(Long noticeId) {
        noticeRepository.incrementViewCount(noticeId);   // UPDATE notices SET view_count = view_count + 1
    }
}
