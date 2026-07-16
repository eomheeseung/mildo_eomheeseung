package mildo.adspend.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import mildo.adspend.config.AdsProperties;
import mildo.common.SharedObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Ads API — 계정 단위 일별 광고비 조회. 무거운 google-ads-java 대신 REST + java.net.http로 호출한다.
 *
 * <p>인증: refresh_token → access_token 교환(oauth2.googleapis.com) 후, {@code customers/{id}/googleAds:search}에
 * {@code developer-token} + {@code login-customer-id}(MCC) 헤더를 실어 GAQL 질의.
 * {@code cost_micros}는 계정 통화의 100만분의 1 — /1,000,000 하면 계정 통화 단위(KRW).</p>
 *
 * <p>실패/자격증명 없음이면 예외 대신 빈 리스트(배치가 다른 플랫폼을 계속 처리하도록).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleAdsClient {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final BigDecimal MICROS = BigDecimal.valueOf(1_000_000);
    private static final String CURRENCY = "KRW"; // 계정 통화(현재 KRW). 통화 다르면 여기 + 저장 currency 조정.

    private final AdsProperties adsProperties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public List<DailyMetric> fetchDaily(LocalDate from, LocalDate to) {
        AdsProperties.Google cfg = adsProperties.getGoogle();
        if (!cfg.hasCredentials()) {
            log.warn("[ADS][GOOGLE] 자격증명 없음 — 조회 스킵");
            return List.of();
        }
        try {
            String accessToken = fetchAccessToken(cfg);
            if (accessToken == null) {
                return List.of();
            }

            String query = "SELECT segments.date, metrics.cost_micros, metrics.impressions, "
                    + "metrics.clicks, metrics.conversions FROM customer "
                    + "WHERE segments.date BETWEEN '" + from.format(DAY) + "' AND '" + to.format(DAY) + "'";
            String body = SharedObjectMapper.get().writeValueAsString(java.util.Map.of("query", query));

            String url = "https://googleads.googleapis.com/" + cfg.getApiVersion()
                    + "/customers/" + cfg.getCustomerId() + "/googleAds:search";

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("developer-token", cfg.getDeveloperToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            // login-customer-id는 MCC를 "경유"해 자식 계정을 볼 때만 필요. 인증 계정이 customer에 <b>직접</b> 접근
            // 권한이 있으면(listAccessibleCustomers에 해당 customer가 직접 노출되는 경우) 이 헤더를 붙일수록
            // USER_PERMISSION_DENIED가 난다. 그래서 값이 설정된 경우에만 붙인다(기본은 미설정 → 미전송).
            String mcc = cfg.getLoginCustomerId();
            if (mcc != null && !mcc.isBlank()) {
                builder.header("login-customer-id", mcc);
            }
            HttpRequest request = builder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("[ADS][GOOGLE] 조회 실패 — http={}, body={}", response.statusCode(), truncate(response.body()));
                return List.of();
            }

            JsonNode root = SharedObjectMapper.get().readTree(response.body());
            List<DailyMetric> result = new ArrayList<>();
            for (JsonNode r : root.path("results")) {
                JsonNode seg = r.path("segments");
                JsonNode m = r.path("metrics");
                String day = seg.path("date").asText(""); // "2026-06-19"
                if (day.length() < 10) {
                    continue;
                }
                // cost_micros: JSON 키는 camelCase(costMicros)
                BigDecimal spend = new BigDecimal(m.path("costMicros").asText("0"))
                        .divide(MICROS, 2, java.math.RoundingMode.HALF_UP);
                result.add(new DailyMetric(
                        LocalDate.parse(day.substring(0, 10)), spend, CURRENCY,
                        m.path("impressions").asLong(0),
                        m.path("clicks").asLong(0),
                        (long) m.path("conversions").asDouble(0)));
            }
            log.info("[ADS][GOOGLE] {}~{} 조회 완료 — {}일치", from, to, result.size());
            return result;
        } catch (Exception e) {
            log.error("[ADS][GOOGLE] 조회 예외 — {}", e.getMessage(), e);
            return List.of();
        }
    }

    /** refresh_token으로 단기 access_token 교환. 실패 시 null. */
    private String fetchAccessToken(AdsProperties.Google cfg) throws Exception {
        String form = "client_id=" + enc(cfg.getClientId())
                + "&client_secret=" + enc(cfg.getClientSecret())
                + "&refresh_token=" + enc(cfg.getRefreshToken())
                + "&grant_type=refresh_token";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = SharedObjectMapper.get().readTree(response.body());
        if (response.statusCode() != 200 || root.path("access_token").isMissingNode()) {
            log.error("[ADS][GOOGLE] access_token 교환 실패 — http={}, body={}",
                    response.statusCode(), truncate(response.body()));
            return null;
        }
        return root.path("access_token").asText();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
