# 상태관리 전략

## 결론부터 — 왜 Redux/Zustand를 안 썼나

이 앱의 상태는 성격이 뚜렷하게 셋으로 갈렸다. 각각에 맞는 도구를 쓰면 전역 스토어가 필요 없었다.

| 상태 종류 | 예시 | 어디에 두나 |
|---|---|---|
| **서버 상태** | 페르소나·매칭·베리 잔액·초대 현황 | API 호출 결과를 화면 로컬 state로. 서버가 진실 소스 |
| **세션/전역 상태** | 로그인 유저, 다크모드, 언어 | React **Context** (`AuthContext` / `ThemeContext`) |
| **화면 간·계정 생성 전 임시 상태** | 진행 중 가입값·약관 동의·초대자 | **AsyncStorage(pending\_\*)** — 화면이 언마운트돼도, 앱을 꺼도 살아남아야 함 |

전역 클라이언트 스토어에 서버 데이터를 미러링하면 **캐시 무효화가 새로운 버그의 원천**이 된다. 서버가 진실 소스이고 화면은 필요할 때 조회하는 편이 이 도메인엔 단순하고 안전했다. "전역에 둘 이유가 있는 것만 전역에" 두는 원칙.

## 1. 세션/전역 — Context

`AuthContext`가 로그인 유저와 인증 파생 상태(`isAuthenticated`)를 들고, 앱 전역에 제공한다. 여기에 **세션 수명에 묶여야 하는 사이드이펙트**를 함께 관리한다.

- **SSE 실시간 알림**을 화면 mount가 아니라 **로그인 상태(`user?.id`)에 묶어** 연결/해제 → 화면 전환마다 재연결되는 churn 방지.
- 로그인/세션 복구 시 분석 SDK에 userId를 심고, 기기 어트리뷰션 ID를 백엔드에 등록(1회).
- 앱 백그라운드→포그라운드 복귀 감지(`AppState`)로 세션 유효성 재확인.

> 포인트: Context를 "값 저장소"가 아니라 **세션 수명 오케스트레이터**로 썼다. 연결/해제·identity 동기화가 로그인 상태 한 곳에서 결정된다.

## 2. 서버 상태 — 화면 로컬 + 통일 envelope

서버 데이터는 화면에서 `useEffect`로 조회해 로컬 state에 담는다. 모든 응답이 `{ success, data, message }`로 정규화돼 있어(→ [`api-and-build.md`](./api-and-build.md)) 화면 코드는 `res.success`/`res.data`만 본다.

로딩·빈 상태·미연동을 **명시적으로 렌더**한다(graceful degradation). 관리자 광고비 대시보드는 백엔드 연동 전에도 `-`/빈상태로 레이아웃이 먼저 서고, API가 붙으면 값이 채워지게 만들었다.

## 3. 화면 간·계정 생성 전 임시 상태 — pending storage 패턴

가장 신경 쓴 부분. **계정이 만들어지기 전**에는 `/users/me/*`를 못 친다(403). 그런데 약관 동의·초대자(리퍼럴)는 가입 화면들을 지나며 먼저 수집된다.

그래서 이 값들을 `AsyncStorage`의 `pending_agreements` / `pending_inviter`에 들고 있다가:
1. 가입 요청(signup-with-persona) **body에 실어** 보내고,
2. 가입 성공 직후 **best-effort로 한 번 더** 확정 저장(멱등 upsert)한다.

이 이중 저장 덕에 **백엔드가 body의 agreements를 아직 안 받는 배포 순서**에도 프론트가 깨지지 않는다(deploy-order-safe). 또 약관 버전이 바뀌면 보관된 옛 동의를 폐기해, "동의한 적 없는 약관이 동의됨으로 기록되는" 법적 리스크를 막는다.

관련 코드: [`../code-excerpts/signupFlow.ts`](../code-excerpts/signupFlow.ts)

## 다크모드 · 다국어

- 테마는 `ThemeContext`가 `colors`/`isDarkMode`를 내려주고, 화면은 토큰(`colors.textPrimary` 등)만 참조 → 라이트/다크 자동 대응.
- i18n은 키 기반(`t('...')`). 관리자 등 웹 전용·빠른 화면은 한국어 우선.
