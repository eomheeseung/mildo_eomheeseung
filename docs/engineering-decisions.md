# 엔지니어링 결정 (왜 그렇게 했나)

"무엇을 만들었나"보다 **"왜 그 선택을 했나"**를 정리했습니다. 실제 개발·운영하며 내린 판단입니다.

## 1. JDK 17 → 21 마이그레이션 — 가상 스레드

**배경.** mildo의 요청은 **외부 I/O 대기가 지배적**입니다. 한 요청이 AI(Claude/OpenAI) 응답, 결제 영수증 검증, NICE 본인인증, OAuth 토큰 교환 등 외부 호출을 기다리는 시간이 CPU 연산보다 훨씬 깁니다.

**결정.** 플랫폼 스레드(요청당 OS 스레드) 모델에서는 이런 I/O 대기가 스레드를 점유해, 동시성을 높이려면 스레드 풀을 키우고 튜닝해야 합니다. **JDK 21의 가상 스레드(Loom)** 로 올려, 블로킹 코드를 **그대로 유지하면서** 대기 중 캐리어 스레드를 반납하도록 했습니다.

```yaml
spring:
  threads:
    virtual:
      enabled: true   # Tomcat 워커 + @Async 태스크에 자동 적용
```

- **리액티브(WebFlux)를 안 쓴 이유** — 명령형 코드의 가독성·디버깅 편의를 포기하지 않으면서 동시성 이득만 취하려고. 팀(1인) 유지보수 관점에서 리액티브의 학습·디버깅 비용이 과함.
- **주의점** — 가상 스레드는 I/O에는 강하지만 `synchronized` 블록에서 캐리어가 pin될 수 있어, 커넥션 풀 등은 `ReentrantLock` 기반인지 확인. HikariCP 풀 크기도 "동시 요청 = 가상 스레드"가 아니라 **DB 커넥션 상한**에 맞춰 별도로 명시.

## 2. HTTP 클라이언트 — 상황에 맞춰 3가지

외부 연동 성격에 따라 클라이언트를 나눠 썼습니다. "하나로 통일"이 항상 옳은 게 아니라, **각 연동의 요구가 달라서** 내린 선택입니다.

| 클라이언트 | 쓴 곳 | 이유 |
|---|---|---|
| **HTTP Interface** (`@HttpExchange`) | AI(Anthropic Messages API) | 인터페이스 선언만으로 호출 — 베이스URL·인증헤더·타임아웃을 설정에 모으고, 호출부는 메서드 하나. 가장 자주·중요하게 쓰는 연동이라 **선언적**으로. |
| **`java.net.http.HttpClient`** | Google Ads, TikTok, Google Play 검증 | 읽기 위주 단발 호출 + **무거운 벤더 SDK를 피하려고**. 의존성 0으로 REST 직접 호출. |
| **RestTemplate** | 카카오·네이버·애플 OAuth, NICE, PortOne | 단순 폼/JSON 교환. 기존 안정 동작 코드라 굳이 교체 안 함(불필요한 리스크 회피). |

> **HTTP Interface 예시** — 선언 한 줄로 끝:
> ```java
> public interface AnthropicApi {
>     @PostExchange("/v1/messages")
>     JsonNode createMessage(@RequestBody Map<String, Object> body);
> }
> ```
> 베이스URL/헤더/타임아웃은 `AnthropicConfig`에서 프록시 팩토리로 주입.

## 3. 트랜잭션 관리 전략

### 부가 작업은 커밋 후 별도 트랜잭션 (`AFTER_COMMIT` + `REQUIRES_NEW`)

회원가입에서 **핵심(유저 저장)과 부가(보너스 적립·초대 보상·광고 전환 이벤트)를 분리**했습니다.

```
가입 트랜잭션 T1 (유저 저장) ── 커밋
      └─ AFTER_COMMIT 이벤트 → REQUIRES_NEW T2 (보상 적립)
```

- **왜** — 보상 적립이 실패해도 **회원가입은 이미 커밋**되어야 함. 부가 로직이 핵심 UX를 볼모로 잡으면 안 됨.
- **주의로 배운 것** — 초기엔 약관 저장 서비스에 `@Transactional`이 붙어 있어, 호출부가 `try-catch`로 삼켜도 프록시가 rollback-only를 표시 → 커밋 시 `UnexpectedRollbackException`으로 가입이 깨졌습니다. **best-effort로 삼켜야 하는 메서드에는 트랜잭션을 붙이지 않는다**는 규칙을 세움.

### 읽기 전용은 `@Transactional(readOnly = true)`
집계·조회 서비스는 명시해 flush/dirty-checking 비용을 줄이고 의도를 드러냄.

### 동시성은 낙관적 락
베리 잔액(`token_account`)에 `@Version`을 둬 **동시 차감 이중차감**을 방지. 비관적 락으로 테이블을 잠그는 대신, 충돌 시 재시도가 값싼 낙관적 락을 선택(충돌 빈도가 낮은 유저별 잔액 특성).

## 4. 멱등성을 데이터 레벨에서 강제

재시도·중복 웹훅·재가입 파밍은 애플리케이션 로직으로 막으면 경쟁 조건이 남습니다. 그래서 **DB 유니크 제약**으로 못 박았습니다.
- 결제: `idempotency_key` UNIQUE (스토어 거래 ID)
- 1회성 보상: `(type, ref_type, ref_id=사람 CI 해시)` UNIQUE
- 초대: `invitee_id` + `invitee_ci_hash` UNIQUE

## 5. 기능 토글 & 설정 분리

- **민감 설정**(키·DB·시크릿)은 `application-credentials.yaml`·`application-prod.yaml`로 분리, **git 추적 제외 + 배포 jar에 내장.**
- **위험한 신기능**(구글 IAP 실검증, 환불 폴링, 광고비 수집)은 **토글**로 감싸 운영에서 단계적으로 켬. 로컬 기본 off, 운영 yaml에서 on.
- 환경별 프로필(local/prod)로 로그 레벨·검증 강도 분리 (로컬 DEBUG, 운영 조용).

---

## 코딩 정책 (팀 컨벤션)

실제로 지킨 프로젝트 규칙입니다.

### API 계약 우선
- **요청/응답 구조를 임의로 바꾸지 않는다.** 프론트가 참조하는 계약이므로, 필드 추가/삭제/이름변경은 합의 후. AI 프롬프트를 바꿔도 **출력 JSON 구조는 유지**(파싱 호환성).

### Jackson 직렬화 규칙
- `boolean isNewUser` → 직렬화 시 `newUser`(is 제거), `Boolean isNewUser` → `isNewUser`(유지).
- 명세와 실제 응답을 일치시키려 **`@JsonProperty`를 명시**하고, 문서 작성 전 실제 JSON 응답을 확인.

### 커밋 단위
- **한 도메인은 전 계층(엔티티~컨트롤러) 완료 후 한 커밋.** 횡단 리팩토링은 별도 커밋.
- 빌드/배포/커밋은 명시적으로만 — 자동 실행 안 함.

### 로깅
- 공통은 조용(INFO), 개발 프로필만 DEBUG. PII(전화번호 평문 등)는 로그에 남기지 않음.

### 예외/에러
- 도메인 에러는 `ErrorCode` enum(HTTP status + 코드 + 메시지)으로 표준화, 프론트가 코드로 분기.
