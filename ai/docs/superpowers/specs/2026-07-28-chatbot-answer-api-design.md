# 챗봇 답변 API 설계 — 단발 호출 경로와 출처 검증

- 작성일: 2026-07-28
- 대상: `POST /internal/v1/answer-context-selections`, `POST /internal/v1/answers`
- Jira: S15P11B106-80 `[AI] 챗봇 RAG 파이프라인`
- 근거: `../../../docs/api/AJT-FastAPI-Internal-API.postman_collection.json` (계약 v1.3.0) · `../../../docs/db/erd.sql` (`ai_question`·`ai_answer`·`answer_source`) · 요구사항 FR-ACL-002

## 1. 배경

계약 7개 엔드포인트 중 4개가 구현됐다. 남은 3개 가운데 이 문서는 답변 생성 2개를 다룬다.

두 엔드포인트는 위키 변환의 1·2단계와 **같은 모양**이다.

```
1단계  Spring → AI: 목차·요약만 준다. "뭐가 필요해?"
       AI → Spring: ID 목록
       Spring: 그 ID 의 본문을 파일에서 읽는다 (권한 재검증 포함)
2단계  Spring → AI: 본문을 준다. "일해줘"
       AI → Spring: 결과
```

왕복을 둘로 쪼갠 이유는 AI 서버가 상태를 갖지 않기 때문이다. 위키 파일이 없고
(`session.py:145` 가 요청마다 `tempfile.mkdtemp`), DB 도 보지 않는다. 필요한 것을 먼저
말하고 받아서 처리한다.

대화 이력도 같은 원리다. Spring 이 `ai_question`(`conversation_key` 인덱스)에 쌓고 매
요청에 `conversationMessages` 로 실어 보낸다. AI 서버를 재시작해도 대화가 끊기지 않고,
서버를 여러 대로 늘려도 된다.

### 1.1 위키 변환과 다른 점

| | 위키 변환 | 챗봇 |
| --- | --- | --- |
| 1단계 입력 | `parsedMarkdown` (문서 본문) | `question` + `conversationMessages` |
| 후보 목차 | `currentIndex` 1개 | `wikiIndexes[]` — scopeKey 여러 개 |
| 후보 종류 | 위키만 | 위키 + 일정 |
| 2단계 성격 | 파일을 **쓴다** — 에이전트·MCP·`lint` 필요 | **쓰지 않는다** — 단발 호출 |
| 허용 시간 | 분 단위 (NFR-PERF-002) | 초 단위. 사용자가 화면에서 기다린다 |

2단계가 쓰기를 하지 않는다는 것이 설계의 축이다. 챗봇에 에이전트가 필요 없는 이유는
권한이 아니라 **쓸 것이 없어서**다.

## 2. 선행 문제 — `complete` 가 구현돼 있지 않다

`base.py:53-65` 가 "MCP 없는 단발 호출"을 `complete` 라는 이름으로 설계해뒀지만 주석뿐이고
두 프로덕션 런타임 모두 구현이 없다. 그래서 `selection.py` 는 **빈 임시 디렉터리를 준 `run`**
으로 물러선다 — 에이전트가 뜨지만 볼 것이 없어 무해하다. 위키 변환은 원래 분 단위라
문제가 없다.

챗봇은 이 경로로 갈 수 없다. 채팅 한 마디에 CLI 에이전트가 두 번 뜬다.

따라서 **`complete` 구현이 이 작업의 선행 단계다.** 두 엔드포인트가 모두 그것을 쓴다.

### 2.1 정본은 deepagents

`claude_code` 런타임은 Claude Code 구독 토큰으로 청구되어 테스트에 쓰는 것이고, 배포는
`deep_agents`(API 키)다. 따라서 `complete` 도 deepagents 를 기준으로 설계하고 CLI 는
테스트 편의로 맞춘다.

```python
# deep_agents.py — 정본
def complete(self, messages, *, tier="quality", timeout=None) -> CompletionResult:
    from langchain.chat_models import init_chat_model
    model = init_chat_model(
        self._model_for(tier),
        timeout=timeout or DEFAULT_COMPLETE_TIMEOUT,   # 클라이언트가 실제로 끊는다
        max_retries=0,                                 # 재시도는 크레딧을 조용히 2배 쓴다
    )
    started = time.monotonic()
    r = model.invoke(messages)
    u = r.usage_metadata or {}
    return CompletionResult(
        text=r.content,
        input_tokens=u.get("input_tokens", 0),
        output_tokens=u.get("output_tokens", 0),
        cache_read_tokens=u.get("input_token_details", {}).get("cache_read", 0),
        model=(r.response_metadata or {}).get("model", ""),  # 응답이 말한 실제 모델
        elapsed_seconds=round(time.monotonic() - started, 2),
    )

# claude_code.py — 테스트용
def complete(self, messages, *, tier="quality", timeout=None) -> CompletionResult:
    subprocess.run(["claude", "-p", _render(messages),
                    "--model", self._model_for(tier)],
                   timeout=timeout or DEFAULT_COMPLETE_TIMEOUT, ...)
```

deepagents 쪽은 `create_deep_agent` 도 `MultiServerMCPClient` 도 부르지 않는다. 모델만
부른다. CLI 쪽은 `--mcp-config` 를 붙이지 않는다.

두 가지가 초안에서 빠져 있었다.

**`timeout` 을 실제로 강제한다.** 초안은 인자를 받고 쓰지 않았다. 그러면 §7 이 약속한 30초·
60초가 배포 경로에서 작동하지 않고, 모델 호출이 멈추면 Spring 의 읽기 타임아웃까지 요청이
남는다. 강제 지점은 **모델 클라이언트의 요청 timeout** 이다 — `run` 이 "sync 런타임의
스레드는 취소할 수 없으므로 런타임 자체 timeout 이 유일한 수단"이라고 적어둔 것과 같은
이유다 (`base.py:49-51`). 바깥의 `asyncio.wait_for` 는 호출자를 풀어주지만 호출 자체를
끊지 못한다.

**`max_retries=0` 을 명시한다.** LangChain 기본값은 재시도를 한다. 실패한 호출이 조용히
2~3배 크레딧을 쓴다. 예산이 좁은 상황에서 이건 기능이 아니라 사고다 (§11).

### 2.1.1 반환형 — `CompletionResult`

`.content` 만 돌려주면 `usage_metadata` 와 응답이 말한 실제 모델이 버려진다. 그런데 측정
계획(§10.3)은 토큰·비용·실제 모델을 기록하도록 되어 있다. 문자열 반환으로는 그 계획이
성립하지 않는다.

```python
@dataclass(frozen=True)
class CompletionResult:
    text: str
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    model: str = ""              # 응답이 말한 모델. 요청한 것과 다를 수 있다
    elapsed_seconds: float = 0.0
```

`model` 을 요청값이 아니라 **응답값**으로 두는 이유는 게이트웨이가 모델을 바꿔 끼울 수
있어서다 (§3.1). 요청한 이름을 기록하면 무엇을 쟀는지 모르게 된다.

`RunResult`(`base.py:30-39`)와 별도 형으로 둔다 — `RunResult` 는 `tool_calls`·`turns` 처럼
에이전트에만 있는 필드를 들고 있고, 단발 호출에는 그것이 언제나 빈 값이다.

### 2.1.2 관측 경로

계약 응답에는 토큰·비용 필드가 없고 바꿀 이유도 없다. `chat_sim.py` 는 실제 HTTP 로 부르니
반환값으로는 `CompletionResult` 를 볼 수 없다.

`wiki_mcp/telemetry.py` 가 이미 같은 문제를 풀어놨다 — 서버 쪽에서 파일에 집계하고 하네스가
그 파일을 읽는다. 같은 방식을 쓴다: `complete` 호출마다 JSON 한 줄을 파일에 append 하고,
`requestId`(`deps.py` 가 재사용하는 값)로 요청과 상관시킨다.

```json
{"requestId":"...","path":"/internal/v1/answers","tier":"quality",
 "model":"claude-sonnet-4-6","inputTokens":8312,"outputTokens":604,
 "cacheReadTokens":3351,"elapsedSeconds":4.21}
```

경로는 환경변수로 주고 비어 있으면 기록하지 않는다 — 배포에서 파일이 무한히 자라면 안 된다.

### 2.2 `run` 은 손대지 않는다

`claude_code.py` 의 docstring 은 선행 spike 에서 네 번 데인 함정을 담고 있고, 측정 규칙이
"CLI 수치는 CLI 수치끼리만 비교"다. `run` 경로를 바꾸면 `experiments/` 의 기존 측정과 대조가
깨진다. `complete` 는 **새 메서드로 추가**하므로 그 문제가 없다.

`base.py:62` 는 별도 이름을 쓰는 이유로 "`agent_runtime/claude_code.py` 가 수정 금지라"고
적었는데, **그 근거는 존재하지 않는다.** `NOTICE` 의 이식 범위는 `src/wiki_mcp/` 뿐이고
`agent_runtime/` 에는 이식 헤더가 없다. 실제 이유는 위의 측정 재현성이다. 이 문장은 담당자
통보 대상(§8)이다.

### 2.3 공통 async 어댑터 — `wiki_api/completion.py`

`complete` 는 sync 메서드다. 그런데 `selection.py:129-131` 은 그것을 async 함수 안에서
**직접** 부른다.

```python
complete = getattr(runtime, "complete", None)
if complete is not None:
    result = complete(prompt, timeout=SELECTION_TIMEOUT_SECONDS)
    if inspect.isawaitable(result):
        result = await asyncio.wait_for(result, timeout=SELECTION_TIMEOUT_SECONDS)
```

awaitable 이면 `wait_for` 를 걸지만 sync 반환이면 그 줄에서 **이벤트 루프가 통째로 멈춘다.**
지금은 `complete` 가 없어서 이 경로가 죽어 있고 문제가 드러나지 않는다.

**즉 `complete` 를 만드는 순간 회귀가 생긴다.** `/wiki-context-selections` 가 최대
`SELECTION_TIMEOUT_SECONDS`(300초) 동안 서버 전체를 막고, 그 사이 다른 모든 요청이 대기한다.
챗봇 라우터를 같은 모양으로 만들면 느린 질문 하나가 나머지 API 를 끌고 내려간다.

따라서 어댑터를 하나 두고 **모든 호출자가 그것만 쓴다.**

```python
# wiki_api/completion.py
async def complete(runtime, messages, *, tier, timeout, error_code,
                   path: str, request_id: str) -> CompletionResult:
    """sync 런타임을 to_thread 로 띄우고 async 런타임은 그대로 await 한다.

    `asyncio.wait_for` 는 호출자를 풀어주지만 스레드를 죽이지 못한다. 실제로 호출을 끊는
    것은 모델 클라이언트의 요청 timeout 이다 (§2.1). 두 겹 다 필요하다 — 앞의 것은
    이벤트 루프를, 뒤의 것은 실제 연결을 지킨다.
    """
```

여기서 `InternalError` 로의 변환(타임아웃·런타임 오류)도 한 곳에 모은다. `selection.py`
의 `_complete` 가 이미 그 로직을 갖고 있으니 그것을 옮겨 오는 것이다.

**`path` 와 `request_id` 는 필수 인자다.** 관측 로그(§2.1.2)가 그 두 값을 쓰는데 초안의
시그니처에는 없었다. `ContextVar` + 미들웨어로도 되지만 명시 인자가 현재 구조에 더 단순하다
— 라우터가 이미 `rid: str = Depends(request_id)` 로 받고 있다(`routers/wiki.py:74`).
`wiki-transformations`·`wiki-edits` 는 그것을 세션에 넘기는데(`:100`, `:144`)
`wiki-context-selections` 만 `select_wikis()` 로 넘기지 않는다. 그 한 줄을 꿰는 것이
`selection.py` 이전 작업의 일부다.

미들웨어 방식을 택하지 않는 이유: 값이 어디서 오는지 시그니처에 보이지 않으면, 하네스가
직접 함수를 부를 때(`--dry-run`) `ContextVar` 가 비어 로그가 상관되지 않는다.

**`wiki_api/selection.py` 를 수정한다.** 초안에는 "건드리지 않는다. `complete` 가 생기면
자동으로 빨라진다"고 적었는데 틀렸다. 저 파일이 어댑터를 쓰도록 바꾸지 않으면 빨라지는 대신
서버가 막힌다.

### 2.4 `langchain` 의존성 선언

`init_chat_model` 은 `langchain` 패키지에 있다. `uv.lock` 에 1.3.14 가 있지만
`pyproject.toml` 에 선언이 없어 전이 의존성이다. `ai/CLAUDE.md` 함정("직접 임포트하는
패키지는 전이 의존성이어도 명시한다 — uvicorn·python-multipart 에서 한 번씩 밟았다")에
따라 `deepagents` extra 에 추가한다.

`init_chat_model` 을 쓰는 이유는 `anthropic:claude-…` 형태의 모델 문자열을 `run` 과 똑같이
해석해서다. `ChatAnthropic` 직접 생성은 접두사를 벗겨야 하고 두 경로의 모델 표기가 갈린다.

## 3. 모델은 `tier` 로 고른다

호출자가 모델 문자열을 직접 넘기면 런타임마다 이름 체계가 달라 깨진다
(`anthropic:claude-haiku-4-5` vs `haiku`). 런타임이 번역한다.

| tier | deepagents | claude-code | 쓰는 곳 |
| --- | --- | --- | --- |
| `fast` | `anthropic:claude-haiku-4-5-20251001` | `claude-haiku-4-5-20251001` | 1단계 분류·선택 |
| `quality` | `anthropic:claude-sonnet-4-6` | `claude-sonnet-4-6` | 2단계 답변 |

두 열의 차이는 `anthropic:` 접두사뿐이다. 접두사는 LangChain 이 제공자를 고르는 데 쓰고
CLI 는 요구하지 않는다.

모델 선택은 취향이 아니라 **게이트웨이가 가진 것**에 묶인다 (§3.1).

환경변수로 덮을 수 있게 둔다. 런타임 객체는 1개를 유지하므로 `serve.py` 의 기동 인자가
늘지 않는다.

1단계에 작은 모델을 쓸 수 있는 근거는 안전망이 두 겹이라는 점이다 — 우리가 화이트리스트로
거르고(§4.1), Spring 이 다시 권한을 재검증한다(계약 정책). 2단계는 환각과 출처 정확도가
걸려 있어 tier 를 올린다.

**양쪽 모두 정확한 모델 이름을 쓴다.** `opus`·`sonnet` 같은 별칭은 시점에 따라 다른 모델로
해석돼 두 측정의 비교를 조용히 깨뜨린다 (`ai/CLAUDE.md` 함정). `claude_code.py` 의 현재
기본값 `"sonnet"`(생성자 인자)이 그 별칭인데, `complete` 의 tier 표는 별칭을 쓰지 않는다.

### 3.1 게이트웨이 — SSAFY GMS

API 는 Anthropic 직결이 아니라 SSAFY GMS 프록시를 지난다. 키 하나로 여러 제공자를
프록시하며 주소 패턴은 `https://gms.ssafy.io/gmsapi/<업스트림 호스트>` 다.

```
ANTHROPIC_API_KEY=<GMS 키>
ANTHROPIC_BASE_URL=https://gms.ssafy.io/gmsapi/api.anthropic.com
```

`langchain-anthropic` 이 이 두 환경변수를 읽으므로 코드에 게이트웨이 지식이 들어가지
않는다. `init_chat_model("anthropic:…")` 그대로 쓴다.

2026-07-29 실측으로 확인한 모델:

| 모델 | GMS |
| --- | --- |
| `claude-haiku-4-5-20251001` | 있음 |
| `claude-sonnet-4-6` | 있음 |
| `claude-sonnet-4-5-20250929` | 있음 |
| `claude-opus-4-6` | 있음 |
| `claude-sonnet-5` · `claude-sonnet-4-5` · `claude-haiku-4-5` · `claude-opus-4-5` | 없음 (400 `Model … is not available`) |

`quality` 가 sonnet-5 가 아니라 sonnet-4-6 인 이유가 이것이다. 그리고 `claude-opus-4-6`
이 있다는 것은 **`experiments/` 의 기존 위키 측정을 GMS 로도 재현할 수 있다**는 뜻이다 —
지금까지 그 측정은 CLI 경로에서만 나왔고 API 경로 수치가 없었다.

접두사 없는 짧은 이름(`claude-haiku-4-5`)이 400 이 되는 것도 별칭을 쓰지 말아야 하는
실증이다.

## 4. 1단계 — `POST /internal/v1/answer-context-selections`

```
입력: questionId, conversationId, question, conversationMessages,
      wikiIndexes[{scopeKey, indexMarkdown}], scheduleSummaries[{scheduleId, title, ...}]
출력: questionType, wikiIds[≤5], scheduleIds[≤5], reason
```

본문은 이 단계에 오지 않는다 (계약 정책).

### 4.1 화이트리스트 두 개

모델이 낸 ID 를 믿지 않는다. `selection.py:8-12` 가 그 이유를 이미 적어뒀다 — 지어낸 ID 가
새면 Spring 이 없는 자료를 조회하거나(다행) 다른 범위의 자료를 읽는다(사고).

| 후보 | 화이트리스트 출처 |
| --- | --- |
| 위키 | `wikiIndexes[]` 전부에 `pages/([A-Za-z0-9_-]+)\.md` 를 돌린 합집합. `selection.py` 의 `index_wiki_ids` 재사용 |
| 일정 | `scheduleSummaries[].scheduleId` 집합 |

일정에 목차가 없어서 방법이 갈린다. 위키는 목차 링크가 곧 존재 증명이지만 일정은 요청이
실어 온 요약 목록이 유일한 근거다.

파싱은 `selection.py` 의 `parse_selection`·`_json_object` 를 따른다: JSON 이 정본이지만
그것에 의존하지 않고, 실패하면 자유 텍스트에서 ID 를 줍고, 어느 경로든 마지막 관문은
화이트리스트다. 중복 제거 후 5개에서 자른다.

### 4.2 `questionType` 은 선택 결과로 교정한다

모델의 선언을 그대로 쓰지 않는다.

| 실제 선택 결과 | `questionType` |
| --- | --- |
| 위키만 | `wiki` |
| 일정만 | `schedule` |
| 둘 다 | `mixed` |
| 둘 다 없음 | 모델 선언을 유지. 파싱 불가면 `wiki` |

이유 두 가지. 모델이 `mixed` 라 선언하고 위키만 고르면 Spring 이 2단계에 빈
`selectedSchedules` 를 싣고, 답변 프롬프트가 있지도 않은 일정을 언급하려 든다. 그리고
DB `chk_ai_question_type` 이 `('WIKI','SCHEDULE','MIXED')` 만 허용하므로 그 밖의 값은
Spring 의 저장 시점에서 터진다 — 우리가 먼저 막는다.

### 4.3 빈 결과는 정상이다

관련 자료가 없으면 두 배열이 모두 빈다. 400 이 아니다. 2단계가 "자료 없음"으로 답한다
(§5.3).

## 5. 2단계 — `POST /internal/v1/answers`

```
입력: questionId, conversationId, questionType, question, conversationMessages,
      selectedWikis[{wikiId, title, contentMarkdown}],
      selectedSchedules[{scheduleId, title, content, startAt, endAt, targetText, location}]
출력: answer, sources[{type, wikiId|scheduleId, title}]
```

`questionType` 별로 쓰는 배열이 다르다 — `wiki` 는 `selectedWikis`, `schedule` 은
`selectedSchedules`, `mixed` 는 둘 다 (계약 정책).

### 5.1 `sources` 검증

모델이 `answer` 와 `sources` 를 한 JSON 으로 내고, 우리가 받은 ID 집합으로 필터한다.

```
101 ∈ selectedWikis   → 통과
999 ∉ 어디에도        → 버림
```

`type` 은 모델 선언을 쓰지 않고 **어느 배열에서 왔는지로 정한다.** `title` 도 요청이 실어 온
값을 쓴다 — 모델이 제목을 바꿔 쓰면 `answer_source.source_title` 에 위키 실제 제목과 다른
문자열이 저장된다.

이 방식의 한계를 명시한다: **"지어낸 ID"는 막지만 "안 쓴 자료를 출처로 신고하는 것"은 막지
못한다.** 모델의 자기 신고에 의존한다. 각주 강제(위키 에이전트가 쓰는 방식)로 기계 검증할
수 있지만 계약에 답변 본문의 각주 표기 형식이 없어 프론트 처리와 계약 변경이 필요하다.
현 범위에서는 채택하지 않고, 정확도가 문제가 되면 그때 계약 변경으로 올린다.

### 5.2 `sources` 는 두 개 이상 가능

계약에 명시돼 있다. 상한을 두지 않는다 — 받은 자료가 1단계에서 이미 각각 5개로 잘려 있다.

### 5.3 모델 출력이 어긋날 때

1단계는 ID 목록만 필요해서 자유 텍스트 fallback 이 성립한다. 2단계는 `answer` 와 `sources`
두 개가 필요해 규칙이 따로 있어야 한다. 초안에 없던 부분이다.

| 상황 | 처리 |
| --- | --- |
| JSON 이 깨졌다 | **응답 전문을 `answer` 로 쓰고 `sources` 는 빈 배열.** 실패시키지 않는다 |
| `answer` 키가 없다 | 위와 같다 — 전문을 `answer` 로 |
| `answer.strip()` 이 비었다 | `ANSWER_GENERATION_FAILED` (500) |
| `sources` 가 배열이 아니다 | 빈 배열로 취급. `answer` 는 살린다 |
| source 에 `wikiId` 와 `scheduleId` 가 둘 다 있다 | 실재하는 쪽으로 판정. 양쪽에 다 실재하면 **버린다** (모호한 출처는 없는 출처보다 나쁘다) |
| 같은 source 가 반복 | `(type, id)` 로 중복 제거, 첫 등장 순서 유지 |
| `type` 이 틀렸거나 없다 | 무시하고 어느 배열에서 왔는지로 정한다 (§5.1) |

**JSON 이 깨져도 답변을 살리는 이유는 크레딧이다.** 모델이 내용은 맞게 답하고 포맷만 틀린
경우가 흔하다. 그것을 버리고 재호출하면 같은 크레딧을 다시 쓴다 (§11). 출처를 잃는 손실은
있지만 `sources` 빈 배열은 계약이 허용하는 값이고, 사용자는 답을 받는다.

`answer` 가 실제로 비었을 때만 실패시킨다. 그때는 살릴 것이 없다.

### 5.4 자료가 0개일 때

400 이 아니다. 계약의 400 은 "질문 또는 컨텍스트 오류"이며 형식 문제를 뜻한다. 자료 없음은
정상 상황이다.

프롬프트가 "주어진 자료 밖의 내용으로 답하지 않는다. 자료가 없으면 모른다고 답한다"를
지시하고, `sources` 는 빈 배열로 나간다.

**`answer` 는 우리가 검사해서 보장한다.** 초안은 근거로 `ai_answer.content TEXT NOT NULL`
을 댔는데 그건 근거가 아니다 — MySQL 의 `NOT NULL` 은 빈 문자열을 막지 않는다. `''` 는
저장된다. 그래서 §5.3 의 `answer.strip()` 검사가 실제 방어다.

## 6. 대화 이력

`conversationMessages` 를 role/content 목록 그대로 `complete` 에 넘긴다. deepagents 가
native 로 받으므로 모델이 역할을 정확히 구분하고, 사용자가 질문 본문에 `답변: …` 이라고 써서
이력을 위조하는 것이 구조적으로 막힌다. CLI 는 어댑터에서 문자열로 렌더한다 — 테스트 경로라
열화를 허용한다.

한 번 뒤집었다가 되돌린 결정이다. 처음에는 "테스트가 CLI 이므로 검증 경로와 배포 경로가
갈린다"는 이유로 문자열 통일을 택했다. 기존 `edit_instruction`(`base.py:165-176`)이 이미
문자열 렌더이기도 하다 — 다만 그것은 `run` 이 문자열만 받아 선택지가 없던 경우다.

GMS 키가 생겨 목록 경로를 실측하자 이유가 사라졌다.

```
init_chat_model('anthropic:claude-haiku-4-5-20251001')
  .invoke([{'role':'user',...},{'role':'assistant',...},{'role':'user',...}])
→ "연차 신청 방법을 물어봤습니다." (이전 턴 정확히 참조)
→ usage_metadata 반환 (토큰 측정 가능)
```

**배포 경로를 직접 측정할 수 있으므로 CLI 문자열로 후퇴할 근거가 없다.** 다만 검증된 것은
`complete` 경로뿐이며 `run`(에이전트 · MCP · `create_deep_agent`)은 여전히 미검증이다 —
`ai/CLAUDE.md` 의 "deepagents 스모크" 항목은 이 작업으로 닫히지 않는다.

`wiki-edits` 의 `ChatMessage` 는 재사용하지 않는다. `senderType: "admin"|"agent"` 이고
챗봇 계약은 `role: "user"|"assistant"` 라 형태가 다르다. 새 스키마 클래스를 둔다.

**상한을 건다: 메시지 12개(= user·assistant 6왕복), 그 12개의 `content` 합계 4000자.**
둘 중 먼저 걸리는 쪽을 적용하고 오래된 메시지부터 버린다. 계약에 상한이 없어서 대화가
길어지면 토큰이 무한히 늘고, 1단계는 목차까지 함께 실린다. 현재 질문(`question`)은
`conversationMessages` 와 별도 필드이므로 이 상한의 대상이 아니며 절대 버리지 않는다.

숫자의 근거는 접근 패턴이다 — 후속 질문("그거 언제지?")이 참조하는 것은 직전 1~2왕복이고,
6왕복이면 주제 전환 한 번을 넘어선다. 측정 후 조정 가능한 상수로 둔다.

### 6.1 전체 입력 예산 — 이력만 막는 것으로는 부족하다

초안은 대화 이력에만 상한을 뒀다. 그런데 비용과 컨텍스트를 실제로 지배하는 값은 다른
쪽이다.

| 값 | 실측 | 초안 상한 |
| --- | --- | --- |
| `wikiIndexes[].indexMarkdown` 합계 | 20페이지 목차 = 3,351토큰 | **없음** |
| `selectedWikis[].contentMarkdown` (최대 5) | 페이지 1장 = 3,472토큰, 평균 10KB | **없음** |
| `selectedSchedules[].content` | — | **없음** |
| `question` | — | **없음** |
| `conversationMessages` | — | 12개 · 4000자 |

이력 4000자를 막아놓고 목차 200페이지(≈33,500토큰)를 통과시키면 상한의 의미가 없다. 그리고
`ai/CLAUDE.md` 는 "문서당 2만 토큰 상한"이 **요구사항에 없는 오기**였다고 적어뒀다 — 즉
계약과 요구사항 어디에도 이 상한이 없으므로 우리가 정해야 한다.

**상한은 토큰으로 정하고, 강제는 UTF-8 바이트로 한다.**

초안은 "바이트÷2.1 ≈ 토큰"을 근거로 곧바로 **문자 수** 상한을 계산했다. 바이트와 문자를
바꿔 쓴 오류다. 실측하면 둘의 비율이 문서마다 흔들린다.

| 파일 | 문자 | 바이트 | 토큰(실측) | 토큰/문자 | 토큰/바이트 |
| --- | --- | --- | --- | --- | --- |
| `index.md` (표·hex ID 다수) | 4,453 | 6,869 | 3,351 | **0.753** | 0.488 |
| `pages/eddb3cec8f14.md` (한국어 산문) | 7,301 | 9,540 | 3,472 | **0.476** | 0.364 |

문자 기준은 1.58배, 바이트 기준은 1.34배 흔들린다. **바이트가 덜 흔들리므로 그쪽을 쓴다.**
목차가 문자당 토큰이 높은 이유는 `pages/64f41068e524.md` 같은 hex ID 가 토큰을 잘게
쪼개서다 — 위키가 커지면 목차는 이 방향으로 더 치우친다.

보수적 상계로 **0.5 토큰/바이트**를 쓴다 (실측 최대 0.488). 즉 바이트 임계값 = 토큰 상한×2.

| 단계 | 토큰 상한 | 바이트 임계값 | 실측 대비 | 최대 크레딧 | 초과 시 |
| --- | --- | --- | --- | --- | --- |
| 1단계 | 20,000 | 40,000 | 20페이지 목차(6,869B)의 5.8배 | ~200 | 400 `INVALID_ANSWER_CONTEXT_REQUEST` |
| 2단계 | 60,000 | 120,000 | 5페이지 최악(50KB)의 2.4배 | ~600 | 400 `INVALID_ANSWER_GENERATION_REQUEST` |

세는 대상은 **조립된 메시지 전체**다 — 목차·일정 요약·이력·질문·지시문을 합친 것. 필드별로
따로 세면 합계가 상한을 넘는 조합이 통과한다.

산문 요청은 이 상한이 실제로는 절반쯤(0.364 비율) 으로 작동한다. 그것을 받아들인다 — 목적이
정확한 과금 예측이 아니라 **폭주 차단**이고, 차단선은 보수적인 쪽으로 틀리는 것이 맞다.

### 6.2 정확한 토큰 수는 `count_tokens` 로

게이트웨이가 `POST /v1/messages/count_tokens` 를 프록시한다 (2026-07-29 확인, HTTP 200).
같은 목차가 `{"input_tokens":3324}` 로 돌아온다.

역할을 나눈다.

| 용도 | 방법 | 이유 |
| --- | --- | --- |
| 요청 거절(§6.1) | UTF-8 바이트 | 네트워크 왕복이 없다. 지연을 늘리지 않는다 |
| 측정 리포트(§10.3) | `count_tokens` | 정확한 값이 필요하다 |

**배포 경로에서 `count_tokens` 를 부르지 않는다.** 매 요청에 왕복이 하나 늘고, 게이트웨이가
그것에 과금하는지 확인되지 않았다 (Anthropic 직결은 무료다).

**자르지 않고 거절한다.** 자르면 조용히 나빠진다 — 목차를 자르면 잘린 뒤의 페이지가
화이트리스트에서 사라져 정답을 놓치고(그런데 응답은 성공으로 보인다), 본문을 자르면 근거가
잘려 환각이 늘어난다. 어느 쪽도 호출자가 알 수 없다.

거절이 계약 안에서 표현된다: 400 + 해당 코드 + `fieldErrors` 에 어느 필드가 얼마나 초과인지.
`ErrorBody` 가 이미 `fieldErrors` 를 실어 보낸다.

상한 값의 근거는 컨텍스트가 아니라 **크레딧**이다. haiku input 100토큰 ≈ 1크레딧이므로
1단계 상한은 호출당 최대 280크레딧, 2단계는 570크레딧이다. 남은 예산에서 폭주 한 번이
전부를 먹는 것을 막는 값이다 (§11).

## 7. 오류와 시간 상한

`selection.py` 의 300초는 에이전트 fallback 기준이라 채팅에 쓸 수 없다. 단발 호출 기준으로
다시 잡는다.

| 단계 | 상한 | 초과 시 | 근거 |
| --- | --- | --- | --- |
| 1단계 | 30초 | 500 `ANSWER_CONTEXT_SELECTION_FAILED` | 목차 읽고 ID 5개. 넘으면 진행이 아니라 고장 |
| 2단계 | 60초 | 500 `ANSWER_GENERATION_FAILED` | 본문 읽고 답변 생성 |

강제 지점은 §2.1 — 모델 클라이언트의 요청 timeout 이다. `asyncio.wait_for` 만으로는 호출이
끊기지 않는다.

### 7.1 `errors.py` 등록은 엔드포인트당 두 곳이다

초안은 "오류 코드 2개"라고 적었는데 틀렸다. `errors.py` 는 딕셔너리가 두 개이고 각각에
경로를 등록해야 한다 (`errors.py:69`, `errors.py:85`).

| 딕셔너리 | 경로 | 코드 |
| --- | --- | --- |
| `_VALIDATION_CODES` | `/internal/v1/answer-context-selections` | `INVALID_ANSWER_CONTEXT_REQUEST` |
| `_VALIDATION_CODES` | `/internal/v1/answers` | `INVALID_ANSWER_GENERATION_REQUEST` |
| `_FAILURE_CODES` | `/internal/v1/answer-context-selections` | `ANSWER_CONTEXT_SELECTION_FAILED` |
| `_FAILURE_CODES` | `/internal/v1/answers` | `ANSWER_GENERATION_FAILED` |

**둘 중 하나를 빼먹으면 계약 위반이 조용히 나간다** — `_VALIDATION_CODES` 누락은
`INVALID_REQUEST`, `_FAILURE_CODES` 누락은 `INTERNAL_SERVER_ERROR` 로 나가고
(`errors.py:96`, `errors.py:112`) 둘 다 계약에 없는 이름이라 Spring 의 `code` 분기가 깨진다.

계약의 400 예시는 `INVALID_ANSWER_CONTEXT_REQUEST` / `INVALID_ANSWER_GENERATION_REQUEST`
이고 500 은 `ANSWER_CONTEXT_SELECTION_FAILED` / `ANSWER_GENERATION_FAILED` 다.

각 엔드포인트가 낼 수 있는 상태는 계약이 정한 400·401·500 뿐이다. 그 밖을 내면 Spring
분기에서 `UNEXPECTED_STATUS` 로 뭉개진다 (`ai/CLAUDE.md`).

`selection.py:160-166` 의 교훈을 따른다 — 런타임은 실패를 예외가 아니라 `RunResult.error`
로 돌려주므로(CLI 는 오류에도 종료 코드 0) 그것을 확인하지 않으면 실패가 「관련 자료 없음」
으로 둔갑한다. 챗봇에서는 그것이 "모르겠습니다" 답변으로 나가 사용자가 오류를 정보로
읽는다. `complete` 가 문자열을 돌려주므로 오류는 예외로 올린다.

## 8. 변경 파일

| 파일 | 변경 |
| --- | --- |
| `agent_runtime/base.py` | `complete` 를 주석에서 선택 메서드로. `CompletionResult` 신설. §2.2 의 잘못된 근거 문장 정정. 답변 선택·답변 생성 지시문 2개를 `selection_instruction` 옆에 추가 |
| `agent_runtime/deep_agents.py` | `complete` 구현 (정본, timeout·`max_retries=0`). `run` 불변 |
| `agent_runtime/claude_code.py` | `complete` 구현 (테스트용). `run` 불변 |
| `wiki_api/completion.py` | **신규 — 공통 async 어댑터** (§2.3). 모든 단발 호출이 여기를 지난다 |
| `wiki_api/selection.py` | **수정** — 어댑터를 쓰게 바꾼다. 안 바꾸면 이벤트 루프가 막힌다 (§2.3) |
| `wiki_api/answer_selection.py` | 신규 — 1단계 |
| `wiki_api/answer.py` | 신규 — 2단계 |
| `wiki_api/routers/answer.py` | 신규 — 엔드포인트 2개 |
| `wiki_api/schemas.py` | 요청·응답 모델. `ChatMessage` 재사용 안 함 (§6) |
| `wiki_api/errors.py` | **딕셔너리 2개에 각각 2경로 = 4개 등록** (§7.1) |
| `wiki_api/app.py` | 라우터 등록 |
| `pyproject.toml` | `deepagents` extra 에 `langchain` |
| `src/.env.example` | `ANTHROPIC_API_KEY`·`ANTHROPIC_BASE_URL` 과 GMS 가용 모델 목록 |
| `tests/` | 화이트리스트, `questionType` 교정, `sources` 필터, 이력·입력 상한, 오류 코드 4개, 어댑터 이벤트 루프, 캐시 적중 |
| `tests/` (mock) | `ANTHROPIC_BASE_URL` 을 받는 로컬 HTTP mock — timeout 강제와 `max_retries=0` 검증 (§9) |
| `experiments/chat_sim.py` | 신규 — Spring 대신 요청을 조립하는 측정 하네스 (§10) |
| `experiments/chat_questions.json` | 신규 — 질문과 기대 ID 정답 라벨 |

`selection.py` 는 fallback 을 타지 않아 빨라지지만 **그것이 공짜로 오지 않는다** — 어댑터로
옮기는 수정이 함께 가야 한다 (§2.3).

### 8.1 옛 작업 공간에서 이관할 것

`/mnt/c/Users/wolyong/workspace/llmwiki/ai-server/` 에 있고 이 저장소에 없다. 별도 커밋으로
가져온다.

| 파일 | 왜 |
| --- | --- |
| `backend_sim.py` (824줄) | `--via-api URL` 모드가 AI 서버를 실제 HTTP 로 부른다 — "측정 경로 = 프로덕션 경로". `chat_sim.py` 가 따라야 할 형태 |
| `experiments.py` | `experiments/INDEX.md` 가 명령으로 적어놨는데 실물이 없다 |
| `compare.py` | 같음 |

`INDEX.md` 는 `backend_sim.py` 만 "옮기지 않았다"고 밝혔다. `experiments.py`·`compare.py` 는
언급 없이 없어서, 문서가 존재하지 않는 명령을 가리키고 있다. 이관으로 함께 해소한다.

`graph_api.py`·`rebuild.py` 는 이미 `wiki_mcp/` 에 이관돼 있다.

## 9. 테스트 범위

단위 테스트로 덮는 것과 덮지 못하는 것을 구분한다 (`ai/CLAUDE.md`: "동작 확인"은 근거를
구분해 말한다).

**단위 테스트 (FakeRuntime) — 크레딧 0**
- 지어낸 위키 ID·일정 ID 가 응답에서 사라진다
- 중복 제거와 5개 컷
- `questionType` 교정 4경우
- `sources` 필터와 `type`·`title` 요청값 우선
- `sources` 어긋남 6경우 (§5.3): JSON 깨짐 · `answer` 없음 · 빈 `answer` · `sources` 비배열 · ID 양쪽 · 중복
- 이력 12개·4000자 컷. 현재 질문은 보존
- 전체 입력 상한 초과 시 400 + `fieldErrors` (§6.1)
- **오류 코드 4개 전부** (§7.1): 두 엔드포인트의 400, 그리고 예상 밖 예외에서의 500
- 자료 0개에서 200 과 빈 `sources`

**어댑터 테스트 — 크레딧 0**
- **sync `complete` 를 호출하는 동안 이벤트 루프가 살아 있다.** 다른 코루틴이 진행하는지로
  확인한다. FakeRuntime 의 sync `complete` 를 `time.sleep` 으로 막고, 같은 루프의 짧은
  태스크가 완료되는지 본다 — 이것이 §2.3 회귀의 회귀 테스트다
- sync 런타임이 timeout 을 넘겨도 어댑터가 `InternalError` 로 올린다
- `CompletionResult` 의 토큰·모델이 관측 로그 한 줄로 나간다

**로컬 HTTP mock 테스트 — 크레딧 0**

`ANTHROPIC_BASE_URL` 을 로컬 mock 서버로 돌린다. 지연과 실패 횟수를 우리가 정하므로 두
항목을 **정확히** 검증할 수 있다.

- **모델 클라이언트 timeout 이 실제로 끊는다.** mock 이 `timeout` 보다 오래 잡고 있게 하고
  끊기는지 본다
- **`max_retries=0` 이 지켜진다.** mock 이 **받은 요청 수를 센다.** 500 을 돌려주고 카운터가
  1이면 재시도가 없는 것

초안은 이 둘을 "`timeout=1` + 큰 프롬프트"와 "관측 로그"로 덮으려 했는데 둘 다 성립하지
않는다.

- `timeout=1` 은 불안정하다 — 모델이 1초 안에 답하거나 입력 오류를 즉시 돌려주면 통과해도
  아무것도 증명하지 않는다
- **관측 로그로는 업스트림 호출 수를 알 수 없다.** 로그는 논리적 `complete()` 호출당 한
  줄이라, LangChain 이 내부에서 세 번 재시도해도 한 줄만 남는다. 세는 층이 틀렸다

**GMS 스모크 (크레딧 소량)** — 위와 별도로 둔다. mock 은 우리 코드가 인자를 지키는지 보고,
스모크는 게이트웨이가 실제로 응답하는지 본다. 두 질문이 다르다.

**단위 테스트로 못 덮는 것 — 측정으로 넘긴다 (§10)**
- 실제 모델의 분류 정확도
- Haiku 가 1단계를 떠받치는지
- `sources` 가 실제 인용과 일치하는지 (사람 판정)

**이 작업으로도 안 닫히는 것**
- deepagents 의 **에이전트** 경로(`run` · MCP · `create_deep_agent`). `complete` 경로는
  GMS 로 실측했지만 `run` 은 별개다. `ai/CLAUDE.md` 의 "deepagents 스모크" 항목 유지
- Spring 실제 클라이언트 연동

## 10. 측정 계획

`experiments/` 규칙을 따른다 — 수치의 정본은 `INDEX.md` 이고 다른 문서에 복사하지 않는다.

### 10.1 위키가 이미 있다

AI 서버에 라이브 위키가 없다는 사실이 측정을 막는 것처럼 보이지만, 재료는 이미 저장소에
있다. `experiments/2026-07-27-opus46-12docs/data/wiki/ALL/` 이 그것이다.

- `index.md` — 20페이지 목차. `pages/{wikiId}.md` 링크 형태가 계약의 `wikiIndexes` 와 같다
- `pages/*.md` 20개 — 실제 본문. 각주 616개가 붙어 있어 답변 근거 추적까지 가능하다
- 도메인이 사내 규정(휴가·보상·경비·온보딩)이라 챗봇 질문을 만들기에 맞는다

`chat_sim.py` 가 Spring 의 일을 대신한다.

```
index.md            → wikiIndexes[0].indexMarkdown
1단계 호출          → wikiIds
pages/{wikiId}.md   → selectedWikis[].contentMarkdown
2단계 호출          → answer + sources
```

일정은 실물이 없어 만든다. 8~10건이면 되고, **위키와 주제가 겹치는 것을 일부러 섞는다** —
그래야 `mixed` 분류가 시험된다.

### 10.2 정답 라벨

목차를 보면 기대 ID 를 사람이 정할 수 있다. `chat_questions.json` 의 초기 세트:

| 질문 | 기대 wikiId | 페이지 |
| --- | --- | --- |
| 연차 며칠까지 쓸 수 있어? | `eddb3cec8f14` | 휴가 정책 |
| 책 사는 거 지원되나? | `b63430958f86` | 도서 지원 정책 |
| 노트북 사양 어떻게 되지? | `469b409d9597` | 장비 지급 기준 |
| 부업 해도 돼? | `e785ad1e47c9` | 겸업(부업) 정책 |
| 스톡옵션 베스팅 기간? | `bfd285bfd962` | 스톡옵션 |
| 퇴사하면 옵션 언제까지 행사? | `bfd285bfd962` + `2d0c4dd38694` | 스톡옵션 + 오프보딩 |
| 육아휴직 급여 보전되나? | `177011e0ae2c` | 육아휴직 |
| 오프사이트 예산 얼마야? | `6466ec527a71` | 오프사이트 정책 |

두 페이지를 요구하는 질문을 넣은 것은 5개 상한이 있는 선택에서 **하나만 고르고 멈추는지**
보기 위해서다.

### 10.3 재는 것

| 지표 | 방법 |
| --- | --- |
| 정답 포함률 | 기대 ID 가 뽑힌 5개 안에 있나 |
| 순위 | 1순위로 나오나 (계약이 "관련도 순서"라 한다) |
| 오답 유입 | 무관한 페이지가 몇 개 섞이나 |
| `questionType` 정확도 | 위키·일정·혼합 라벨과 일치하나 |
| `sources` 일치 | 답변이 실제로 그 위키를 근거로 했나 (사람 판정) |
| 지연 | 1단계·2단계 각각 초 |
| 토큰·비용 | `usage_metadata` 에서 |

`fast`(Haiku)와 `quality` 로 각각 돌려 대조하면 tier 배정의 근거가 나온다.

`manifest.json` 에 tier→모델 해석 결과와 게이트웨이 주소를 남긴다. 게이트웨이가 모델을
바꿔 끼울 수 있으므로 실제 응답의 `model` 필드를 기록한다.

### 10.4 못 재는 것 — 목차 확장성

1단계는 벡터 검색이 아니라 목차 전문을 프롬프트에 싣는다. 위키 20페이지면 목차가 짧지만
200페이지면 목차만으로 토큰이 커진다. **이 실험으로는 그 위험이 드러나지 않는다.**

목차를 인위적으로 부풀려 지연·토큰은 잴 수 있지만 정답 라벨이 없어 정확도는 못 본다.
한계로 기록하고, 위키가 커진 뒤 다시 잰다.

## 11. 크레딧 예산

GMS 는 달러가 아니라 크레딧으로 과금하고, 환율이 원 API 단가와 다르다. 2026-07-29 시점
잔여는 **3,000 미만**이다. 이것이 측정 계획의 실질 제약이다.

### 11.1 실측 환산

| 호출 | input 토큰 | 크레딧 |
| --- | --- | --- |
| 목차 20페이지 (haiku) | 3,351 | 33.56 |
| 페이지 1장 7.3KB (haiku) | 3,472 | 34.77 |

**haiku input 100토큰 ≈ 1크레딧.** sonnet 은 이보다 약 3배로 추정한다 — 원장에 sonnet
실호출이 초소형뿐이라 정확한 비율은 미실측이다.

### 11.2 왜 계획을 바꿨나

질문 8개를 1단계 haiku + 2단계 sonnet 으로 한 번 돌리면 약 **2,340크레딧 = 잔여의 78%**다.
달러로는 $0.24 지만 크레딧으로는 예산의 대부분이다. **한 번 돌리면 끝난다.**

그래서 세 가지를 바꿨다.

1. **2단계도 haiku 로 먼저 본다.** sonnet 대조는 haiku 품질이 부족하다는 근거가 나온 뒤
2. ~~**1단계에 프롬프트 캐싱을 건다.**~~ **실측으로 폐기됐다 (2026-07-29).** 게이트웨이가
   프롬프트 캐싱을 지원하지 않는다 — 원시 curl 로 `cache_control` 을 정확히 붙여 같은
   목차를 두 번 보내도 `cache_creation_input_tokens`·`cache_read_input_tokens` 가 모두
   0 이다. 우리 클라이언트가 그것을 전송하는 것은 mock 으로 확인했으므로
   (`tests/api/test_prompt_cache.py`) 원인은 게이트웨이다. 1단계 8질문은 약 320크레딧이다.
   **`cache_control` 을 붙이는 코드는 남긴다** — Anthropic 직결로 바꾸면 그때 살아나고,
   붙여 둔 것이 손해는 아니다. 규칙은 §11.2.1 에 그대로 둔다

#### 11.2.1 캐싱 규칙

캐시는 **접두사가 완전히 같을 때만** 맞는다. 그래서 메시지 배열의 순서가 절약을 결정한다.

```python
messages = [{
    "role": "user",
    "content": [
        # ① 고정 — 캐시 대상. 질문마다 같아야 한다
        {"type": "text", "text": index_markdown,
         "cache_control": {"type": "ephemeral"}},
        # ② 변동 — 캐시 경계 뒤. 질문마다 달라도 된다
        {"type": "text", "text": schedules_and_history_and_question},
    ],
}]
```

**목차가 맨 앞에 와야 한다.** 질문이나 이력이 앞에 있으면 접두사가 매번 달라져 적중이 0 이
된다. `cache_control` 은 "여기까지가 캐시 가능한 접두사"라는 표시이므로 그 뒤의 변동은
무해하다. 시스템 프롬프트를 쓴다면 그것도 고정이어야 한다.

두 가지 조건을 명시해 둔다.

| 조건 | 깨지면 |
| --- | --- |
| 캐시 최소 토큰 (haiku 계열은 2,048 로 알려져 있다) | 캐싱이 **아예 걸리지 않는다.** 지금 목차는 3,324 토큰으로 넘지만, 위키가 작은 스코프에서는 못 넘는다 |
| TTL — `ephemeral` 기본 5분 | 질문 8개를 5분 안에 돌려야 적중한다. 사이에 프롬프트를 고치며 느리게 돌리면 매번 생성이다. 필요하면 1시간(`ephemeral_1h`)으로 — `usage_metadata` 에 그 필드가 이미 보인다 |

**회귀 테스트: 2회차 호출의 `cache_read_tokens > 0`.** `CompletionResult` 가 그 값을 들고
있으니(§2.1.1) 확인할 수 있다. 이 값이 0 이면 예산 계산이 틀린 것이고 조용히 3배를 쓰게
된다 — 그래서 테스트로 못박는다.

CLI 경로의 `_render()` 는 content block 목록을 텍스트로 이어붙이고 `cache_control` 을
버린다. 테스트 경로는 캐싱하지 않는다 — CLI 는 구독 청구라 크레딧과 무관하다.
3. **API 를 쓰지 않는 작업을 앞으로 몰았다.** 단위 테스트·어댑터·하네스·`--dry-run` 이
   전부 크레딧 0 이고, 로직 버그를 여기서 다 잡은 뒤 API 를 부른다

### 11.3 제외한 것

| 항목 | 비용 | 판정 |
| --- | --- | --- |
| 위키 변환 12건 API 재현 (opus-4-6) | 14.8M 토큰 · $25 | 제외. 예산 생길 때 |
| deepagents 에이전트(`run`) 스모크 | 문서 1건 = 1.2M 토큰 | 보류. MCP 루프라 급이 다르다 |
| 목차 확장성 실험 (§10.4) | 호출당 33k 토큰 | 보류 |
| tier 2종 전면 대조 | 2배 | 부분만 |

`experiments/2026-07-27-opus46-12docs/report.json` 실측: 12건 누적 input 14,774,954 ·
$25.37 · 95분. 문서당 평균 input 1,231,246 · $2.11. **챗봇 질문 1건(≈12.8k)의 96배다.**

### 11.4 폭주 방지

크레딧은 조용히 새기 때문에 설계에 방어를 박아둔다.

| 방어 | 어디 |
| --- | --- |
| `max_retries=0` | §2.1 — 실패가 2~3배 되지 않게 |
| 전체 입력 상한 | §6.1 — 호출당 최대 크레딧을 못박는다 |
| JSON 깨짐에도 답변 살리기 | §5.3 — 재호출을 피한다 |
| `--dry-run` | §10 하네스 — 프롬프트 검사는 크레딧 0 |
| `--limit N`, `--stage 1\|2` | 2질문으로 먼저 확인, 8질문은 마지막 한 번 |
| 프롬프트 캐싱 | §11.2 |

### 11.5 작업 순서와 예상 소모

| 순서 | 작업 | 크레딧 |
| --- | --- | --- |
| 1 | `complete` + `CompletionResult` + 어댑터 | 0 |
| 2 | `selection.py` 를 어댑터로 이전 | 0 |
| 3 | 1·2단계 엔드포인트 | 0 |
| 4 | 단위 테스트 전부 (§9) | 0 |
| 5 | **로컬 HTTP mock** — timeout 강제·`max_retries=0` 검증 | 0 |
| 6 | 하네스 이관 (`backend_sim.py` 등) + `chat_sim.py --dry-run` | 0 |
| 7 | 프롬프트 캐싱 + 적중 회귀 테스트 (§11.2.1) | 0 |
| 8 | GMS 스모크 1건 | ~40 |
| 9 | 1단계 8질문 (haiku) | ~320 |
| 10 | 2단계 2질문 (haiku) | ~130 |

**계획은 205크레딧을 예상했고 실측은 약 690이었다** (`experiments/2026-07-29-chat-haiku/`).
초과 원인 둘: 캐싱 미지원(위 2번)과 단계적 확인으로 1단계를 8번이 아니라 14번 부른 것.
후자는 의도한 안전 비용이다 — 8질문을 한 번에 돌리기 전에 배관을 확인했다.

1~7번이 크레딧 0 이면서 산출물이 남는다는 것은 유효했다.

초안은 7번을 "실제 런타임 timeout 테스트(~20크레딧)"로 잡았는데 mock 으로 옮겨 0 이 됐고,
검증도 더 정확해졌다 (§9).

**적중 회귀 테스트가 예산 항목이라는 판단은 맞았다** — 그 테스트가 조립·전송까지는
통과하는데 실제 적중이 0 이라는 것을 실측이 잡아냈다. 조립이 맞아도 게이트웨이가 버리면
같은 결과이므로, 예산은 `completions.jsonl` 의 `cacheReadTokens` 로만 확정할 수 있다.

## 12. 담당자 통보

| 대상 | 내용 |
| --- | --- |
| 기획·백엔드 | 티켓 S15P11B106-80 AC 가 계약과 다르다. "벡터DB 임베딩 및 검색"은 계약에 없다 (Spring 이 목차를 주고 AI 가 고른다). "권한 기반 검색"도 AI 몫이 아니다 — 요청이 권한 통과분만 실어 온다. 필드명은 `answerSources` 가 아니라 `sources` |
| 프론트 | `sources` 가 모델 자기 신고 기반이라는 한계 (§5.1). 기계 검증이 필요하면 각주 표기 형식을 계약에 추가해야 한다 |

계약 자체를 바꿀 필요는 없다. 티켓 설명이 계약보다 먼저 쓰인 것으로 보이며, 정본은 계약이다
(`ai/CLAUDE.md`).
