// src/utils/photoReveal.ts
//
// 사진 흐리기·공개 판정 — 서버가 정하고 앱은 따른다.
//
// 서버는 사진 주소가 나가는 응답에 세 필드를 **더한다**:
//   photoRevealed(boolean) · photoLockReason(NONE|NEED_MY_PHOTO|NO_PHOTO|LOCKED) · photoRevealCost(재화 수)
// 공개 API: POST /v2/photos/reveal/{targetUserId} → { photoRevealed: true, profileImageUrl }
//   (402 재화 부족, 400 사진 없는 상대, 404 없는 사용자)
//
// ★photoRevealed 가 **아예 없으면(옛 서버)** 선명하게 보인다 — 앱이 서버보다 먼저 배포돼도 화면이 안 깨지게.
// ★import 없는 순수 함수만 둔다 — 경우별 결과를 단위 테스트로 고정한다.
export type PhotoLockReason = 'NONE' | 'NO_PHOTO' | 'LOCKED';

/** 옛 서버가 NEED_MY_PHOTO 에 비용을 안 실어 보냈을 때 쓰는 값 — 지금 서버의 「내 사진 없음」 비용과 같다 */
const NEED_MY_PHOTO_FALLBACK_COST = 3;

export interface PhotoLockFields {
  photoRevealed?: boolean | null;
  photoLockReason?: string | null;
  photoRevealCost?: number | null;
}

export interface PhotoLock {
  /** 흐리게 보일지 */
  locked: boolean;
  reason: PhotoLockReason;
  /** 「사진 보기」에 드는 재화(0 이면 무료) */
  cost: number;
}

const UNLOCKED: PhotoLock = { locked: false, reason: 'NONE', cost: 0 };

export function photoLockOf(x?: PhotoLockFields | null): PhotoLock {
  if (!x || typeof x.photoRevealed !== 'boolean' || x.photoRevealed) return UNLOCKED;
  const r = x.photoLockReason;
  // NEED_MY_PHOTO(옛 서버)는 일반 잠금으로 본다 — 모르는 이유와 같이 LOCKED
  const reason: PhotoLockReason = r === 'NO_PHOTO' ? 'NO_PHOTO' : 'LOCKED';
  const c = Number(x.photoRevealCost);
  let cost = Number.isFinite(c) && c > 0 ? Math.floor(c) : 0;
  if (r === 'NEED_MY_PHOTO' && cost === 0) cost = NEED_MY_PHOTO_FALLBACK_COST;
  return { locked: true, reason, cost };
}

/** 프로필 상세 사진 아래에 무엇을 보일지. */
export type RevealAction =
  | 'none' // 잠기지 않음 — 아무것도 없음
  | 'button' // 「사진 보기 · n재화」
  | 'noPhoto'; // 상대 사진 없음 — 버튼 없이 「사진 없음」(재화를 받지 않음)

export function revealActionOf(lock: PhotoLock): RevealAction {
  if (!lock.locked) return 'none';
  if (lock.reason === 'NO_PHOTO') return 'noPhoto';
  return 'button';
}

/** 공개 API 실패를 화면 동작으로. 성공이면 null. */
export function revealFailureOf(res?: { success?: boolean; status?: number | null } | null): 'insufficient' | 'notAllowed' | 'error' | null {
  if (res?.success) return null;
  if (res?.status === 402) return 'insufficient'; // 재화 부족 창
  if (res?.status === 400) return 'notAllowed'; // 사진 없음(PV001)·옛 서버의 내 사진 없음(PV002) — 서버 문구 그대로
  return 'error';
}
