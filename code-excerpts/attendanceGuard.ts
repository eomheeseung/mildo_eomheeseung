/**
 * 출석 체크인 호출 가드 — 클라이언트 시계를 믿지 않는다
 *
 * 배경
 *  출석 보상은 서버가 KST 자정을 기준으로 하루 한 번만 지급한다(멱등).
 *  클라이언트가 하는 일은 "오늘 아직 안 불렀으면 부른다"뿐인데, 이 판단을
 *  **단말 시계**로 하면 하루가 통째로 누락되는 창이 생긴다.
 *
 * 무엇이 문제였나
 *  응답의 `granted: false`가 두 가지를 동시에 뜻했다.
 *    (1) 오늘 이미 출석했다
 *    (2) 서버는 아직 어제다 (단말 시계가 앞서 있다)
 *  단말이 1분 빠른 상태로 자정을 넘기면 (2)가 성공으로 보이고,
 *  가드에 **내일 날짜**가 박힌다. 실제 자정이 지나도 "이미 했다"로 판정해
 *  그날 출석이 한 번도 나가지 않는다. 앱을 강제 종료해야 풀리는 상태였다.
 *
 * 해결
 *  서버에 **판정에 사용한 날짜**를 응답에 실어달라고 요청하고(`date`),
 *  가드를 그 값으로 박는다. 단말 시계가 어느 쪽으로 틀어져 있든,
 *  서버 날짜와 단말 날짜가 달라지는 동안에는 호출이 계속 열려 있으므로 누락이 없다.
 *
 * 대가
 *  두 날짜가 어긋난 창에서는 포커스마다 호출이 나갈 수 있다.
 *  지급은 서버가 멱등이라 안전하지만 트래픽이 낭비되므로 그 창에만 쓰로틀을 건다.
 */

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;
const SKEW_THROTTLE_MS = 30_000;

/** 단말이 추정하는 KST 날짜. **판정용이 아니라 "부를지 말지"의 1차 필터로만 쓴다.** */
const deviceKstDate = (): string =>
  new Date(Date.now() + KST_OFFSET_MS).toISOString().slice(0, 10);

/**
 * ★가드에 담기는 값은 **서버가 준 date**다(단말 계산값이 아니다).
 *   서버가 그 필드를 안 주는 구버전이면 단말 값으로 폴백한다 — 그 경우 종전 동작과 같다.
 */
let lastCheckedDate: string | null = null;
let lastCalledAt = 0;

export async function runAttendanceCheck(opts?: { force?: boolean }): Promise<void> {
  const today = deviceKstDate();

  if (!opts?.force) {
    // 서버 날짜와 단말 날짜가 같다 = 오늘 몫은 끝났다
    if (lastCheckedDate === today) return;
    // 어긋난 창에서만 걸리는 쓰로틀. 가드가 비어 있을 때(첫 호출·직전 실패)는 통과시킨다 —
    // 여기서 막으면 정작 복구가 필요한 순간에 호출이 늦어진다.
    if (lastCheckedDate && Date.now() - lastCalledAt < SKEW_THROTTLE_MS) return;
  }

  lastCheckedDate = today;      // 진행 중 중복 호출 방지(성공하면 서버 date 로 덮는다)
  lastCalledAt = Date.now();

  try {
    const res = await apiClient.checkAttendance();

    if (res?.success) {
      lastCheckedDate = res.data?.date ?? today;
      if (res.data?.granted) showRewardEffect(res.data.amount ?? 0);
      return;
    }

    // ★API 래퍼가 4xx/5xx 에서 throw 하지 않고 {success:false} 를 돌려주는 구조라
    //   아래 catch 는 이 경로를 절대 못 잡는다. 여기서 직접 풀어줘야 한다.
    //   이 한 줄이 없어서, 서버가 한 번 실패하면 그 프로세스에서는 재시도가
    //   영영 없던 사고가 있었다(강제 종료해야만 출석이 들어감).
    lastCheckedDate = null;
  } catch {
    lastCheckedDate = null;     // 네트워크 예외 등 — 다음 진입에 재시도
  }
}

/** 계정 전환 시 반드시 푼다. 프로세스 단위 값이라 로그아웃해도 남는다. */
export function resetAttendanceGuard(): void {
  lastCheckedDate = null;
}

// ────────────────────────────────────────────────────────────────────────────
// 이중 방어 — 화면 진입 시 서버 판정으로 가드를 교정한다
//
// 위 가드는 "부를지 말지"를 클라이언트가 정하는 구조라, 어떤 이유로든 어긋나면
// 스스로 알아채지 못한다. 그래서 **서버가 오늘 출석을 갖고 있는지**(checkedToday)를
// 보는 화면에서 한 번 더 교정한다. 조회를 이미 하고 있으므로 추가 왕복이 없다.
//
// ★순서가 중요하다 — 복구를 **먼저** 끝내고 조회해야 그 결과가 이번 화면에 실린다.
// ★사용자가 누른 동작이 아니라 백그라운드 복구다. 실패해도 조용히 넘긴다.
//   여기서 실패 토스트를 띄우면 아무것도 안 한 사용자에게 "출석 실패"가 뜬다.
// ────────────────────────────────────────────────────────────────────────────

let retriedForDate: string | null = null;

export async function loadMissionsWithRecovery(): Promise<void> {
  await runAttendanceCheck();                    // 가드에 걸리면 호출 자체가 안 나간다

  const [missionRes, attendRes] = await Promise.all([
    apiClient.getDailyMissions().catch(() => null),
    apiClient.getAttendanceStatus().catch(() => null),
  ]);

  applyMissions(missionRes?.data);

  const a = attendRes?.data;
  if (!a) return;
  applyAttendance(a);

  // 서버가 "오늘 출석 없음"이라고 하면 가드를 무시하고 한 번 더 부른다.
  // 날짜를 키로 하루 1회만 재시도한다 — 서버가 계속 false 를 줘도 루프가 되지 않는다.
  const serverDate = String(a.date ?? '');
  if (!a.checkedToday && serverDate && retriedForDate !== serverDate) {
    retriedForDate = serverDate;
    await runAttendanceCheck({ force: true });
    await loadMissionsWithRecovery();
  }
}
