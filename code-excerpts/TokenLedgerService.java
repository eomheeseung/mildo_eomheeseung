package mildo.token.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import mildo.ex.CustomException;
import mildo.ex.ErrorCode;
import mildo.token.entity.TokenAccount;
import mildo.token.entity.TokenGrant;
import mildo.token.entity.TokenGrantSource;
import mildo.token.entity.TokenTransaction;
import mildo.token.entity.TokenTransactionType;
import mildo.token.repository.TokenAccountRepository;
import mildo.token.repository.TokenGrantRepository;
import mildo.token.repository.TokenTransactionRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 베리 적립/차감 원장 코어. 잔액 갱신·거래 기록·멱등을 한곳에서 처리한다.
 *
 * <ul>
 *   <li>{@link #creditPaid} — 유상 적립(충전)</li>
 *   <li>{@link #creditFree} — 무상 적립(보너스/출석/사진). 30일 만료 로트 생성</li>
 *   <li>{@link #spend} — 차감(유료 먼저 → 무료 만료임박 FIFO), 잔액 부족 시 INSUFFICIENT_TOKENS</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenLedgerService {

    /** 무상 베리 유효기간(일). // 임시값 */
    private static final int FREE_EXPIRE_DAYS = 30;

    private final TokenAccountRepository tokenAccountRepository;
    private final TokenTransactionRepository tokenTransactionRepository;
    private final TokenGrantRepository tokenGrantRepository;

    /**
     * 유상 베리 적립. 멱등: idempotencyKey 또는 (type, refType, refId) 중복 시 기존 거래 반환.
     */
    @Transactional
    public TokenTransaction creditPaid(Long userId, int amount, TokenTransactionType type,
                                       String refType, String refId,
                                       String idempotencyKey, String description) {
        if (amount <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String normalizedKey = (idempotencyKey != null && !idempotencyKey.isBlank()) ? idempotencyKey : null;

        if (normalizedKey != null) {
            Optional<TokenTransaction> dup = tokenTransactionRepository.findByIdempotencyKey(normalizedKey);
            if (dup.isPresent()) {
                return dup.get();
            }
        }
        if (refType != null && refId != null) {
            Optional<TokenTransaction> dup =
                    tokenTransactionRepository.findByTypeAndRefTypeAndRefId(type, refType, refId);
            if (dup.isPresent()) {
                return dup.get();
            }
        }

        TokenAccount account = getOrCreateAccount(userId);
        account.setPaidBalance(account.getPaidBalance() + amount);

        TokenTransaction tx = TokenTransaction.builder()
                .userId(userId)
                .type(type)
                .amount(amount)
                .paidAmount(amount)
                .freeAmount(0)
                .balanceAfter(account.getPaidBalance() + account.getFreeBalance())
                .refType(refType)
                .refId(refId)
                .idempotencyKey(normalizedKey)
                .description(description)
                .build();
        try {
            return tokenTransactionRepository.saveAndFlush(tx);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
    }

    /**
     * 무상 베리 적립. 30일 만료 로트(token_grant)를 만들고 free_balance를 증가시킨다.
     * 멱등: (type, refType, refId) 중복 시 기존 거래 반환(예: 가입 보너스 1회).
     */
    @Transactional
    public TokenTransaction creditFree(Long userId, int amount, TokenGrantSource source,
                                       TokenTransactionType type, String refType, String refId,
                                       String description) {
        if (amount <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (refType != null && refId != null) {
            Optional<TokenTransaction> dup =
                    tokenTransactionRepository.findByTypeAndRefTypeAndRefId(type, refType, refId);
            if (dup.isPresent()) {
                return dup.get();
            }
        }

        LocalDateTime now = LocalDateTime.now();
        TokenAccount account = getOrCreateAccount(userId);

        TokenGrant grant = TokenGrant.builder()
                .userId(userId)
                .source(source)
                .amount(amount)
                .remaining(amount)
                .grantedAt(now)
                .expireAt(now.plusDays(FREE_EXPIRE_DAYS))
                .build();
        tokenGrantRepository.save(grant);

        account.setFreeBalance(account.getFreeBalance() + amount);

        TokenTransaction tx = TokenTransaction.builder()
                .userId(userId)
                .type(type)
                .amount(amount)
                .paidAmount(0)
                .freeAmount(amount)
                .balanceAfter(account.getPaidBalance() + account.getFreeBalance())
                .refType(refType)
                .refId(refId)
                .description(description)
                .build();
        try {
            return tokenTransactionRepository.saveAndFlush(tx);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
    }

    /**
     * 만료된 무상 로트 소멸 + 무료 잔액 캐시 동기화. 스케줄러가 주기적으로 호출.
     *
     * <p>각 {@link TokenGrant}는 자기 {@code expireAt} 기준으로 독립 만료(로트별). {@code expireAt <= now && remaining > 0}
     * 행의 remaining을 0으로 만들고, 유저별 만료 합만큼 {@code token_account.free_balance}에서 차감한다(0 하한).
     * 유저별 실제 차감량으로 {@code EXPIRE} 거래를 남긴다(감사). spend는 원래 만료분을 제외(FIFO)하므로 이 배치는
     * "표시 잔액을 실제와 맞추는" 정리 역할이다.</p>
     *
     * @return 소멸 처리한 로트 수
     */
    @Transactional
    public int expireFreeGrants() {
        LocalDateTime now = LocalDateTime.now();
        List<TokenGrant> expired = tokenGrantRepository.findExpiredGrants(now);
        if (expired.isEmpty()) {
            return 0;
        }

        // 유저별 만료 remaining 합산 + 로트 소멸
        Map<Long, Integer> expiredByUser = new HashMap<>();
        for (TokenGrant grant : expired) {
            expiredByUser.merge(grant.getUserId(), grant.getRemaining(), Integer::sum);
            grant.setRemaining(0);
        }
        tokenGrantRepository.saveAll(expired);

        for (Map.Entry<Long, Integer> entry : expiredByUser.entrySet()) {
            Long userId = entry.getKey();
            int expiredSum = entry.getValue();
            if (expiredSum <= 0) {
                continue;
            }
            TokenAccount account = tokenAccountRepository.findByUserIdForUpdate(userId).orElse(null);
            if (account == null) {
                continue;
            }
            int before = account.getFreeBalance();
            int after = Math.max(0, before - expiredSum);
            int applied = before - after; // 캐시가 이미 낮으면 실제 반영량만
            if (applied <= 0) {
                continue;
            }
            account.setFreeBalance(after);

            TokenTransaction tx = TokenTransaction.builder()
                    .userId(userId)
                    .type(TokenTransactionType.EXPIRE)
                    .amount(-applied)
                    .paidAmount(0)
                    .freeAmount(-applied)
                    .balanceAfter(account.getPaidBalance() + after)
                    .refType("EXPIRE")
                    .description("무상 베리 만료 소멸")
                    .build();
            tokenTransactionRepository.save(tx);
        }

        log.info("[TokenExpiry] 만료 로트 {}건 소멸, 유저 {}명 잔액 정리", expired.size(), expiredByUser.size());
        return expired.size();
    }

    /**
     * 베리 차감. <b>유료 먼저 → 무료(만료 임박분 FIFO)</b> 순으로 소진한다.
     * 잔액 부족 시 {@link ErrorCode#INSUFFICIENT_TOKENS}.
     *
     * <p>refType/refId가 모두 주어지면 동일 차감 재현(멱등). 차감 시점 paid/free 분해량을 기록해
     * 후속 환불 복원에 사용할 수 있게 한다. 계정 행은 비관적 락으로 보호한다.</p>
     */
    @Transactional
    public TokenTransaction spend(Long userId, int amount, String refType, String refId, String description) {
        if (amount <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (refType != null && refId != null) {
            Optional<TokenTransaction> dup = tokenTransactionRepository
                    .findByTypeAndRefTypeAndRefId(TokenTransactionType.SPEND, refType, refId);
            if (dup.isPresent()) {
                return dup.get();
            }
        }

        LocalDateTime now = LocalDateTime.now();
        TokenAccount account = tokenAccountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INSUFFICIENT_TOKENS));

        if (account.getPaidBalance() + account.getFreeBalance() < amount) {
            throw new CustomException(ErrorCode.INSUFFICIENT_TOKENS);
        }

        int fromPaid = Math.min(account.getPaidBalance(), amount);
        int fromFree = amount - fromPaid;

        if (fromPaid > 0) {
            account.setPaidBalance(account.getPaidBalance() - fromPaid);
        }
        if (fromFree > 0) {
            // 무상은 만료 임박분 우선(FIFO)으로 로트에서 차감
            List<TokenGrant> grants = tokenGrantRepository.findActiveGrantsForSpend(userId, now);
            int toConsume = fromFree;
            for (TokenGrant grant : grants) {
                if (toConsume <= 0) {
                    break;
                }
                int take = Math.min(grant.getRemaining(), toConsume);
                grant.setRemaining(grant.getRemaining() - take);
                toConsume -= take;
            }
            if (toConsume > 0) {
                // free_balance 캐시와 로트 잔여 합이 불일치 — 안전하게 부족 처리(트랜잭션 롤백).
                throw new CustomException(ErrorCode.INSUFFICIENT_TOKENS);
            }
            account.setFreeBalance(account.getFreeBalance() - fromFree);
        }

        TokenTransaction tx = TokenTransaction.builder()
                .userId(userId)
                .type(TokenTransactionType.SPEND)
                .amount(-amount)
                .paidAmount(fromPaid)
                .freeAmount(fromFree)
                .balanceAfter(account.getPaidBalance() + account.getFreeBalance())
                .refType(refType)
                .refId(refId)
                .description(description)
                .build();
        return tokenTransactionRepository.save(tx);
    }

    /**
     * 스토어/PG 환불에 따른 적립 회수(CLAWBACK). 원 유상 적립량을 유상 잔액에서 차감한다.
     *
     * <p><b>정책(기획 확정):</b> 환불 시 남은 베리를 회수하되, 이미 소진했다면 {@code paid_balance}가
     * <b>음수(빚)</b>가 될 수 있다 — 재충전 시 자동 상계되고, 그 전까지는 {@link #spend}의 잔액검사가 사용을 막는다.
     * 음수 진입은 어뷰징 추적용으로 WARN 로그를 남기며, 반복 계정은 관리자가 수동 제재한다.</p>
     *
     * <p>멱등: {@code (CLAWBACK, refType, refId)} 중복이면 추가 회수 없이 기존 거래 반환(중복 환불 알림 안전).
     * 호출 전제 — 스토어 환불 알림이 서명검증을 통과했고 대상이 베리 충전임이 확인된 상태여야 한다.</p>
     */
    @Transactional
    public TokenTransaction clawback(Long userId, int amount, String refType, String refId, String description) {
        if (amount <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (refType != null && refId != null) {
            Optional<TokenTransaction> dup = tokenTransactionRepository
                    .findByTypeAndRefTypeAndRefId(TokenTransactionType.CLAWBACK, refType, refId);
            if (dup.isPresent()) {
                return dup.get();
            }
        }

        TokenAccount account = tokenAccountRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> tokenAccountRepository.save(TokenAccount.builder().userId(userId).build()));

        // 유상 적립 회수 — 이미 소진했다면 음수 허용(빚). 무상(보너스) 잔액은 건드리지 않는다.
        account.setPaidBalance(account.getPaidBalance() - amount);
        int balanceAfter = account.getPaidBalance() + account.getFreeBalance();

        // 어뷰징 신호 = 유상 잔액이 음수(환불받았는데 이미 소진). 무상(보너스)에 가려지지 않게 paid 기준으로 로깅.
        if (account.getPaidBalance() < 0) {
            log.warn("[CLAWBACK] 환불 회수로 유상잔액 음수(빚): userId={}, amount={}, paidBalance={}, totalAfter={}, ref={}/{} — 어뷰징 추적 대상",
                    userId, amount, account.getPaidBalance(), balanceAfter, refType, refId);
        }

        TokenTransaction tx = TokenTransaction.builder()
                .userId(userId)
                .type(TokenTransactionType.CLAWBACK)
                .amount(-amount)
                .paidAmount(amount)
                .freeAmount(0)
                .balanceAfter(balanceAfter)
                .refType(refType)
                .refId(refId)
                .description(description)
                .build();
        try {
            return tokenTransactionRepository.saveAndFlush(tx);
        } catch (DataIntegrityViolationException e) {
            // 동시 같은 환불 알림 2건 경합: uk_token_tx_ref로 1건만 기록되고 패자는 전체 롤백 → 이중회수 없음(금전 안전).
            // flush 실패로 트랜잭션이 rollback-only라 여기서 기존 거래를 반환할 수 없으므로 creditPaid와 동일하게 throw.
            // 순차 재전송(스토어 재시도)은 위 선검사에서 기존 거래로 멱등 반환되고, 이 경로는 드문 동시경합뿐 — 호출부는 이미 처리됨으로 취급.
            throw new CustomException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
    }

    /**
     * 잔액(유료+무료 합) 충분 여부 — 읽기 전용 사전 검증용. 실제 차감은 {@link #spend}.
     * 계정이 없으면(=0베리) {@code amount>0}에 대해 false.
     */
    @Transactional(readOnly = true)
    public boolean canAfford(Long userId, int amount) {
        if (amount <= 0) {
            return true;
        }
        return tokenAccountRepository.findByUserId(userId)
                .map(a -> a.getPaidBalance() + a.getFreeBalance() >= amount)
                .orElse(false);
    }

    private TokenAccount getOrCreateAccount(Long userId) {
        return tokenAccountRepository.findByUserId(userId)
                .orElseGet(() -> tokenAccountRepository.save(
                        TokenAccount.builder().userId(userId).build()));
    }
}
