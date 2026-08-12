// ─────────────────────────────────────────────────────────────────────────────
// 푸시 권한 판정 · 토큰 수명 관리 (발췌)
//
// 실측에서 시작한 코드입니다. 등록 사용자 대비 푸시가 실제로 닿는 기기가 27%뿐이었고,
// 서버에 보고된 권한 상태는 "거부"가 압도적이었습니다. 그런데 그 숫자가 틀린 숫자였습니다.
//
// ★핵심: 안드로이드에는 iOS의 'undetermined'(아직 안 물어봄)가 없습니다.
//   한 번도 묻지 않은 기기도 status='denied'로 옵니다. 구분은 `canAskAgain` 하나뿐입니다.
//   이 차이를 모르고 status만 보면 두 가지가 동시에 깨집니다:
//     ① 신규 설치자가 전부 "거부"로 집계된다 → 거부율이 부풀고, 유도 가능한 사람과 영구 거부가 섞인다
//     ② 권한 요청을 `status === 'undetermined'` 로 게이팅하면 안드로이드에서 OS 팝업이 영영 안 뜬다
//
// 전체 서비스 코드에서 권한·토큰 수명에 관한 부분만 발췌했습니다.
// ─────────────────────────────────────────────────────────────────────────────

import * as Notifications from 'expo-notifications';
import * as Device from 'expo-device';
import { Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import apiClient from '../api/client';

const PUSH_TOKEN_KEY = 'push_token';

// 네이티브 전용 모듈 — 웹 번들이 깨지지 않도록 동적 로드로 격리한다.
let messaging: any = null;
if (Platform.OS !== 'web') {
  try {
    messaging = require('@react-native-firebase/messaging').default;
  } catch {
    /* 웹/미설치 환경 */
  }
}

class PushService {
  private pushToken: string | null = null;
  private permissionReportFailures = 0;
  private permissionReportDisabled = false;

  // 포그라운드 FCM 핸들러 해제 함수.
  // ★등록 함수는 로그인·세션복구·동의화면·권한동기화 4곳에서 불린다.
  //   해제 없이 재구독하면 핸들러가 누적돼 푸시 1건을 N번 처리한다
  //   (로컬 알림 N건 → 인앱 토스트 N건 → 알림함 N줄).
  //   호출부를 줄이는 대신 구독 쪽을 멱등하게 만든다 — 각 호출부에 나름의 이유가 있어서다.
  private unsubscribeOnMessage: (() => void) | null = null;

  // 토큰 로테이션 구독 해제 함수.
  // ★토큰은 앱이 떠 있는 동안에도 갱신된다(앱 데이터 삭제·재설치·FCM 자체 로테이션).
  //   재시작 때는 메모리가 비어 재등록되지만, 켜둔 채 갱신되면 서버에 낡은 토큰만 남아
  //   그 기기로는 푸시가 **조용히** 안 간다. 에러도 로그도 남지 않는 종류의 유실이다.
  private unsubscribeOnTokenRefresh: (() => void) | null = null;

  // ───────────────────────────────────────────────────────────────────────────
  // ① 권한 상태 판정 — 플랫폼 차이를 여기 한 곳에서 흡수한다
  // ───────────────────────────────────────────────────────────────────────────

  // 프리퍼미션 모달을 띄울지(=undetermined) 설정으로 보낼지(=denied) 판단하는 단일 진입점.
  //
  // ★★안드로이드에는 'undetermined'가 없다 — **한 번도 안 물어본 상태도 status='denied'**로 온다.
  //   물어볼 수 있는지는 `canAskAgain`으로만 구분된다.
  //   status만 보고 판단하면 안드로이드에서 프리퍼미션이 늘 '설정 열기' 모드로 떠서
  //   **OS 팝업을 단 한 번도 못 띄운다**(= 신규 설치 사용자를 통째로 놓친다). 실기기에서 확인.
  //   iOS는 미요청이 undetermined + canAskAgain=true, 거부는 canAskAgain=false라 같은 규칙으로 맞는다.
  async getPermissionStatus(): Promise<'granted' | 'denied' | 'undetermined' | 'unsupported'> {
    if (Platform.OS === 'web' || !Device.isDevice) return 'unsupported';
    try {
      const perm = await Notifications.getPermissionsAsync();
      if (perm.status === 'granted') return 'granted';
      return perm.canAskAgain ? 'undetermined' : 'denied';
    } catch {
      return 'unsupported';
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // ② 등록 — 기본값은 "팝업을 띄우지 않는다"
  // ───────────────────────────────────────────────────────────────────────────

  // ★iOS는 시스템 권한 팝업이 기기당 사실상 1회다. 맥락 없이 소진하면 회복 수단이 설정 유도뿐이라
  //   팝업은 "왜 필요한지 설명한 화면"에서만 띄운다 → 그 지점만 requestPermission: true로 부른다.
  //   로그인·세션복구·동의화면·권한동기화 같은 자동 호출은 전부 기본값(false)이어야 한다.
  //   기본값을 false로 둔 것이 이 API 설계의 핵심이다 — 실수로 부르면 아무 일도 안 일어난다.
  async registerForPushNotifications(opts?: { requestPermission?: boolean }): Promise<string | null> {
    if (Platform.OS === 'web') return null; // 웹은 SSE/폴링 경로를 쓴다
    if (!Device.isDevice) return null;

    const perm = await Notifications.getPermissionsAsync();
    let finalStatus = perm.status;

    // 호출부가 명시적으로 요청했고, OS가 아직 물어볼 수 있을 때만 팝업을 띄운다.
    // ★★판정은 status가 아니라 `canAskAgain`으로 한다 —
    //   안드로이드는 "한 번도 안 물어본 상태"도 status='denied'라서 `=== 'undetermined'`로 보면
    //   **요청 자체를 영영 건너뛴다**(안드로이드에서 OS 팝업이 한 번도 안 뜸).
    if (perm.status !== 'granted' && perm.canAskAgain && opts?.requestPermission === true) {
      const { status } = await Notifications.requestPermissionsAsync();
      finalStatus = status;
    }
    if (finalStatus !== 'granted') return null;

    // iOS는 FCM 토큰을, 안드로이드는 디바이스 토큰을 쓴다.
    // (iOS에서 getDevicePushTokenAsync()는 APNs 원시 토큰이라 FCM으로 발송할 수 없다)
    const token: string =
      Platform.OS === 'ios' && messaging
        ? await messaging().getToken()
        : (await Notifications.getDevicePushTokenAsync()).data;

    const deviceType = Platform.OS === 'ios' ? 'IOS' : 'ANDROID';

    // ★서버 등록에 성공했을 때만 "토큰 있음"으로 기억한다.
    //   실패를 성공으로 기억하면 아래 syncTokenWithPermission()이 `!this.pushToken`에 걸려
    //   재시도를 건너뛰고, "OS 권한은 허용인데 서버엔 토큰이 없는" 상태가 세션 내내 고착된다.
    const registered = await this.registerTokenToServer(token, deviceType);
    this.pushToken = registered ? token : null;

    if (Platform.OS === 'android') {
      await Notifications.setNotificationChannelAsync('default', {
        name: 'default',
        importance: Notifications.AndroidImportance.MAX,
      });
    }

    this.subscribeTokenRefresh(deviceType);
    this.subscribeForegroundMessages();
    return token;
  }

  // ───────────────────────────────────────────────────────────────────────────
  // ③ 토큰 로테이션 — 조용한 유실을 막는다
  // ───────────────────────────────────────────────────────────────────────────

  private subscribeTokenRefresh(deviceType: string) {
    if (!messaging) return;
    this.unsubscribeOnTokenRefresh?.(); // 멱등: 이전 구독 해제 후 재구독
    this.unsubscribeOnTokenRefresh = messaging().onTokenRefresh(async (newToken: string) => {
      if (!newToken || newToken === this.pushToken) return;
      const ok = await this.registerTokenToServer(newToken, deviceType);
      this.pushToken = ok ? newToken : null; // 실패를 성공으로 기억하지 않는다(위와 같은 이유)
    });
  }

  // ───────────────────────────────────────────────────────────────────────────
  // ④ 포그라운드 수신 — 표시에 실패해도 알림을 잃지 않는다
  // ───────────────────────────────────────────────────────────────────────────

  private subscribeForegroundMessages() {
    if (!messaging) return;
    this.unsubscribeOnMessage?.(); // 없으면 호출될 때마다 핸들러가 쌓인다
    this.unsubscribeOnMessage = messaging().onMessage(async (remoteMessage: any) => {
      const { notification, data } = remoteMessage;
      try {
        await Notifications.scheduleNotificationAsync({
          content: {
            title: notification?.title || 'mildo',
            body: notification?.body || '',
            data: data as Record<string, string>,
            sound: 'default',
          },
          // ★안드로이드는 채널을 지정한 즉시 트리거를 써야 한다. trigger:null로 두면
          //   SDK가 사운드 URI를 만들 때 트리거에서 채널을 읽다가 NPE로 죽는다.
          //   네이티브 예외라 JS로 올라오지 않고, 알림이 조용히 사라져 인앱 토스트가 영영 안 떴다.
          //   iOS는 채널 개념이 없으므로 즉시(null) 그대로.
          trigger: Platform.OS === 'android' ? ({ channelId: 'default' } as any) : null,
        });
      } catch (e) {
        // 표시에 실패해도 삼키고 넘어간다 — 알림을 통째로 잃는 것보다 낫다.
        console.log('[Push] 로컬 알림 재발행 실패:', e);
      }
    });
  }

  // ───────────────────────────────────────────────────────────────────────────
  // ⑤ 권한 상태 보고 — 계측이 틀리면 개선 방향도 틀린다
  // ───────────────────────────────────────────────────────────────────────────

  // "토큰 없는 유저"가 거부한 사람인지, 아직 안 물어본 사람인지 구분해야 유도 방식을 정할 수 있다
  // (거부=설정 유도만 가능 / 미요청=팝업 가능).
  //
  // 실패해도 앱 동작에 영향이 없어야 하므로 완전히 삼키고, 연속 실패가 쌓이면 세션 동안 중단한다
  // (엔드포인트 미배포 상태에서 포그라운드마다 헛요청하지 않도록).
  async reportPermissionStatus(): Promise<void> {
    if (this.permissionReportDisabled) return;
    if (Platform.OS === 'web' || !Device.isDevice) return;
    try {
      // ★status를 그대로 대문자로 올리면 안드로이드는 "아직 안 물어봄"까지 DENIED로 보고된다.
      //   그러면 서버의 거부율이 실제보다 부풀고, "아직 유도 가능한 사람"과 "영구 거부"가 구분되지 않는다.
      //   여기서는 **요청 가능 여부** 기준으로 매핑한다: 요청 가능 → UNDETERMINED / 불가 → DENIED.
      const mapped = await this.getPermissionStatus();
      if (mapped === 'unsupported') return;

      const res: any = await apiClient.reportPushPermission({
        status: mapped.toUpperCase() as 'GRANTED' | 'DENIED' | 'UNDETERMINED',
        platform: Platform.OS === 'ios' ? 'IOS' : 'ANDROID',
      });
      if (res?.success) this.permissionReportFailures = 0;
      else if (++this.permissionReportFailures >= 3) this.permissionReportDisabled = true;
    } catch {
      if (++this.permissionReportFailures >= 3) this.permissionReportDisabled = true;
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // ⑥ 권한 ↔ 토큰 동기화
  // ───────────────────────────────────────────────────────────────────────────

  // OS 설정에서 알림을 끄고 돌아오는 경로는 앱이 이벤트로 알 수 없다. 포그라운드 복귀 때 맞춘다.
  // OS 상태를 담는 서버 플래그는 두지 않았다 — **토큰 존재 유무 자체가 배달 가능 신호**다.
  // 상태를 두 곳에 두면 반드시 어긋나고, 어긋났을 때 무엇이 진실인지 정할 근거가 없다.
  async syncTokenWithPermission(): Promise<void> {
    if (Platform.OS === 'web' || !Device.isDevice) return;
    try {
      const { status } = await Notifications.getPermissionsAsync();
      if (status === 'granted') {
        if (!this.pushToken) await this.registerForPushNotifications();
      } else {
        await this.unregisterToken();
      }
    } catch {
      /* 동기화 실패는 다음 포그라운드에서 재시도된다 */
    }
  }

  private async registerTokenToServer(token: string, deviceType: string): Promise<boolean> {
    try {
      const result = await apiClient.registerDeviceToken({
        token,
        deviceType: deviceType as 'ANDROID' | 'IOS' | 'WEB',
      });
      if (result.success) {
        // 앱 재시작 후(메모리 비어 있음)에도 OS-off 시 삭제할 수 있게 보관
        await AsyncStorage.setItem(PUSH_TOKEN_KEY, token).catch(() => {});
        return true;
      }
      return false;
    } catch {
      return false;
    }
  }

  // 로그아웃 / OS 알림 off 시. 메모리에 없으면 저장해둔 토큰으로 삭제한다.
  async unregisterToken(): Promise<void> {
    const token = this.pushToken ?? (await AsyncStorage.getItem(PUSH_TOKEN_KEY).catch(() => null));
    if (!token) return;
    try {
      await apiClient.deleteDeviceToken(token);
    } finally {
      this.pushToken = null;
      // 로그아웃·권한 해제 뒤에 토큰이 갱신되면 인증 없이 등록 요청이 나간다 → 구독 해제.
      this.unsubscribeOnTokenRefresh?.();
      this.unsubscribeOnTokenRefresh = null;
      await AsyncStorage.removeItem(PUSH_TOKEN_KEY).catch(() => {});
    }
  }
}

export const pushService = new PushService();
export default pushService;
