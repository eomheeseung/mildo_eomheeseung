package mildo.attendance.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 연속 출석 — 도장판(7칸)과 3일차·7일차 보너스.
 *
 * <p><b>처음에는 저장하지 않고 매번 로그에서 다시 셌다.</b> 오늘부터 하루씩 거슬러 올라가며
 * 출석 로그가 끊기는 지점에서 멈추는 구조다. 로그가 유일한 진실이라 값이 어긋날 수가 없고,
 * 운영에서 로그를 손대도 다음 조회에 자동으로 맞춰진다는 장점이 있었다.</p>
 *
 * <p><b>그런데 읽어야 할 행 수가 곧 그 사람의 연속 일수다.</b> 「하루라도 빠지면 초기화」라는
 * 규칙은 이걸 줄여주지 못한다 — 줄여주는 건 <i>끊긴 사람</i>이고, 문제가 되는 건
 * <i>한 번도 안 끊긴 사람</i>이다. 무한정 읽지 않으려고 넣은 {@code LIMIT 60}이 결국
 * 정답을 바꿔버렸다. 루프가 멈추는 이유가 둘인데 코드는 그걸 구분하지 않았다.</p>
 *
 * <pre>
 * ① 빈 날을 만났다        → 진짜로 연속이 끝났다
 * ② 받아온 60행을 다 썼다  → 그냥 자료가 떨어졌다
 * </pre>
 *
 * <p>60일 연속인 사람은 ②로 멈추는데 코드는 ①로 읽어 「연속 60일」이라 답한다. 61일째도 60,
 * 62일째도 60 — <b>연속 일수가 얼어붙는다.</b> 도장판은 4칸에 고정되고 3일차·7일차에 다시
 * 닿지 못해 보너스가 영영 안 나간다. 예외도 로그도 없다. <b>가장 우수한 유저에게서만 터진다.</b></p>
 *
 * <p>그래서 상태로 저장하도록 바꿨다. 결석 허용이 없으므로 <b>마지막 출석일 하나만</b> 보면
 * 되고, 읽기가 1행으로 끝나 상한이라는 개념이 사라진다.</p>
 *
 * <p><b>로그를 없애지 않은 것이 핵심이다.</b> 상태는 캐시고 {@code attendance_log}가 진실이다.
 * 둘이 어긋나면 로그에서 다시 계산해 덮어쓸 수 있다. 상태값을 유일한 진실로 삼았다면
 * 자가 복구 경로를 통째로 잃었을 것이다.</p>
 */
public class AttendanceStreakService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int ATTENDANCE_BERRY = 2;
    private static final int STREAK_CYCLE = 7;

    /** 주기 안에서 이 일차에 도달하면 추가 지급. 표시 순서를 지키려고 LinkedHashMap. */
    private static final Map<Integer, Integer> STREAK_BONUS = new LinkedHashMap<>();
    static {
        STREAK_BONUS.put(3, 3);
        STREAK_BONUS.put(7, 10);
    }

    private final AttendanceLogRepository attendanceLogRepository;
    private final AttendanceStreakRepository streakRepository;
    private final TokenLedgerService tokenLedgerService;

    /**
     * 출석체크. 오늘(KST) 첫 호출에만 지급한다.
     *
     * <p>{@code INSERT IGNORE}(UNIQUE user_id+date)로 하루 1회와 동시요청을 <b>원자적으로</b>
     * 함께 막는다. 앱이 로그인·세션복구·포그라운드 복귀 세 지점에서 호출하므로 하루에 여러 번 온다.</p>
     *
     * <p><b>날짜 판정은 전부 서버(KST)다.</b> 프론트가 날짜를 세면 기기 시계를 바꿔
     * 도장판과 보너스를 그대로 우회할 수 있다.</p>
     */
    @Transactional
    public AttendanceCheckInResponse checkIn(Long userId) {
        LocalDate today = LocalDate.now(KST);

        // created_at은 앱이 계산한 KST를 넘긴다 — SQL NOW()는 DB 서버 시계라 환경마다 갈린다.
        int inserted = attendanceLogRepository.insertIgnore(
                userId, today, ATTENDANCE_BERRY, LocalDateTime.now(KST));

        if (inserted == 0) {
            // 오늘 이미 출석 — 중복 지급 없이 200 + granted:false. 에러가 아니다.
            return AttendanceCheckInResponse.builder()
                    .date(today)                       // 서버 판정 날짜를 함께 내려준다(아래 주석)
                    .granted(false)
                    .streak(cycleDay(adoptIfMissing(userId, today)))
                    .build();
        }

        // 무상 적립. 멱등키에 날짜가 들어가 원장 레벨에서 한 번 더 막힌다.
        TokenTransaction tx = tokenLedgerService.creditFree(
                userId, ATTENDANCE_BERRY, TokenGrantSource.ATTENDANCE,
                TokenTransactionType.EARN_ATTENDANCE, "ATTENDANCE",
                "ATTENDANCE:" + userId + ":" + today, "출석체크");

        // 연속 갱신은 같은 트랜잭션 안이다 — 적립이 터지면 이 갱신도 함께 롤백된다.
        int cycleDay = cycleDay(advanceStreak(userId, today));
        int bonus = grantStreakBonus(userId, today, cycleDay);

        return AttendanceCheckInResponse.builder()
                .date(today)
                .granted(true)
                .balance(bonus > 0 ? tx.getBalanceAfter() + bonus : tx.getBalanceAfter())
                .streak(cycleDay)
                .bonusGranted(bonus > 0)
                .bonusAmount(bonus)
                .build();
    }

    /**
     * 오늘 첫 출석 — 연속을 한 칸 전진시킨다.
     * <b>어제 출석했으면 +1, 아니면 1로 리셋.</b> 히스토리를 거슬러 올라갈 필요가 없다.
     */
    private int advanceStreak(Long userId, LocalDate today) {
        AttendanceStreak streak = streakRepository.findById(userId)
                .orElseGet(() -> AttendanceStreak.builder().userId(userId).build());

        LocalDate last = streak.getLastAttendDate();
        int next = (last != null && last.isEqual(today.minusDays(1)))
                ? streak.getStreakCount() + 1
                : 1;

        streak.setStreakCount(next);
        streak.setLastAttendDate(today);
        streak.setUpdatedAt(LocalDateTime.now(KST));
        streakRepository.save(streak);
        return next;
    }

    /**
     * 조회용. 마지막 출석일이 오늘이나 어제면 저장값이 유효하다 — 오늘 아직 안 찍었어도
     * 어제까지의 연속은 살아 있다(오늘 찍으면 이어진다). 그제 이하로 벌어졌으면 이미 끊긴 것이라 0이다.
     */
    private int readStreak(Long userId, LocalDate today) {
        return streakRepository.findById(userId)
                .filter(s -> s.getLastAttendDate() != null)
                .filter(s -> !s.getLastAttendDate().isBefore(today.minusDays(1)))
                .map(AttendanceStreak::getStreakCount)
                .orElse(0);
    }

    /**
     * 누적 일수를 도장판 위치(1~7)로. <b>{@code % 7}을 해서 저장하지 않는 이유가 여기 있다.</b>
     *
     * <p>나눠서 저장하면 7일차와 「출석 없음」이 같은 0으로 뭉개진다({@code % 8}로 바꿔도
     * 8일차가 0이 되어 한 칸 밀릴 뿐이다). {@code -1} 하고 나눈 뒤 {@code +1}을 도로 더하면
     * 1~7로 떨어져 0이 나올 자리가 없다. API가 실제 연속 일수도 함께 내려주므로
     * 어차피 원본이 필요하다.</p>
     */
    private int cycleDay(int totalStreak) {
        return totalStreak == 0 ? 0 : ((totalStreak - 1) % STREAK_CYCLE) + 1;
    }

    /**
     * 연속 보너스. <b>실패를 삼키지 않는다.</b>
     *
     * <p>{@code creditFree}는 이 트랜잭션에 그대로 참여한다. 여기서 예외를 잡아도 트랜잭션은
     * 이미 rollback-only라 커밋 시점에 다시 튄다. 그냥 올려보내면 출석 행까지 함께 롤백되고
     * 다음 호출이 처음부터 다시 시도한다 — <b>롤백이 사고가 아니라 방어가 작동한 것</b>이다.</p>
     *
     * <p>멱등키에 <b>날짜</b>를 넣는다. 주기 인덱스로 하면 연속이 끊겼다 다시 3일차에 닿았을 때
     * 같은 키가 되어 지급이 막힌다. 하루에 같은 일차를 두 번 밟을 수는 없으므로 날짜면 충분하다.</p>
     */
    private int grantStreakBonus(Long userId, LocalDate today, int cycleDay) {
        Integer bonus = STREAK_BONUS.get(cycleDay);
        if (bonus == null) {
            return 0;
        }
        tokenLedgerService.creditFree(userId, bonus, TokenGrantSource.ATTENDANCE,
                TokenTransactionType.EARN_ATTENDANCE, "ATTENDANCE_BONUS",
                userId + ":" + today + ":D" + cycleDay, cycleDay + "일 연속 출석 보너스");
        return bonus;
    }
}
