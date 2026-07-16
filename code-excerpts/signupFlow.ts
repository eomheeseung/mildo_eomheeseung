// src/utils/signupFlow.ts — 가입/온보딩 라우팅 + 계정 생성 전 상태 보존
//
// [포트폴리오 발췌] 민감정보 없음. 설계 의도를 보기 위한 발췌입니다.
//
// 두 가지 설계 판단이 담겨 있다.
//
//  (1) "다음 화면"을 로컬 화면 스택이 아니라 **서버의 signup-status 응답**으로 결정한다.
//      → 앱을 껐다 켜거나 다른 기기에서 재로그인해도, 서버가 진실 소스라 항상 같은 자리로 복원된다.
//
//  (2) 계정이 만들어지기 "전"에는 /users/me/* 를 못 친다(403). 그런데 약관 동의·초대자(리퍼럴)는
//      가입 화면을 지나며 먼저 수집된다. 그래서 이 값들을 로컬(pending_*)에 들고 있다가
//      가입 요청(signup-with-persona) body에 실어 보내고, 가입 성공 직후 best-effort로 한 번 더
//      확정 저장한다(멱등 upsert). → 백엔드가 body의 agreements를 아직 안 받는 배포 순서에도
//      프론트가 깨지지 않는다(deploy-order-safe).
import AsyncStorage from '@react-native-async-storage/async-storage';
import { apiClient } from '../api/client';

// ── 가입 전 동의 항목 보관 ─────────────────────────────────────────────
const AGREEMENTS_KEY = 'pending_agreements';
export const AGREEMENT_VERSION = 'v1.0'; // 개정 시 이 값만 올린다(백엔드 기본 버전과 일치)

export interface PendingAgreement { agreementType: string; version: string; agreed: boolean }

export async function setPendingAgreements(items: PendingAgreement[]): Promise<void> {
  await AsyncStorage.setItem(AGREEMENTS_KEY, JSON.stringify(items));
}

// ⚠️ 보관값의 버전이 현재 버전과 다르면 폐기한다.
// 약관 개정(v1.0→v1.1) 시점에 앱에 남아 있던 옛 동의가 가입 요청에 실리면, 사용자가 새 약관에
// 동의한 적이 없는데 서버는 "동의 완료"로 기록한다(법적으로 방어 불가). 폐기하면 동의 화면을 다시 태운다.
export async function getPendingAgreements(): Promise<PendingAgreement[] | null> {
  try {
    const parsed = JSON.parse((await AsyncStorage.getItem(AGREEMENTS_KEY)) ?? 'null');
    if (!Array.isArray(parsed) || parsed.length === 0) return null;
    const items = parsed as PendingAgreement[];
    if (items.some((it) => it?.version !== AGREEMENT_VERSION)) {
      await clearPendingAgreements();
      return null;
    }
    return items;
  } catch {
    return null;
  }
}

export async function clearPendingAgreements(): Promise<void> {
  await AsyncStorage.removeItem(AGREEMENTS_KEY);
}

// ── 초대자(리퍼럴) 보관 ─────────────────────────────────────────────
// 딥링크로 유입된 초대자 userId를 가입 시점까지 들고 가서 body.inviterId로 실어 보낸다.
// 백엔드가 잘못된 inviterId를 조용히 무시(가입은 성공)하므로, 프론트는 검증 없이 그대로 싣는다.
const INVITER_KEY = 'pending_inviter';

export async function setPendingInviter(inviterId: number): Promise<void> {
  if (!Number.isFinite(inviterId) || inviterId <= 0) return;
  await AsyncStorage.setItem(INVITER_KEY, String(inviterId));
}

export async function getPendingInviter(): Promise<number | null> {
  const raw = await AsyncStorage.getItem(INVITER_KEY);
  const n = Number(raw);
  return raw && Number.isFinite(n) && n > 0 ? n : null;
}

export async function clearPendingInviter(): Promise<void> {
  await AsyncStorage.removeItem(INVITER_KEY);
}

export interface SignupRoute { path: string; params?: Record<string, string> }

// 가입 진행 단계에 따라 이동할 라우트를 signup-status 응답 기준으로 결정.
// (로컬 플래그/화면 스택이 아니라 서버 응답 기준 — 앱 재시작/재로그인해도 같은 결과)
//   isVerified=false                          → 본인인증
//   isVerified=true, agreementCompleted=false → 약관/알림 동의  (단, 로컬에 pending 동의가 있으면 이미 지난 것으로 간주)
//   isVerified=true, agreementCompleted=true  → 설문 단계 (surveyDraft 있으면 저장 위치로 복원)
//   personaStatus=COMPLETE                    → 홈
export async function getSignupStepRoute(d: any): Promise<SignupRoute> {
  if (d?.personaStatus === 'COMPLETE') return { path: '/home' };
  if (!d?.isVerified) return { path: '/nice-auth' };

  // 동의는 가입 완료 시점에 서버에 기록되므로 가입 전까지 agreementCompleted=false다.
  // 로컬에 보관된 동의(pending_agreements)가 있으면 이미 이 화면을 지난 것이므로 설문 단계로 그대로 보낸다.
  if (!d?.agreementCompleted && !(await getPendingAgreements())) return { path: '/push-consent' };

  // 설문 임시저장(surveyDraft)이 있으면 저장된 위치로 복원 (앱 종료/새로고침 후 이어하기)
  const draft = d?.surveyDraft;
  if (draft?.answers) {
    const params = { draftPage: String(draft.currentPage), draftAnswers: JSON.stringify(draft.answers) };
    return draft.answers.step === 'survey' ? { path: '/persona-survey', params } : { path: '/onboarding', params };
  }

  switch (d?.personaStatus) {
    case 'VALIDATED':
    case 'CREATED': return { path: '/persona-report' };
    case 'PENDING':
    case 'ANALYZING': return { path: '/persona-survey' };
    default:
      return d?.basicInfoCompleted ? { path: '/persona-survey' } : { path: '/persona-start' };
  }
}

// 로그인/앱 진입 후 signup-status를 조회해 위 표대로 라우트 결정.
export async function resolveSignupRoute(loginData?: any): Promise<SignupRoute> {
  if (loginData?.personaStatus === 'COMPLETE') return { path: '/home' };
  try {
    const status = await apiClient.getSignupStatus();
    return await getSignupStepRoute((status as any)?.data || {});
  } catch {
    return { path: '/nice-auth' }; // 조회 실패 시 안전하게 본인인증부터
  }
}
