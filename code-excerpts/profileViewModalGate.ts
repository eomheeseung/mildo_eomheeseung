/**
 * 「누가 내 프로필을 봤어요」 선택 모달 — 언제 띄우고, 언제 띄우지 않을 것인가
 *
 * 진입 경로는 세 가지다.
 *   ① 앱이 켜져 있을 때 푸시 탭   ② 앱이 꺼진 상태에서 푸시 탭(콜드스타트)   ③ 홈에 20초 머물면 자동 1회
 * 모달 본체는 앱 루트에 한 번만 마운트하고, 화면 밖 경로(푸시·콜드스타트)는 작은 버스로 요청만 보낸다.
 *
 * 이 발췌는 출시 직후 하루 사이에 드러난 세 가지 문제와 그 판단을 담고 있다.
 *
 *  1) 모달을 띄웠을 뿐인데 상대에게 「프로필을 확인했어요」가 거꾸로 나갔다
 *     사진을 받으려고 프로필 상세를 조회했는데, 그 전날 서버 정책이 "프로필 상세 조회 = 실제 열람"
 *     (조회 기록 + 상대에게 알림)으로 바뀌어 있었다. 모달 작성 시점의 전제가 하루 만에 뒤집힌 것.
 *     → 앱이 조회를 피하는 대신, 서버와 **기록·알림을 남기지 않는 조회 플래그(`from=noti`)** 를 합의했다.
 *       응답과 404 판정은 그대로라 모달은 한 줄만 바뀐다. 모달의 「프로필 보러 가기」는 실제 열람이라 플래그 없이.
 *
 *  2) iOS 에서만 콜드스타트 푸시 탭(②)이 통째로 죽었다
 *     스플래시도 <Modal> 이다. iOS 는 Modal 이 이미 present 중이면 두 번째 present 를 조용히 무시하고,
 *     visible 이 true 그대로라 스플래시가 사라져도 다시 시도하지 않는다.
 *     → 마운트는 유지하고 **그리기만** 스플래시 뒤로 미룬다.
 *       마운트 자체를 늦추면 버스(보관 없이 지금 붙은 리스너에만 전달)가 콜드스타트 요청을 잃는다.
 *
 *  3) 마이페이지·대화 탭에 있어도 「홈 20초」 모달이 떴다
 *     하단 탭이 라우트가 아니라 `/home` 한 화면 안의 state 였다. 경로만으로는 어느 탭인지 알 수 없다.
 *     → 홈 화면이 탭 변경을 버스로 알리고, 타이머는 "경로 + 홈 탭"일 때만 돈다.
 *     또 자동 노출(③)은 **볼 수 있는 사람일 때만** 띄운다. 권유하려고 먼저 띄우는 모달인데
 *     「볼 수 없는 프로필」(숨김·차단·탈퇴)만 뜨면 할 수 있는 게 없다.
 *     → 띄우기 전에 조회해 404면 다음 후보로 넘어가고, 건너뛴 알림은 읽음 처리하지 않는다(사용자가 본 적 없다).
 *     푸시를 직접 눌러 온 ①② 는 사용자가 연 것이라 지금처럼 띄우고 안내한다.
 */

/* ── 버스: 화면 밖 요청 + 「지금 홈 탭인가」 ───────────────────── */

export interface ProfileViewTarget { userId: number; nickname?: string | null; notificationId?: number | null }

const listeners = new Set<(t: ProfileViewTarget) => void>();
export const openProfileViewModal = (t: ProfileViewTarget) => listeners.forEach((fn) => fn(t));
export const subscribeProfileView = (fn: (t: ProfileViewTarget) => void) => { listeners.add(fn); return () => listeners.delete(fn); };

let homeTabActive = true;
const homeTabListeners = new Set<(v: boolean) => void>();
export function setHomeTabActive(v: boolean) {            // HomeScreen: useEffect(() => setHomeTabActive(tab === 0), [tab])
  if (homeTabActive === v) return;
  homeTabActive = v;
  homeTabListeners.forEach((fn) => fn(v));
}
export const isHomeTabActive = () => homeTabActive;
export const subscribeHomeTab = (fn: (v: boolean) => void) => { homeTabListeners.add(fn); return () => homeTabListeners.delete(fn); };

/* ── 모달 본체 (발췌) ─────────────────────────────────────────── */

/*
export default function ProfileViewModal({ splashDone }: { splashDone: boolean }) {
  ...
  const onHomeTab = () => pathnameRef.current === '/home' && isHomeTabActive();

  // @returns 실제로 띄웠는가 — 자동 노출이 다음 후보로 넘어갈지 정한다
  const open = useCallback(async (next: ProfileViewTarget, opts?: { auto?: boolean }) => {
    if (opts?.auto && !onHomeTab()) return false;

    if (opts?.auto) {
      // ③ 볼 수 있는 사람일 때만. from=noti 라 이 사전 조회도 상대에게 흔적을 남기지 않는다
      const res = await api.getExploreProfileDetail(next.userId, 'noti').catch(() => null);
      if (res?.status === 404) return false;            // 숨김·차단·탈퇴 → 조용히 다음 후보, 읽음 처리 안 함
      if (!res?.success) return false;                   // 통신 오류 → 다음 기회로
      if (!onHomeTab()) return false;                    // 조회하는 사이 다른 탭·화면으로 갔다
      setTarget(next); setPhoto(res.data.profileImageUrl); setPhase('ready');
      markRead(next.notificationId);
      return true;
    }

    // ①② 사용자가 직접 연 경우 — 바로 띄우고, 404 면 「볼 수 없는 프로필」로 안내
    setTarget(next); setPhase('loading'); markRead(next.notificationId);
    const res = await api.getExploreProfileDetail(next.userId, 'noti');
    setPhase(res?.status === 404 ? 'unavailable' : 'ready');
    return true;
  }, [...]);

  // 홈 20초 — 경로 + 홈 탭일 때만 센다. 다른 탭으로 가면 타이머가 풀리고 돌아오면 다시 센다
  useEffect(() => {
    if (pathname !== '/home' || !homeTab || autoDoneRef.current) return;
    const t = setTimeout(async () => {
      const unread = await api.getNotifications({ isRead: false });
      const hits = unread.filter(isRecentProfileView);
      for (const hit of hits.slice(0, 3)) {               // 최근 것부터, 볼 수 없으면 다음 사람
        if (await open(toTarget(hit), { auto: true }) || !onHomeTab()) break;
      }
    }, 20_000);
    return () => clearTimeout(t);
  }, [pathname, homeTab]);

  // 2) iOS — 마운트는 유지(요청 수신), 그리기만 스플래시 뒤로
  if (!target || !splashDone) return null;
  return <Modal visible ...>...</Modal>;
}
*/
