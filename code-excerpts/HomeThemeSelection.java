package mildo.theme.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 홈 「테마로 찾아보기」 후보 선발 — 결정적 정렬이 만든 「매일 같은 사람」을 고친 부분.
 *
 * <p><b>처음 정렬은 전부 결정적이었다.</b> 등급(유사도·지역) → 최근 접속 → 프로필 완성도 → id.
 * 등급은 거의 안 변하는 값이라 <b>상위 3명이 내일도 같은 3명</b>이었다. 순서가 흔들리는 건
 * 누군가 접속해 {@code lastVisit}이 움직일 때뿐인데 상위권이 통째로 바뀌지는 않는다.
 * 상세 화면이 20명 → 3명으로 줄면서 그게 그대로 드러났다.</p>
 *
 * <p><b>같은 함정을 이미 두 번 겪었다.</b> 홈 캐러셀은 노출 칸이 10개인데 후보 풀이 6·11명이라
 * 섞어도 결국 전원이 나왔다 — 셔플 코드는 멀쩡했고 <b>발동 조건이 안 맞았을 뿐</b>이다.
 * 그래서 이번에는 코드 리뷰 대신 <b>등급 안 후보 수를 운영 데이터로 셌다</b>
 * (남성 뷰어 54명 / 여성 뷰어 215명 vs 노출 3명 — 캐러셀이 죽었던 조건에 걸리는 뷰어 0명).</p>
 *
 * <p>정렬 순서는 <b>등급 → 사진 유무 → 최근 노출 → 셔플</b>이다. 사진 유무를 남긴 것은
 * 요청 사양과 다른 판단인데, 완성도 정렬을 전부 셔플로 갈면 사진 없는 계정이 아바타 자리에
 * 올라오기 때문이다(운영 실측: 여성 풀 57명 중 31명이 사진 없음). 홈 카드는 얼굴 3개가 전부라
 * 두 갈래로만 나누고 각 갈래 안에서 셔플이 돌게 했다. 완성도 전체를 되살리면 그건 다시
 * 결정적 정렬이라 원래 문제로 돌아간다.</p>
 */
public class HomeThemeSelection {

    /** 홈 카드 아바타 수 = 테마가 성립하기 위한 최소 인원. */
    private static final int HOME_AVATARS = 3;

    /**
     * 한 테마에 예약하는 인원. <b>노출 수가 아니라 이 값만큼 {@code taken}에 잡힌다</b> —
     * 한 사람은 하루에 한 테마에만 나가므로 앞 테마가 예약한 만큼 뒤 테마의 후보가 줄어든다.
     *
     * <p>원래 20이었다. 상세가 3명으로 줄어 20을 쥘 이유가 없어졌고, 20이면 작은 풀에서
     * 세 번째 테마가 굶는다 — 풀 35명이면 {@code 20 + 15 + 0}으로 테마 하나가 통째로 사라진다.
     * 6이면 {@code 6+6+6 = 18}이라 풀이 20명대여도 세 테마가 다 뜬다.</p>
     */
    private static final int LIST_CAPACITY = 6;

    /** 최근 이 일수 안에 노출된 사람은 후순위로 민다. <b>제외가 아니라 후순위</b>라 후보가 모자라면 다시 올라온다. */
    private static final int RECENT_EXPOSURE_DAYS = 2;

    /** 이 일수만큼 연속 노출됐으면 하루 쉰다. 후보가 적을 때 후순위만으로는 계속 올라오기 때문이다. */
    private static final int CONSECUTIVE_LIMIT = 3;

    /**
     * 한 테마의 인원 선발.
     *
     * <p>「3일 연속 노출자 제외」는 <b>테마가 사라질 것 같으면 푼다</b> — 반복을 줄이려다
     * 카드를 통째로 잃는 건 손해가 더 크다.</p>
     */
    private List<User> pick(ThemeType type, User viewer, List<User> pool, Set<Long> taken,
                            ExposureHistory history, LocalDate today) {
        List<User> picked = select(type, viewer, pool, taken, history, today, true);
        if (picked.size() < HOME_AVATARS) {
            picked = select(type, viewer, pool, taken, history, today, false);
        }
        return picked;
    }

    private List<User> select(ThemeType type, User viewer, List<User> pool, Set<Long> taken,
                              ExposureHistory history, LocalDate today, boolean dropConsecutive) {
        Map<Long, Integer> tiers = new HashMap<>();
        List<User> candidates = new ArrayList<>();
        for (User u : pool) {
            if (taken.contains(u.getId())) {
                continue;
            }
            if (dropConsecutive && history.consecutive().contains(u.getId())) {
                continue;
            }
            int tier = tierOf(type, viewer, u);
            if (tier == TIER_EXCLUDED) {
                continue;
            }
            tiers.put(u.getId(), tier);
            candidates.add(u);
        }

        long viewerId = viewer.getId();
        return candidates.stream()
                .sorted(Comparator
                        .comparingInt((User u) -> tiers.get(u.getId()))                       // 조건 부합도
                        .thenComparingInt(u -> hasPhoto(u) ? 0 : 1)                           // 빈 카드는 뒤로
                        .thenComparingInt(u -> history.recent().contains(u.getId()) ? 1 : 0)  // 최근 노출자 후순위
                        .thenComparingLong(u -> shuffleKey(viewerId, u.getId(), today)))      // 등급 안 일일 셔플
                .limit(LIST_CAPACITY)
                .toList();
    }

    /**
     * 일일 셔플 키. 같은 (뷰어, 상대, 날짜)면 항상 같은 값이라 <b>저장하지 않아도 재현</b>된다 —
     * 캐시가 없어 같은 날 다시 계산해도 순서가 같다.
     *
     * <p>{@code Objects.hash}를 쓰지 않는 이유 — 값이 작고 뭉쳐 있어 정렬이 사실상 id 순으로
     * 돌아간다. 64비트로 흩뜨려야 등급 안에서 고르게 섞인다.</p>
     */
    private static long shuffleKey(long viewerId, long candidateId, LocalDate date) {
        long h = viewerId * 0x9E3779B97F4A7C15L;
        h ^= candidateId * 0xC2B2AE3D27D4EB4FL;
        h ^= date.toEpochDay() * 0x165667B19E3779F9L;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return h;
    }

    /**
     * 반복 노출 판정 근거를 지난 편성에서 뽑는다.
     *
     * <p><b>예약된 전원이 아니라 실제로 화면에 뜬 앞 3명만 센다.</b> 편성은 6명을 예약하지만
     * 홈 카드도 상세도 3명만 보여준다 — 예약만 되고 한 번도 안 보인 사람까지 후순위로 미는 건
     * 근거가 없다.</p>
     *
     * <p>연속은 교집합이다. 하루라도 편성이 비면(신규 유저·미접속) 연속이 성립하지 않는다.</p>
     */
    private ExposureHistory loadExposure(Long viewerId, LocalDate today) {
        List<HomeThemeCache> rows = themeCacheRepository.findByUserIdAndThemeDateBetween(
                viewerId, today.minusDays(CONSECUTIVE_LIMIT), today.minusDays(1));
        if (rows.isEmpty()) {
            return ExposureHistory.empty();
        }

        Map<LocalDate, Set<Long>> byDate = new HashMap<>();
        for (HomeThemeCache row : rows) {
            byDate.computeIfAbsent(row.getThemeDate(), d -> new HashSet<>())
                    .addAll(parseIds(row.getUserIds()).stream().limit(HOME_AVATARS).toList());
        }

        Set<Long> recent = new HashSet<>();
        for (int back = 1; back <= RECENT_EXPOSURE_DAYS; back++) {
            recent.addAll(byDate.getOrDefault(today.minusDays(back), Set.of()));
        }

        Set<Long> consecutive = null;
        for (int back = 1; back <= CONSECUTIVE_LIMIT; back++) {
            Set<Long> day = byDate.get(today.minusDays(back));
            if (day == null || day.isEmpty()) {
                consecutive = Set.of();
                break;
            }
            if (consecutive == null) {
                consecutive = new HashSet<>(day);
            } else {
                consecutive.retainAll(day);
            }
        }
        return new ExposureHistory(recent, consecutive == null ? Set.of() : consecutive);
    }

    /**
     * 배정은 <b>후보가 적은 테마부터</b> 한다. 표시 순서는 선언 순서 그대로라 화면은 ①②③이다.
     *
     * <p>선언 순서(①→②→③)로 배정하면 「지금 활동 중」이 구조적으로 굶는다. 앞 테마가 정원을
     * 채울 때 <b>하필 최근 접속자부터</b> 데려가는데, 그게 활동 테마가 필요로 하는 바로 그
     * 사람들이기 때문이다. 로컬 실험에서 후보로 살아있던 최근 접속자 7명이 <b>전원</b>
     * 유사도 테마로 갔고 활동 테마는 편성조차 되지 않았다.</p>
     *
     * <p>특정 테마를 하드코딩해 앞세우지 않은 이유는, 나중에 다른 축(예: 밸런스 투표 기반)으로
     * 테마를 교체해도 <b>그 테마 역시 초기엔 모수가 적어 똑같이 굶기</b> 때문이다.
     * 규칙으로 두면 새 테마가 들어와도 알아서 먼저 배정된다.</p>
     */
    private List<ThemeType> assignOrder(User viewer, List<User> pool) {
        return Arrays.stream(ThemeType.values())
                .sorted(Comparator.comparingInt(t -> eligibleCount(t, viewer, pool)))
                .toList();
    }
}
