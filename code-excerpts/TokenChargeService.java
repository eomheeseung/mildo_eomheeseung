package mildo.token.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import mildo.ad.event.ConversionEvent;
import mildo.auth.entity.User;
import mildo.auth.repository.UserRepository;
import mildo.ex.CustomException;
import mildo.ex.ErrorCode;
import mildo.token.dto.ChargePackageResponse;
import mildo.token.dto.ChargeVerifyRequest;
import mildo.token.dto.ChargeVerifyResponse;
import mildo.token.entity.TokenAccount;
import mildo.token.entity.TokenTransaction;
import mildo.token.entity.TokenTransactionType;
import mildo.token.repository.TokenAccountRepository;
import mildo.token.repository.TokenTransactionRepository;

import java.util.Map;

/**
 * 베리 충전(결제 검증 → 유상 적립) 오케스트레이션.
 * 영수증 검증은 {@link ChargeReceiptVerifier}(현재 DEV 스텁, prod 차단)에 위임하고,
 * 적립은 {@link TokenLedgerService#creditPaid}(멱등)로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenChargeService {

    private static final String REF_TYPE_CHARGE = "CHARGE";

    private final UserRepository userRepository;
    private final TokenPackageCatalog tokenPackageCatalog;
    private final ChargeReceiptVerifier chargeReceiptVerifier;
    private final TokenLedgerService tokenLedgerService;
    private final TokenAccountRepository tokenAccountRepository;
    private final TokenTransactionRepository tokenTransactionRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ChargeVerifyResponse verifyAndCredit(String email, ChargeVerifyRequest request, String idempotencyKey) {
        Long userId = userRepository.findByEmail(email)
                .map(User::getId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        ChargePackageResponse pkg = tokenPackageCatalog.findById(request.getPackageId())
                .orElseThrow(() -> new CustomException(ErrorCode.TOKEN_PACKAGE_NOT_FOUND));

        ChargeReceiptVerifier.VerifiedReceipt verified =
                chargeReceiptVerifier.verify(request.getPlatform(), request.getPackageId(), request.getReceipt());

        // #7 광고 결제 전환 판정 (creditPaid 전에):
        // - newCharge: 이 거래가 아직 기록 안 됨 → 새 충전. 멱등 replay(뒤로가기/재시도)면 false → 이벤트 스킵(이중집계 방지)
        // - firstPurchase: 이 유저의 첫 유상 충전 여부
        boolean newCharge = tokenTransactionRepository
                .findByTypeAndRefTypeAndRefId(TokenTransactionType.EARN_CHARGE, REF_TYPE_CHARGE, verified.platformTransactionId())
                .isEmpty();
        boolean firstPurchase = !tokenTransactionRepository.existsByUserIdAndType(userId, TokenTransactionType.EARN_CHARGE);

        int berries = pkg.getTotalBerries();
        TokenTransaction tx = tokenLedgerService.creditPaid(
                userId,
                berries,
                TokenTransactionType.EARN_CHARGE,
                REF_TYPE_CHARGE,
                verified.platformTransactionId(),
                idempotencyKey,
                berries + " 베리 충전");

        TokenAccount account = tokenAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));

        // #7 광고 전환 발행 (베리 충전 완료 = 결제 진실 소스). 새 충전일 때만 1회, 커밋 후 AppsFlyer S2S.
        // af_revenue = 카탈로그 정책가(원, 서버 검증값), af_currency=KRW.
        if (newCharge) {
            eventPublisher.publishEvent(ConversionEvent.purchase(
                    userId,
                    pkg.getPrice(),
                    "KRW",
                    Map.of("packageId", pkg.getId(), "first_purchase", firstPurchase)));
        }

        return ChargeVerifyResponse.builder()
                .creditedBerries(berries)
                .transactionId(tx.getId())
                .total(account.getTotalBalance())
                .paid(account.getPaidBalance())
                .free(account.getFreeBalance())
                .build();
    }

    /**
     * 스토어 환불에 따른 베리 회수. 원 충전 거래({@code EARN_CHARGE, CHARGE, platformTransactionId})를 찾아
     * 적립량만큼 회수({@link TokenLedgerService#clawback})한다. 이미 소진했다면 잔액이 음수(빚)가 될 수 있다.
     *
     * <p>대상 충전 거래가 없으면(=베리 충전이 아니거나 기록 없음) 안전하게 무시한다. 회수는 멱등이라
     * 같은 환불 알림이 중복 도착해도 한 번만 차감된다.</p>
     *
     * <p><b>호출 전제:</b> 스토어 환불 알림(Apple ASSNv2 REFUND / Google voided)이 <b>서명검증을 통과</b>했고
     * 대상 productId가 베리(berry_*)임이 확인된 상태여야 한다. platformTransactionId는 충전 시 저장한 것과
     * 동일 형식(예 {@code "APPLE:" + transactionId})이어야 매칭된다.</p>
     *
     * @return 회수 거래(또는 멱등 재호출 시 기존 거래), 대상 없으면 null
     */
    @Transactional
    public TokenTransaction clawbackByTransaction(String platformTransactionId) {
        return tokenTransactionRepository
                .findByTypeAndRefTypeAndRefId(TokenTransactionType.EARN_CHARGE, REF_TYPE_CHARGE, platformTransactionId)
                .map(charge -> {
                    log.info("[CLAWBACK] 베리 환불 회수: userId={}, amount={}, txId={}",
                            charge.getUserId(), charge.getAmount(), platformTransactionId);
                    return tokenLedgerService.clawback(charge.getUserId(), charge.getAmount(),
                            REF_TYPE_CHARGE, platformTransactionId, "스토어 환불 회수");
                })
                .orElseGet(() -> {
                    log.warn("[CLAWBACK] 환불 회수 대상 충전 거래 없음(베리 충전 아님 가능): txId={}", platformTransactionId);
                    return null;
                });
    }
}
