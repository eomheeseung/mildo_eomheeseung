package mildo.explore.service.assembler;

import mildo.auth.entity.User;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 나이 차 필터 — 빼기만 하면 화면이 빈다, 모자랄 때만 넓힌다 (발췌, 결정 §33).
 *
 * <p>남녀 모두 나와 ±{@code gapYears}살인 상대만 보인다. 그 안의 사람이 {@code fillMin}명보다 적으면
 * 나이가 가까운 순으로 ±{@code maxYears}살까지만 넓혀 채운다. 세 값은 설정이라 배포 없이 되돌린다.</p>
 *
 * <p><b>왜 채움이 필요한가.</b> 여성 쪽은 남성 후보가 많아 ±5로도 수백 명이 남지만, 남성 쪽은 활동 여성이 적어
 * ±5로 자르면 남성 103명 중 65명의 화면이 10명 미만이 됐다(7일 방문자 실측).</p>
 *
 * <p>후보 필터 안에서 <b>휴면 회원 채움보다 먼저</b> 돈다 — 채움도 나이 범위 안에서만 고르게.</p>
 */
class AgeGapFill {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final int gapYears;   // 기본 5 — 0 이면 나이로 거르지 않는다
    private final int maxYears;   // 기본 10
    private final int fillMin;    // 기본 10

    AgeGapFill(int gapYears, int maxYears, int fillMin) {
        this.gapYears = gapYears;
        this.maxYears = maxYears;
        this.fillMin = fillMin;
    }

    /**
     * 생년월일을 모르는 후보는 남긴다(「모르면 제외하지 않는다」). 내 생년월일을 모르면 거르지 않는다.
     * 순서는 넘겨받은 그대로 둔다 — 정렬은 호출부(추천 줄 세우기)의 몫이다.
     */
    List<User> apply(User viewer, List<User> eligible) {
        Integer viewerAge = ageOf(viewer.getBirthDate());
        if (gapYears <= 0 || viewerAge == null) {
            return eligible;
        }
        Set<Long> keep = new HashSet<>();
        List<User> wider = new ArrayList<>();
        for (User u : eligible) {
            Integer age = ageOf(u.getBirthDate());
            if (age == null || Math.abs(age - viewerAge) <= gapYears) {
                keep.add(u.getId());
            } else if (Math.abs(age - viewerAge) <= maxYears) {
                wider.add(u);
            }
        }
        int fill = fillMin - keep.size();
        if (fill > 0 && !wider.isEmpty()) {
            // 넓힐 때는 「아무나」가 아니라 나이가 가까운 사람부터
            wider.stream()
                    .sorted(Comparator.comparingInt(u -> Math.abs(ageOf(u.getBirthDate()) - viewerAge)))
                    .limit(fill)
                    .forEach(u -> keep.add(u.getId()));
        }
        return eligible.stream().filter(u -> keep.contains(u.getId())).toList();
    }

    /** 만 나이(KST 오늘 기준). 생년월일이 없으면 null. */
    private static Integer ageOf(LocalDate birthDate) {
        return birthDate == null ? null : Period.between(birthDate, LocalDate.now(KST)).getYears();
    }
}
