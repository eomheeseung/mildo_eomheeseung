# mildo — Backend

AI 페르소나 기반 소개팅/매칭 서비스 **mildo**의 백엔드입니다.
소셜/이메일 회원가입부터 본인인증, AI 페르소나 생성·분석, 매칭, 인앱 재화(베리) 결제, 관리자 대시보드, 광고비 연동까지 **0→1로 기획·구현·배포·운영**한 실서비스입니다.

> 이 저장소는 **포트폴리오용 발췌본**입니다. 실서비스 코드에서 API 키·DB 접속정보·개인정보 등 민감 정보를 모두 제거하고, 제가 설계·구현을 주도한 대표 도메인의 코드와 설계 문서만 담았습니다.

---

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| Language / Runtime | **Java 21** (가상 스레드), **Spring Boot 3.x** |
| Security | Spring Security 6, JWT (Access/Refresh) |
| Persistence | JPA/Hibernate, MySQL(개발) / MariaDB(운영) |
| AI | Anthropic **Claude**, OpenAI — 멀티 프로바이더 추상화 |
| 결제 | Apple StoreKit2 (JWS 오프라인 검증), Google Play Developer API |
| 본인인증 | NICE 표준창 (CI/DI) |
| 광고/추적 | Google Ads API, TikTok Marketing API, AppsFlyer S2S |
| 알림 | FCM, Web Push(VAPID), SSE |
| Infra | 단일 서버 배포(자체 스크립트), Docker(로컬 DB) |

---

## 아키텍처 한눈에

```
[모바일 앱 / 관리자 웹]
        │  REST (JWT)
        ▼
┌─────────────────────────────────────────────┐
│               Spring Boot (Java 21)          │
│                                              │
│  auth ── nice(본인인증) ── agreement(약관)    │
│  persona(AI 페르소나) ── explore/match       │
│  token(베리 원장·결제·환불)                   │
│  referral(초대) ── notification              │
│  adspend(광고비 집계) ── admin(관리자)        │
│                                              │
│  ai/client ─ Claude / OpenAI 추상화          │
└───────┬───────────────┬─────────────┬────────┘
        │               │             │
   MariaDB         외부 결제/인증    외부 API
                   Apple·Google·NICE  AI·Ads·AppsFlyer
```

도메인별 패키지로 분리하고, 외부 연동(AI·결제·광고)은 각 도메인의 `client`/`service`로 캡슐화했습니다.

---

## 하이라이트 — 설계 판단이 담긴 부분

리뷰어가 코드로 바로 볼 수 있도록 대표 4개를 [`code-excerpts/`](./code-excerpts)에 담았습니다.

### 1. 인앱 재화(베리) 원장 — 멱등성과 어뷰징 방지
[`TokenLedgerService.java`](./code-excerpts/TokenLedgerService.java) · [`TokenRewardEventListener.java`](./code-excerpts/TokenRewardEventListener.java)

- **모든 적립/차감을 원장(ledger) 1행으로 기록** — 잔액은 파생값이 아니라 거래의 합으로 추적.
- **1회성 보상의 멱등키를 `userId`가 아니라 `사람(본인인증 CI 해시)`으로 설계.** userId 기준이면 **탈퇴→재가입마다 새 userId가 생겨 가입 보너스가 재지급**되는 파밍이 뚫립니다. CI 해시를 멱등키로 쓰면 탈퇴로 원 계정 CI가 파기돼도 원장 행에 남아, 같은 사람이 재가입해도 `(type, refType, refId)` 유니크로 1회만 지급됩니다.
- **무상 베리는 30일 만료 로트(grant) 단위로 FIFO 소진**, 유상/무상 혼합 차감 시 분해량을 영속화해 **환불 시 동일 비율로 복원**.

> 처음엔 `existingUser == null`로 신규만 보너스를 주게 짰다가, redesign 플로우에서 쉘 유저가 미리 생성돼 **보너스가 한 번도 안 나가던 버그**를 실운영에서 발견 → 멱등 설계 덕에 "무조건 발행해도 사람당 1회"로 안전하게 고쳤습니다.

### 2. 친구 초대(리퍼럴) — 3중 어뷰징 방지
[`ReferralService.java`](./code-excerpts/ReferralService.java)

보상 한도가 **무제한**인 정책에서, 자기 초대·재가입 파밍을 막기 위해 3중 방어를 설계했습니다.
- 자기 자신 초대 금지 (**userId뿐 아니라 본인인증 CI 동일**까지 차단 → 부계정 우회 봉쇄)
- `referrals` 유니크 2개 (초대 계정 1회 + **사람(CI 해시) 1회**)
- 원장 멱등키를 **피초대자 CI 해시**로 잡음 (초대자는 무제한이라 초대자 CI로 잡으면 2번째부터 막힘 — 이 방향성이 핵심)
- 초대 처리는 **가입 트랜잭션 커밋 후 별도 트랜잭션**에서 → 잘못된 `inviterId`가 회원가입 자체를 깨지 않음

### 3. 광고비 대시보드 — 외부 API 연동 + 일 배치
[`GoogleAdsClient.java`](./code-excerpts/GoogleAdsClient.java)

Google Ads·TikTok 광고비를 매일 당겨와 베리 매출과 합쳐 **ROAS·순이익**을 집계합니다.
- 무거운 `google-ads-java` SDK 대신 **`java.net.http.HttpClient` + REST**로 직접 호출 (의존성 최소화)
- `refresh_token → access_token` 교환 후 GAQL 질의
- 저장 구조를 **처음부터 `날짜 × 플랫폼 × 지표`** 로 잡아 메타 등 확장에 대비, `(날짜,플랫폼)` 유니크로 **멱등 upsert** (지연 반영·정정분을 재조회로 덮어씀)

### 4. AI 멀티 프로바이더 추상화
[`ClaudeClient.java`](./code-excerpts/ClaudeClient.java)

- `AiClient` 인터페이스로 Claude/OpenAI를 교체 가능하게 추상화, 관리자에서 기능별 모델 지정.
- **Claude 5 세대 대응**: 신모델이 `temperature`를 폐기(400 에러) → 지원 모델에만 파라미터를 싣도록 분기. 복잡한 프롬프트에서 응답 앞에 `thinking` 블록을 자동 삽입 → `content[0].text`가 비어 파싱이 깨지던 것을, **`type=text`인 첫 블록을 찾도록** 수정.
- JSON 파싱 실패 시 "순수 JSON만 반환" 시스템 메시지로 **1회 자동 재호출**하는 방어층.

---

## 그 외 구현 범위

- **회원가입 게이트** — 소셜(카카오/네이버/애플) + 이메일 + NICE 본인인증. 이메일 도메인 화이트리스트, 심사(App Store/Play) 계정 bypass.
- **NICE 본인인증 1인 1계정** — 재인증 시 기존 계정 CI를 훔쳐가던 문제를, `users.ci` 유니크 + 살아있는 계정이 보유한 CI면 가입 거부로 차단.
- **결제/환불** — Apple JWS 오프라인 검증, Google Play 실검증, 스토어 환불 웹훅/폴링으로 적립 회수(clawback).
- **관리자 대시보드** — 유저/매칭/신고/분석(시계열·퍼널)·베리 매출·광고비.
- **탐색/매칭** — AI 페르소나 4축 유사도 기반 추천, 신고 시 양방향 발견 차단.

---

## 트러블슈팅 (실운영)

문서: [`docs/troubleshooting.md`](./docs/troubleshooting.md)

실서비스를 운영하며 진단·해결한 사례를 정리했습니다. 예시:
- 소셜 가입자가 **설문 완료 직전에 막히던 버그** — 이메일 도메인 화이트리스트가 소셜 경로에 누락 (@kakao.com 등)
- **Google Ads API 연동 3종 삽질** — API 버전 sunset(v21만 유효), MCC 헤더로 인한 권한 거부, 통화 micros 환산
- **push-consent 403** 원인 규명 — 백엔드가 아니라 클라이언트 토큰 미첨부(도메인 이전 중 origin-scoped localStorage 유실)

---

## 문서

- [`docs/architecture.md`](./docs/architecture.md) — 시스템/도메인 구조
- [`docs/berry-ledger.md`](./docs/berry-ledger.md) — 베리 원장 설계 상세
- [`docs/ad-spend.md`](./docs/ad-spend.md) — 광고비 연동 설계
- [`docs/troubleshooting.md`](./docs/troubleshooting.md) — 실운영 트러블슈팅
