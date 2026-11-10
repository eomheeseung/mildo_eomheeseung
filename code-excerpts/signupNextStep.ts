// 발췌: 가입 마지막 구간의 「다음 화면」 규칙 — 전문(테스트 29건 대상)
/**
 * 가입 마지막 구간의 「다음 화면」 규칙 (2026-10-30).
 *
 * 순서: 설문 → 사진(/redesign/profile-photo) → (분석 중) → 리포트 → 홈.
 * 예전엔 설문 → 분석 중(페르소나 평균 42초 + 리포트 AI 16~19초) → 리포트 → 사진 → 홈이라,
 * 가입 직후 첫 화면에서 1분 가까이 로딩만 봤다. 이제 분석은 사진을 고르는 동안 뒤에서 돈다.
 *
 * ★이 파일은 import 가 없는 순수 함수만 둔다 — node --test 로 경우별 결과를 고정하기 위해서다.
 *   비동기 응답(분석 완료)이 사진 화면에 있을 때 오든, 건너뛰기를 누른 뒤에 오든
 *   **같은 입력이면 같은 화면**으로 가야 한다. 화면 코드는 이 함수의 답대로만 움직인다.
 * 실행: node --experimental-strip-types --test src/utils/signupNextStep.test.ts
 */

/** 최소 생성 시간(23초) 전에는 확정적으로 미완성이라 첫 조회를 20초 시점으로 미룬다. */
export const PERSONA_POLL_FIRST_DELAY_MS = 20_000;
export const PERSONA_POLL_INTERVAL_MS = 5_000;
/** 제출 시각 기준 상한. 실패는 서버가 personaGenerationFailed 로 바로 알려 주므로 상한까지 안 기다린다. */
export const PERSONA_POLL_DEADLINE_MS = 180_000;

/** 페르소나가 서버에 만들어졌다고 볼 수 있는 상태(NONE → ANALYZING → CREATED → VALIDATED → COMPLETE). */
export const PERSONA_EXISTS_STATUSES = ['CREATED', 'VALIDATED', 'COMPLETE'];

/** 분석(페르소나 생성) 진행 상태 — 화면이 아니라 서버 상태를 앱 쪽 말로 옮긴 것. */
export type PersonaPhase = 'analyzing' | 'ready' | 'failed' | 'timeout';

export interface SignupStatusLike {
  success?: boolean;
  data?: { personaStatus?: string | null; personaGenerationFailed?: boolean | null } | null;
}

/**
 * signup-status 응답 한 번 → 분석 상태.
 * - 완성(CREATED·VALIDATED·COMPLETE)이면 시간과 상관없이 ready — 상한 직후에 온 완성도 살린다.
 * - 서버가 실패로 되돌렸으면(NONE + personaGenerationFailed) failed.
 * - 그 밖(ANALYZING·NONE·조회 실패)은 상한 전이면 analyzing, 지났으면 timeout.
 */
export function personaPhaseOf(input: { res?: SignupStatusLike | null; submitAt: number; now: number }): PersonaPhase {
  const { res, submitAt, now } = input;
  const status = res?.success ? res.data?.personaStatus ?? null : null;
  if (status && PERSONA_EXISTS_STATUSES.includes(status)) return 'ready';
  if (res?.success && status === 'NONE' && res.data?.personaGenerationFailed === true) return 'failed';
  if (now - submitAt > PERSONA_POLL_DEADLINE_MS) return 'timeout';
  return 'analyzing';
}

/** 화면이 할 일. */
export type NextStep =
  | { kind: 'stay'; banner: boolean } // 사진 화면에 그대로(띠를 보일지)
  | { kind: 'wait' } // 사진 화면 자리에서 분석 중 화면을 보이고 기다린다
  | { kind: 'report' } // 리포트로
  | { kind: 'retry' } // 생성 실패 → 설문 화면의 「다시 시도」 창
  | { kind: 'timeout' }; // 3분 넘음 → 기존 오류 안내

/**
 * 사진 화면에 있는 동안 분석 상태가 바뀌었을 때.
 * ★화면은 절대 바꾸지 않는다 — 사진을 고르는 사람을 끌고 가지 않는다(기획 결정).
 *   완료면 띠만, 실패·시간 초과는 사진 단계가 끝난 뒤에 알린다.
 */
export function onPhaseWhileOnPhoto(phase: PersonaPhase): { kind: 'stay'; banner: boolean } {
  return { kind: 'stay', banner: phase === 'ready' };
}

/** 사진 단계에서 일어난 일. */
export type PhotoStepEvent = 'skip' | 'uploaded' | 'uploadRejected';

/**
 * 사진 단계를 끝냈을 때(건너뛰기·등록 성공) 또는 그 뒤 분석 중 화면에서 상태가 바뀌었을 때.
 * - 사진 거절(400 등)은 단계가 끝난 게 아니다 → 사진 화면에 남아 다른 사진을 고르거나 건너뛴다.
 */
export function afterPhotoStep(ev: PhotoStepEvent, phase: PersonaPhase): NextStep {
  if (ev === 'uploadRejected') return onPhaseWhileOnPhoto(phase);
  switch (phase) {
    case 'ready': return { kind: 'report' };
    case 'failed': return { kind: 'retry' };
    case 'timeout': return { kind: 'timeout' };
    default: return { kind: 'wait' };
  }
}

/**
 * 설문 제출 응답을 받은 뒤 어디서 기다릴지.
 * - 재분석(10베리)은 사진 단계가 없다 — 지금처럼 설문 화면에서 분석 중을 보인다.
 * - 가입 비동기 접수(202 ANALYZING)면 사진 화면으로. 단, 이번 가입에서 사진 단계를 이미 거쳤으면
 *   (생성 실패 뒤 「다시 시도」로 다시 낸 경우) 사진을 또 보이지 않고 설문 화면에서 기다린다.
 * - 그 밖(구서버 동기 201·끊김 복구)은 지금처럼 설문 화면에서 기다린다.
 */
export function waitPlaceAfterSubmit(input: { isReanalyze: boolean; asyncAccepted: boolean; photoStepDone: boolean }): 'photo' | 'survey' {
  if (input.isReanalyze) return 'survey';
  if (input.asyncAccepted && !input.photoStepDone) return 'photo';
  return 'survey';
}

/** 이번 가입에서 사진 단계를 거쳤는가(앱 메모리 — 앱을 다시 켜면 사라진다). */
export interface PhotoStepMemo { reward: boolean }

export type ReportExit =
  | { kind: 'back' }
  | { kind: 'go'; path: string; params?: Record<string, string> };

/**
 * 리포트 「확인」 뒤.
 * - 홈 「자세히보기」·재분석에서 왔으면 지금처럼 뒤로.
 * - 이번 가입에서 사진 단계를 거쳤으면 홈(사진 보상을 받았으면 홈에서 보상 안내).
 * - 안 거쳤으면(앱 재시작 뒤 이어하기·구서버 동기 가입) 지금처럼 사진 화면 → 그 뒤 홈.
 */
export function afterReportConfirm(input: { from?: string | null; photoStep: PhotoStepMemo | null }): ReportExit {
  if (input.from === 'home' || input.from === 'reanalyze') return { kind: 'back' };
  if (input.photoStep) {
    return input.photoStep.reward
      ? { kind: 'go', path: '/home', params: { reward: 'photo' } }
      : { kind: 'go', path: '/home' };
  }
  return { kind: 'go', path: '/redesign/profile-photo' };
}

/**
 * 앱을 다시 켜고 로그인했을 때 페르소나 상태별 이어갈 화면(설문 임시저장이 없을 때).
 * CREATED/VALIDATED → 리포트, ANALYZING/PENDING → 설문(분석 중 대기를 이어받음), COMPLETE → 홈.
 */
export function routeForPersonaStatus(status: string | null | undefined, basicInfoCompleted: boolean): string {
  switch (status) {
    case 'COMPLETE': return '/home';
    case 'VALIDATED':
    case 'CREATED': return '/persona-report';
    case 'PENDING':
    case 'ANALYZING': return '/persona-survey';
    default: return basicInfoCompleted ? '/persona-survey' : '/redesign/persona-start';
  }
}
