# 베리(인앱 재화) 원장 설계

mildo의 유료 기능(매칭 신청·AI 채팅·통합 리포트 등)은 **베리**라는 인앱 재화로 차감합니다.
구독제와 독립된 additive 도메인으로, **원장(ledger) 패턴**으로 구현했습니다.

관련 코드: [`TokenLedgerService.java`](../code-excerpts/TokenLedgerService.java) · [`TokenRewardEventListener.java`](../code-excerpts/TokenRewardEventListener.java) · [`CiHasher.java`](../code-excerpts/CiHasher.java)

## 데이터 모델

| 테이블 | 역할 |
|---|---|
| `token_account` | 유저별 잔액 (paid_balance / free_balance) |
| `token_grant` | 무상 베리 **지급 로트** — 30일 만료, FIFO 소진 단위 |
| `token_transaction` | **모든 증감을 1행씩** 기록하는 원장 (적립·차감·환불·회수·만료) |

잔액은 별도로 신뢰하지 않고, 거래 원장이 진실의 원천(source of truth)입니다.

## 핵심 설계 판단

### 1. 무상 베리는 "만료 로트" 단위 FIFO

무상 베리는 30일 후 만료됩니다. 단순히 잔액 숫자만 두면 "어느 베리가 언제 만료되는지" 알 수 없습니다.
그래서 지급마다 `token_grant`(로트)를 만들고, **차감 시 만료 임박 로트부터 FIFO로 소진**합니다.

### 2. 혼합 차감 → 환불 복원

한 번의 차감에 유상·무상이 섞일 수 있습니다(무료분 우선/유료분 우선 정책).
차감 시점의 `paidAmount`/`freeAmount` **분해량을 원장에 영속화**해, 환불·회수 시 **동일 비율로 정확히 복원**합니다.

### 3. 1회성 보상의 멱등키 = 사람(CI 해시), userId 아님

**이 설계가 이 도메인의 핵심입니다.**

가입 보너스처럼 "사람당 1회"인 보상을 `userId` 기준으로 멱등 처리하면:
```
가입(userId=100) → 보너스 지급
탈퇴 → 재가입(userId=200) → 새 userId라 보너스 또 지급  ← 파밍!
```

그래서 멱등키를 **본인인증 CI의 SHA-256 해시**로 잡았습니다.
```
멱등키 = (type=EARN_BONUS, refType=SIGNUP, refId=SHA256(CI))
        + token_transaction 의 (type, refType, refId) UNIQUE
```
탈퇴로 원 계정의 CI가 파기돼도 **원장 행에는 해시가 남아**, 같은 사람이 재가입해도 유니크 제약에 걸려 1회만 지급됩니다.

> CI 원문(개인정보)을 그대로 저장하지 않기 위해 해시로 씁니다. 해시 방식을 바꾸면 기존 멱등키와 어긋나 과거 수령자에게 재지급되므로 **변경 금지**로 못 박았습니다.

### 4. 보상은 가입과 분리된 트랜잭션에서

보너스 적립은 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 로 처리합니다.
→ 적립이 실패해도 **회원가입은 이미 커밋**되어 안전. 사용자 경험을 재화 로직이 볼모로 잡지 않습니다.

## 실운영에서 잡은 버그

초기엔 `existingUser == null`(신규 가입)일 때만 보너스를 발행했습니다.
그런데 페르소나 redesign 플로우에서 **쉘(shell) 유저가 본인인증 시점에 미리 생성**되면서, 정작 가입 완료 시엔 항상 `existingUser != null` → **보너스가 한 번도 안 나가던 버그**가 실운영에서 발견됐습니다.

멱등 설계 덕분에 수정은 간단했습니다 — 조건을 없애고 **무조건 발행**해도, `(type, refType, CI해시)` 유니크가 "사람당 1회"를 보장하므로 재지급 걱정이 없었습니다. 이미 놓친 기존 완주자에게는 동일 멱등키로 **소급 지급 SQL**을 실행해 백필했습니다.
