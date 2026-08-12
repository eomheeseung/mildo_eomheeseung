package mildo.aichat.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 대화 호감도 채점 — 응답 분리 + 턴 점수 환산 + EMA 누적.
 *
 * <p><b>경로가 둘이다.</b></p>
 * <ol>
 *   <li><b>구조화 응답(주 경로)</b> — {@link #replySchema()}를 API에 실어 보내 모델이 형식을
 *       <b>어길 수 없게</b> 만든다(Claude tool use). 본문과 4축이 애초에 별도 필드로 오므로
 *       잘라낼 것도, 화면에 샐 것도 없다.</li>
 *   <li><b>마커(폴백)</b> — 프로바이더가 구조화 응답을 지원하지 않거나 호출이 실패하면
 *       답변 끝의 {@code [[SCORE n,n,n,n]]}을 잘라 쓴다. 이건 "부탁"이라 모델이 안 지킬 수 있다.</li>
 * </ol>
 *
 * <p>JSON으로 감싸 텍스트로 받는 방식은 쓰지 않는다. 본문이 형식 안에 갇혀 토큰 제한에 잘리거나
 * 따옴표 이스케이프가 깨지는 순간 <b>본문까지 못 꺼낸다</b>. 채팅에서는
 * "가끔 점수가 안 뜬다"가 "가끔 답장이 안 온다"보다 낫다.
 * ({@code callWithJsonResponse}는 파싱 실패 시 LLM을 한 번 더 부르고 그것도 실패하면 예외를 던져
 * 형식 오류가 곧 전송 실패가 된다 — 그래서 쓰지 않는다.)</p>
 */
public final class AffinityScoring {

    private AffinityScoring() {}

    /** 응답 끝에 붙는 마커. 프롬프트에 이 형식 그대로 지시한다(구조화 응답 실패 시 폴백 경로에서만 쓰인다). */
    public static final String MARKER_FORMAT = "[[SCORE 자연스러움,호응,깊이,따뜻함]]";

    /** 구조화 응답(Claude tool use)의 도구명. */
    public static final String TOOL_NAME = "chat_reply";

    /**
     * 구조화 응답 스키마. 이걸 API에 실어 보내면 모델이 형식을 <b>어길 수 없다</b> —
     * 마커처럼 "붙여달라고 부탁"하는 게 아니라 생성 자체가 제약된다.
     *
     * <p>{@code minimum/maximum}으로 1~5를 스키마 레벨에서 강제하므로 범위 밖 값이 원천 차단된다.
     * 그래도 {@link Scores#clamped()}는 유지한다 — 폴백(마커) 경로에는 이 제약이 없다.</p>
     */
    public static Map<String, Object> replySchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "reply", Map.of(
                                "type", "string",
                                "description", "상대에게 보낼 대화 답변. 1~3문장. 채점 얘기는 절대 넣지 않는다."),
                        "naturalness", axis("대화가 끊기지 않고 자연스럽게 이어졌는가"),
                        "engagement", axis("질문과 호응이 오갔는가 (한쪽만 말하지 않았는가)"),
                        "depth", axis("주제가 확장되거나 깊어졌는가"),
                        "warmth", axis("감정 표현이 오갔는가")),
                "required", List.of("reply", "naturalness", "engagement", "depth", "warmth"));
    }

    private static Map<String, Object> axis(String description) {
        return Map.of("type", "integer", "minimum", 1, "maximum", 5, "description", description);
    }

    /**
     * 구조화 응답 결과를 {@link ParsedReply}로 변환. 필수 필드가 없거나 타입이 어긋나면 null 반환.
     *
     * <p>스키마가 강제하므로 정상 경로에서는 실패하지 않지만, 방어를 남긴다 —
     * 프로바이더가 바뀌거나 스키마 준수가 느슨한 모델이 붙을 수 있다.</p>
     */
    public static ParsedReply fromSchema(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        Object reply = result.get("reply");
        if (!(reply instanceof String text) || text.isBlank()) {
            return null;
        }
        Integer n = intOrNull(result.get("naturalness"));
        Integer e = intOrNull(result.get("engagement"));
        Integer d = intOrNull(result.get("depth"));
        Integer w = intOrNull(result.get("warmth"));

        // 본문은 있는데 점수가 빠진 경우 — 대화는 살리고 점수만 포기한다.
        Scores scores = (n == null || e == null || d == null || w == null)
                ? null : new Scores(n, e, d, w);

        // 스키마로 받아도 모델이 본문에 마커를 흉내 낼 수 있으니 청소는 그대로 태운다.
        return new ParsedReply(parse(text).text(), scores);
    }

    private static Integer intOrNull(Object v) {
        return v instanceof Number num ? num.intValue() : null;
    }

    /**
     * 점수 추출용 — 엄격. 4개 정수가 정확히 이 형태여야 채점으로 인정한다.
     */
    private static final Pattern PARSE = Pattern.compile(
            "\\[\\[\\s*SCORE\\s+(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]\\]");

    /**
     * 화면 청소용 — 느슨. <b>파싱 성공 여부와 무관하게 무조건 돌린다.</b>
     *
     * <p>대괄호 개수가 틀리거나(<code>[SCORE ...]</code>), 토큰 제한에 잘려 닫는 괄호가
     * 없는 조각(<code>[[SCORE 4,5,</code>)까지 지운다. 이게 없으면 파싱만 실패하고
     * 깨진 마커가 그대로 대화창에 노출된다.</p>
     */
    private static final Pattern CLEAN = Pattern.compile(
            "\\[{1,2}\\s*SCORE\\b[^\\[\\]]*\\]{0,2}");

    /**
     * 폴백 — 모델이 대괄호를 빼고 <b>숫자만</b> 마지막 줄에 쓰는 경우.
     *
     * <p>2026-08-05 실측에서 48턴 중 2턴(4.2%)이 이 형태였고, {@link #CLEAN}이 "SCORE" 키워드를
     * 찾기 때문에 걸러내지 못해 <b>{@code 4,4,3,4} 가 그대로 대화창에 노출됐다.</b></p>
     *
     * <p>조건을 아주 좁게 잡는다 — <b>마지막 줄 전체</b>가 1~5 한 자리 숫자 4개와 쉼표뿐일 때만.
     * 한국어 대화가 이 형태로 끝날 일은 사실상 없다. 범위를 {@code \d}로 넓히면
     * "2026,08,05,15" 같은 정상 문장을 삼킬 수 있어 한 자리로 제한한다.</p>
     */
    private static final Pattern BARE_TAIL = Pattern.compile(
            "(?:\\R|^)\\s*([1-5])\\s*,\\s*([1-5])\\s*,\\s*([1-5])\\s*,\\s*([1-5])\\s*$");

    private static final int AXIS_MIN = 1;
    private static final int AXIS_MAX = 5;

    /** 4축 합의 최솟값(전부 1점). 이 값이 곧 점수 하한 70의 근거다. */
    private static final int SUM_MIN = AXIS_MIN * 4;
    /** 4축 합의 폭(20-4). 25점 구간을 이 폭에 매핑한다. */
    private static final int SUM_RANGE = (AXIS_MAX * 4) - SUM_MIN;

    private static final double BASE = 70.0;
    private static final double SPAN = 25.0;

    /** EMA 가중치. 반감기 약 2턴 — 나가기 직전에 보여주는 지표라 최근 대화가 지배해야 한다. */
    private static final double ALPHA = 0.3;

    /** 표시 클램프. 축 단위 clamp가 있으면 발동하지 않는 안전망이다. */
    private static final int DISPLAY_MIN = 70;
    private static final int DISPLAY_MAX = 96;

    /** 4축 원점수(각 1~5). */
    public record Scores(int naturalness, int engagement, int depth, int warmth) {

        /** 합산 <b>전에</b> 각 축을 1~5로 자른다. LLM이 0이나 7을 주는 경우가 있다. */
        Scores clamped() {
            return new Scores(clamp(naturalness), clamp(engagement), clamp(depth), clamp(warmth));
        }

        private static int clamp(int v) {
            return Math.max(AXIS_MIN, Math.min(AXIS_MAX, v));
        }

        int sum() {
            return naturalness + engagement + depth + warmth;
        }

        /** 4축이 전부 같은 값인가. 동조가 심하면 점수가 5가지로 좁아져 루브릭 조정이 필요하다. */
        public boolean uniform() {
            return naturalness == engagement && engagement == depth && depth == warmth;
        }
    }

    /**
     * 마커를 떼어낸 결과.
     *
     * @param text   사용자에게 보여줄 본문. 마커 조각이 제거된 상태다.
     * @param scores 채점 결과. <b>파싱 실패면 null</b> — 이때 호감도는 갱신하지 않고 직전 값을 유지한다.
     */
    public record ParsedReply(String text, Scores scores) {
        public boolean scored() {
            return scores != null;
        }
    }

    /**
     * AI 원문에서 마커를 분리한다.
     *
     * <p>순서가 중요하다 — <b>점수 추출보다 청소를 뒤에 두되, 청소는 실패 여부와 무관하게 항상 수행한다.</b>
     * 마커가 여러 개면 마지막 것을 점수로 쓰고 전부 지운다(모델이 중간에 끼워 넣는 경우가 있다).</p>
     *
     * @return 본문과 점수. 본문이 비면 {@code text}가 빈 문자열이며, 호출측이 실패로 처리해야 한다.
     */
    public static ParsedReply parse(String raw) {
        if (raw == null) {
            return new ParsedReply("", null);
        }

        Scores scores = null;
        Matcher m = PARSE.matcher(raw);
        while (m.find()) { // 마커가 여러 개면 마지막 것을 채택
            scores = new Scores(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)));
        }

        // 마커를 지운 자리에 빈 줄이 남는다(모델이 본문 중간에 끼워 넣은 경우). 3줄 이상 개행은 2줄로 접는다.
        String text = CLEAN.matcher(raw).replaceAll("")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();

        // 마커가 없으면 "숫자만 있는 마지막 줄"을 폴백으로 본다. 점수를 얻지 못하더라도
        // 이 줄은 반드시 제거해야 한다 — 실측에서 사용자 화면에 그대로 노출됐다.
        if (scores == null) {
            Matcher bare = BARE_TAIL.matcher(text);
            if (bare.find()) {
                scores = new Scores(
                        Integer.parseInt(bare.group(1)),
                        Integer.parseInt(bare.group(2)),
                        Integer.parseInt(bare.group(3)),
                        Integer.parseInt(bare.group(4)));
                text = text.substring(0, bare.start()).strip();
            }
        }

        return new ParsedReply(text, scores);
    }

    /**
     * 이번 1왕복의 점수. 4축 전부 1점이면 70.0, 전부 5점이면 95.0.
     *
     * <p>하한 70이 클램프가 아니라 <b>식으로 보장된다</b>.</p>
     */
    public static double turnScore(Scores raw) {
        Scores s = raw.clamped();
        return BASE + (s.sum() - SUM_MIN) * SPAN / SUM_RANGE;
    }

    /**
     * 누적값 갱신. 첫 채점이면 턴 점수를 그대로 시작값으로 쓴다.
     *
     * <p>시드를 두지 않는 이유 — 상수로 시작하면 1턴째 화면 숫자의 70%가 그 상수가 된다.</p>
     */
    public static double ema(Double previous, double turnScore) {
        return previous == null ? turnScore : ALPHA * turnScore + (1 - ALPHA) * previous;
    }

    /** 저장된 원값(반올림 전)을 표시용 정수로. */
    public static int display(double affinity) {
        return Math.max(DISPLAY_MIN, Math.min(DISPLAY_MAX, (int) Math.round(affinity)));
    }
}
