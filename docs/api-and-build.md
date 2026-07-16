# API 연동 · 환경 해석 · 빌드/배포

## API 클라이언트 — 화면이 몰라도 되게

모든 서버 통신은 `api/client.ts` 한 곳을 지난다. 화면은 엔드포인트·토큰·에러 형태를 신경 쓰지 않는다.

- **요청 인터셉터** — 공개 API(로그인/회원가입)엔 토큰 미첨부, 그 외엔 Access 토큰 자동 첨부.
- **응답 인터셉터** — 401이면 Refresh로 토큰 갱신 후 **원래 요청을 그대로 1회 재시도**. 갱신도 실패하면 토큰을 비워 로그인 흐름으로. (refresh 호출은 인터셉터를 안 타는 별도 axios로 → 무한 루프 방지.)
- **통일 envelope** — 성공/실패 모두 `{ success, data, message, code, status }` 로 정규화. 화면은 `res.success`만 보면 되고, 중복 인증 같은 도메인 에러는 `code`로 분기.

관련 코드: [`../code-excerpts/apiClient.ts`](../code-excerpts/apiClient.ts)

## base URL 해석 — 하나의 코드, 여러 환경

base URL을 코드에 하드코딩하지 않는다.

- **웹** — 브라우징 중인 도메인(오리진)을 그대로 따라간다. dev 도메인에서 열면 dev API, 운영 도메인에서 열면 운영 API에 자동으로 붙는다(nginx가 `/api` 프록시). 로컬/LAN이면 로컬 백엔드로 직결.
- **네이티브** — 빌드에 구워진 값(`app.config.js extra.apiUrl`)을 쓰되, **없으면 반드시 운영으로 폴백**(fail-safe). env 실수로 앱이 사설망을 가리키는 일이 없게.
- **오배포 감지** — 릴리즈 빌드인데 사설/로컬 IP에 붙어있으면 앱 안에서 감지해 배지로 노출할 수 있게(`isLikelyMisconfigured`).

관련 코드: [`../code-excerpts/env.ts`](../code-excerpts/env.ts)

## 릴리즈 env 가드 — 오배포를 사람이 아니라 파이프라인이 막는다

Expo는 `.env.local`을 `.env`보다 우선해서 읽는다. 개발자가 로컬 백엔드를 `.env.local`에 넣어두면, 릴리즈 전에 `.env`를 운영으로 바꿔도 `.env.local`이 덮어써서 **로컬 IP(http)가 프로덕션 앱에 박히는** 사고가 난다(실제로 라이브에 http LAN URL이 나간 적이 있다).

그래서:
- `validate-release-env.cjs`가 Expo와 동일한 우선순위로 실제 해석될 `API_URL`을 재현하고, **사설망이면 예외를 던진다.**
- 이 검증을 `app.config.js`(extra.apiUrl 결정)와 Android `build.gradle`의 `preReleaseBuild` 전에 물려서, **릴리즈 빌드에서 로컬 URL이면 빌드 자체가 실패**한다.

관련 코드: [`../code-excerpts/validate-release-env.cjs`](../code-excerpts/validate-release-env.cjs)

## 빌드/배포 파이프라인

| 대상 | 흐름 |
|---|---|
| **웹** | `expo export -p web` → OG/메타 주입(postbuild) → 서버로 배포. 빌드 시 `.env.local`을 잠깐 비활성해 운영 URL 보장, 끝나면 자동 복원 |
| **Android(AAB)** | 릴리즈 전 체크리스트(env·versionCode·서명·네이티브 크래시 방지)를 **한 번에** 점검 → 1회 빌드. env 가드가 사설 URL을 최종 차단 |

- 웹 배포는 `.env.local` 자동 스왑/복원을 스크립트로 감싸, 실수로 로컬 URL이 나가지 않게 한다.
- Android는 EXPO_PUBLIC 값(어트리뷰션 키 등)이 릴리즈 번들에 실제로 구워지는지까지 검증한다(릴리즈에서 `.env.local`을 끄기 때문에 운영 env에도 값이 있어야 함).
