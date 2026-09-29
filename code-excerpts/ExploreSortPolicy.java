package mildo.explore.service.assembler;

import mildo.auth.entity.User;
import mildo.explore.dto.ExploreUserResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.Comparator;
import java.util.Map;

/**
 * 추천 줄 세우기 — <b>발췌</b>: v2 탐색·홈 추천이 공유하는 우선순위 비교자와 셔플 해시.
 * (v1 점수 정렬, 홈 테마용 나이 판정 {@code isAgeAcceptable} 등은 생략)
 *
 * <p>이 클래스의 원칙은 세 가지다.</p>
 * <ol>
 *   <li><b>제외 대신 후순위</b> — 풀이 작아서 빼는 순간 배치가 안 찬다. 나이·휴면·사진 모두 "뒤로" 보낼 뿐 빼지 않는다.</li>
 *   <li><b>정확값 대신 버킷</b> — 정확한 타임스탬프·점수로 비교하면 동점이 없어 뒤의 셔플이 영영 실행되지 않는다.</li>
 *   <li><b>회전을 살리는 비교자 순서</b> — 「마지막 노출일」이 굳은 기준(사진·접속)보다 위에 있어야 본 사람이 뒤로 빠진다.</li>
 * </ol>
 *
 * <p>비교자는 한 줄로 끝나지만, 각 순위의 <b>위치</b>가 운영 실측으로 몇 번씩 옮겨진 결과다.
 * 아래 주석은 왜 그 자리에 있는지를 남긴 것이다.</p>
 */
@Component
public class ExploreSortPolicy {

    // 고정 시드 셔플용 큰 소수 (Knuth multiplicative hashing)
    private static final long SHUFFLE_MULTIPLIER = 2654435761L;

    /**
     * [v2 탐색] 우선순위 비교자.
     * <ol>
     *   <li>1차: <b>나이 근접 티어</b>(또래 먼저) — 뷰어 나이대별 tolerance로 소프트 가중(제외 아님)</li>
     *   <li>2차: <b>30일 내 접속 여부</b>(활성 먼저) — 제외가 아니라 후순위</li>
     *   <li>3차: <b>마지막 노출일 오름차순</b> — 미노출자가 맨 앞, 어제 본 상대가 맨 뒤(순환 큐)</li>
     *   <li>4차: <b>사진 유무</b> — 점수가 아니라 유무만</li>
     *   <li>5차: <b>최근 접속 버킷</b>(최근 접속자 먼저) — 1일/3일/7일/그이상</li>
     *   <li>6차: <b>시드 셔플</b>(viewerId · epochDay · batchSeq) — 매일/배치마다 회전해 정적 리스트 방지</li>
     * </ol>
     * 나이/최근접속 모르면 중립 처리(하드 제외 없음).
     *
     * <p><b>접속을 버킷으로 뭉개는 이유 — 정확 시각으로 두면 셔플이 죽는다.</b> 더 이전 구현은
     * {@code lastVisitAt}을 정확 시각 내림차순으로 비교했다. 타임스탬프는 동점이 사실상 없어
     * (운영 실측: 후보 54명 중 서로 다른 값 53개) 비교가 항상 여기서 끝났고, {@code epochDay}가 들어 있는
     * 마지막 셔플은 <b>한 번도 실행되지 않았다.</b> 그래서 "매일 회전"은 코드에만 있고 실제로는
     * 배치 0의 30%가 전날과 8명 전원 동일했다(2026-08-13 운영 실측, 연속일 306쌍 기준).
     * 버킷으로 뭉개면 (나이티어 × 노출 × 버킷) 그룹 안에서 동점이 대량 발생해 셔플이 살아난다.</p>
     *
     * <p><b>노출 순위를 접속보다 위에 두는 이유.</b> 버킷화만으로는 상위 버킷이 풀리지 않는다 —
     * "1일 내 접속"이 몇 명뿐이라 그룹이 작으면 셔플해도 전원이 그대로 뽑힌다. 본 상대를
     * 아래로 내려야 활동 중인 소수도 순환한다. 휴면 유저를 제외하지 않는 것과 같은 이유로
     * <b>여기서도 제외는 하지 않는다</b> — 풀이 작아 빼는 순간 배치가 안 찬다.</p>
     *
     * <p><b>2순위(30일 내 접속)를 신설한 이유.</b> 30일 이상 미접속자에게 매칭 신청을 걸면
     * <b>93.5%가 무응답</b>이다(7일 내는 55.6%). 최근 60일 신청 217건 중 154건(71%)이 8일 이상
     * 미접속자에게 갔다 — 노출해도 사용자에게 손해다. 그래서 "풀을 넓히려고 휴면자를 앞으로
     * 올리는 것"은 반복 노출의 답이 아니다.</p>
     *
     * <p><b>3순위를 「마지막 노출일」로 바꾼 이유.</b> 예전에는 2일 창짜리 <b>이진값</b>이라
     * 사흘 전에 본 사람이 「안 본 사람」과 동급이 되어 다시 앞으로 왔다. 어제 본 사람과 그저께 본
     * 사람도 구분이 없었다. 날짜로 바꾸면 자연스러운 순환 큐가 된다 — 미노출자가 맨 앞,
     * 어제 본 사람이 맨 뒤다. 이틀 연속 사용자 기준 전날 중복률이 10~45%였다.
     * 이 순위는 <b>노출 기록이 남아야만</b> 작동한다 — 기록을 안 남기는 화면이 하나라도 있으면
     * 그 화면에서는 회전이 조용히 꺼진다.</p>
     *
     * <p><b>접속 버킷을 아래로 내린 이유.</b> 후보의 접속 분포가 극단적이라(여성 활성 166명 중
     * 1일 내 6명·7일 초과 136명) 상위 버킷 소수가 매일 앞자리를 차지했다. 어떤 남성 회원은
     * 후보 166명 중 <b>78명만</b> 노출됐고 그 78명을 <b>평균 7.4회</b> 반복해 봤다.
     * 88명은 한 번도 노출된 적이 없다.</p>
     *
     * @param lastSeen 상대별 마지막 노출일. <b>오늘 것은 들어 있지 않다</b> — 서비스의 조회 쿼리가
     *                 {@code seen_date < today} 로 잘라 준다(「어제까지만」). 여기 없는 상대는
     *                 한 번도 노출된 적이 없다는 뜻이라 {@link LocalDate#MIN} 으로 맨 앞에 선다.
     */
    public Comparator<ExploreUserResponse> getPriorityComparator(User viewer, long epochDay, long batchSeq,
                                                                 Map<Long, LocalDate> lastSeen) {
        Integer viewerAge = ageOf(viewer.getBirthDate());
        int tolerance = toleranceFor(viewerAge);
        long vid = viewer.getId();
        return Comparator
                .comparingInt((ExploreUserResponse r) -> ageTier(viewerAge, r.getAge(), tolerance))
                .thenComparingInt(r -> isRecentlyActive(r.getLastVisitAt()) ? 0 : 1)
                .thenComparing(r -> lastSeen.getOrDefault(r.getUserId(), LocalDate.MIN))
                .thenComparingInt(r -> hasPhoto(r) ? 0 : 1)
                .thenComparingInt(r -> recencyBucket(r.getLastVisitAt()))
                .thenComparingLong(r -> shuffleHash(r.getUserId(), vid, epochDay, batchSeq));
    }

    /**
     * [홈 「이런 사람은 어때요?」] <b>새 얼굴 우선</b> 비교자 (2026-09-23).
     *
     * <p>{@link #getPriorityComparator} 와 재료는 같고 <b>「안 본 사람」이 「또래」보다 위</b>라는 점만 다르다.
     * 나이가 먼 사람(티어 2)만 맨 뒤로 보내고, 그 안에서는 안 본 사람 → 또래 → 사진 → 접속 순이다.</p>
     *
     * <p><b>왜 줄을 따로 세우나.</b> 또래 우선 줄 하나를 여러 화면이 나눠 쓰면 홈이 하루 14자리를 소모하는데,
     * 또래·30일 접속 후보가 그보다 조금 큰 뷰어(40대 남성은 21명 안팎)는 이틀이면 전원을 보고
     * 「어제 본 또래」가 「한 번도 안 본 근접 나이」보다 앞에 서서 매일 같은 얼굴이 된다. 반대로 줄 전체를
     * 이 순서로 바꾸면 20대 남성은 안 본 또래 22명을 이틀에 소진하고 오늘의 인연까지 근접 나이로
     * 채워진다(운영 데이터 7일 시뮬레이션). 그래서 오늘의 인연·탐색은 또래 우선 줄, 이 화면만 새 얼굴 우선 줄이다.</p>
     *
     * <p>시드는 항상 0번 배치 기준 — 홈은 하루 종일 같은 6명이어야 한다.</p>
     */
    public Comparator<ExploreUserResponse> getFreshFaceComparator(User viewer, long epochDay,
                                                                  Map<Long, LocalDate> lastSeen) {
        Integer viewerAge = ageOf(viewer.getBirthDate());
        int tolerance = toleranceFor(viewerAge);
        long vid = viewer.getId();
        return Comparator
                .comparingInt((ExploreUserResponse r) -> ageTier(viewerAge, r.getAge(), tolerance) >= AGE_TIER_FAR ? 1 : 0)
                .thenComparingInt(r -> isRecentlyActive(r.getLastVisitAt()) ? 0 : 1)
                .thenComparing(r -> lastSeen.getOrDefault(r.getUserId(), LocalDate.MIN))
                .thenComparingInt(r -> ageTier(viewerAge, r.getAge(), tolerance))
                .thenComparingInt(r -> hasPhoto(r) ? 0 : 1)
                .thenComparingInt(r -> recencyBucket(r.getLastVisitAt()))
                .thenComparingLong(r -> shuffleHash(r.getUserId(), vid, epochDay, 0));
    }

    /**
     * 프로필 사진이 있는가 — 정렬 4순위(2026-09-23). <b>유무만 본다, 점수는 안 본다.</b>
     *
     * <p>사진 점수(3~8점)로 줄을 세우면 4~5점에 60%가 몰려 순위가 굳고 셔플이 죽는다 — 접속 시각을
     * 정확값으로 비교하던 8월과 같은 구조다. 점수 컷라인도 미결이라 유무만 쓴다. 회전(마지막 노출일)보다
     * 아래에 두는 이유: 위에 두면 사진 있는 사람은 봤든 안 봤든 항상 앞이라 사진 풀이 홈 자리(14)보다
     * 작은 뷰어는 「이런 사람은 어때요?」가 매일 같은 5명으로 얼어붙는다(운영 데이터 시뮬레이션).</p>
     */
    private static boolean hasPhoto(ExploreUserResponse r) {
        return r.getProfileImageUrl() != null && !r.getProfileImageUrl().isBlank();
    }

    /** 「최근 접속」으로 볼 기간(일). 이보다 오래된 상대는 뒤로 민다 — 제외가 아니다. */
    private static final int ACTIVE_DAYS = 30;

    /**
     * 최근 {@value #ACTIVE_DAYS}일 안에 접속했는가. 접속 기록이 없으면 비활성으로 본다.
     *
     * <p><b>제외가 아니라 후순위다.</b> 후보에서 빼면 배치가 안 차서 빈 화면이 늘고, 갱신 가능
     * 횟수가 남성 기준 11회 → 8회로 준다. 나이 tier·노출 페널티와 같은 원칙이다.</p>
     */
    private boolean isRecentlyActive(LocalDateTime lastVisit) {
        return lastVisit != null
                && Duration.between(lastVisit, LocalDateTime.now()).toDays() <= ACTIVE_DAYS;
    }

    /** {@link #ageTier} 에서 「나이가 멀다」로 판정되는 값. */
    private static final int AGE_TIER_FAR = 2;

    /** 생년월일 → 만나이. 없으면 null. */
    private Integer ageOf(LocalDate birthDate) {
        return birthDate == null ? null : Period.between(birthDate, LocalDate.now()).getYears();
    }

    /** 뷰어 나이대별 선호 ±(tolerance). 젊을수록 좁게. 나이 모르면 중간값. */
    private int toleranceFor(Integer age) {
        if (age == null) return 5;
        if (age <= 24) return 3;
        if (age <= 30) return 4;
        if (age <= 37) return 5;
        if (age <= 45) return 7;
        return 10;
    }

    /** 0=이상적(또래), 1=허용, 2=멀다. 나이 모르면 1(중립, 하드 제외 안 함). */
    private int ageTier(Integer viewerAge, Integer candAge, int tolerance) {
        if (viewerAge == null || candAge == null) return 1;
        int gap = Math.abs(viewerAge - candAge);
        if (gap <= tolerance) return 0;
        if (gap <= tolerance * 2) return 1;
        return 2;
    }

    /** 최근 접속 버킷: 0=1일내, 1=3일내, 2=7일내, 3=그이상/없음. 낮을수록 우선(최근). */
    private int recencyBucket(LocalDateTime lastVisit) {
        if (lastVisit == null) return 3;
        long days = Duration.between(lastVisit, LocalDateTime.now()).toDays();
        if (days <= 1) return 0;
        if (days <= 3) return 1;
        if (days <= 7) return 2;
        return 3;
    }

    /**
     * 후보ID·뷰어·날짜·배치 기반 고정 해시. 같은 (후보,뷰어,날짜,배치)면 항상 같은 순서,
     * 배치/날짜가 바뀌면 순서가 <b>완전히</b> 회전한다.
     *
     * <p><b>왜 XOR만으로는 안 되는가.</b> 이전 구현은 각 항을 곱한 뒤 XOR로 이어붙이기만 했다
     * ({@code (cid*M) ^ (vid*K) ^ (epochDay*C)}). 이러면 후보 간 순서를 가르는 비트는
     * {@code cid*M}의 상위 비트(운영 후보ID 범위에서는 40~41번째)인데, {@code epochDay}는
     * 하루에 약 2^31만 움직여 그 상위 비트에 닿지 못한다. 결과적으로 <b>날짜가 바뀌어도 상위 비트가
     * 그대로라 순서가 고정됐다</b> — 3일을 돌려도 상위 8명이 8/8 동일했다(2026-08-13 시뮬레이션).
     *
     * <p>그래서 항을 합친 뒤 splitmix64 finalizer로 <b>비트를 확산(avalanche)</b>시킨다.
     * 입력이 1비트만 달라도 출력 비트의 절반가량이 뒤집히므로 {@code epochDay}가 1 늘면 순서가 새로 섞인다.
     * 순수 함수라 재진입/멱등 안정성은 그대로다 — 같은 날 같은 배치를 다시 열면 같은 줄이 나온다.</p>
     */
    private long shuffleHash(long candidateId, long viewerId, long epochDay, long batchSeq) {
        long z = candidateId * SHUFFLE_MULTIPLIER
                + viewerId * 0x9E3779B97F4A7C15L
                + epochDay * 0x165667B19E3779F9L
                + batchSeq * 0x27D4EB2F165667C5L;
        z ^= (z >>> 33);
        z *= 0xFF51AFD7ED558CCDL;
        z ^= (z >>> 33);
        z *= 0xC4CEB9FE1A85EC53L;
        z ^= (z >>> 33);
        return z;
    }
}
