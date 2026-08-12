package mildo.feed.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 게시물 댓글. 댓글과 대댓글이 한 테이블에 들어가며 {@link #parentId}로 구분한다.
 *
 * <p><b>깊이는 2단계로 고정한다.</b> {@code parentId}는 최상위 댓글만 가리킬 수 있다.
 * 무한 트리는 들여쓰기가 화면을 먹고 정렬·알림 규칙이 급격히 복잡해진다. 프론트가 대댓글에
 * 답글 버튼을 그리지 않지만 <b>API를 직접 부르면 뚫리므로 저장 시점에 막는다</b>
 * ({@code FeedCommentService.resolveParent}).</p>
 *
 * <p><b>삭제·숨김은 행을 지우지 않고 상태로 남긴다.</b> 대댓글이 달린 최상위 댓글을 물리 삭제하면
 * 스레드가 고아가 된다. 상태({@code ACTIVE/HIDDEN/DELETED})로 두면 자리는 유지한 채
 * "삭제된 댓글입니다"로 그릴 수 있고, 신고 처리·감사 추적도 원본이 남아 가능하다.</p>
 *
 * <p><b>좋아요 수는 컬럼으로 두지 않는다.</b> 게시물({@code interest_count})과 다른 선택인데,
 * 댓글은 한 화면에 20건 남짓이라 {@code feed_comment_like}를 한 번 묶어 세면 N+1이 나지 않는다.
 * 카운터 컬럼은 증감 누락으로 조용히 어긋나고 동시성 처리가 따라붙는다 — 원본을 세면 그 문제가 없다.</p>
 *
 * <p>작성자가 탈퇴하면 신고되지 않은 댓글은 {@code DELETED}로 파기된다
 * ({@code FeedWithdrawalPurger}) — 게시물과 같은 규칙이다.</p>
 */
@Entity
@Table(name = "feed_comment", indexes = {
        // 목록이 (게시물, 최상위 여부, id 순)으로 스캔한다. 커서가 id라 정렬 키가 곧 인덱스 순서다.
        @Index(name = "idx_comment_post", columnList = "post_id, parent_id, id"),
        // 부모 기준 대댓글 조회.
        @Index(name = "idx_comment_parent", columnList = "parent_id"),
        // 탈퇴 파기가 작성자 기준으로 훑는다.
        @Index(name = "idx_comment_user", columnList = "user_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    /** 부모 댓글. {@code null}이면 최상위 댓글이다. 대댓글의 id는 여기 들어올 수 없다. */
    @Column(name = "parent_id")
    private Long parentId;

    /** 작성자. 탈퇴해도 행은 남으며 상태가 {@code DELETED}로 바뀐다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 본문. 상한 500자는 일상 글과 같으며 검증도 같은 규칙을 재사용한다(연락처·외부 링크 차단 포함). */
    @Column(nullable = false, length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FeedCommentStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 최상위 댓글인가. */
    public boolean isRoot() {
        return parentId == null;
    }
}
