# 코드 발췌

실서비스에서 제가 설계·구현을 주도한 대표 코드입니다. 민감 정보(키·DB·개인정보)는 포함돼 있지 않습니다.
전체 빌드용이 아니라 **설계 판단을 읽기 위한 발췌**입니다.

| 파일 | 무엇을 보나 |
|---|---|
| `TokenLedgerService.java` | 인앱 재화 원장 — 유상/무상 적립, FIFO 만료 로트, 혼합 차감, 멱등 |
| `TokenRewardEventListener.java` | 가입 커밋 후 별도 트랜잭션 보상 + **CI 해시 멱등키**(재가입 파밍 차단) |
| `CiHasher.java` | 사람 단위 멱등키를 위한 CI 해시 (개인정보 미저장) |
| `ReferralService.java` | 친구 초대 — 3중 어뷰징 방지, 가입과 분리된 처리 |
| `GoogleAdsClient.java` | 외부 광고 API 연동 (OAuth 토큰 교환 + GAQL, SDK 없이 REST) |
| `ClaudeClient.java` | AI 멀티 프로바이더 — Claude 5 세대 대응(temperature/thinking), JSON 파싱 방어 |

설계 배경은 [`../docs`](../docs)의 문서에 정리돼 있습니다.
