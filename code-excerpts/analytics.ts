// src/services/analytics.ts — 어트리뷰션/딥링크 (발췌)
//
// [포트폴리오 발췌] AppsFlyer dev key는 애초에 코드가 아니라 env(EXPO_PUBLIC_*)로 주입된다.
//                  OneLink URL/앱 스킴은 placeholder로 치환했습니다.
//
// 친구 초대(리퍼럴) 딥링크 캡처를 "이중 경로"로 설계한 이유:
//
//   초대 링크를 누른 친구가
//     · 앱이 이미 설치돼 있으면  → 앱이 mildo 스킴으로 열림(direct)
//     · 앱이 없으면            → 스토어 설치 후 첫 실행(deferred)
//   이 둘을 모두 잡아야 초대자(inviter)를 가입에 귀속시킬 수 있다.
//
//   AppsFlyer의 UDL(onDeepLink)은 "신규 설치(deferred) 귀속"엔 강하지만, adb/직접 스킴 진입 같은
//   raw 스킴은 못 잡는 경우가 있었다. 그래서 expo-linking으로 스킴 URL의 파라미터를 직접 파싱하는
//   백업 경로를 얹었다(belt-and-suspenders). 어느 경로로 들어와도 inviter가 유실되지 않는다.
import { Platform } from 'react-native';
import * as Linking from 'expo-linking';
import { setPendingInviter } from '../utils/signupFlow';

let appsFlyer: any = null;
try {
  appsFlyer = require('react-native-appsflyer').default; // 웹 번들에서 깨질 수 있어 동적 require로 보호
} catch {}

const AF_DEV_KEY = process.env.EXPO_PUBLIC_APPSFLYER_DEV_KEY ?? ''; // ⚠️ env 주입 (코드에 키 없음)
const REFERRAL_ONELINK_BASE = 'https://<ONELINK_BASE>'; // placeholder — 실제 OneLink는 대시보드 발급값
let initialized = false;

// (A) AppsFlyer UDL — 신규 설치(deferred) 포함. initSdk 전에 등록해야 deferred가 잡힌다.
function registerReferralDeepLink(): void {
  if (!appsFlyer) return;
  appsFlyer.onDeepLink((res: any) => {
    const d = res?.data;
    if (!d || d.deep_link_value !== 'invite') return;
    const n = Number(d.inviter);
    if (Number.isFinite(n) && n > 0) void setPendingInviter(n);
  });
}

// (B) 스킴 딥링크 백업 — mildo://...?inviter=N 를 직접 파싱. UDL이 raw 스킴을 못 잡는 케이스 대응.
function captureInviterFromUrl(url?: string | null): void {
  if (!url) return;
  const raw = Linking.parse(url).queryParams?.inviter;
  if (raw == null) return;
  const n = Number(Array.isArray(raw) ? raw[0] : raw);
  if (Number.isFinite(n) && n > 0) void setPendingInviter(n);
}

// 앱 시작 시 1회. 콜드스타트(getInitialURL) + 웜스타트(url 이벤트) 모두 처리.
export function initReferralLinking(): void {
  if (Platform.OS === 'web') return;
  Linking.getInitialURL().then(captureInviterFromUrl).catch(() => {});
  Linking.addEventListener('url', (e) => captureInviterFromUrl(e.url));
}

export function initAnalytics(): void {
  if (initialized || Platform.OS === 'web' || !appsFlyer) return;
  registerReferralDeepLink(); // initSdk 전에 등록
  appsFlyer.initSdk({ devKey: AF_DEV_KEY, isDebug: __DEV__ }, () => {}, () => {});
  initialized = true;
}

// 초대 링크 생성. 템플릿 단축URL은 deep_link_value=invite를 이미 담고 있어 ?inviter= 만 붙이면
// 콜백이 deep_link_value·inviter 둘 다 받는다. (SDK generateInviteLink는 버전별로 커스텀 파라미터
// 누락 위험이 있어, 확실히 동작하는 수동 URL을 쓴다.)
export async function generateReferralLink(inviterId: string | number): Promise<string> {
  return `${REFERRAL_ONELINK_BASE}?inviter=${inviterId}`;
}

export async function logAnalyticsEvent(name: string, params: Record<string, any> = {}): Promise<void> {
  if (Platform.OS === 'web' || !appsFlyer) return;
  appsFlyer.logEvent(name, params, () => {}, () => {});
}
