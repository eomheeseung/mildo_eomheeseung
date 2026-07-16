package tideflo.tide_match.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import tideflo.tide_match.ai.client.AnthropicApi;
import tideflo.tide_match.ai.service.AiUsageLogService;
import tideflo.tide_match.ai.type.AiModel;
import tideflo.tide_match.ai.type.AiPromptType;
import tideflo.tide_match.ai.util.AiRetryUtils;
import tideflo.tide_match.ex.CustomException;
import tideflo.tide_match.ex.ErrorCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeClient implements AiClient {

    private final AnthropicApi anthropicApi;
    private final ObjectMapper objectMapper;
    private final AiUsageLogService aiUsageLogService;

    @Value("${anthropic.model:claude-sonnet-4-5-20250929}")
    private String defaultModel;

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*([\\s\\S]*?)```");
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");

    // ThreadLocal로 현재 요청의 사용자 ID 저장
    private static final ThreadLocal<Long> currentUserId = new ThreadLocal<>();

    public static void setCurrentUserId(Long userId) {
        currentUserId.set(userId);
    }

    public static void clearCurrentUserId() {
        currentUserId.remove();
    }

    @Override
    public String getProviderName() {
        return "CLAUDE";
    }

    @Override
    public Map<String, Object> callWithJsonResponse(String prompt, AiPromptType promptType, String model) {
        return callWithJsonResponse(prompt, promptType, model, null);
    }

    @Override
    public Map<String, Object> callWithJsonResponse(String prompt, AiPromptType promptType, String model, String systemMessage) {
        return callWithJsonResponse(prompt, promptType, model, systemMessage, null);
    }

    @Override
    public Map<String, Object> callWithJsonResponse(String prompt, AiPromptType promptType, String model, String systemMessage, Map<String, Object> metadata) {
        long startTime = System.currentTimeMillis();
        Long userId = currentUserId.get();
        String useModel = (model != null && !model.isBlank()) ? model : defaultModel;

        try {
            CallResult result = callInternalWithRetry(prompt, promptType, useModel, systemMessage);
            Map<String, Object> jsonResponse = tryParseWithRetry(result.content, prompt, promptType, useModel, systemMessage);

            int responseTimeMs = (int) (System.currentTimeMillis() - startTime);
            aiUsageLogService.saveUsageLog(
                    userId, useModel, promptType.name(),
                    result.inputTokens, result.outputTokens,
                    responseTimeMs, true, null, metadata
            );

            return jsonResponse;
        } catch (Exception e) {
            log.error("Claude API 호출 실패: {}", e.getMessage(), e);

            int responseTimeMs = (int) (System.currentTimeMillis() - startTime);
            aiUsageLogService.saveUsageLog(
                    userId, useModel, promptType.name(),
                    null, null,
                    responseTimeMs, false, e.getMessage(), metadata
            );

            throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
        }
    }

    @Override
    public String call(String prompt, AiPromptType promptType, String model, String systemMessage) {
        long startTime = System.currentTimeMillis();
        Long userId = currentUserId.get();
        String useModel = (model != null && !model.isBlank()) ? model : defaultModel;

        try {
            CallResult result = callInternalWithRetry(prompt, promptType, useModel, systemMessage);

            int responseTimeMs = (int) (System.currentTimeMillis() - startTime);
            aiUsageLogService.saveUsageLog(
                    userId, useModel, promptType.name(),
                    result.inputTokens, result.outputTokens,
                    responseTimeMs, true, null, null
            );

            return result.content;
        } catch (Exception e) {
            log.error("Claude API 호출 실패: {}", e.getMessage(), e);

            int responseTimeMs = (int) (System.currentTimeMillis() - startTime);
            aiUsageLogService.saveUsageLog(
                    userId, useModel, promptType.name(),
                    null, null,
                    responseTimeMs, false, e.getMessage(), null
            );

            throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
        }
    }

    /**
     * Retry + Exponential Backoff 적용된 callInternal 래퍼
     * 최대 3회 재시도 (총 4회 시도), backoff: 1초 -> 2초 -> 4초
     */
    private CallResult callInternalWithRetry(String prompt, AiPromptType promptType, String model, String systemMessage) {
        int maxRetries = AiRetryUtils.getMaxRetries();
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return callInternal(prompt, promptType, model, systemMessage);
            } catch (HttpStatusCodeException e) {
                lastException = e;
                int statusCode = e.getStatusCode().value();
                if (AiRetryUtils.isRetryableStatusCode(statusCode) && attempt < maxRetries) {
                    log.warn("Claude API 재시도 가능 에러 (HTTP {}) - attempt {}/{}", statusCode, attempt + 1, maxRetries + 1);
                    AiRetryUtils.sleepWithBackoff(attempt);
                } else {
                    throw e;
                }
            } catch (Exception e) {
                lastException = e;
                if (AiRetryUtils.isNetworkError(e) && attempt < maxRetries) {
                    log.warn("Claude API 네트워크 에러 - attempt {}/{}: {}", attempt + 1, maxRetries + 1, e.getMessage());
                    AiRetryUtils.sleepWithBackoff(attempt);
                } else {
                    throw e;
                }
            }
        }

        throw new RuntimeException("Claude API 재시도 모두 실패", lastException);
    }

    private CallResult callInternal(String prompt, AiPromptType promptType, String model, String systemMessage) {
        log.info("Claude API 호출 - type: {}, model: {}", promptType, model);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", promptType.getMaxTokens());
        // 신세대 모델(Claude 5 / Opus 4.8+)은 temperature를 폐기했다 — 실어 보내면 400. 지원 모델에만 포함.
        if (AiModel.modelSupportsTemperature(model)) {
            requestBody.put("temperature", promptType.getTemperature());
        }

        if (systemMessage != null && !systemMessage.isBlank()) {
            requestBody.put("system", systemMessage);
        }

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        requestBody.put("messages", messages);

        JsonNode responseNode = anthropicApi.createMessage(requestBody);

        String content = extractText(responseNode);
        Integer inputTokens = responseNode.path("usage").path("input_tokens").asInt();
        Integer outputTokens = responseNode.path("usage").path("output_tokens").asInt();

        log.debug("Claude 응답 수신 완료 - inputTokens: {}, outputTokens: {}", inputTokens, outputTokens);
        return new CallResult(content, inputTokens, outputTokens);
    }

    /**
     * JSON 파싱 시도 후 실패 시 "순수 JSON만 반환" 시스템 메시지를 추가하여 1회 재호출
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> tryParseWithRetry(String content, String prompt, AiPromptType promptType, String model, String systemMessage) {
        try {
            return parseJsonResponse(content);
        } catch (Exception firstParseError) {
            log.warn("Claude JSON 파싱 실패, 순수 JSON 재호출 시도 - error: {}", firstParseError.getMessage());

            String enhancedSystemMessage = (systemMessage != null && !systemMessage.isBlank())
                    ? systemMessage + "\n\n" + AiRetryUtils.JSON_ONLY_SYSTEM_MESSAGE
                    : AiRetryUtils.JSON_ONLY_SYSTEM_MESSAGE;

            CallResult retryResult = callInternalWithRetry(prompt, promptType, model, enhancedSystemMessage);

            try {
                return parseJsonResponse(retryResult.content);
            } catch (Exception secondParseError) {
                log.error("Claude JSON 재파싱도 실패: {}", secondParseError.getMessage());
                log.error("재호출 원본 응답: {}", retryResult.content);
                throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonResponse(String content) throws JsonProcessingException {
        String jsonStr = extractJson(content);
        jsonStr = AiRetryUtils.sanitizeJson(jsonStr);
        return objectMapper.readValue(jsonStr, Map.class);
    }

    private String extractJson(String content) {
        Matcher blockMatcher = JSON_BLOCK_PATTERN.matcher(content);
        if (blockMatcher.find()) {
            return blockMatcher.group(1).trim();
        }
        Matcher objectMatcher = JSON_OBJECT_PATTERN.matcher(content);
        if (objectMatcher.find()) {
            return objectMatcher.group().trim();
        }
        return content.trim();
    }

    /**
     * 응답 content 배열에서 <b>첫 번째 {@code text} 블록</b>의 텍스트를 뽑는다.
     *
     * <p>Claude 5 세대(sonnet-5 등)는 복잡한 프롬프트에서 요청 없이도 {@code thinking} 블록을 앞에 넣는다.
     * 그러면 {@code content[0]}이 thinking(=text 필드 없음)이라 예전처럼 {@code content[0].text}를 뽑으면 빈 문자열이 나와
     * JSON 파싱이 "No content to map"으로 실패한다. type이 text인 첫 블록을 찾아 그 문제를 피한다.
     * 구형 모델은 응답이 {@code [{type:text}]}뿐이라 첫 text 블록이 곧 {@code content[0]} — 동작 변화 없음.</p>
     */
    private String extractText(JsonNode responseNode) {
        JsonNode contentArray = responseNode.path("content");
        if (contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                if ("text".equals(block.path("type").asText())) {
                    return block.path("text").asText();
                }
            }
        }
        // text 블록을 못 찾으면 기존 방식으로 폴백(진단 로그 남김)
        String fallback = contentArray.path(0).path("text").asText();
        log.warn("Claude 응답에 text 블록 없음 - content blocks={}, fallback len={}",
                contentArray.isArray() ? contentArray.size() : -1, fallback.length());
        return fallback;
    }

    private record CallResult(String content, Integer inputTokens, Integer outputTokens) {}
}
