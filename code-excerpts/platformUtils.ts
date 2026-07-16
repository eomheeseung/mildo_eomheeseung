// 크로스플랫폼 UX 유틸 (발췌)
//
// [포트폴리오 발췌] 스토어 URL·패키지명은 placeholder로 치환했습니다.
//
// "웹은 브라우저마다·환경마다 되는 게 다르다"를 실제로 다룬 두 유틸.
// 화면 코드가 이 예외들을 몰라도 되게 한 곳에 모았다.

// ── 1. 다운로드 버튼 — User-Agent로 OS 분기 ──
// 웹 랜딩의 "앱 다운로드"는 방문자 OS에 맞는 스토어로 보내야 한다.
// iOS/macOS → App Store, 그 외(Android/Windows 등) → Play Store.
const APP_STORE_URL = 'https://apps.apple.com/app/id<APP_STORE_ID>'; // placeholder
const PLAY_STORE_URL = 'https://play.google.com/store/apps/details?id=com.mildo.app';

export function getStoreUrl(): string {
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent : '';
  if (/iPhone|iPad|iPod/i.test(ua)) return APP_STORE_URL;
  if (/Macintosh|Mac OS X/i.test(ua)) return APP_STORE_URL; // 데스크탑 macOS도 App Store로
  return PLAY_STORE_URL; // Android · Windows · 기타
}

// ── 2. 클립보드 복사 — 보안 컨텍스트 폴백 ──
// navigator.clipboard 는 "보안 컨텍스트(https/localhost)"에서만 동작한다.
// LAN·http로 접속하면 undefined라 복사가 조용히 실패하는데, "복사됨"이라고 안내하면
// 사용자가 붙여넣기가 안 돼 당황한다(실제로 사내 테스트에서 겪음).
// → 레거시 execCommand 폴백을 얹고, "실제 복사 성공 여부"를 반환해 안내를 정직하게 한다.
export async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // 권한 거부 등 → 폴백으로
  }
  try {
    if (typeof document === 'undefined') return false;
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0'; // 화면에 안 보이게
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}
