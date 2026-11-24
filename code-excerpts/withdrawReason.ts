/**
 * 발췌: 회원 탈퇴 사유 — 선택지(코드) + 자유 입력(전체).
 *
 * 단일 선택, ETC 일 때만 직접 입력 필수.
 * 서버 요청은 { reason, reasonCode, reasonDetail? }. reason 은 옛 서버·옛 관리자 집계 호환용으로
 * 계속 보낸다(서버에서 필수). 새 서버는 reasonCode 로 집계한다.
 * reasonCode 를 모르는 옛 서버는 그 필드를 무시한다(추가 필드라 요청이 깨지지 않는다).
 */

export const WITHDRAW_REASON_CODES = [
  'NO_MATCH',
  'BORING',
  'HARD_TO_USE',
  'FOUND_PARTNER',
  'TOO_MANY_NOTI',
  'PRIVACY',
  'PRICE',
  'ETC',
] as const;

export type WithdrawReasonCode = (typeof WITHDRAW_REASON_CODES)[number];

/** i18n 키 — personalInfo.withdrawReasonOptions.<code> */
export const withdrawReasonLabelKey = (code: WithdrawReasonCode) => `personalInfo.withdrawReasonOptions.${code}`;

/** 서버 reasonDetail 최대 길이(BE 500자) */
export const WITHDRAW_DETAIL_MAX = 500;

export type WithdrawReasonCheck =
  | { ok: true; payload: { reason: string; reasonCode: WithdrawReasonCode; reasonDetail?: string } }
  | { ok: false; error: 'NO_CODE' | 'DETAIL_REQUIRED' };

/**
 * 고른 선택지·입력값 → 서버 요청. 검증 실패면 이유를 돌려준다.
 * @param label 고른 선택지의 화면 문구(reason 에 그대로 담는다)
 */
export function withdrawReasonPayload(code: WithdrawReasonCode | null, label: string, detail: string): WithdrawReasonCheck {
  if (!code) return { ok: false, error: 'NO_CODE' };
  const d = detail.trim().slice(0, WITHDRAW_DETAIL_MAX);
  if (code === 'ETC' && !d) return { ok: false, error: 'DETAIL_REQUIRED' };
  // reason 은 사람이 읽는 한 줄 — 선택지 문구, 입력이 있으면 「문구 / 입력」
  const reason = (d ? `${label} / ${d}` : label).slice(0, WITHDRAW_DETAIL_MAX);
  return { ok: true, payload: d ? { reason, reasonCode: code, reasonDetail: d } : { reason, reasonCode: code } };
}
