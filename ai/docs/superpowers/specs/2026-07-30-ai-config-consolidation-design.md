# AI 서버 설정 일원화와 OpenAI 런타임 경로 — 설계

**티켓** S15P11B106-155
**브랜치** `feature/S15P11B106-155-ai-config-consolidation`
**선행** S15P11B106-143 (`AI_RUNTIME` 환경변수와 기동 가드, MR !95 머지 완료)

## 1. 왜 지금 하나

프론트에서 실제 AI 를 쓰는 것이 최우선인데 배포 런타임(`deepagents`)을 돌릴 키가 없다.
GMS 게이트웨이의 Anthropic 키가 소진 상태다.

가진 것은 개인 OpenAI 키다. **품질 측정이 아니라 배선이 끝까지 도는지** 확인하는 용도다 —
Spring 이 요청을 보내고, 에이전트가 MCP 툴을 부르고, 위키가 나오고, Spring 이 반영하는 흐름.
그것이 확인되면 GMS 키가 생겼을 때 모델 이름만 바꿔 끼우면 된다.

그런데 지금은 **모델과 키를 바꾸는 데 코드 수정이 필요하다.**

## 2. 문제

### 2.1 설정 읽는 방법이 세 갈래다

| 방법 | 어디서 | 읽는 것 |
| --- | --- | --- |
| pydantic `Settings` | `wiki_mcp/config.py` | `.env` 의 `WORKSPACE_PATH`·`APP_URL` |
| argparse | `serve.py`·`local_server.py` | `--port`·`--runtime`·`--root` 등 |
| 날 `os.environ` | `serve.py`·`deep_agents.py`, LangChain 내부 | `INTERNAL_API_KEY`·`AI_RUNTIME`·`AI_MODEL_*`·`ANTHROPIC_API_KEY` |

`.env` 는 첫 번째에만 연결돼 있다. `Settings` 가 선언한 두 필드 외에는 `extra="ignore"` 로
버리고, 그 값들은 파이썬 객체로만 쓰여 `os.environ` 에 올라가지 않는다.

**그래서 `.env` 에 API 키를 넣어도 조용히 무시된다.** LangChain 은 `os.environ` 을 본다.

`.env.example` 도 그것을 인정하고 변수마다 예외를 달아 두고 있다 — "이 변수는 `.env` 가
아니라 프로세스 환경 또는 `--internal-api-key` 로 준다".

`python-dotenv` 가 의존성에 있는데 `load_dotenv` 를 부르는 곳은 없다. 붙이려다 만 흔적이다.

**왜 이렇게 됐나.** `config.py` 는 이식해 온 코드(lucas-llmwiki)에서 왔고 워크스페이스 설정만
담당했다. 나중에 붙은 서버·런타임 설정은 그것을 쓰지 않고 각자 `os.environ` 을 봤다.
아무도 합치지 않았다.

### 2.2 모델이 코드에 박혀 있다

```python
# deep_agents.py
DEFAULT_TIER_MODELS = {FAST: "anthropic:claude-haiku-4-5-20251001",
                       QUALITY: "anthropic:claude-sonnet-4-6"}
def __init__(self, model: str = "anthropic:claude-opus-4-6")
```

`--model` 은 생성자만 바꾼다. 티어 경로(`complete`)는 여전히 anthropic 을 부른다.
`langchain-openai` 도 의존성에 없다.

## 3. 원칙 — 설정은 가장자리에서 읽고 인자로 내린다

`load_dotenv` 로 `.env` 를 `os.environ` 에 올리는 방법은 **쓰지 않는다.**

그것은 전역 가변 상태를 만든다. 설정이 "어디서든 꺼내 쓸 수 있는 공기" 가 되고, 그것이
지금 병의 원인이다 — 아무 데서나 `os.environ` 을 봐서 출처가 셋으로 갈렸다. 채널을 그대로
두고 물만 더 붓는 셈이다.

대신:

* **설정을 한 번, 한 곳에서 읽는다.** 타입 있는 객체로 만든다
* **그 객체를 필요한 곳에 인자로 넘긴다.** 안쪽 코드는 환경을 모른다
* 우선순위는 `초기화 인자 > 환경변수 > .env > 기본값` 이다. **pydantic-settings 가 이미
  그 순서로 동작한다** — 우리가 만들려던 규칙이 그 라이브러리의 기본 동작이라 `load_dotenv`
  없이 그대로 나온다

의존 방향은 그대로다. `wiki_api → agent_runtime → wiki_mcp`, 설정도 그 방향으로만 흐른다.

## 4. 설계

### 4.1 `wiki_api/settings.py` — 서버 설정 한 곳

pydantic-settings `BaseSettings` 하나. 서버·런타임 설정을 **타입·기본값·설명과 함께**
선언한다.

```python
class ServerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=_ENV_FILE, extra="ignore")

    internal_api_key: str = Field("", validation_alias="INTERNAL_API_KEY")
    backend_base_url: str = Field("", validation_alias="BACKEND_BASE_URL")

    runtime: str = Field("claude-code", validation_alias="AI_RUNTIME")
    model: str | None = Field(None, validation_alias="AI_MODEL")
    model_fast: str | None = Field(None, validation_alias="AI_MODEL_FAST")
    model_quality: str | None = Field(None, validation_alias="AI_MODEL_QUALITY")

    anthropic_api_key: str = Field("", validation_alias="ANTHROPIC_API_KEY")
    anthropic_base_url: str = Field("", validation_alias="ANTHROPIC_BASE_URL")
    openai_api_key: str = Field("", validation_alias="OPENAI_API_KEY")
    openai_base_url: str = Field("", validation_alias="OPENAI_BASE_URL")
```

**환경변수 이름은 지금 것을 그대로 쓴다.** 배포 설정과 `.env` 를 깨지 않는다. 필드 이름은
파이썬 관례를 따르고 `validation_alias` 로 잇는다.

**`env_file` 경로는 `wiki_mcp/config.py` 와 같은 `src/.env` 다.** 파일 하나가 두 층에
읽히는 것이고, 각 층은 자기 필드만 선언한다. 다른 층의 변수는 `extra="ignore"` 로 통과한다.

`runtime` 값 검증은 여기서 한다 — `RUNTIMES` 밖의 값이면 validator 가 즉시 터진다.
`serve.py` 의 `_runtime_from_environment` 가 하던 일이 이리로 온다. **오타가 조용히 통과해
배포에서 첫 요청에 실패하는 것을 막는다는 목적은 그대로다.**

### 4.2 `serve.py` — 조립 지점

여기가 유일하게 설정을 읽는 곳이다.

```python
args = build_parser().parse_args()
settings = ServerSettings(**cli_overrides(args))   # CLI 가 최우선
assert_runtime_is_usable(settings.runtime)
app = create_app(api_key=settings.internal_api_key,
                 backend_base_url=settings.backend_base_url)
app.state.runtime = load_runtime(settings.runtime, settings.model, ...)
```

`cli_overrides` 는 `None` 이 아닌 CLI 인자만 담은 딕셔너리를 만든다. pydantic-settings 는
초기화 인자를 최우선으로 치므로 **우선순위가 그것으로 성립한다.** 별도 병합 코드가 없다.

**순서 함정 하나를 여기서 없앤다.** 지금 `--runtime` 의 기본값이
`_runtime_from_environment()` 호출이라 **파서를 만드는 시점에** 환경을 읽는다. 설정 읽기가
그 뒤로 가면 `.env` 의 `AI_RUNTIME` 이 또 무시된다 — 고치려는 것과 똑같은 함정이다.
그래서 argparse 기본값을 전부 `None` 으로 두고 병합을 설정 객체에 맡긴다. **파서는 환경을
읽지 않는다.**

### 4.3 `deep_agents.py` — 환경을 보지 않는다

티어 모델과 자격증명을 **생성자 인자**로 받는다.

```python
class DeepAgentsRuntime:
    def __init__(self, model: str, *, fast_model: str | None = None,
                 quality_model: str | None = None,
                 credentials: dict[str, tuple[str, str]] | None = None):
```

`os.environ.get(TIER_ENV[tier])` 가 사라진다. `DEFAULT_TIER_MODELS` 는 인자가 없을 때의
기본값으로 남는다 — GMS 실측으로 확인된 이름이라는 근거는 유지한다.

**최초 설계는 `api_key: str = ""`·`base_url: str = ""` 단일 쌍이었다.** 리뷰에서 티어
모델이 에이전트 모델과 다른 프로바이더일 수 있다는 것이 지적돼(4.4) 프로바이더별 표로
바꿨다 — 근거와 함정 재현은 4.4 에 있다.

모델 키는 `init_chat_model(..., api_key=..., base_url=...)` 로 **명시해 넘긴다.**
LangChain 이 환경변수를 읽게 두지 않는다. 문서 확인: `init_chat_model` 은 kwargs 를
프로바이더 생성자로 그대로 넘기고, `ChatOpenAI`·`ChatAnthropic` 둘 다 두 인자를 받는다.
어느 프로바이더의 값을 넘길지는 `_credential_kwargs(model)` 이 **그 호출의 모델**
접두사로 표에서 조회해 정한다 — `complete()` 는 티어 모델로, `_run` 은 에이전트 모델로
부른다.

빈 문자열은 넘기지 않는다 — 넘기면 SDK 가 "빈 키" 로 읽어 환경변수 폴백조차 막는다.
값이 있을 때만 kwargs 에 넣는다.

**얻는 것:** 테스트에서 `os.environ` 을 몽키패치할 필요가 없다. 인자로 준다.

### 4.4 자격증명 고르기 — **모델 하나가 아니라 표로 고른다** (리뷰 이후 수정)

최초 설계는 에이전트 모델(`settings.model`) 하나의 접두사로 자격증명 **한 쌍**을 골라
그것을 에이전트 경로와 `complete()` 티어 경로에 동일하게 넘기는 것이었다.

```python
def credentials_for(model: str, settings: ServerSettings) -> tuple[str, str]:
    """모델 문자열의 프로바이더 접두사로 (api_key, base_url) 을 고른다."""
```

**이것이 최종 리뷰에서 벤더 교차 버그로 지적됐다.** `AI_MODEL_FAST`·`AI_MODEL_QUALITY`
(티어 모델)는 에이전트 모델과 다른 프로바이더일 수 있다 — 예: 에이전트는
`anthropic:claude-opus-4-6`, fast 티어는 `openai:gpt-5.4-mini`. 에이전트 모델 기준으로
고른 자격증명 하나를 두 경로에 그대로 쓰면 **Anthropic 키가 OpenAI 클라이언트로 간다**
(벤더가 다른 키를 받으므로 보안 문제이기도 하다). 더 나쁜 경우: `AI_MODEL` 을 비우고
티어만 지정하면 `credentials_for(None, settings)` 가 접두사를 못 찾아 자격증명이
통째로 빈다 — 그런데 그 형태(§5 의 전환 예시, `AI_MODEL` 없이 `AI_MODEL_FAST`/
`AI_MODEL_QUALITY`만 쓰는 것)가 이 티켓이 실제로 쓰려는 구성이다.

그래서 자격증명을 **모델별로** 고르도록 바꿨다. `credentials_for(model, settings)` 는
"모델 하나 → 자격증명 하나"라는 더 단순한 질문에 여전히 유효해 남겨 뒀지만(테스트에서도
씀), `serve.py` 는 더 이상 이것을 쓰지 않는다. 대신:

```python
def credential_table(settings: ServerSettings) -> dict[str, tuple[str, str]]:
    """provider -> (api_key, base_url). 값이 하나도 없는 프로바이더는 뺀다."""
```

이 표를 `load_runtime` 에 그대로 넘긴다. `DeepAgentsRuntime` 이 호출할 모델(에이전트
모델 또는 그때그때의 티어 모델)의 접두사로 이 표에서 자기 몫을 조회한다 —
`_credential_kwargs(self)` 가 `_credential_kwargs(self, model)` 로 바뀌었고,
`complete()` 는 티어 모델로, `_run`(에이전트 경로)은 에이전트 모델로 이 함수를 부른다.

`anthropic:` → Anthropic 것, `openai:` → OpenAI 것. 모르는 접두사는 빈 값을 돌려주고
SDK 의 기본 동작에 맡긴다 — 여기서 프로바이더 목록을 관리하지 않는다. 표는 평범한
`dict` 다 — `agent_runtime` 이 `wiki_api`(`ServerSettings`)를 임포트하지 않아도 되게
하기 위해서다. 의존 방향 `wiki_api → agent_runtime → wiki_mcp` 는 그대로다.

### 4.5 `load_runtime` — 설정을 그대로 통과시킨다 (리뷰 이후 수정)

```python
def load_runtime(name, model=None, *, effort=None, fast_model=None,
                 quality_model=None,
                 credentials: dict[str, tuple[str, str]] | None = None) -> Runtime
```

단일 `api_key`/`base_url` 대신 4.4 의 `credential_table` 이 만든 표를 받는다.

`claude-code` 는 모델 자격증명이 필요 없다 — CLI 가 로그인 세션으로 과금한다. 그 경로에
`credentials` 가 (비어 있지 않게) 오면 **거부한다.** 조용히 무시하면 "키를 줬으니 그
키로 돌겠지" 라는 오해가 남고, 그것이 143 에서 `effort` 로 이미 겪은 실수다.

### 4.6 손대지 않는 것

`wiki_mcp/config.py` 는 그대로 둔다. MCP 서버는 **별도 프로세스**이고 자기 조립 지점
(`local_server.main`)을 갖는다. 서버 설정까지 들고 있을 이유가 없고, 흡수하면 `wiki_mcp` 가
위층을 아는 꼴이라 의존 방향이 깨진다.

`local_server.py` 는 `os.environ` 을 읽지 않는다. 손댈 것이 없다.

## 5. 함께 하는 것

* `pyproject.toml` deepagents extra 에 `langchain-openai` 추가
* `.env.example` 재작성. **변수마다 붙은 "이건 `.env` 로 안 된다" 예외가 통째로 사라진다.**
  우선순위 규칙 한 줄과 변수 목록만 남는다. OpenAI 항목(`OPENAI_API_KEY`·`OPENAI_BASE_URL`)과
  티어 변수 예시를 넣는다

전환이 이 정도로 끝나는 것이 목표다. **코드 수정 0.**

```
# 지금 (OpenAI, 배선 확인)
AI_MODEL_FAST=openai:<모델>
AI_MODEL_QUALITY=openai:<모델>
OPENAI_API_KEY=...

# 나중 (GMS, 실제 운영)
AI_MODEL_FAST=anthropic:claude-haiku-4-5-20251001
AI_MODEL_QUALITY=anthropic:claude-sonnet-4-6
ANTHROPIC_API_KEY=...
ANTHROPIC_BASE_URL=https://gms.ssafy.io/gmsapi/api.anthropic.com
```

## 6. 주의

**`python-dotenv` 는 남는다.** pydantic-settings 가 `env_file` 을 읽는 데 쓴다. 다만
deepagents extra 가 아니라 기본 의존성 자리가 맞다 — `wiki_mcp/config.py` 가 이미 `.env` 를
읽으므로 CLI 경로에서도 필요하다.

**`.env` 는 커밋하지 않는다.** 지금 권한이 `600` 이고 `.gitignore` 에 있다. 그대로 둔다.

**OpenAI 로 잰 수치를 `experiments/INDEX.md` 에 넣지 않는다.** 모델도 하네스도 다르면
비교가 깨진다 (`INDEX.md` 의 대조 규칙). 배선 확인 기록은 따로 남긴다.

**API 키가 로그·예외 메시지에 남지 않게 한다.** 설정 객체를 통째로 로그에 찍는 코드를
만들지 않는다.

## 7. 검증

| 무엇 | 확인 |
| --- | --- |
| `.env` 만으로 뜬다 | 지금은 무시되는 값이다. 이것이 이 티켓의 핵심 |
| 환경변수가 `.env` 를 이긴다 | |
| CLI 플래그가 둘 다 이긴다 | |
| 잘못된 `AI_RUNTIME` | 기동 시점에 터진다. 첫 요청까지 미루지 않는다 |
| `claude-code` + 모델 키 | 거부한다 |
| 티어 모델 인자 | `os.environ` 없이 `complete` 가 그 모델을 부른다 |
| 빈 자격증명 | kwargs 에 넣지 않는다 |
| 기존 동작 | `uv run pytest -m "not ocr"` 기존 실패 0 |

마지막으로 **OpenAI 로 문서 1건을 실제로 돌린다.** `AI_MODEL_*` 만 바꿔서. 단위 테스트가
아니라 실기동이다 — 이 티켓의 목적이 그것이다.

## 8. 범위 밖

| 항목 | 이유 |
| --- | --- |
| 품질 측정 | 이 티켓은 배선 확인용이다 |
| 창구(federated) 경로 | S15P11B106-151·152·154 |
| `wiki_mcp/config.py` 흡수 | 계층이 흐려진다 (4.6) |
| 백엔드·프론트 변경 | `ai/` 안에서 끝난다 |
| `experiments/` 의 설정 경로 | `backend_sim.py` 는 측정용 별도 진입점이다. 필요해지면 별건 |

## 9. 관련

* 티켓: https://ssafy.atlassian.net/browse/S15P11B106-155
* 선행 구현: `serve.py` 의 `_runtime_from_environment`·`assert_runtime_is_usable` (S15P11B106-143)
* 근거 문서: LangChain `init_chat_model` 이 kwargs 를 프로바이더 생성자로 넘긴다 (Context7, 2026-07-30)
