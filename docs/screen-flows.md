# 주요 화면 플로우

## 1. 가입/온보딩 — 서버가 "다음 화면"을 안다

가입은 여러 단계다: 로그인/소셜 → 본인인증(NICE) → 약관·알림 동의 → 페르소나 설문 → 리포트 → 홈.
이걸 화면 스택으로 관리하면, 앱을 껐다 켜거나 다른 기기에서 재로그인했을 때 "어디까지 했는지"가 유실된다.

그래서 **다음 화면을 로컬이 아니라 서버 응답으로 결정**한다.

```
로그인/앱 진입
   │
   ├─ GET /users/me/signup-status  ← 서버가 진행 상태를 안다
   │
   ▼  getSignupStepRoute(status)  (순수 함수)
   isVerified=false            → /nice-auth
   agreementCompleted=false    → /push-consent   (단, 로컬 pending 동의 있으면 지난 걸로 간주)
   surveyDraft 있음            → 저장된 위치로 복원(/onboarding | /persona-survey)
   personaStatus=COMPLETE      → /home
```

- **라우팅 결정을 순수 함수(`getSignupStepRoute`)로 분리** → 테스트·추론이 쉽고, 진입 지점(로그인/딥링크/콜드스타트)이 뭐든 같은 규칙으로 흐른다.
- 설문은 페이지 단위로 **surveyDraft를 서버에 임시저장** → 중간에 나가도 그 페이지부터 복원.

관련 코드: [`../code-excerpts/signupFlow.ts`](../code-excerpts/signupFlow.ts)

### 계정 생성 전 상태 보존
본인인증·동의 화면은 아직 계정이 없어 `/users/me/*`를 못 쓴다. 동의/초대자를 로컬(`pending_*`)에 들고 있다가 가입 body에 실어 보내고, 가입 후 재확정한다(→ [`state-management.md`](./state-management.md)).

## 2. 친구 초대(리퍼럴) — 딥링크 → 귀속 → 보상

```
초대자 A: 마이페이지 '친구 초대하기'
   → generateReferralLink(A.id)  =  <ONELINK>?inviter=A.id  공유
        │
친구 B가 링크 클릭
   ├─ 앱 설치됨   → mildo:// 스킴으로 앱 열림  → expo-linking이 inviter 파싱     ┐
   └─ 앱 없음     → 스토어 설치 → 첫 실행 → AppsFlyer(deferred)가 inviter 전달   ┘
        │ (둘 다 pending_inviter 에 저장)
        ▼
   B 가입: signup body에 inviterId 실어 전송
        ▼
   백엔드: A↔B 귀속 + 양쪽 보상. 잘못된 inviterId는 조용히 무시(가입은 성공)
```

- **이중 캡처 경로**: AppsFlyer UDL(신규 설치 귀속) + expo-linking 스킴 파싱(직접 진입 백업). 어느 경로로 들어와도 초대자가 유실되지 않는다. → [`../code-excerpts/analytics.ts`](../code-excerpts/analytics.ts)
- `inviterId`는 **best-effort**로 취급. 백엔드가 유효성(자기초대·중복 등)을 판단하고 잘못돼도 가입을 실패시키지 않으므로, 프론트는 검증 없이 실어 보낸다. → 프론트/백 책임 경계를 단순화.

## 3. 인앱 재화(베리) 사용 — 낙관적 확인 + 서버 정산

베리 차감 액션(매칭 신청, 리포트 생성 등)은 **차감 모달로 잔액·비용을 먼저 확인**시키고, 부족하면 충전 유도 모달로 분기한다. 실제 잔액/차감은 서버가 원장(ledger)으로 정산하며, 화면은 서버가 돌려준 잔액을 신뢰한다.

- 표시 금액은 포맷터 한 곳에서 규칙을 갖는다: 광고비처럼 소수점이 오는 값은 **표시만 반올림**, 합산은 서버 정밀값을 신뢰(→ 관리자 대시보드도 동일 규칙).

## 4. 웹 랜딩 — OS 분기 다운로드

랜딩(데스크탑/태블릿/모바일 3종)의 다운로드 버튼은 User-Agent로 OS를 판별해 iOS→App Store / Android·기타→Play Store로 분기한다. 웹 공유(초대 링크 복사)는 보안 컨텍스트가 아닐 때 `navigator.clipboard` 대신 레거시 `execCommand` 폴백으로 http/LAN에서도 동작하게 했다.
