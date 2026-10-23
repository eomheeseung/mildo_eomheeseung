# mildo 알림 중계(alert-relay) 구축기

- 작성일: 2026-10-01
- 선행 작업: [운영 모니터링 구축기](./monitoring-infra.md) (Grafana·Loki·Prometheus·Uptime Kuma 기본 스택)
- 목적: Grafana·Uptime Kuma의 알림을 그냥 메일로 때우지 않고, **GPT로 쉬운 설명을 붙여서** 보낸다. "p95가 3초를 넘었습니다" 같은 알림보다 "회원가입이 느려지고 있고, 원인은 DB 커넥션 대기로 보입니다"가 비개발자(대표)에게 더 쓸모 있다.

---

## 1. 왜 운영 서버가 아니라 모니터링 PC에서 메일을 보내는가

운영 WAS 안에도 이미 `AlertMailer`라는 오류 알림 메일 기능이 있다. 그런데 모니터링용 알림은 이걸 재사용하지 않고 **별도로** 만들었다.

**이유**: 모니터링이 가장 중요하게 잡아야 하는 상황은 "운영 서버·Spring이 완전히 죽었을 때"다. 그런데 그 순간은 메일을 보낼 WAS 코드도 같이 죽어 있다. 모니터링 알림은 **운영 서버 바깥**(모니터링 PC)에서 나가야 운영 서버가 죽어도 "서버가 죽었다"는 메일이 간다.

```
운영 서버가 살아있을 때 생기는 문제 → WAS 의 AlertMailer 가 처리 (기존)
운영 서버 자체가 죽는 문제        → 모니터링 PC 의 alert-relay 가 처리 (신규)
```

그래서 겹치는 알림(5xx 비율, AI 호출 실패 등)은 **일부러 중복 발송하지 않도록** 역할을 나눴다(4장 참고).

---

## 2. 구조

```
Grafana 알림 규칙 ─┐
                   ├─ 웹훅(JSON) ──▶ alert-relay (모니터링 PC, Docker)
Uptime Kuma 다운 ──┘                    │
                                         ├─ 1) 개인정보 가리기 (이메일·전화·토큰)
                                         ├─ 2) 쿨다운 체크 (같은 알림 30분에 한 번)
                                         ├─ 3) GPT 로 "쉽게 풀면" 생성 (30초 시한)
                                         └─ 4) MailPass 로 수신자별 발송
```

`alert-relay`는 기존 Docker Compose 스택(Grafana·Loki·Prometheus·Uptime Kuma)에 **다섯 번째 서비스로 추가**했다. 호스트 포트를 열지 않고, 같은 compose 네트워크 안에서만 `http://alert-relay:18080`으로 닿는다 — Grafana·Kuma가 이 서비스를 부를 수는 있어도, 외부에서 직접 두드릴 수는 없다.

---

## 3. 구현

### 3-1. 디렉터리

```
mildo-monitor/
  alert-relay/
    app.py
    Dockerfile
    requirements.txt
  .env                 (실제 키, git 제외 대상)
  docker-compose.yml   (서비스 추가)
```

### 3-2. 개인정보 가리기

알림 원문(로그 메시지)을 메일에 그대로 실으면 이메일·전화번호·토큰이 새어나갈 수 있다. 세 가지 정규식으로 치환한다.

```python
RE_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(\.[\w-]+)+")
RE_PHONE = re.compile(r"01[016789]-?\d{3,4}-?\d{4}")
RE_TOKEN = re.compile(r"(eyJ[\w-]{10,}\.[\w-]+\.[\w-]+|(sk|key)-[\w-]{16,}|Bearer\s+\S+)")

def mask(text: str) -> str:
    text = RE_EMAIL.sub("(메일 가림)", text)
    text = RE_PHONE.sub("(전화 가림)", text)
    text = RE_TOKEN.sub("(토큰 가림)", text)
    return text
```

원문은 1200자에서 자르고 ` …`를 붙인다 — 긴 스택트레이스가 메일을 전부 채우는 걸 막는다.

**실측 검증**:
```
입력: connect EHOSTUNREACH 1.2.3.4:443 test@example.com 010-1234-5678 Bearer eyJhbGci...
출력: connect EHOSTUNREACH 1.2.3.4:443 (메일 가림) (전화 가림) (토큰 가림)
```
IP 주소는 가리는 대상이 아니라서 그대로 남는다 — 장애 원인 파악에는 IP가 필요하기 때문에 의도된 동작이다.

### 3-3. 쿨다운 — 복구 알림은 예외

같은 알림이 반복해서 울리면 메일함이 도배된다. 알림 키(Grafana는 `alertname`, Kuma는 `kuma:<모니터 이름>`)당 **30분에 한 번**만 보낸다.

```python
_last_sent: dict[str, datetime] = {}

def should_send(key: str, is_resolved: bool) -> bool:
    if is_resolved:
        return True   # "다시 살아났다"는 쿨다운과 무관하게 바로 알아야 한다
    now = datetime.now(timezone.utc)
    last = _last_sent.get(key)
    if last and now - last < timedelta(minutes=30):
        return False
    _last_sent[key] = now
    return True
```

메모리 딕셔너리라 컨테이너가 재시작되면 쿨다운 기록이 초기화된다 — 이건 의도적으로 감수했다(지속성보다 단순함을 택함). 운영 중 재시작이 잦지 않은 서비스라 실질적 영향은 적다.

**실측 검증**:
```
1차 다운 알림 발송 → 성공
2차(즉시 재전송) → {"sent": false, "reason": "cooldown"}
복구 알림(쿨다운 중에 보냄) → {"sent": true}  ← 쿨다운 무시하고 바로 나감
```

### 3-4. GPT "쉽게 풀면" — 실패해도 메일은 반드시 나간다

알림 원문과 사실관계(라벨, 측정값, 시각)를 GPT에 넘겨 비개발자도 이해할 수 있는 설명을 받는다. 30초 안에 응답이 없거나 호출이 실패하면 **그 섹션만 빼고 메일은 그대로 보낸다** — 설명이 안 붙는 것과 알림 자체가 안 가는 것은 완전히 다른 문제이기 때문이다.

```python
async def gpt_explain(alert_title, raw_masked):
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            r = await client.post(OPENAI_URL, headers=..., json={
                "model": "gpt-5.6-luna",
                "messages": [...],
                "max_completion_tokens": 2000,
                "reasoning_effort": "low",
                # ⚠ temperature 넣으면 안 됨 — gpt-5 계열(추론 모델)은 400 에러
            })
            r.raise_for_status()
            return r.json()["choices"][0]["message"]["content"].strip() or None
    except Exception as e:
        log.warning(f"GPT 호출 실패 — 쉽게 풀면 없이 발송: {e}")
        return None   # 예외를 삼키고 None 반환 — 호출부는 이걸 "설명 없음"으로만 처리
```

**실측 검증**: `OPENAI_API_KEY`를 일부러 틀린 값으로 바꾸고 알림을 발생시켰다.
```
GPT 호출 → 401 Unauthorized
로그: "GPT 호출 실패 — 쉽게 풀면 없이 발송"
메일 발송 → 수신자 2명 모두 200 OK (쉽게 풀면 섹션만 빠진 채 정상 도착)
```
장애 대응 체계의 일부가 외부 API(GPT)에 의존할 때는 그 API가 죽어도 핵심 기능(알림 발송)은 살아 있어야 한다 — 이 테스트로 그 요구사항을 직접 확인했다.

### 3-5. MailPass 발송

수신자마다 **개별 요청**을 보낸다(한 번에 여러 명에게 보내는 API가 아님). 실패하면 1회 재시도 후 컨테이너 로그에 남긴다.

```python
async def send_one_mail(to_email, subject, html):
    for attempt in (1, 2):
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                r = await client.post(MAILPASS_URL, headers=..., json={...})
                if r.status_code < 400:
                    return True
        except Exception as e:
            log.warning(f"MailPass 예외(시도 {attempt}) {to_email}: {e}")
    log.error(f"MailPass 최종 실패 — 메일 못 보냄: {to_email}")
    return False
```

---

## 4. 알림 역할 분담 — WAS와 겹치지 않게

운영 WAS가 이미 메일로 보내는 것들(5xx 급증, 401 비율, AI 호출 실패, 결제 검증 실패, 푸시 실패)은 **모니터링 쪽에서 다시 보내지 않는다.** 같은 사고에 메일이 두 통 가면 무엇이 먼저인지, 뭘 봐야 하는지 헷갈린다.

모니터링(`alert-relay`)이 맡는 건 **WAS가 스스로 알아챌 수 없는 것**들이다 — WAS 프로세스 자체가 죽거나, 서버 자원이 바닥나거나, 모니터링 수집 경로 자체가 끊기는 경우.

| 알림 | 조건 | 왜 WAS가 못 보는가 |
|---|---|---|
| API 다운 | Uptime Kuma 2회 연속 실패 | 서버가 죽으면 WAS도 같이 죽는다 |
| Spring 재기동/죽음 | `up{job="mildo-spring"}==0` 3분 | 〃 |
| 수집 끊김 | Prometheus 지표 10분간 없음 | Alloy·Tailscale 자체 장애는 WAS 입장에선 안 보인다 |
| 디스크/메모리 | 80% / 10% 미만 임계값 | WAS 코드 안에서 OS 자원까지는 안 본다 |

**재시도 횟수를 2로 둔 이유**: 처음엔 1회 실패에도 알림이 울렸는데, 10월 1일 새벽 6시에 **회사 내부망이 2분간 끊긴 것**(`EHOSTUNREACH`)만으로 "서버 다운" 알림이 울렸다. 운영 서버는 멀쩡했고 모니터링 PC 쪽 네트워크 문제였다. 재시도를 2회로 늘려 일시적 끊김은 걸러지게 했다.

---

## 5. 연결 — Grafana·Uptime Kuma

### Grafana (API로 프로비저닝)

```bash
# Contact point 생성
curl -u admin:'<비번>' -X POST http://127.0.0.1:13000/api/v1/provisioning/contact-points \
  -d '{"name":"alert-relay","type":"webhook",
       "settings":{"url":"http://alert-relay:18080/grafana","httpMethod":"POST"}}'

# 알림 정책 기본 수신처로 지정
curl -u admin:'<비번>' -X PUT http://127.0.0.1:13000/api/v1/provisioning/policies \
  -d '{"receiver":"alert-relay","group_wait":"30s","group_interval":"5m","repeat_interval":"4h"}'
```

### Uptime Kuma (API가 없어 화면에서 직접)

Uptime Kuma는 소켓 통신 기반이라 REST API로 알림 채널을 만들 수 없다 — 화면에서 직접 등록했다.

```
Settings → Notifications → Setup Notification
  종류: Webhook
  Post URL: http://alert-relay:18080/kuma
  Content Type: application/json
  "기존 모니터에 모두 적용" 체크 → 저장 시 등록된 모니터 전부에 자동 연결
```

모니터별 **재시도(Retries) 2 / 하트비트 재시도 주기(Heartbeat Retry Interval) 60초**도 같이 설정했다 — 위 4장의 "회사 망 2분 끊김" 오탐을 막기 위함이다. 설정 결과는 Kuma의 SQLite를 직접 조회해 검증했다(화면 저장이 실제로 반영됐는지는 DB를 봐야 확실하다 — 이전에 Save 버튼을 안 누르고 넘어가 저장이 안 된 적이 있었다).

```bash
docker exec <kuma-container> sqlite3 /app/data/kuma.db \
  "SELECT name, maxretries, retry_interval FROM monitor;"
# mildo API | 2 | 60
# mildo 웹  | 2 | 60
```

---

## 6. 전체 검증

| 항목 | 방법 | 결과 |
|---|---|---|
| 내부망 전용 | 컨테이너 안에서 vs 호스트에서 각각 접속 | 컨테이너 내부 `200`, 호스트 `000`(연결 안 됨) — 의도대로 격리 |
| 개인정보 가리기 | 이메일·전화·토큰 섞은 가짜 로그 투입 | 셋 다 정상 치환, IP는 보존 |
| 쿨다운 | 같은 알림 즉시 재전송 | 2번째부터 `cooldown`으로 차단 |
| 복구는 쿨다운 예외 | 쿨다운 중에 복구 상태 전송 | 즉시 발송 |
| GPT 장애 격리 | API 키를 일부러 틀리게 | 설명 없이 메일은 정상 발송 |
| Grafana 경로 전체 | Grafana API로 테스트 알림 발송 | `status:"ok"`, alert-relay 로그에서 수신·GPT·메일 전 과정 확인 |
| 실제 수신 확인 | 테스트 알림 2건을 실제 메일함에서 확인 | 받은편지함 정상 수신(스팸 아님) |

---

## 7. 얻은 교훈

1. **장애 대응 체계는 자기 자신이 의존하는 것의 장애에도 버텨야 한다.** 알림 서비스가 GPT에 의존한다고 해서 GPT가 죽으면 알림도 죽는 설계는 본말전도다. "핵심 기능(발송)"과 "부가 기능(설명)"을 명확히 분리해 부가 기능의 실패가 핵심 기능을 막지 않게 했다.
2. **UI 저장은 반드시 저장 결과를 다시 조회해 확인한다.** 화면에서 "저장했다"고 느껴도 실제로 DB에 반영 안 된 경우가 있었다(Uptime Kuma 모니터 등록 때도, 이번 재시도 설정 때도 공통). 클릭 자체가 아니라 저장된 데이터를 보는 게 진짜 검증이다.
3. **같은 종류의 알림을 두 시스템이 중복으로 보내면 안 된다.** 운영 서버 자체 알림과 모니터링 알림이 겹치는 영역을 먼저 가려내고, 서로 못 보는 영역만 맡기도록 역할을 나눴다.
4. **일시적 네트워크 끊김과 진짜 장애를 구분하는 장치(재시도 횟수)가 없으면 알림이 신뢰를 잃는다.** 한 번이라도 거짓 알림이 울리면 다음부터 진짜 알림도 가볍게 넘기게 된다.
