/**
 * 앱 아이콘 배지 — 알림함 미읽음 개수를 아이콘 숫자로
 *
 * 배경
 *  숫자의 출처를 **서버 미읽음 API 하나**로 고정했다.
 *   - 앱이 켜져 있을 때: 앱이 그 값으로 맞춘다(이 파일).
 *   - 앱이 꺼져 있을 때: 서버가 같은 함수로 센 값을 푸시 페이로드
 *     (iOS `aps.badge` / Android `notification_count`)에 싣는다.
 *  두 곳이 따로 세면 아이콘 숫자와 앱 안 숫자가 어긋난다.
 *
 * 무엇이 문제였나 — 실기기 대조 검증 중 발견
 *  라이브러리(expo-notifications)의 Android 구현에서 배지를 0으로 맞추는 호출은
 *  "숫자 지우기"가 아니라 이 앱의 **알림창 알림 전체 삭제**였다.
 *
 *      if (badgeCount == 0) notificationManager.cancelAll()
 *
 *  처음 구현은 홈의 30초 주기 갱신에서도 값을 그대로 넣었으므로, 미읽음이 0인 사용자는
 *  앱을 열기만 해도 30초마다 알림창이 비워졌다. 알림함에 쌓이지 않는 **채팅 알림까지** 사라진다.
 *  에러도 경고도 없다.
 *
 *  게다가 일부 런처(삼성 One UI 실측)는 앱이 넣는 숫자를 아예 보지 않고 **알림창에 남은 푸시의
 *  notification_count** 로 배지를 그린다 → Android 에서 앱이 할 수 있는 건 사실상 "지우기"뿐이다.
 *
 * 해결
 *  Android 의 0 은 사용자가 실제로 읽었을 때(하나 읽음·모두 읽음·삭제)와 로그아웃에만 보낸다.
 *  주기 갱신·화면 복귀처럼 사용자 행동이 아닌 경로에서는 0을 건너뛴다.
 *
 * 하지 않은 것
 *  "지난번과 같은 값이면 호출 생략" 캐시는 넣지 않았다 — 백그라운드에서 푸시가 배지를 바꿔 놓으므로
 *  "지난번과 같은 값"이 실제 아이콘 숫자와 같다는 보장이 없다.
 */
import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

export async function syncAppBadge(count: number, opts?: { clearShade?: boolean }): Promise<void> {
  if (Platform.OS === 'web') return;
  const n = Math.max(0, Math.floor(count || 0));
  // ★Android 에서 0 = 알림창 전체 삭제. 사용자가 읽어서 0 이 된 게 아니면 건너뛴다.
  if (Platform.OS === 'android' && n === 0 && !opts?.clearShade) return;
  try {
    await Notifications.setBadgeCountAsync(n);
  } catch (e) {
    console.log('[Badge] set failed:', e); // iOS 는 알림 권한(배지 포함)이 없으면 OS 가 무시한다
  }
}

// 로그아웃·세션 만료 — 다음 사람이 앞 계정의 숫자(와 알림)를 보지 않게
export function clearAppBadge(): Promise<void> {
  return syncAppBadge(0, { clearShade: true });
}

/* ── 호출부 (요약) ─────────────────────────────────────────────

  // 홈: 미읽음 개수를 불러올 때 — 0 이어도 알림창은 건드리지 않는다
  const res = await api.getUnreadNotificationCount();
  setUnreadCount(res.data.count);
  syncAppBadge(res.data.count);

  // 알림함: 사용자가 읽어서 바뀐 경우에만 0 으로 알림창까지 정리
  const userReadRef = useRef(false);
  useEffect(() => {
    if (!loading) syncAppBadge(unreadCount, { clearShade: userReadRef.current });
  }, [unreadCount, loading]);
  // markAllRead / onPress(읽음) / remove 에서 userReadRef.current = true 후 setUnreadCount

  // AuthContext.logout / 인증 실패로 세션 정리 시
  clearAppBadge();
*/
