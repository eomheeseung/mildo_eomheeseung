package mildo.aidate.service;

// 발췌: 페르소나 소개팅 궁합 점수. 설명: docs/design/engineering-decisions.md §28

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 페르소나 소개팅 궁합 점수 — 대화가 아니라 <b>두 페르소나의 네 축 점수</b>(axis_scores)로 코드가 낸다.
 *
 * <p>대화도 AI 가 쓰고 그 대화를 AI 가 채점하면 늘 80~99 로 몰린다(11/06 로컬 4건: 99·91·88·80). 축 점수는 사람마다
 * 실제로 다르고, 같은 두 사람이면 언제 돌려도 같은 점수다. 통합 리포트 궁합 게이지와 같은 데이터다.</p>
 *
 * <p>점수 = 100 − (네 축 차이 평균 × 2), 0~100. 차이를 그대로 쓰면(100 − 평균 차이) 평균 차이가 작아 다시 75~95 로
 * 몰려서 2배로 벌린다. 같은 4쌍이 90·69·58·51 로 퍼진다.</p>
 */
public final class AiDateScore {

    /** 축 키 → 프롬프트·설명용 이름 (통합 리포트 게이지와 같은 짝) */
    static final Map<String, String> AXES = Map.of(
            "social", "성격",
            "value", "가치관",
            "energy", "생활패턴",
            "reality", "커리어·경제관");
    private static final List<String> ORDER = List.of("social", "value", "energy", "reality");

    static final int AXIS_DEFAULT = 50;
    static final int SPREAD = 2;
    /**
     * 이 차이 이하면 「비슷」, 이 이상이면 「많이 다름」. 11/06 남녀 300×300 쌍 실측으로 정했다:
     * 15/35 이면 비슷 56%·조금 34%·많이 10% 로 긍정에 쏠려서, 10/25 로 낮춰 42%·32%·26% 로 고르게 했다.
     * (축 점수는 MBTI 45/55 + 설문 ±20 이라 실제 범위가 25~90 이고 차이가 크게 벌어지지 않는다)
     */
    static final int SIMILAR_MAX = 10;
    static final int DIFFERENT_MIN = 25;

    private AiDateScore() {
    }

    public static int score(Map<String, Integer> a, Map<String, Integer> b) {
        double sum = 0;
        for (String axis : ORDER) {
            sum += Math.abs(val(a, axis) - val(b, axis));
        }
        double avgDiff = sum / ORDER.size();
        long s = Math.round(100 - avgDiff * SPREAD);
        return (int) Math.max(0, Math.min(100, s));
    }

    /**
     * AI 에게 줄 축별 비교 — 대화·궁합 한 줄이 점수와 같은 방향으로 쓰이게. 예: 「가치관: 비슷함」「생활패턴: 많이 다름」.
     * 숫자는 주지 않는다(AI 가 숫자를 문장에 옮겨 적지 않게).
     */
    public static List<String> axisNotes(Map<String, Integer> a, Map<String, Integer> b) {
        List<String> out = new ArrayList<>();
        for (String axis : ORDER) {
            int d = Math.abs(val(a, axis) - val(b, axis));
            String level = d <= SIMILAR_MAX ? "비슷함" : (d >= DIFFERENT_MIN ? "많이 다름" : "조금 다름");
            out.add(AXES.get(axis) + ": " + level);
        }
        return out;
    }

    /**
     * 결과 화면 퍼센트 아래 「왜 이 점수인지」 — 축별 비교(앱용). AI 가 아니라 코드라 점수와 항상 맞는다.
     * 예: {label:"생활패턴", level:"DIFFERENT", text:"많이 달라요"}. level = SIMILAR | SLIGHT | DIFFERENT
     */
    public static List<Map<String, Object>> axesForApp(Map<String, Integer> a, Map<String, Integer> b) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String axis : ORDER) {
            int d = Math.abs(val(a, axis) - val(b, axis));
            String level = d <= SIMILAR_MAX ? "SIMILAR" : (d >= DIFFERENT_MIN ? "DIFFERENT" : "SLIGHT");
            String text = switch (level) {
                case "SIMILAR" -> "비슷해요";
                case "DIFFERENT" -> "많이 달라요";
                default -> "조금 달라요";
            };
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("label", AXES.get(axis));
            m.put("level", level);
            m.put("text", text);
            out.add(m);
        }
        return out;
    }

    /** 점수 구간 → AI 에게 줄 톤 지시 */
    public static String tone(int score) {
        if (score >= 80) {
            return "잘 맞는 편 — 대화가 편하게 이어지고 공감하는 순간이 많게";
        }
        if (score >= 60) {
            return "무난함 — 통하는 부분과 다른 부분이 섞이게";
        }
        if (score >= 40) {
            return "차이가 꽤 있음 — 다른 부분에서 대화가 살짝 어긋나거나 머뭇거리게(비난·싸움은 금지)";
        }
        return "많이 다름 — 서로 다른 점이 분명히 드러나고 공감이 적게(비난·싸움은 금지)";
    }

    private static int val(Map<String, Integer> m, String key) {
        if (m == null) {
            return AXIS_DEFAULT;
        }
        Integer v = m.get(key);
        return v == null ? AXIS_DEFAULT : v;
    }
}
