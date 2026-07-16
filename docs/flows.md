# 주요 플로우 (시퀀스)

핵심 흐름 두 가지를 시퀀스로 정리했습니다. 설계에서 중요한 **트랜잭션 경계**와 **멱등 지점**에 주석을 달았습니다.

## 1. 인앱 결제 → 베리 적립

애플/구글 영수증을 **서버가 직접 검증**하고, 검증된 스토어 거래 ID를 멱등키로 원장에 적립합니다.

```mermaid
sequenceDiagram
    participant App as 모바일 앱
    participant API as 충전 API
    participant V as ChargeReceiptVerifier
    participant AV as AppleChargeReceiptVerifier
    participant L as TokenLedgerService
    participant DB as DB(원장)

    App->>API: POST 충전 (platform, receipt, packageId)
    API->>V: verify(platform, ...)
    V->>AV: 플랫폼별 검증기로 위임
    Note over AV: StoreKit2 JWS 오프라인 서명검증<br/>x5c 체인 → Apple Root CA<br/>bundleId/environment 확인
    AV-->>V: 스토어 거래ID(멱등키)
    V-->>API: VerifiedReceipt
    API->>L: creditPaid(userId, amount, idempotencyKey=거래ID)
    Note over L,DB: idempotency_key UNIQUE<br/>중복 영수증이면 기존 거래 반환(재적립 X)
    L->>DB: token_account 잔액↑ + token_transaction 기록
    L-->>API: 적립 결과
    API-->>App: 잔액 응답
```

**핵심**
- 검증 실패/미지원 플랫폼은 적립 전에 차단.
- 같은 영수증이 재전송돼도 `idempotency_key` 유니크로 **이중 적립 불가**.
- 애플 서버 왕복 없이 서명만으로 검증 → 외부 지연/장애에 덜 의존.

## 2. 회원가입 → 보너스·초대 보상 (트랜잭션 분리)

가입 트랜잭션이 **커밋된 뒤** 보너스·초대·전환 이벤트를 별도 트랜잭션에서 처리합니다.
→ 보상 로직이 실패해도 **회원가입 자체는 절대 롤백되지 않습니다.**

```mermaid
sequenceDiagram
    participant App as 앱
    participant Auth as AuthService
    participant DB as DB
    participant EV as EventListener
    participant L as TokenLedgerService

    App->>Auth: 회원가입 (소셜/이메일 + NICE + inviterId?)
    Note over Auth: CI 중복 검사(1인 1계정)<br/>약관 저장(best-effort)
    Auth->>DB: 유저 저장 (트랜잭션 T1)
    Auth->>EV: publish(UserSignedUp / UserReferred)
    Note over Auth,DB: T1 커밋
    Auth-->>App: 가입 완료 + JWT (즉시 응답)

    Note over EV: AFTER_COMMIT + REQUIRES_NEW (T2)
    EV->>L: 가입 보너스 creditFree(refId=SHA256(CI))
    EV->>L: 초대 보상 creditFree(양쪽, refId=피초대자 CI해시)
    Note over L: (type, refType, CI해시) UNIQUE<br/>→ 재가입해도 사람당 1회
    L->>DB: 적립 (실패해도 가입 T1은 이미 커밋됨)
```

**핵심**
- 가입은 사용자 경험의 최우선 → 부가 로직(보상·이벤트)이 볼모로 잡지 않음.
- 잘못된 `inviterId`(없는 유저·자기 자신·탈퇴)는 예외 없이 **초대만 스킵**.
- 보상 멱등키가 `사람(CI 해시)`이라, 탈퇴 후 재가입해도 재지급 안 됨.
