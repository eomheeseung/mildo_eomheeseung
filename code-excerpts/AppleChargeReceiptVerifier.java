package mildo.token.service;

import com.apple.itunes.storekit.model.Environment;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.verification.SignedDataVerifier;
import com.apple.itunes.storekit.verification.VerificationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import mildo.ex.CustomException;
import mildo.ex.ErrorCode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * 애플(App Store) 충전 영수증 검증 — 베리제 iOS 전용.
 *
 * <p><b>실검증(B안)</b>: StoreKit2 JWS를 애플 공식 라이브러리 {@link SignedDataVerifier}로 서명검증
 * (x5c 인증서 체인 → Apple Root CA, 페이로드 bundleId/environment 확인). 샌드박스·운영 둘 다 지원 —
 * JWS의 {@code environment} 필드로 verifier를 라우팅한다(TestFlight/심사 결제는 샌드박스).
 * {@code token.charge.apple-verify=false}면 무검증(A안 스텁)으로 폴백.</p>
 *
 * <p>애플 검증에만 책임 → Google/기타 변경과 무관(iOS 격리).</p>
 */
@Slf4j
@Component
public class AppleChargeReceiptVerifier implements PlatformChargeVerifier {

    private static final String PLATFORM = "APPLE";
    private static final String ENV_PRODUCTION = "Production";
    /** refId 컬럼(length=100) 내로 유지하기 위한 폴백 식별자 최대 길이. */
    private static final int REF_ID_MAX = 90;

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final boolean appleVerifyEnabled;
    private final String bundleId;
    private final Long appAppleId;
    private final String rootCaPath;

    private SignedDataVerifier sandboxVerifier;
    private SignedDataVerifier productionVerifier;

    public AppleChargeReceiptVerifier(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${token.charge.apple-verify:false}") boolean appleVerifyEnabled,
            @Value("${token.charge.apple.bundle-id:com.example.app}") String bundleId,
            @Value("${token.charge.apple.app-apple-id:6759034625}") Long appAppleId,
            @Value("${token.charge.apple.root-ca:classpath:apple/AppleRootCA-G3.cer}") String rootCaPath) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.appleVerifyEnabled = appleVerifyEnabled;
        this.bundleId = bundleId;
        this.appAppleId = appAppleId;
        this.rootCaPath = rootCaPath;
    }

    /**
     * 실검증용 verifier(샌드박스/운영) 준비. Root CA 로드 실패해도 앱 기동은 유지하고,
     * 충전 시점에만 에러를 낸다(전체 앱이 죽지 않도록).
     */
    @PostConstruct
    void init() {
        if (!appleVerifyEnabled) {
            log.warn("[APPLE] apple-verify=false → 무검증(A안 스텁) 모드로 동작");
            return;
        }
        try {
            byte[] root = resourceLoader.getResource(rootCaPath).getContentAsByteArray();
            // 샌드박스: appAppleId=null (샌드박스 JWS는 appAppleId가 없음)
            this.sandboxVerifier = new SignedDataVerifier(
                    Set.of(new ByteArrayInputStream(root)), bundleId, null, Environment.SANDBOX, false);
            // 운영: appAppleId 필수
            this.productionVerifier = new SignedDataVerifier(
                    Set.of(new ByteArrayInputStream(root)), bundleId, appAppleId, Environment.PRODUCTION, false);
            log.info("[APPLE] JWS 실검증 준비 완료 (bundleId={}, appAppleId={})", bundleId, appAppleId);
        } catch (Exception e) {
            log.error("[APPLE] 실검증 초기화 실패 — Root CA({}) 확인 필요(충전 검증 차단됨).", rootCaPath, e);
        }
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public ChargeReceiptVerifier.VerifiedReceipt verify(String packageId, String receipt) {
        if (!appleVerifyEnabled) {
            // A안 폴백: 무검증(구독 IAP와 동일 수준). transactionId만 추출해 멱등키로 사용.
            String txId = extractTransactionId(receipt);
            log.warn("[APPLE] 무검증 적립(apple-verify=false). packageId={}, txId={}", packageId, txId);
            return new ChargeReceiptVerifier.VerifiedReceipt(PLATFORM + ":" + txId);
        }
        if (sandboxVerifier == null || productionVerifier == null) {
            log.error("[APPLE] 실검증 미초기화(Root CA 로드 실패). packageId={}", packageId);
            throw new CustomException(ErrorCode.BAD_REQUEST, "영수증 검증 설정 오류");
        }

        boolean prod = ENV_PRODUCTION.equalsIgnoreCase(peekEnvironment(receipt));
        SignedDataVerifier verifier = prod ? productionVerifier : sandboxVerifier;
        try {
            JWSTransactionDecodedPayload payload = verifier.verifyAndDecodeTransaction(receipt);
            String txId = payload.getTransactionId();
            log.info("[APPLE] JWS 실검증 통과 → env={}, productId={}, txId={}",
                    payload.getEnvironment(), payload.getProductId(), txId);
            return new ChargeReceiptVerifier.VerifiedReceipt(PLATFORM + ":" + txId);
        } catch (VerificationException e) {
            log.error("[APPLE] JWS 검증 실패(env={}, packageId={}): {}",
                    prod ? "Production" : "Sandbox", packageId, e.getMessage());
            throw new CustomException(ErrorCode.BAD_REQUEST, "영수증 검증에 실패했습니다");
        }
    }

    /** JWS payload의 environment 필드만 미리 확인(서명검증 전) — verifier 라우팅용. 실패 시 샌드박스로 간주(안전). */
    private String peekEnvironment(String receipt) {
        String[] parts = (receipt == null) ? new String[0] : receipt.split("\\.");
        if (parts.length == 3) {
            try {
                JsonNode node = objectMapper.readTree(
                        new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
                return node.path("environment").asText("Sandbox");
            } catch (Exception ignored) {
                // 파싱 실패 → 샌드박스로 간주
            }
        }
        return "Sandbox";
    }

    // ===== A안 폴백(무검증) 유틸 — apple-verify=false 일 때만 사용 =====

    /** 서명검증 없이 JWS에서 transactionId만 추출. JWS 아니면 축약 폴백. */
    private String extractTransactionId(String receipt) {
        String[] parts = (receipt == null) ? new String[0] : receipt.split("\\.");
        if (parts.length == 3) {
            try {
                String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                JsonNode node = objectMapper.readTree(payloadJson);
                String txId = node.path("transactionId").asText(null);
                if (txId != null && !txId.isBlank()) {
                    return txId;
                }
            } catch (Exception e) {
                log.warn("[APPLE] JWS 디코드 실패: {}", e.getMessage());
            }
        }
        return shortId(receipt);
    }

    private String shortId(String receipt) {
        if (receipt == null || receipt.isBlank()) {
            return "empty";
        }
        return receipt.length() <= REF_ID_MAX ? receipt : Integer.toHexString(receipt.hashCode());
    }
}
