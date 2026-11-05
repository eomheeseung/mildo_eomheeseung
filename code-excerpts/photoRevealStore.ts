// src/utils/photoRevealStore.ts
//
// 사진 공개 상태 — 앱 전역 기억(「한 번 풀면 모든 곳에서 풀림」).
//
// 서버는 「누가 누구를 풀었나」 한 줄로 판정하므로, 앱도 사람(userId) 단위로 한 번만 기억한다.
// - 「사진 보기」 성공 → markPhotoRevealed(id) → 열려 있는 홈·피드·대화 목록 등이 다시 불러오지 않아도 바로 선명해진다.
//   이번 실행에서 직접 연 사람(paid)은 서버가 나중에 false 를 줘도 계속 선명하다.
// - 그 밖의 사람은 서버 값을 따른다. photoRevealed=true 면 공개, **명시적으로 false** 면 다시 잠금.
//   (홈 추천 목록에 있는 동안만 무료 공개라, 목록에서 빠지면 서버가 false 를 준다.)
//   ★잠금으로 바뀔 때는 기억만 바꾸고 다른 화면에 알리지 않는다 — 두 화면이 서로 다른 시점의 값을 들고 있을 때
//     알림을 주고받으며 끝없이 다시 그리는 것을 막는다(공개로 바뀔 때만 알린다).
// - 필드가 null(소켓 실시간 메시지 등)이면 직전에 기억한 값을 쓴다. 기억도 필드도 없으면(옛 서버) 선명.
//   단, 판정이 원래 안 실리는 경로는 unknownLocked 로 「모르면 흐림」을 켠다(트러블슈팅 34).
// ★import 없는 순수 모듈 — 경우별 결과를 단위 테스트로 고정한다.

/** photoReveal.ts 의 PhotoLockFields 와 같은 모양(import 없이 두려고 다시 적는다) */
interface LockFields {
  photoRevealed?: boolean | null;
}

type Known = 'revealed' | 'locked';

let viewerId: number | null = null;
const known = new Map<number, Known>();
/** 이번 실행에서 「사진 보기」(공개 API 성공)로 직접 연 사람 — 서버가 false 를 줘도 선명하게 둔다 */
const paid = new Set<number>();
const listeners = new Set<() => void>();
let version = 0;
let emitQueued = false;

function emit() {
  version += 1;
  listeners.forEach((l) => l());
}

/** 렌더 중에 알게 된 공개는 바로 알리지 않고 다음 틱에 알린다(다른 화면을 렌더 중에 갱신하지 않게). */
function emitLater() {
  if (emitQueued) return;
  emitQueued = true;
  setTimeout(() => { emitQueued = false; emit(); }, 0);
}

export function subscribePhotoReveal(l: () => void) {
  listeners.add(l);
  return () => { listeners.delete(l); };
}

export function photoRevealVersion() {
  return version;
}

/** 보는 사람(로그인 계정)이 바뀌면 기억을 비운다 — 다른 계정의 공개가 섞이지 않게. */
export function setPhotoRevealViewer(id: number | null | undefined) {
  const next = id ?? null;
  if (next === viewerId) return;
  viewerId = next;
  paid.clear();
  if (known.size) { known.clear(); emitLater(); }
}

/** 「사진 보기」 성공 등 확실히 풀린 사람. */
export function markPhotoRevealed(userId?: number | string | null) {
  const id = Number(userId);
  if (!Number.isFinite(id) || id <= 0) return;
  paid.add(id);
  if (known.get(id) === 'revealed') return;
  known.set(id, 'revealed');
  emit();
}

/**
 * 이 사람 사진을 흐릴지. 서버 값을 기억에 반영하면서 판정한다.
 * extraLocked: 서버와 별개로 화면이 잠그는 경우(랜덤TALK 5턴 전 등) — 둘 중 하나라도 잠금이면 흐림.
 */
export function isPhotoLocked(opts: {
  userId?: number | string | null;
  fields?: LockFields | null;
  extraLocked?: boolean;
  /** 판정을 모르는 사람(기억도 필드도 없음)을 흐릴지 — 밀도TALK 실시간 말풍선처럼 서버 판정을 아직 못 받은 경우만 켠다 */
  unknownLocked?: boolean;
}): boolean {
  const id = Number(opts.userId);
  const hasId = Number.isFinite(id) && id > 0;
  if (hasId && viewerId != null && id === viewerId) return false; // 내 사진은 나에게 늘 선명
  if (opts.extraLocked) return true;

  const f = opts.fields;
  const hasField = !!f && typeof f.photoRevealed === 'boolean';
  if (!hasId) return hasField ? !f!.photoRevealed : false;

  if (paid.has(id)) return false; // 이번 실행에서 직접 연 사람
  const prev = known.get(id);
  if (hasField) {
    if (f!.photoRevealed) {
      known.set(id, 'revealed');
      if (prev === 'locked') emitLater(); // 다른 곳에서 잠가 둔 같은 사람도 풀어 준다
      return false;
    }
    // 서버가 명시적으로 잠금 — 기억만 바꾸고 알리지 않는다(위 머리말 ★)
    known.set(id, 'locked');
    return true;
  }
  if (prev === undefined) return !!opts.unknownLocked;
  return prev === 'locked';
}

/** 이 사람 판정을 서버에 다시 물어야 하나 — 기억도 없고 받은 값에도 판정(boolean)이 없을 때. 본인은 묻지 않는다. */
export function needsPhotoVerdict(userId?: number | string | null, fields?: LockFields | null): boolean {
  const id = Number(userId);
  if (!Number.isFinite(id) || id <= 0) return false;
  if (viewerId != null && id === viewerId) return false;
  if (fields && typeof fields.photoRevealed === 'boolean') return false;
  return !known.has(id);
}

/** 목록 응답(참여자 등)의 판정을 한 번에 기억하고 열린 화면에 알린다. 직접 연 사람(paid)만 되돌아가지 않는다. */
export function recordPhotoVerdicts(items: Array<{ userId?: number | string | null; photoRevealed?: boolean | null }>) {
  let changed = false;
  for (const it of items) {
    const id = Number(it?.userId);
    if (!Number.isFinite(id) || id <= 0 || typeof it.photoRevealed !== 'boolean') continue;
    if (paid.has(id)) continue;
    const prev = known.get(id);
    const next: Known = it.photoRevealed ? 'revealed' : 'locked';
    if (prev !== next) { known.set(id, next); changed = true; }
  }
  if (changed) emit();
}

/** 시험용 — 기억을 비운다. */
export function resetPhotoRevealStore() {
  known.clear();
  paid.clear();
  viewerId = null;
}
