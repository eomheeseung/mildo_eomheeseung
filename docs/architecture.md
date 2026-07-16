# 아키텍처 & 도메인 구조

## 개요

mildo 백엔드는 **도메인 단위 패키지**로 구성된 Spring Boot 3 / Java 21 모놀리식 애플리케이션입니다.
단일 서버 배포지만, 외부 연동(AI·결제·본인인증·광고)을 각 도메인의 `client`/`service`로 캡슐화해 교체·확장이 쉽도록 설계했습니다.

## 도메인 맵

| 도메인 | 책임 |
|---|---|
| `auth` | 소셜/이메일 회원가입, JWT 발급, 로그인 게이트(도메인 화이트리스트·심사 bypass) |
| `nice` | NICE 표준창 본인인증 (CI/DI), 1인 1계정 강제 |
| `agreement` | 약관 동의 (버전 관리, best-effort 저장) |
| `persona` | AI 페르소나 생성·재분석·리포트 |
| `explore` / `match` | 페르소나 유사도 기반 탐색·매칭, 신고 시 양방향 차단 |
| `token` | **베리 원장**(적립/차감/만료/환불), 결제 검증(Apple/Google), 환불 회수 |
| `referral` | 친구 초대 보상 + 어뷰징 방지 + 관리자 추적 |
| `adspend` | Google/TikTok 광고비 일 배치 수집 → ROAS·순이익 집계 |
| `notification` | FCM / Web Push / SSE |
| `ad` | AppsFlyer S2S 전환 이벤트 |
| `admin` | 관리자 대시보드 (유저·매칭·신고·분석·매출·광고비) |
| `ai` | Claude/OpenAI 멀티 프로바이더 추상화 |

## 설계 원칙

### 1. 외부 연동은 도메인 안에 캡슐화
AI·결제·광고 API 호출을 각 도메인의 `client`로 감싸고, 서비스 계층은 인터페이스에만 의존합니다.
예) `ai/client/AiClient` ← `ClaudeClient` / `OpenAiClient` 를 런타임에 선택.

### 2. 재화(베리)는 additive 도메인
구독제와 독립된 **추가(additive)** 재화 시스템으로 설계해, 기존 구독 로직을 건드리지 않고 얹었습니다.
원장(ledger) 패턴으로 모든 증감을 1행으로 기록 → 잔액은 거래의 합.

### 3. 부가 작업은 가입 트랜잭션과 분리
가입 보너스·초대 보상·전환 이벤트는 **가입 커밋 후(AFTER_COMMIT) 별도 트랜잭션(REQUIRES_NEW)** 에서 처리.
→ 보상/이벤트 처리가 실패해도 **회원가입 자체는 절대 깨지지 않음.**

### 4. 멱등성 우선
재시도·중복 호출·재가입 파밍에 대비해 1회성 작업은 멱등키로 방어.
- 결제: 클라이언트 idempotencyKey
- 1회성 보상: `(type, refType, 사람 CI 해시)` 유니크

## 배포

- 워킹트리 빌드 → 단일 jar → 서버 교체.
- 민감 설정(`application-credentials.yaml`, `application-prod.yaml`)은 git 추적 제외, jar에 내장.
- 기능 토글은 운영 yaml에서 on/off (예: 광고비 수집, 환불 폴링).
