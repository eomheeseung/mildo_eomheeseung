package mildo.token.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import mildo.ex.CustomException;
import mildo.ex.ErrorCode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 충전 영수증 검증 디스패처. {@code platform}으로 플랫폼별 검증기({@link PlatformChargeVerifier})를 골라 위임한다.
 *
 * <p>요청/응답(우리 API)과 다운스트림(카탈로그 조회·{@code creditPaid} 적립·멱등·잔액)은 공유하고,
 * <b>검증 로직만 플랫폼별 구현 클래스에 격리</b>한다 → 한 플랫폼(예 Google) 추가/변경이 다른 플랫폼(Apple)에 영향 없음.
 * 등록된 검증기가 없는 플랫폼(예 PORTONE 등 베리 미지원)은 {@code TOKEN_CHARGE_NOT_SUPPORTED}.</p>
 */
@Slf4j
@Component
public class ChargeReceiptVerifier {

    /** platform(대문자) → 검증기. Spring이 PlatformChargeVerifier 구현 빈을 모두 주입한다. */
    private final Map<String, PlatformChargeVerifier> verifiers;

    public ChargeReceiptVerifier(List<PlatformChargeVerifier> platformVerifiers) {
        this.verifiers = platformVerifiers.stream()
                .collect(Collectors.toMap(v -> v.platform().toUpperCase(Locale.ROOT), Function.identity()));
    }

    /**
     * 영수증을 검증하고 멱등용 거래식별자를 반환한다.
     *
     * @throws CustomException 미지원 플랫폼 또는 실검증 미연동(prod)에서는 {@code TOKEN_CHARGE_NOT_SUPPORTED}
     */
    public VerifiedReceipt verify(String platform, String packageId, String receipt) {
        PlatformChargeVerifier verifier = (platform == null) ? null
                : verifiers.get(platform.toUpperCase(Locale.ROOT));
        if (verifier == null) {
            log.error("지원하지 않는 충전 플랫폼: {}", platform);
            throw new CustomException(ErrorCode.TOKEN_CHARGE_NOT_SUPPORTED);
        }
        return verifier.verify(packageId, receipt);
    }

    /** 검증 결과 — 멱등 dedup에 쓰이는 스토어 거래 고유 식별자. */
    public record VerifiedReceipt(String platformTransactionId) {
    }
}
