# 아키텍처 & 구조

## 개요

mildo 프론트엔드는 **React Native + Expo (Expo Router)** 기반으로, **하나의 코드베이스가 iOS·Android 앱과 웹(랜딩 + 관리자 대시보드)을 동시에** 빌드하는 구조입니다.
0→1로 화면·상태·API 연동·빌드/배포 파이프라인까지 직접 설계하고 실서비스로 운영했습니다.

## 런타임 3면

```
                 하나의 소스(app/ · src/)
                          │
        ┌─────────────────┼──────────────────┐
        ▼                 ▼                  ▼
   iOS / Android        웹 랜딩            관리자 대시보드(웹)
   (네이티브 앱)        (바이럴/체험)        (/admin, 웹 전용)
        │                 │                  │
        └── 공통 API 레이어(axios) ── 인터셉터 · 통일 envelope ──┐
                                                              ▼
                                            base URL = 런타임 해석(env.ts)
                                         웹: 브라우징 도메인 추종 / 네이티브: 빌드 값
```

- **파일 기반 라우팅(Expo Router)** — `app/` 디렉토리 구조가 곧 라우트. `app/(route).tsx`는 얇은 진입점이고 실제 화면은 `src/screens`에 둔다.
- **플랫폼 분기** — 웹/네이티브 동작이 갈리는 지점은 `Platform.OS` 분기 또는 `*.web.tsx` / `*.native.tsx` 파일 분리로 처리. 네이티브 전용 모듈(AppsFlyer 등)은 동적 `require`로 감싸 **웹 번들이 깨지지 않게** 한다.

## 레이어

| 레이어 | 역할 | 대표 |
|---|---|---|
| `app/` | Expo Router 진입점(라우트 = 파일) | `app/home.tsx`, `app/referral.tsx` |
| `src/screens` | 화면 컴포넌트 | `HomeScreen`, `ReferralScreen`, `AdminScreen` |
| `src/context` | 전역 상태(인증/테마) — React Context | `AuthContext`, `ThemeContext` |
| `src/services` | 사이드이펙트 캡슐화 | `analytics`(어트리뷰션/딥링크), push, chat(SSE) |
| `src/api` | 서버 통신 단일 창구 | `client.ts`(인터셉터·envelope) |
| `src/utils` | 순수 로직·플로우 | `signupFlow`(라우팅 결정), 포맷터 |
| `src/config` | 환경 해석 | `env.ts`(base URL·오배포 감지) |
| `scripts/` | 빌드 안전장치 | `validate-release-env.cjs` |

## 설계 원칙

### 1. 서버 통신은 한 곳(`api/client.ts`)으로
모든 요청이 인터셉터(토큰 첨부·401 자동 리프레시)를 지나고, 응답은 `{ success, data, message }` 한 형태로 정규화된다. 화면은 엔드포인트나 토큰 만료를 몰라도 된다. → [`api-and-build.md`](./api-and-build.md)

### 2. "다음 화면"은 서버가 안다
가입/온보딩 단계 이동을 로컬 화면 스택이 아니라 **서버 signup-status 응답**으로 결정. 앱 재시작·재로그인·기기 변경에도 같은 자리로 복원. → [`screen-flows.md`](./screen-flows.md)

### 3. 환경은 런타임에 해석, 오배포는 빌드가 막는다
base URL을 하드코딩하지 않고 웹은 브라우징 도메인, 네이티브는 빌드 값으로 해석한다. 실수로 로컬 서버 주소가 프로덕션에 나가는 것은 **빌드 타임 가드**가 차단한다. → [`api-and-build.md`](./api-and-build.md)

### 4. 네이티브 의존성은 격리
결제·어트리뷰션 같은 네이티브 모듈은 동적 require로 감싸 웹에서 no-op, 실패해도 앱 전체가 죽지 않게 방어한다.

## 문서

- [`state-management.md`](./state-management.md) — 상태관리 전략
- [`screen-flows.md`](./screen-flows.md) — 주요 화면 플로우
- [`api-and-build.md`](./api-and-build.md) — API 연동 · 환경 해석 · 빌드/배포
- [`troubleshooting.md`](./troubleshooting.md) — 실운영 트러블슈팅
