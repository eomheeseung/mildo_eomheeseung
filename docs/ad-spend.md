# 광고비 대시보드 — 외부 API 연동 + 일 배치

Google Ads·TikTok에 집행한 광고비를 매일 자동 수집해, 인앱 재화(베리) 매출과 합쳐 **ROAS·순이익**을 관리자에서 보여주는 기능입니다.

관련 코드: [`GoogleAdsClient.java`](../code-excerpts/GoogleAdsClient.java)

## 구성

```
매일 05:40 배치
   │
   ├── GoogleAdsClient  ─ refresh_token→access_token → GAQL(SearchStream)
   └── TikTokAdsClient  ─ Reporting API (30일 청크 분할)
   │
   ▼
ad_spend_daily  (날짜 × 플랫폼 × 지표)   ← (날짜,플랫폼) UNIQUE, 멱등 upsert
   │
   ▼
관리자 집계 API  ─ 광고비 + 베리매출(충전−환불) → ROAS, 순이익
```

## 설계 판단

### 1. SDK 대신 REST 직접 호출
`google-ads-java`는 gRPC 기반의 무거운 의존성입니다. 우리가 필요한 건 **읽기 전용 리포팅 하나**뿐이라, `java.net.http.HttpClient`로 REST를 직접 호출해 의존성을 0으로 유지했습니다. (기존 코드베이스의 Google Play 연동도 같은 패턴을 써 일관성 확보.)

### 2. 저장 구조를 처음부터 플랫폼별로
당장은 구글·틱톡뿐이지만, 메타 확장을 대비해 `날짜 × 플랫폼 × 지표` 스키마로 시작했습니다. 플랫폼 추가 = 클라이언트 하나 + enum 값 하나.

### 3. 멱등 upsert + lookback 재조회
광고 플랫폼은 당일·전일 수치를 **뒤늦게 정정**합니다(전환 지연 반영 등). 하루치만 긁으면 어긋나므로, 매 실행 **최근 N일을 재조회**해 `(날짜,플랫폼)` 유니크 기준으로 덮어씁니다. 재조회가 안전한 이유는 upsert가 멱등이기 때문입니다.

### 4. 플랫폼별 트랜잭션 분리
한 플랫폼 조회/저장이 실패해도 다른 플랫폼은 커밋되도록, 플랫폼 단위로 트랜잭션을 나눴습니다. 외부 API 실패가 전체 배치를 무너뜨리지 않습니다.

## 연동하며 겪은 삽질 (기록)

실제 외부 API를 붙이며 부딪힌 것들 — 문서에 없던 것들입니다.

1. **Google Ads API 버전 sunset** — REST 경로의 버전(`v18`)이 폐기돼 404. 인증된 호출로 probe해보니 `v17`~`v20`은 전부 sunset, **`v21`만 유효**. 버전을 설정값으로 빼서 코드 수정 없이 교체 가능하게 함.
2. **MCC 헤더로 권한 거부** — `login-customer-id`(MCC)를 항상 붙였더니 `USER_PERMISSION_DENIED`. `listAccessibleCustomers`로 확인하니 인증 계정이 대상 광고 계정에 **직접 접근 권한**이 있어 MCC 경유가 불필요 → 헤더를 빼자 정상. (MCC 경유가 필요한 경우에만 붙이도록 조건화.)
3. **통화 단위** — Google은 `cost_micros`(계정 통화의 100만분의 1), TikTok은 정수 문자열. 저장 시 통화 코드를 함께 보관해 혼용을 방지.
4. **TikTok 30일 제한** — `stat_time_day` 리포트는 1회 최대 30일. 긴 기간 초기 적재는 30일 청크로 분할 호출.

## ROAS 해석 주의 (문서에 명시)

이 ROAS는 "그 기간 광고비 vs 그 기간 **전체** 베리매출"입니다. 광고를 보고 결제한 사람만 집계한 **어트리뷰션 기반이 아닙니다.** 진짜 광고 기여 매출은 AppsFlyer 어트리뷰션이 별도로 필요하며, 이 지표는 관리자용 러프 지표임을 문서에 못 박았습니다.
