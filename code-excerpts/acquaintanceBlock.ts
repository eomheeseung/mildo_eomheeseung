/**
 * 발췌: 지인 차단 — 기기 연락처를 서버로 보내기 전에 다듬는 규칙(전체).
 *
 * ★import 없는 순수 함수만 둔다 — 경우별 결과를 단위 테스트(10건)로 고정한다.
 *
 * 개인정보 원칙:
 * - 원래 이름은 서버로 보내지 않는다. 기기에서 성 한 글자만 잘라 보낸다.
 * - 회원 여부는 어디에도 드러내지 않는다 — 숫자는 「등록한 번호 수」뿐.
 */

/** 한 번에 보내는 최대 개수(명세 2번). */
export const CONTACTS_CHUNK_SIZE = 500;
/** 사용자당 전체 등록 한도(명세 2번). 기기에서도 이만큼만 보낸다 — 넘는 건 서버가 어차피 건너뛴다. */
export const ACQ_TOTAL_LIMIT = 5000;
/** 직접 입력 메모 최대 길이(명세 4번). */
export const ACQ_MEMO_MAX = 10;

/**
 * 전화번호 → `010` 으로 시작하는 11자리 숫자. 휴대폰 번호가 아니면 null.
 * `+82 10-1234-5678`, `82-10…`, `010.1234.5678`, `(010) 1234 5678` 모두 받는다.
 */
export function normalizePhone(raw?: string | null): string | null {
  if (typeof raw !== 'string') return null;
  let digits = raw.replace(/\D/g, '');
  if (digits.startsWith('82')) digits = '0' + digits.slice(2).replace(/^0/, '');
  return /^010\d{8}$/.test(digits) ? digits : null;
}

/** 입력 중인 번호에 하이픈을 넣는다(숫자만 남기고 010-1234-5678 모양으로). 11자리를 넘는 숫자는 버린다. */
export function formatPhoneInput(raw: string): string {
  const d = raw.replace(/\D/g, '').slice(0, 11);
  if (d.length <= 3) return d;
  if (d.length <= 7) return `${d.slice(0, 3)}-${d.slice(3)}`;
  return `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7)}`;
}

const HANGUL_SYLLABLE = /^[\uAC00-\uD7A3]$/;
const LATIN = /^[A-Za-z]$/;
/**
 * 여기서 멈추는 글자(성으로 쓸 수 없고 건너뛰지도 않음): 숫자·한글 자모·한자·가나.
 * 그 밖(공백·기호·이모지 등)은 건너뛴다. ★유니코드 속성(\p{…})은 Hermes 지원이 불확실해 범위로 적는다.
 */
const STOP = /^[0-9\u1100-\u11FF\u3130-\u318F\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]$/;

/** 앞의 기호·이모지를 건너뛰고 첫 글자가 한글 음절이면 그 글자, 영문이면 대문자. 그 밖이면 null. */
function firstLetter(text?: string | null): string | null {
  if (typeof text !== 'string') return null;
  for (const ch of Array.from(text)) {
    if (HANGUL_SYLLABLE.test(ch)) return ch;
    if (LATIN.test(ch)) return ch.toUpperCase();
    if (STOP.test(ch)) return null;
  }
  return null;
}

/**
 * 연락처 이름 → 성 한 글자(서버 `lastInitial`).
 * - 성 칸(lastName)이 있으면 그 첫 글자.
 * - 없으면 표시 이름(name)의 첫 글자. 안드로이드 연락처는 대개 성·이름을 나누지 않고 「김철수」 한 칸이다.
 *   영문 표시 이름(「John Smith」)은 성이 뒤에 오지만 기기에서 확실히 가를 수 없어 첫 글자를 쓴다.
 * - 한글 음절·영문이 아니면(숫자로 시작, 한자, 자모만 등) null — 서버 규칙: 한글 1자 또는 영문 대문자 1자.
 */
export function lastInitialOf(contact: { lastName?: string | null; name?: string | null; firstName?: string | null }): string | null {
  // 성 칸에서 못 찾으면 표시 이름으로 넘어간다 — 안드로이드는 「★이민호」를 성 「★」·이름 「이민호」로 나눠 저장한다(실기기 확인)
  return firstLetter(contact.lastName) ?? firstLetter(contact.name) ?? firstLetter(contact.firstName);
}

export interface DeviceContactLike {
  name?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  phoneNumbers?: { number?: string | null }[] | null;
}

export interface ContactPayload {
  phone: string;
  lastInitial: string | null;
}

/**
 * 기기 연락처 → 보낼 목록. 휴대폰 번호만, 같은 번호는 한 번(성 글자가 있는 쪽을 남김), 최대 ACQ_TOTAL_LIMIT 개.
 * 내 번호를 빼는 건 서버가 한다(skipped 로 셈).
 */
export function contactsToPayload(contacts: DeviceContactLike[], limit = ACQ_TOTAL_LIMIT): ContactPayload[] {
  const byPhone = new Map<string, string | null>();
  for (const c of contacts) {
    const initial = lastInitialOf(c);
    for (const p of c.phoneNumbers ?? []) {
      const phone = normalizePhone(p?.number);
      if (!phone) continue;
      if (!byPhone.has(phone)) {
        if (byPhone.size >= limit) continue;
        byPhone.set(phone, initial);
      } else if (byPhone.get(phone) == null && initial) {
        byPhone.set(phone, initial);
      }
    }
  }
  return Array.from(byPhone, ([phone, lastInitial]) => ({ phone, lastInitial }));
}

/** 목록을 CONTACTS_CHUNK_SIZE 개씩 자르고 마지막 묶음에 finalChunk:true. 비었으면 빈 배열(보내지 않음 — 서버는 빈 목록을 AQ001 로 거절). */
export function toChunks(list: ContactPayload[], size = CONTACTS_CHUNK_SIZE): { contacts: ContactPayload[]; finalChunk: boolean }[] {
  const out: { contacts: ContactPayload[]; finalChunk: boolean }[] = [];
  for (let i = 0; i < list.length; i += size) {
    out.push({ contacts: list.slice(i, i + size), finalChunk: i + size >= list.length });
  }
  return out;
}

/** 오류 code → i18n 키. 분기는 message 말고 code 로(명세). 모르는 code 는 null → 화면이 일반 오류 문구를 쓴다. */
export function acqErrorKey(code?: string | null): string | null {
  switch (code) {
    case 'AQ001': return 'acqBlock.err.AQ001';
    case 'AQ002': return 'acqBlock.err.AQ002';
    case 'AQ003': return 'acqBlock.err.AQ003';
    case 'AQ004': return 'acqBlock.err.AQ004';
    case 'AQ005': return 'acqBlock.err.AQ005';
    case 'AQ006': return 'acqBlock.err.AQ006';
    case 'AQ007': return 'acqBlock.err.AQ007';
    default: return null;
  }
}
