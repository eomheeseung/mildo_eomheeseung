# 코드 발췌

실서비스 프론트엔드에서 제가 설계·구현한 대표 코드입니다. 민감 정보(키·서버 주소·개인정보)는 제거하거나 placeholder(`api.example.com`, `<ONELINK_BASE>` 등)로 치환했습니다.
전체 빌드용이 아니라 **설계 판단을 읽기 위한 발췌**입니다.

| 파일 | 무엇을 보나 |
|---|---|
| `env.ts` | 하나의 코드베이스로 웹(도메인 자동 추종)+네이티브(빌드 값) 지원. 폴백은 항상 운영(fail-safe) |
| `validate-release-env.cjs` | 릴리즈 빌드에 로컬/사설 API_URL이 박히면 **빌드 자체를 실패**시키는 가드 |
| `signupFlow.ts` | 서버 응답 기반 가입 라우팅 + 계정 생성 전 상태 보존(pending) + deploy-order-safe |
| `apiClient.ts` | 401 자동 리프레시-재시도 인터셉터 + `{success,data,message}` 통일 envelope |
| `analytics.ts` | 리퍼럴 deferred 딥링크 **이중 캡처**(AppsFlyer UDL + expo-linking 백업) |
| `RevenueReportTab.tsx` | 관리자 대시보드 — graceful degradation, 표시 반올림 vs 서버 정밀계산 분리 |
| `platformUtils.ts` | 크로스플랫폼 UX — OS 분기 다운로드 + 보안컨텍스트 클립보드 폴백 |
| `ReferralScreen.tsx` | 화면 컴포넌트 — 병렬 조회·상태 렌더 + 네이티브/웹 공유 분기 |
| `pushPermission.ts` | 푸시 권한 판정의 **플랫폼 차이 흡수**(안드로이드엔 `undetermined`가 없다) + 토큰 로테이션·멱등 구독 |
| `usePushPrimer.ts` | 프리퍼미션 — 팝업을 "설명한 화면" 뒤로 옮기고, 재노출 억제를 **모드별로** 분리 |

설계 배경은 [`../docs`](../docs)의 문서에 정리돼 있습니다.
