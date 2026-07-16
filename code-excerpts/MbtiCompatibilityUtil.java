package mildo.explore.util;

import java.util.HashMap;
import java.util.Map;

/**
 * MBTI 궁합 점수 계산 유틸리티
 * mildo_persona_design.md 기준
 */
public class MbtiCompatibilityUtil {

    private static final Map<String, Map<String, Integer>> COMPATIBILITY_MAP = new HashMap<>();

    static {
        // INFP 기준
        COMPATIBILITY_MAP.put("INFP", Map.ofEntries(
                Map.entry("ENFJ", 100), Map.entry("ENTJ", 100),
                Map.entry("INFP", 80), Map.entry("ENFP", 80), Map.entry("INFJ", 80), Map.entry("INTJ", 80),
                Map.entry("INTP", 60), Map.entry("ENTP", 60),
                Map.entry("ISFP", 40), Map.entry("ESFP", 40), Map.entry("ISTP", 40), Map.entry("ESTP", 40),
                Map.entry("ISFJ", 20), Map.entry("ESFJ", 20), Map.entry("ISTJ", 20), Map.entry("ESTJ", 20)
        ));

        // INFJ 기준
        COMPATIBILITY_MAP.put("INFJ", Map.ofEntries(
                Map.entry("ENFP", 100), Map.entry("ENTP", 100),
                Map.entry("INFP", 80), Map.entry("INFJ", 80), Map.entry("ENFJ", 80), Map.entry("INTJ", 80),
                Map.entry("ENTJ", 80), Map.entry("INTP", 80),
                Map.entry("ISFP", 40), Map.entry("ESFP", 40), Map.entry("ISTP", 40), Map.entry("ESTP", 40),
                Map.entry("ISFJ", 20), Map.entry("ESFJ", 20), Map.entry("ISTJ", 20), Map.entry("ESTJ", 20)
        ));

        // INTP 기준
        COMPATIBILITY_MAP.put("INTP", Map.ofEntries(
                Map.entry("ENTJ", 100), Map.entry("ESTJ", 100),
                Map.entry("INFP", 80), Map.entry("ENFP", 80), Map.entry("INFJ", 80), Map.entry("ENFJ", 80),
                Map.entry("INTJ", 80), Map.entry("INTP", 80), Map.entry("ENTP", 80),
                Map.entry("ISFP", 40), Map.entry("ESFP", 40), Map.entry("ISTP", 40), Map.entry("ESTP", 40),
                Map.entry("ISFJ", 20), Map.entry("ESFJ", 20), Map.entry("ISTJ", 20)
        ));

        // INTJ 기준
        COMPATIBILITY_MAP.put("INTJ", Map.ofEntries(
                Map.entry("ENFP", 100), Map.entry("ENTP", 100),
                Map.entry("INFP", 80), Map.entry("INFJ", 80), Map.entry("ENFJ", 80), Map.entry("INTJ", 80),
                Map.entry("ENTJ", 80), Map.entry("INTP", 80),
                Map.entry("ISFP", 40), Map.entry("ESFP", 40), Map.entry("ISTP", 40), Map.entry("ESTP", 40),
                Map.entry("ISFJ", 20), Map.entry("ESFJ", 20), Map.entry("ISTJ", 20), Map.entry("ESTJ", 20)
        ));

        // ISFP 기준
        COMPATIBILITY_MAP.put("ISFP", Map.ofEntries(
                Map.entry("ENFJ", 100), Map.entry("ESFJ", 100), Map.entry("ESTJ", 100),
                Map.entry("ISFP", 70), Map.entry("ESFP", 70), Map.entry("ISTP", 70), Map.entry("ESTP", 70),
                Map.entry("ISFJ", 50), Map.entry("ISTJ", 50), Map.entry("INTP", 50), Map.entry("ENTP", 50),
                Map.entry("INFP", 20), Map.entry("ENFP", 20), Map.entry("INFJ", 20), Map.entry("INTJ", 20), Map.entry("ENTJ", 20)
        ));

        // ISFJ 기준
        COMPATIBILITY_MAP.put("ISFJ", Map.ofEntries(
                Map.entry("ESFP", 100), Map.entry("ESTP", 100),
                Map.entry("ISFJ", 80), Map.entry("ESFJ", 80), Map.entry("ISTJ", 80), Map.entry("ESTJ", 80),
                Map.entry("ISFP", 50), Map.entry("ISTP", 50), Map.entry("INTP", 50), Map.entry("ENTP", 50),
                Map.entry("INFP", 20), Map.entry("ENFP", 20), Map.entry("INFJ", 20), Map.entry("ENFJ", 20),
                Map.entry("INTJ", 20), Map.entry("ENTJ", 20)
        ));

        // ISTP 기준
        COMPATIBILITY_MAP.put("ISTP", Map.ofEntries(
                Map.entry("ESFJ", 100), Map.entry("ESTJ", 100),
                Map.entry("ISFP", 70), Map.entry("ESFP", 70), Map.entry("ISTP", 70), Map.entry("ESTP", 70),
                Map.entry("ISFJ", 50), Map.entry("ISTJ", 50), Map.entry("INTP", 50), Map.entry("ENTP", 50),
                Map.entry("INFP", 20), Map.entry("ENFP", 20), Map.entry("INFJ", 20), Map.entry("ENFJ", 20),
                Map.entry("INTJ", 20), Map.entry("ENTJ", 20)
        ));

        // ISTJ 기준
        COMPATIBILITY_MAP.put("ISTJ", Map.ofEntries(
                Map.entry("ESFP", 100), Map.entry("ESTP", 100),
                Map.entry("ISFJ", 80), Map.entry("ESFJ", 80), Map.entry("ISTJ", 80), Map.entry("ESTJ", 80),
                Map.entry("ISFP", 50), Map.entry("ISTP", 50), Map.entry("INTP", 50), Map.entry("ENTP", 50),
                Map.entry("INFP", 20), Map.entry("ENFP", 20), Map.entry("INFJ", 20), Map.entry("ENFJ", 20),
                Map.entry("INTJ", 20), Map.entry("ENTJ", 20)
        ));

        // ENFP 기준
        COMPATIBILITY_MAP.put("ENFP", Map.ofEntries(
                Map.entry("INFJ", 100), Map.entry("INTJ", 100),
                Map.entry("INFP", 80), Map.entry("ENFP", 80), Map.entry("ENFJ", 80), Map.entry("ENTJ", 80),
                Map.entry("INTP", 60), Map.entry("ENTP", 60),
                Map.entry("ISFP", 40), Map.entry("ESFP", 40), Map.entry("ISTP", 40), Map.entry("ESTP", 40),
                Map.entry("ISFJ", 20), Map.entry("ESFJ", 20), Map.entry("ISTJ", 20), Map.entry("ESTJ", 20)
        ));

        // ENFJ 기준
        COMPATIBILITY_MAP.put("ENFJ", Map.ofEntries(
                Map.entry("INFP", 100), Map.entry("ISFP", 100),
                Map.entry("ENFP", 80), Map.entry("INFJ", 80), Map.entry("ENFJ", 80), Map.entry("INTJ", 80),
                Map.entry("ENTJ", 80), Map.entry("INTP", 80), Map.entry("ENTP", 80),
                Map.entry("ESFP", 40), Map.entry("ISTP", 40), Map.entry("ESTP", 40),
                Map.entry("ISFJ", 20), Map.entry("ESFJ", 20), Map.entry("ISTJ", 20), Map.entry("ESTJ", 20)
        ));

        // ENTP 기준
        COMPATIBILITY_MAP.put("ENTP", Map.ofEntries(
                Map.entry("INFJ", 100), Map.entry("INTJ", 100),
                Map.entry("INFP", 80), Map.entry("ENFP", 80), Map.entry("ENFJ", 80), Map.entry("ENTJ", 80),
                Map.entry("INTP", 80), Map.entry("ENTP", 80),
                Map.entry("ISFP", 40), Map.entry("ESFP", 40), Map.entry("ISTP", 40), Map.entry("ESTP", 40),
                Map.entry("ISFJ", 20), Map.entry("ESFJ", 20), Map.entry("ISTJ", 20), Map.entry("ESTJ", 20)
        ));

        // ENTJ 기준
        COMPATIBILITY_MAP.put("ENTJ", Map.ofEntries(
                Map.entry("INFP", 100), Map.entry("INTP", 100),
                Map.entry("ENFP", 80), Map.entry("INFJ", 80), Map.entry("ENFJ", 80), Map.entry("INTJ", 80),
                Map.entry("ENTJ", 80), Map.entry("ENTP", 80),
                Map.entry("ISFP", 40), Map.entry("ESFP", 40), Map.entry("ISTP", 40), Map.entry("ESTP", 40),
                Map.entry("ISFJ", 20), Map.entry("ESFJ", 20), Map.entry("ISTJ", 20), Map.entry("ESTJ", 20)
        ));

        // ESFP 기준
        COMPATIBILITY_MAP.put("ESFP", Map.ofEntries(
                Map.entry("ISFJ", 100), Map.entry("ISTJ", 100),
                Map.entry("ISFP", 70), Map.entry("ESFP", 70), Map.entry("ISTP", 70), Map.entry("ESTP", 70),
                Map.entry("ESFJ", 50), Map.entry("ESTJ", 50), Map.entry("INTP", 50), Map.entry("ENTP", 50),
                Map.entry("INFP", 20), Map.entry("ENFP", 20), Map.entry("INFJ", 20), Map.entry("ENFJ", 20),
                Map.entry("INTJ", 20), Map.entry("ENTJ", 20)
        ));

        // ESFJ 기준
        COMPATIBILITY_MAP.put("ESFJ", Map.ofEntries(
                Map.entry("ISFP", 100), Map.entry("ISTP", 100),
                Map.entry("ISFJ", 80), Map.entry("ESFJ", 80), Map.entry("ISTJ", 80), Map.entry("ESTJ", 80),
                Map.entry("ESFP", 50), Map.entry("ESTP", 50), Map.entry("INTP", 50), Map.entry("ENTP", 50),
                Map.entry("INFP", 20), Map.entry("ENFP", 20), Map.entry("INFJ", 20), Map.entry("ENFJ", 20),
                Map.entry("INTJ", 20), Map.entry("ENTJ", 20)
        ));

        // ESTP 기준
        COMPATIBILITY_MAP.put("ESTP", Map.ofEntries(
                Map.entry("ISFJ", 100), Map.entry("ISTJ", 100),
                Map.entry("ISFP", 70), Map.entry("ESFP", 70), Map.entry("ISTP", 70), Map.entry("ESTP", 70),
                Map.entry("ESFJ", 50), Map.entry("ESTJ", 50), Map.entry("INTP", 50), Map.entry("ENTP", 50),
                Map.entry("INFP", 20), Map.entry("ENFP", 20), Map.entry("INFJ", 20), Map.entry("ENFJ", 20),
                Map.entry("INTJ", 20), Map.entry("ENTJ", 20)
        ));

        // ESTJ 기준
        COMPATIBILITY_MAP.put("ESTJ", Map.ofEntries(
                Map.entry("ISFP", 100), Map.entry("ISTP", 100), Map.entry("INTP", 100),
                Map.entry("ISFJ", 80), Map.entry("ESFJ", 80), Map.entry("ISTJ", 80), Map.entry("ESTJ", 80),
                Map.entry("ESFP", 50), Map.entry("ESTP", 50), Map.entry("ENTP", 50),
                Map.entry("INFP", 20), Map.entry("ENFP", 20), Map.entry("INFJ", 20), Map.entry("ENFJ", 20),
                Map.entry("INTJ", 20), Map.entry("ENTJ", 20)
        ));
    }

    /**
     * 두 MBTI 간의 궁합 점수 반환
     * @param myMbti 나의 MBTI
     * @param targetMbti 상대방 MBTI
     * @return 궁합 점수 (0-100), 알 수 없는 경우 50
     */
    public static int getCompatibilityScore(String myMbti, String targetMbti) {
        if (myMbti == null || targetMbti == null) {
            return 50;
        }

        String my = myMbti.toUpperCase().trim();
        String target = targetMbti.toUpperCase().trim();

        Map<String, Integer> scores = COMPATIBILITY_MAP.get(my);
        if (scores == null) {
            return 50;
        }

        return scores.getOrDefault(target, 50);
    }
}
