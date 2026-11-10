/**
 * 발췌: 페르소나 소개팅 — 서버 응답을 화면 값으로 옮기는 판정(전체 330줄 중 일부).
 *
 * 내 페르소나와 상대 페르소나가 AI끼리 대화하고, 나는 결과 리포트만 본다.
 * 생성은 비동기 — 시작(POST)은 바로 GENERATING 으로 돌아오고, 끝나면 완료/실패 알림.
 *
 * ★공통 요청 함수는 실패해도 throw 하지 않고 { success:false, code, status, message } 를 준다.
 * ★import 없는 순수 함수만 둔다 — 경우별 결과를 단위 테스트(23건)로 고정한다.
 */

export type AiDateStatus = 'GENERATING' | 'READY' | 'FAILED';

export interface AiDateQuote {
  available?: boolean | null;
  unavailableReason?: string | null;
  free?: boolean | null;
  cost?: number | null;
  existing?: { aiDateId: number; status: AiDateStatus | string; rerunAvailable?: boolean | null } | null;
}

// AiDateDetail(상세 응답 타입) 정의는 생략 — status · result · failReason · viewerRole · matchState 를 쓴다.

export type AiDateButton =
  | { kind: 'none' }
  | { kind: 'start'; free: boolean; cost: number }
  | { kind: 'generating'; aiDateId: number }
  | { kind: 'result'; aiDateId: number }
  /** 페르소나가 바뀌어 다시 돌릴 수 있음 — 「결과 보기」와 「새로 돌리기」 둘 다 */
  | { kind: 'resultAndRerun'; aiDateId: number; free: boolean; cost: number };

/** quote 응답 → 프로필 상세 버튼. 응답이 없거나 실패면 버튼을 그리지 않는다(옛 서버·기능 꺼짐). */
export function aiDateButtonOf(q?: AiDateQuote | null): AiDateButton {
  if (!q) return { kind: 'none' };
  const free = q.free === true;
  const cost = free ? 0 : Math.max(0, Math.floor(Number(q.cost) || 0));
  const ex = q.existing;
  const exId = Number(ex?.aiDateId);
  if (ex && Number.isFinite(exId) && exId > 0) {
    if (ex.status === 'GENERATING') return { kind: 'generating', aiDateId: exId };
    if (ex.status === 'READY') {
      // 결과는 available 이 false 여도 보여 준다(명세 3-1). 새로 돌리기는 available 일 때만
      if (ex.rerunAvailable === true && q.available !== false) return { kind: 'resultAndRerun', aiDateId: exId, free, cost };
      return { kind: 'result', aiDateId: exId };
    }
  }
  if (q.available === false) return { kind: 'none' };
  return { kind: 'start', free, cost };
}

export type AiDateStartOutcome =
  /** 202 GENERATING — 진행 화면으로 */
  | { kind: 'progress'; aiDateId: number }
  /** 200 READY — 이미 같은 페르소나로 결과 있음, 결과 화면으로(과금 없음) */
  | { kind: 'result'; aiDateId: number }
  /** 402 TK001 — 기존 베리 부족 창 */
  | { kind: 'insufficient' }
  /** 409 DT002 — 다른 상대와 진행 중, 또는 같은 순간 두 번 누름 */
  | { kind: 'busy' }
  /** 404 P001 — 내 페르소나 없음 */
  | { kind: 'noMyPersona' }
  /** DT001 꺼짐 · DT003 상대 페르소나 없음 · U001(차단·신고·동성·본인·탈퇴를 서버가 하나로 묶음) — 안내 뒤 버튼을 숨긴다 */
  | { kind: 'unavailable'; code: string; serverMessage: string | null }
  | { kind: 'error'; serverMessage: string | null };

const UNAVAILABLE_CODES = new Set(['DT001', 'DT003', 'U001']);

function serverMessageOf(res: any): string | null {
  // 서버가 응답했을 때(status 있음)만 서버 문구를 쓴다 — 네트워크 오류면 axios 영문 메시지가 들어 있다
  const msg = typeof res?.message === 'string' ? res.message.trim() : '';
  return res?.status && msg ? msg : null;
}

export function aiDateStartOutcomeOf(res: any): AiDateStartOutcome {
  if (res?.success) {
    const id = Number(res?.data?.aiDateId);
    if (!Number.isFinite(id) || id <= 0) return { kind: 'error', serverMessage: null };
    return res.data.status === 'READY' ? { kind: 'result', aiDateId: id } : { kind: 'progress', aiDateId: id };
  }
  const code = typeof res?.code === 'string' ? res.code : '';
  if (res?.status === 402 || code === 'TK001') return { kind: 'insufficient' };
  if (code === 'DT002') return { kind: 'busy' };
  if (code === 'P001') return { kind: 'noMyPersona' };
  if (UNAVAILABLE_CODES.has(code)) return { kind: 'unavailable', code, serverMessage: serverMessageOf(res) };
  return { kind: 'error', serverMessage: serverMessageOf(res) };
}

/** 상세 응답 → 화면 단계. 모르는 status 는 진행 중으로 보지 않고 오류로(폴링이 끝나지 않는 것을 막는다) */
export function aiDatePhaseOf(d?: AiDateDetail | null): 'loading' | 'generating' | 'ready' | 'failed' {
  if (!d) return 'loading';
  if (d.status === 'GENERATING') return 'generating';
  if (d.status === 'READY' && d.result) return 'ready';
  return 'failed';
}

/** 「볼 수 없는 소개팅」(404 DT004) — 권한 없음·탈퇴·차단·신고를 서버가 하나로 묶는다. 이유를 나누지 않는다 */
export function isAiDateGone(res: any): boolean {
  return !res?.success && res?.code === 'DT004';
}


