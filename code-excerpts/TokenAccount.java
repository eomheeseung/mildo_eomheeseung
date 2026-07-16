package mildo.token.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 사용자별 베리(토큰) 계정. 사용자당 1행.
 *
 * <p>잔액은 유상(paid)/무상(free)으로 분리 보관한다. 차감 순서는 <b>유료 먼저 → 무료</b>.
 * {@code freeBalance}는 유효한 {@link TokenGrant#getRemaining()} 합과 동기화되는 캐시값이다
 * (조회 성능을 위해 집계값을 들고 있으며, 만료 배치/차감 시 동기화).</p>
 *
 * <p>동시 차감으로 인한 이중차감을 막기 위해 {@code @Version} 낙관적 락을 둔다.
 * 강한 일관성이 필요한 차감 경로는 {@code findByUserIdForUpdate}(비관적 락)를 사용한다.</p>
 */
@Entity
@Table(name = "token_account",
        uniqueConstraints = @UniqueConstraint(name = "uk_token_account_user", columnNames = "user_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 유상(충전) 베리 잔액. 유효기간 없음(약관상 상법 소멸시효 5년 형식적 상한). */
    @Column(name = "paid_balance", nullable = false)
    @Builder.Default
    private int paidBalance = 0;

    /** 무상(보너스) 베리 잔액 — token_grant.remaining 합과 동기화되는 캐시값. */
    @Column(name = "free_balance", nullable = false)
    @Builder.Default
    private int freeBalance = 0;

    /** 낙관적 락 버전 (동시 차감 이중차감 방지). */
    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 사용 가능한 총 베리 (유상 + 무상). */
    @Transient
    public int getTotalBalance() {
        return paidBalance + freeBalance;
    }
}
