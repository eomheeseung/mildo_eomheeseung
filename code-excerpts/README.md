# 코드 발췌

실서비스에서 제가 설계·구현을 주도한 대표 코드입니다. 민감 정보(키·DB·개인정보·스토어 식별자)는 포함돼 있지 않습니다.
전체 빌드용이 아니라 **설계 판단을 읽기 위한 발췌**입니다.

## 재화(베리) — 원장·결제·동시성

| 파일 | 무엇을 보나 |
|---|---|
| `TokenLedgerService.java` | 인앱 재화 원장 — 유상/무상 적립, FIFO 만료 로트, 혼합 차감, 멱등 |
| `TokenAccount.java` | **`@Version` 낙관적 락** — 동시 차감 이중차감 방지 |
| `ChargeReceiptVerifier.java` | 결제 검증 **디스패처** — platform으로 플랫폼별 검증기에 위임(격리) |
| `AppleChargeReceiptVerifier.java` | Apple **StoreKit2 JWS 오프라인 서명검증** (x5c 체인 → Apple Root CA, 샌드박스/운영 라우팅) |
| `TokenChargeService.java` | 충전 검증→적립 오케스트레이션 + **환불 회수(clawback)** — 이미 소진 시 잔액이 음수(빚)가 될 수 있음 |
| `TokenRewardEventListener.java` | 가입 커밋 후 별도 트랜잭션 보상 + **CI 해시 멱등키**(재가입 파밍 차단) |
| `CiHasher.java` | 사람 단위 멱등키를 위한 CI 해시 (개인정보 미저장) |

## 도메인 로직

| 파일 | 무엇을 보나 |
|---|---|
| `NiceIdentityGuard.java` | NICE 본인인증 **1인 1계정** 가드 (CI 유니크 + 살아있는 계정 중복 거부 / 탈퇴 계정 승계) |
| `ReferralService.java` | 친구 초대 — 3중 어뷰징 방지, 가입과 분리된 처리 |
| `ExploreCandidateFilter.java` | 탐색 후보 필터 — 스킵/신고(양방향 차단)/기매칭/요청중 제외 |
| `MbtiCompatibilityUtil.java` | 페르소나/MBTI 궁합 점수 계산 — 매칭·추천의 도메인 규칙 |
| `FeedComment.java` | 커뮤니티 댓글 엔티티 — 2단계 고정, **상태 보존(soft delete)**, 카운터 컬럼 대신 원본 집계 |

## 알림 · AI 채점

| 파일 | 무엇을 보나 |
|---|---|
| `PushService.java` | 푸시 카테고리 필터 — 판정을 발사 지점이 아니라 **토큰 조회 쿼리**에 집중, 야간 광고 차단(법령), 미발송도 기록 |
| `AffinityScoring.java` | LLM 대화 채점 — 구조화 출력/마커 이중 경로, 깨진 마커 방어(실측 기반), EMA 누적 |

## 외부 연동

| 파일 | 무엇을 보나 |
|---|---|
| `GoogleAdsClient.java` | 광고 API 연동 (OAuth 토큰 교환 + GAQL, SDK 없이 REST) |
| `ClaudeClient.java` | AI 멀티 프로바이더 — Claude 5 세대 대응(temperature/thinking), JSON 파싱 방어 |

---

설계 배경은 [`../docs`](../docs)의 문서에 정리돼 있습니다.

### 읽는 순서 추천
1. `TokenLedgerService` + `TokenAccount` — 원장 패턴과 동시성
2. `AppleChargeReceiptVerifier` — 돈을 다루는 검증 로직(오프라인 JWS)
3. `TokenRewardEventListener` + `CiHasher` — "사람 단위 멱등"이라는 판단
4. `ReferralService` — 위 멱등 개념을 어뷰징 방지에 응용
