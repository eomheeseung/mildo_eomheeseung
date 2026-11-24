/**
 * 발췌: 페르소나 재분석 비동기 — 순수 판단만(전체). 저장소·네트워크는 화면/훅에 있다.
 *
 *  - 재분석 제출에 async:true → 202 { status: ANALYZING } 를 바로 받는다.
 *  - 대기 화면 「분석 중이에요(1분쯤 걸려요). 화면을 나가도 분석은 계속돼요」 + 5초 폴링(상한 3분).
 *  - 나가면 폴링 멈춤. 앱 시작·포그라운드 복귀 때 상태 확인 → ANALYZING=대기 화면 다시,
 *    DONE=「새 페르소나가 준비됐어요」+리포트(한 번만), FAILED=「실패, 베리는 빠지지 않았어요」+다시 시도.
 *  - 완료 푸시 없음.
 */

export type ReanalyzeStatus = 'NONE' | 'ANALYZING' | 'DONE' | 'FAILED';

export interface ReanalyzeState {
  status: ReanalyzeStatus;
  startedAt?: string | null;
  finishedAt?: string | null;
  failReason?: string | null;
  personaVersion?: number | null;
}

export const REANALYZE_POLL_INTERVAL_MS = 5000;
export const REANALYZE_POLL_DEADLINE_MS = 3 * 60 * 1000;

/** 이 기기에서 비동기 재분석을 접수했다는 기록 — 결과 안내를 이 기록이 있을 때만 띄운다(다른 기기 결과로 갑자기 뜨지 않게).
 *  답도 같이 둔다: 실패 뒤 「다시 시도」에서 설문을 처음부터 다시 풀지 않게 */
export const REANALYZE_PENDING_KEY = 'reanalyze_async_pending';
/** 마지막으로 안내한(또는 대기 화면에서 확인한) personaVersion — DONE 안내를 한 번만 띄운다 */
export const REANALYZE_SEEN_VERSION_KEY = 'reanalyze_seen_persona_version';
/** 실패 안내 뒤 「다시 시도」로 설문에 채워 넣을 지난 답 */
export const REANALYZE_RETRY_KEY = 'reanalyze_retry_answers';

export interface ReanalyzePending {
  startedAt: number;
  answers: Record<string, string>;
}

/** 재분석 응답이 「비동기 접수(202 ANALYZING)」인가. 옛 서버는 async 를 무시하고 동기 성공으로 답한다 → false */
export function isAsyncAccepted(res: any): boolean {
  return !!res?.success && res?.data?.status === 'ANALYZING';
}

/** 대기 화면 폴링 한 번의 결과 → 다음 동작 */
export function pollStep(state: ReanalyzeState | null, elapsedMs: number): 'done' | 'failed' | 'continue' | 'timeout' {
  if (state?.status === 'DONE') return 'done';
  if (state?.status === 'FAILED') return 'failed';
  // 조회 실패(null)·ANALYZING·NONE(접수 직후 반영 전) 은 기다린다
  return elapsedMs >= REANALYZE_POLL_DEADLINE_MS ? 'timeout' : 'continue';
}

/**
 * 앱 시작·포그라운드 복귀 때 할 일.
 * @param hasPending 이 기기에서 접수한 기록이 있는가
 * @param seenVersion 마지막으로 안내한 personaVersion(없으면 null)
 */
export function watchAction(
  state: ReanalyzeState | null,
  hasPending: boolean,
  seenVersion: number | null,
): 'none' | 'resumeWait' | 'notifyDone' | 'notifyFailed' {
  if (!state || !hasPending) return 'none';
  switch (state.status) {
    case 'ANALYZING':
      return 'resumeWait';
    case 'DONE': {
      const v = state.personaVersion ?? null;
      if (v == null || seenVersion == null) return 'notifyDone';
      return v > seenVersion ? 'notifyDone' : 'none';
    }
    case 'FAILED':
      return 'notifyFailed';
    default:
      return 'none';
  }
}

