package mildo.persona.service;

// 발췌: 리포트 호칭 자리표시 채우기. 설명: docs/engineering-decisions.md §22

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 리포트 문장 속 호칭 자리표시 {@value #PLACEHOLDER} 를 <b>보여 주는 순간의</b> 닉네임으로 채운다.
 *
 * <p>리포트(personas.cached_report)는 한 번 만들어 캐시한다. 예전에는 만든 시점의 닉네임을 문장에 박아
 * 저장했는데, 닉네임을 바꾼 회원도 리포트에는 옛 닉네임이 남아 다른 사람이 보는 프로필 상세에 그대로 나갔다.
 * 그래서 프롬프트에는 이름을 주지 않고 자리표시만 쓰게 하고, 응답을 만들 때 현재 닉네임으로 바꾼다.</p>
 *
 * <p>닉네임이 비어 있으면 다른 개인정보(실명 등)로 대신하지 않는다 — {@value #FALLBACK} 로 채운다.</p>
 */
public final class ReportNickname {

    public static final String PLACEHOLDER = "{닉네임}";
    static final String FALLBACK = "회원";

    private ReportNickname() {}

    public static String fill(String text, String nickname) {
        if (text == null || !text.contains(PLACEHOLDER)) {
            return text;
        }
        String name = (nickname != null && !nickname.isBlank()) ? nickname : FALLBACK;
        // 닉네임이 「공주님」처럼 님으로 끝나면 「{닉네임}님」이 「공주님님」이 된다
        String filled = name.endsWith("님") ? text.replace(PLACEHOLDER + "님", name) : text;
        return filled.replace(PLACEHOLDER, name);
    }

    /** 리포트 맵 전체(중첩 맵·리스트 포함)의 문자열을 채운 사본. 원본(엔티티 캐시)은 건드리지 않는다. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fill(Map<String, Object> report, String nickname) {
        return report == null ? null : (Map<String, Object>) fillNode(report, nickname);
    }

    private static Object fillNode(Object node, String nickname) {
        if (node instanceof String s) {
            return fill(s, nickname);
        }
        if (node instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k, fillNode(v, nickname)));
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(v -> out.add(fillNode(v, nickname)));
            return out;
        }
        return node;
    }
}
