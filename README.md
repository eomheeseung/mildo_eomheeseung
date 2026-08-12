# mildo — Backend

AI 페르소나 기반 소개팅/매칭 서비스 **mildo**의 백엔드입니다.
소셜/이메일 회원가입부터 본인인증, AI 페르소나 생성·분석, 매칭, 인앱 재화(베리) 결제, 관리자 대시보드, 광고비 연동까지 **0→1로 기획·구현·배포·운영**한 실서비스입니다.

> 이 저장소는 **포트폴리오용 발췌본**입니다. 실서비스 코드에서 API 키·DB 접속정보·개인정보 등 민감 정보를 모두 제거하고, 제가 설계·구현을 주도한 대표 도메인의 코드와 설계 문서만 담았습니다.

## 역할

**백엔드 전 영역을 1인으로 담당** — 아키텍처 설계부터 도메인 구현, 외부 연동, 배포, 운영·장애 대응까지.
DB 스키마 설계, 결제/재화 시스템, 본인인증·회원가입 게이트, AI 연동, 관리자 대시보드, 광고비 파이프라인을 직접 설계·구현하고, 운영 중 발생한 이슈를 로그·DB로 진단해 해결했습니다.

## 운영 범위 (실서비스)

- **회원가입** — 소셜(카카오·네이버·애플) + 이메일 + NICE 본인인증 (1인 1계정)
- **인앱결제 + 재화(베리) 경제** — Apple·Google 영수증 서버 검증, 환불 자동 회수(clawback)까지 운영
- **AI 페르소나** — Claude/OpenAI로 성향 분석·리포트·매칭 추천
- **광고비 파이프라인** — Google Ads · TikTok 실연동, 일 배치로 ROAS·순이익 집계
- **관리자 대시보드** — 유저·매칭·신고·분석(시계열·퍼널)·매출·광고비
- **커뮤니티 피드** — 게시물 · 2단계 댓글 · 좋아요 · 신고, 상태 기반 노출 제어(soft delete)
- **알림** — FCM/Expo 푸시 7종 카테고리 수신 동의 + 야간 광고 차단(정보통신망법), AI 대화 호감도 채점

## 실운영 지표

> **출시 초기 (2026.06.19 ~ 07.16)** 실서비스 데이터. 테스트 계정 제외, 집계 수치만.

| 지표 | 값 |
|---|---|
| 가입자 | **152명** (소셜 118 · 이메일 34) |
| 본인인증(NICE) 완료 | 85명 |
| 페르소나 생성 완료 | 61명 |
| 매칭 신청 | 55건 |
| AI 호출 | **2,215회** (성공 2,191 · 99%) |
| 집행 광고비 | Google · TikTok 합 **124,497원** |

*이제 막 런칭한 서비스라 규모는 작지만, **유저·본인인증·결제·AI·광고가 실제로 도는** 프로덕션입니다.*

## 실제 서비스

실제 스토어에 출시·운영 중인 서비스입니다.

- **App Store**: https://apps.apple.com/kr/app/mildo/id6759034625
- **Google Play**: https://play.google.com/store/apps/details?id=com.tidematch.app&hl=ko

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

### 기술 선택 근거 (따라 한 게 아니라 판단한 것)

- **Java 21 가상 스레드** — AI·결제·본인인증 등 **외부 I/O 대기가 많은** 요청 특성상, 스레드 풀 튜닝 없이 블로킹 코드 그대로 높은 동시성을 얻기 위해 JDK 17→21로 올림. 리액티브의 복잡도 없이 명령형 코드를 유지.
- **HTTP 클라이언트 3종 구분** — AI는 Spring 6 **HTTP Interface(`@HttpExchange`)** 로 선언적 호출, 광고·결제 검증 등 단발 조회는 **`java.net.http`**(SDK 없이), OAuth는 RestTemplate. 연동 성격에 맞춰 선택.
- **모놀리식** — 1인 개발·초기 서비스 규모에서 MSA의 운영 오버헤드는 과설계. 대신 **도메인 패키지 경계를 엄격히** 지켜 추후 분리가 쉽도록 설계.
- **원장(ledger) 패턴** — 돈·재화는 잔액을 직접 수정하지 않고 **모든 증감을 거래로 기록**해, 정산·환불·감사를 거래의 재생으로 해결.
- **트랜잭션 분리** — 가입 등 핵심은 커밋하고, 보상·이벤트는 `AFTER_COMMIT` + `REQUIRES_NEW`로 분리해 부가 로직 실패가 핵심을 롤백하지 않게 함.
- **잔액 동시 갱신은 비관적 락** — 처음엔 낙관적 락을 골랐으나 **선택의 전제였던 재시도가 구현돼 있지 않아** 운영에서 지급이 유실됐습니다. 선택지 4가지를 다시 놓고 비교해 비관적 락으로 통일하고, 다중 락 경로는 userId 오름차순으로 순서를 고정(데드락 예방)했습니다.
- **저장 시각은 앱이 정한다** — OS 타임존·DB 시계에 기대던 지점을 걷어내고 JVM 기본 타임존을 코드로 고정. "지금 맞으니 됐다"와 "왜 맞는지 코드가 보장한다"는 다르다고 판단.

> 상세: [`docs/engineering-decisions.md`](./docs/engineering-decisions.md) — JDK 21 마이그레이션, HTTP 클라이언트 선택, 트랜잭션 전략, **동시성 제어(선택지 비교와 번복)**, 시각 처리, 백오피스 분리, 알림 카테고리 설계, LLM 응답 형식 선택, 코딩 정책.

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

리뷰어가 코드로 바로 볼 수 있도록 대표 코드를 [`code-excerpts/`](./code-excerpts)에 담았습니다.

### 1. 인앱 재화(베리) 원장 — 멱등성·동시성·어뷰징 방지
[`TokenLedgerService.java`](./code-excerpts/TokenLedgerService.java) · [`TokenAccount.java`](./code-excerpts/TokenAccount.java) · [`TokenRewardEventListener.java`](./code-excerpts/TokenRewardEventListener.java)

- **모든 적립/차감을 원장(ledger) 1행으로 기록** — 잔액은 파생값이 아니라 거래의 합으로 추적.
- **동시 차감 이중차감 방지** — 잔액 계좌에 `@Version` 낙관적 락을 둬, 동시 요청이 같은 잔액을 두 번 깎지 못하게 함.
- **1회성 보상의 멱등키를 `userId`가 아니라 `사람(본인인증 CI 해시)`으로 설계.** userId 기준이면 **탈퇴→재가입마다 새 userId가 생겨 가입 보너스가 재지급**되는 파밍이 뚫립니다. CI 해시를 멱등키로 쓰면 탈퇴로 원 계정 CI가 파기돼도 원장 행에 남아, 같은 사람이 재가입해도 `(type, refType, refId)` 유니크로 1회만 지급됩니다.
- **무상 베리는 30일 만료 로트(grant) 단위로 FIFO 소진**, 유상/무상 혼합 차감 시 분해량을 영속화해 **환불 시 동일 비율로 복원**.

> 처음엔 `existingUser == null`로 신규만 보너스를 주게 짰다가, redesign 플로우에서 쉘 유저가 미리 생성돼 **보너스가 한 번도 안 나가던 버그**를 실운영에서 발견 → 멱등 설계 덕에 "무조건 발행해도 사람당 1회"로 안전하게 고쳤습니다.

### 2. 인앱 결제 검증 — 오프라인 JWS + 플랫폼 격리
[`ChargeReceiptVerifier.java`](./code-excerpts/ChargeReceiptVerifier.java) · [`AppleChargeReceiptVerifier.java`](./code-excerpts/AppleChargeReceiptVerifier.java)

- **결제 검증 디스패처** — `platform`으로 플랫폼별 검증기에 위임. Apple 검증이 Google 변경과 무관하도록 **책임 격리**.
- **Apple StoreKit2 JWS 오프라인 서명검증** — 영수증의 x5c 인증서 체인을 Apple Root CA까지 검증하고, 페이로드의 bundleId/environment를 확인. 애플 서버 왕복 없이 검증하며, JWS의 `environment` 필드로 샌드박스/운영 verifier를 라우팅(TestFlight·심사 결제는 샌드박스).
- 검증 결과의 스토어 거래 ID를 **멱등키**로 넘겨, 원장의 중복 적립을 원천 차단.

### 3. 친구 초대(리퍼럴) — 3중 어뷰징 방지
[`ReferralService.java`](./code-excerpts/ReferralService.java)

보상 한도가 **무제한**인 정책에서, 자기 초대·재가입 파밍을 막기 위해 3중 방어를 설계했습니다.
- 자기 자신 초대 금지 (**userId뿐 아니라 본인인증 CI 동일**까지 차단 → 부계정 우회 봉쇄)
- `referrals` 유니크 2개 (초대 계정 1회 + **사람(CI 해시) 1회**)
- 원장 멱등키를 **피초대자 CI 해시**로 잡음 (초대자는 무제한이라 초대자 CI로 잡으면 2번째부터 막힘 — 이 방향성이 핵심)
- 초대 처리는 **가입 트랜잭션 커밋 후 별도 트랜잭션**에서 → 잘못된 `inviterId`가 회원가입 자체를 깨지 않음

### 4. 광고비 대시보드 — 외부 API 연동 + 일 배치
[`GoogleAdsClient.java`](./code-excerpts/GoogleAdsClient.java)

Google Ads·TikTok 광고비를 매일 당겨와 베리 매출과 합쳐 **ROAS·순이익**을 집계합니다.
- 무거운 `google-ads-java` SDK 대신 **`java.net.http.HttpClient` + REST**로 직접 호출 (의존성 최소화)
- `refresh_token → access_token` 교환 후 GAQL 질의
- 저장 구조를 **처음부터 `날짜 × 플랫폼 × 지표`** 로 잡아 메타 등 확장에 대비, `(날짜,플랫폼)` 유니크로 **멱등 upsert** (지연 반영·정정분을 재조회로 덮어씀)

### 5. 페르소나 궁합 계산 — 매칭/추천 도메인 규칙
[`MbtiCompatibilityUtil.java`](./code-excerpts/MbtiCompatibilityUtil.java)

- 탐색/추천의 핵심인 **페르소나 궁합 점수**를 규칙 테이블로 계산. 서비스의 도메인 지식이 코드로 응결된 부분.

### 6. AI 멀티 프로바이더 추상화
[`ClaudeClient.java`](./code-excerpts/ClaudeClient.java)

- `AiClient` 인터페이스로 Claude/OpenAI를 교체 가능하게 추상화, 관리자에서 기능별 모델 지정.
- **Claude 5 세대 대응**: 신모델이 `temperature`를 폐기(400 에러) → 지원 모델에만 파라미터를 싣도록 분기. 복잡한 프롬프트에서 응답 앞에 `thinking` 블록을 자동 삽입 → `content[0].text`가 비어 파싱이 깨지던 것을, **`type=text`인 첫 블록을 찾도록** 수정.
- JSON 파싱 실패 시 "순수 JSON만 반환" 시스템 메시지로 **1회 자동 재호출**하는 방어층.

### 7. 푸시 알림 7종 카테고리 필터 — 판정을 조회 쿼리에 집중
[`PushService.java`](./code-excerpts/PushService.java)

- 수신 동의를 마스터 + 7종 세부 카테고리로 세분화. 발사 지점 11곳이 각자 토글을 검사하는 대신, **디바이스 토큰 조회 쿼리 하나가 판정** — 지점 하나가 빠져 정책 구멍이 나는 구조를 원천 제거.
- **광고(MARKETING)만 옵트인 + 야간(21~08 KST) 차단** — 정보통신망법 요구를 발송 진입점 코드로 강제.
- 토글에 걸려 **안 나간 발송도 push_log에 기록**(`requested=0`) — 미발송을 로그로 증명 가능하게.

### 8. AI 대화 호감도 채점 — 구조화 출력 대신 마커를 골랐다
[`AffinityScoring.java`](./code-excerpts/AffinityScoring.java)

- LLM 응답에서 본문과 4축 점수를 분리. 구조화 출력(tool use)은 형식을 강제하지만 실측 **+37% 느려** 채팅에는 부적합 → 응답 말미 마커 방식을 운영 채택, 두 경로 모두 구현하고 토글로 전환 가능.
- 판단 기준: **"가끔 점수가 안 뜬다"가 "가끔 답장이 안 온다"보다 낫다** — 채점이 깨져도 본문은 살리는 파서.
- 실측(48턴 중 2턴)에서 깨진 마커가 화면에 노출된 사례를 좁은 폴백 패턴으로 방어. 점수는 EMA(최근 대화 가중)로 누적.

---

## 그 외 구현 범위

- **회원가입 게이트** — 소셜(카카오/네이버/애플) + 이메일 + NICE 본인인증. 이메일 도메인 화이트리스트, 심사(App Store/Play) 계정 bypass.
- **NICE 본인인증 1인 1계정** — 재인증 시 기존 계정 CI를 훔쳐가던 문제를, `users.ci` 유니크 + 살아있는 계정이 보유한 CI면 가입 거부로 차단. → [`NiceIdentityGuard.java`](./code-excerpts/NiceIdentityGuard.java)
- **결제/환불** — Apple JWS 오프라인 검증, Google Play 실검증, 스토어 환불 웹훅/폴링으로 적립 회수(clawback). → [`TokenChargeService.java`](./code-excerpts/TokenChargeService.java)
- **관리자 대시보드** — 유저/매칭/신고/분석(시계열·퍼널)·베리 매출·광고비.
- **탐색/매칭** — AI 페르소나 4축 유사도 기반 추천, 신고 시 양방향 발견 차단. 갱신 버튼 잠금·과금 여부를 **서버 판정으로 이관**(`refreshAvailable`/`charged`). → [`ExploreCandidateFilter.java`](./code-excerpts/ExploreCandidateFilter.java)
- **커뮤니티 피드** — 게시물·2단계 댓글·좋아요·신고. 삭제/숨김은 상태 보존(스레드 유지·감사 추적), 카운터 컬럼 대신 원본 집계. → [`FeedComment.java`](./code-excerpts/FeedComment.java)
- **문의 자동응답** — 접수 즉시 메일 + 푸시 회신, 안내 문구는 단일 소스로 앱 화면·메일 통일.
- **OS 알림 권한 계측** — 권한 상태 3값 수집 + 변경 이력 로그(중복 생략) — 발송 조건이 아닌 측정 전용으로 분리.

---

## 트러블슈팅 (실운영)

문서: [`docs/troubleshooting.md`](./docs/troubleshooting.md)

실서비스를 운영하며 진단·해결한 사례를 정리했습니다. 예시:
- **동시 지급 하나가 조용히 사라지던 문제** — 적립 경로만 락이 빠져 있어 앱 기동 시 두 보상이 충돌, 한쪽이 롤백. nginx 로그 전수 집계(979건 중 409 3건)로 특정하고, `Thread.sleep`으로 충돌 창을 강제로 벌려 수정 전/후를 대조 검증
- **앱이 24시간마다 로그아웃되던 문제** — 미인증을 403으로 주고 있어 앱의 토큰 갱신이 발화하지 못함. 운영 로그로 `refresh` 호출 0건을 입증
- **탈퇴가 12회 연속 실패** — `try/catch`로는 트랜잭션 격리가 안 된다(rollback-only 마크). 대상을 `REQUIRES_NEW`로 분리하되, 락 경합이 나는 구간은 일부러 남김
- **이벤트 보상이 앱을 열지 않아도 지급** — 인터셉터가 요청 하나를 "접속"으로 판정. 푸시가 OS를 깨우며 보낸 백그라운드 요청만으로 11건 지급
- 소셜 가입자가 **설문 완료 직전에 막히던 버그** — 이메일 도메인 화이트리스트가 소셜 경로에 누락 (@kakao.com 등)
- **push-consent 403** 원인 규명 — 백엔드가 아니라 클라이언트 토큰 미첨부(도메인 이전 중 origin-scoped localStorage 유실)
- **Google Ads API 연동 3종 삽질** — API 버전 sunset(v21만 유효), MCC 헤더로 인한 권한 거부, 통화 micros 환산
- **"알림 거부율 67%"는 측정 오류** — Android가 권한 미요청 상태를 거부로 보고. 지표 발표 전에 수집 정의부터 검증, 3값 체계로 전환 + 변경 이력으로 재측정
- **`ddl-auto`는 ENUM 컬럼을 안 늘린다** — 자바 enum에 값을 추가해도 DB 목록은 그대로. 배포 후 첫 INSERT에서 터질 것을 사전 점검에서 차단

---

## 문서

- [`docs/architecture.md`](./docs/architecture.md) — 시스템/도메인 구조
- [`docs/engineering-decisions.md`](./docs/engineering-decisions.md) — **왜 그렇게 했나** (JDK 21·HTTP·트랜잭션·코딩 정책)
- [`docs/data-model.md`](./docs/data-model.md) — 데이터 모델(ERD)
- [`docs/flows.md`](./docs/flows.md) — 주요 플로우(결제·가입 시퀀스)
- [`docs/berry-ledger.md`](./docs/berry-ledger.md) — 베리 원장 설계 상세
- [`docs/ad-spend.md`](./docs/ad-spend.md) — 광고비 연동 설계
- [`docs/troubleshooting.md`](./docs/troubleshooting.md) — 실운영 트러블슈팅
