# 데이터 모델 (ERD)

핵심 도메인의 데이터 설계입니다. (전체 스키마 중 대표 테이블만 발췌 — 컬럼도 핵심만.)

## 재화(베리) — 원장 중심

베리 시스템의 핵심은 **원장(`token_transaction`)** 입니다. 잔액(`token_account`)은 파생 캐시이고,
무상 베리는 만료 로트(`token_grant`) 단위로 관리합니다.

```mermaid
erDiagram
    users ||--o| token_account : "1:1 잔액"
    users ||--o{ token_grant : "무상 지급 로트"
    users ||--o{ token_transaction : "거래 원장"

    users {
        bigint id PK
        varchar email UK
        varchar nickname
        varchar ci UK "본인인증 고유키(암호화/유니크)"
        varchar status "ACTIVE/WITHDRAWN..."
        char use_yn
        boolean is_test
    }
    token_account {
        bigint id PK
        bigint user_id FK
        int paid_balance "유상 잔액"
        int free_balance "무상 잔액"
        bigint version "낙관적 락"
    }
    token_grant {
        bigint id PK
        bigint user_id FK
        varchar source "BONUS/REFERRAL/PHOTO..."
        int amount
        int remaining "FIFO 소진 잔량"
        datetime expire_at "지급+30일"
    }
    token_transaction {
        bigint id PK
        bigint user_id FK
        varchar type "EARN_*/SPEND/REFUND/CLAWBACK..."
        int amount "증감(+/-)"
        int paid_amount "혼합차감 분해량"
        int free_amount
        varchar ref_type
        varchar ref_id "멱등키 구성요소"
        varchar idempotency_key UK
    }
```

**멱등 유니크 제약**
- `token_transaction.idempotency_key` UNIQUE — 결제 재시도 dedup
- `(type, ref_type, ref_id)` UNIQUE — 1회성 적립/차감 중복 차단 (예: 가입 보너스 = `EARN_BONUS + SIGNUP + SHA256(CI)`)

## 회원·페르소나·매칭

```mermaid
erDiagram
    users ||--o| personas : "AI 페르소나"
    users ||--o{ my_matchings : "매칭 신청(요청/수신)"
    users ||--o{ referrals : "초대(초대자/피초대자)"
    users ||--o{ complaints : "신고(신고자/피신고자)"

    personas {
        bigint id PK
        bigint user_id FK
        json personality "4축 성향"
        json cached_report "리포트 캐시"
        decimal quality_score
    }
    my_matchings {
        bigint id PK
        bigint requester_id FK
        bigint receiver_id FK
        varchar status "REQUESTED/ACCEPTED/REJECTED"
    }
    referrals {
        bigint id PK
        bigint inviter_id FK "초대한 회원"
        bigint invitee_id FK,UK "가입한 회원(1회 고정)"
        varchar invitee_ci_hash UK "사람 1회(재가입 파밍 차단)"
        boolean rewarded
    }
```

## 광고비 집계

```mermaid
erDiagram
    ad_spend_daily {
        bigint id PK
        date stat_date
        varchar platform "GOOGLE/TIKTOK/META"
        decimal spend
        varchar currency
        bigint impressions
        bigint clicks
        bigint conversions
    }
```

`(stat_date, platform)` UNIQUE — 일 배치가 최근 N일을 재조회해 **멱등 upsert**(지연 반영·정정분 덮어쓰기).

## 설계 포인트

- **`users.ci` 유니크** — NICE 본인인증 고유키로 1인 1계정 강제.
- **베리 잔액은 원장의 파생값** — 정합성 문제 시 원장 재생으로 복구 가능.
- **1회성 보상의 멱등키에 `사람(CI 해시)`** 포함 — 탈퇴/재가입 파밍을 데이터 레벨(유니크 제약)에서 차단.
- **모든 다대다·자기참조 관계**(매칭·초대·신고)는 두 유저 컬럼(요청/수신 등)으로 표현.
