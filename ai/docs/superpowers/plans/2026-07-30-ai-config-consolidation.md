# AI 서버 설정 일원화 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 설정을 가장자리에서 한 번 읽어 인자로 내리고, `.env` 만 바꿔 OpenAI·Anthropic 을 갈아끼울 수 있게 한다.

**Architecture:** `wiki_api/settings.py` 의 pydantic-settings 객체가 유일한 설정 출처다. `serve.py` 가 조립 지점이고 CLI 인자를 초기화 인자로 얹어 우선순위를 만든다. `deep_agents.py` 는 `os.environ` 을 보지 않고 모델·자격증명을 생성자로 받는다. 모델 키는 `init_chat_model` 에 명시해 넘긴다.

**Tech Stack:** Python 3.12 · uv · pydantic-settings · FastAPI · LangChain(`init_chat_model`) · pytest

**설계 문서:** `ai/docs/superpowers/specs/2026-07-30-ai-config-consolidation-design.md`

## Global Constraints

- **`load_dotenv` 를 쓰지 않는다.** `.env` 는 pydantic-settings 의 `env_file` 로만 읽는다. `os.environ` 에 값을 올리는 코드를 만들지 않는다.
- **우선순위는 `초기화 인자 > 환경변수 > .env > 기본값`.** pydantic-settings 의 기본 동작이고 그것을 그대로 쓴다. 별도 병합 코드를 만들지 않는다.
- **환경변수 이름을 바꾸지 않는다.** `INTERNAL_API_KEY`·`BACKEND_BASE_URL`·`AI_RUNTIME`·`AI_MODEL_FAST`·`AI_MODEL_QUALITY`·`ANTHROPIC_API_KEY`·`ANTHROPIC_BASE_URL`. 배포 설정과 기존 `.env` 를 깨뜨린다.
- **`wiki_mcp/config.py` 와 `wiki_mcp/local_server.py` 를 건드리지 않는다.** MCP 서버는 별도 프로세스이고 자기 조립 지점을 갖는다.
- **API 키를 로그·예외 메시지에 넣지 않는다.** 설정 객체를 통째로 찍는 코드를 만들지 않는다.
- `.env` 파일 자체를 커밋하지 않는다. `src/.env.example` 만 고친다.
- 수정 범위는 `ai/` 뿐이다. 커밋 전 `git status` 로 확인한다.
- 커밋 메시지에 Jira 키를 넣지 않는다 (`docs/conventions/git-convention.md`). `Co-Authored-By` 트레일러도 붙이지 않는다.
- 테스트는 `cd ai && uv run pytest -m "not ocr"`.

---

## File Structure

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `ai/src/wiki_api/settings.py` | 서버 설정 선언 한 곳 + 프로바이더별 자격증명 선택 | 신규 |
| `ai/src/agent_runtime/deep_agents.py` | 티어 모델·자격증명을 인자로 받는다. `os.environ` 제거 | 수정 |
| `ai/src/agent_runtime/__init__.py` | `load_runtime` 이 설정을 통과시킨다 | 수정 |
| `ai/src/wiki_api/serve.py` | 조립 지점. 파서가 환경을 읽지 않는다 | 수정 |
| `ai/pyproject.toml` | `langchain-openai` 추가 | 수정 |
| `ai/src/.env.example` | 재작성 | 수정 |
| `ai/tests/api/test_settings.py` | 설정 우선순위·검증 | 신규 |
| `ai/tests/api/test_serve.py` | 조립 지점 배선 | 수정 |
| `ai/tests/api/test_deepagents_complete.py` | 인자 주입 | 수정 |

순서: 1 설정 → 2 deepagents → 3 load_runtime → 4 serve → 5 문서·의존성 → 6 실기동.

---

### Task 1: `wiki_api/settings.py` — 설정 선언 한 곳

**Files:**
- Create: `ai/src/wiki_api/settings.py`
- Test: `ai/tests/api/test_settings.py`

**Interfaces:**
- Consumes: 없음 (최하단)
- Produces: `ServerSettings` (pydantic `BaseSettings`), 필드 `internal_api_key`·`backend_base_url`·`runtime`·`model`·`model_fast`·`model_quality`·`anthropic_api_key`·`anthropic_base_url`·`openai_api_key`·`openai_base_url`; 상수 `RUNTIMES`; 함수 `credentials_for(model: str | None, settings: ServerSettings) -> tuple[str, str]`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/api/test_settings.py` 를 새로 만든다.

```python
"""서버 설정의 우선순위와 검증.

`.env` 에 넣은 값이 조용히 무시되던 것이 이 모듈이 생긴 이유다. LangChain 은
`os.environ` 을 보는데 `.env` 는 pydantic Settings 의 선언된 필드로만 들어갔다.
"""

import pytest

from wiki_api.settings import RUNTIMES, ServerSettings, credentials_for


def _write_env(tmp_path, body: str):
    path = tmp_path / ".env"
    path.write_text(body, encoding="utf-8")
    return path


def test_reads_values_from_the_env_file(tmp_path):
    env = _write_env(tmp_path, "OPENAI_API_KEY=from-file\nAI_RUNTIME=deepagents\n")

    settings = ServerSettings(_env_file=env)

    assert settings.openai_api_key == "from-file"
    assert settings.runtime == "deepagents"


def test_process_environment_beats_the_env_file(tmp_path, monkeypatch):
    env = _write_env(tmp_path, "OPENAI_API_KEY=from-file\n")
    monkeypatch.setenv("OPENAI_API_KEY", "from-environment")

    assert ServerSettings(_env_file=env).openai_api_key == "from-environment"


def test_explicit_argument_beats_everything(tmp_path, monkeypatch):
    env = _write_env(tmp_path, "OPENAI_API_KEY=from-file\n")
    monkeypatch.setenv("OPENAI_API_KEY", "from-environment")

    assert ServerSettings(_env_file=env, openai_api_key="explicit").openai_api_key == "explicit"


def test_defaults_to_the_claude_code_runtime(tmp_path):
    assert ServerSettings(_env_file=_write_env(tmp_path, "")).runtime == "claude-code"


def test_rejects_an_unknown_runtime_at_construction(tmp_path):
    """오타가 조용히 통과하면 배포가 뜨고 첫 요청에서 실패한다."""
    env = _write_env(tmp_path, "AI_RUNTIME=deepagent\n")

    with pytest.raises(ValueError) as excinfo:
        ServerSettings(_env_file=env)
    assert "deepagent" in str(excinfo.value)
    for name in RUNTIMES:
        assert name in str(excinfo.value)


def test_unrelated_env_entries_are_ignored(tmp_path):
    """같은 `.env` 를 `wiki_mcp/config.py` 도 읽는다. 남의 변수로 터지면 안 된다."""
    env = _write_env(tmp_path, "WORKSPACE_PATH=/tmp/ws\nAPP_URL=http://x\n")

    assert ServerSettings(_env_file=env).runtime == "claude-code"


@pytest.mark.parametrize("model,expected", [
    ("anthropic:claude-opus-4-6", ("anthropic-key", "https://anthropic.example")),
    ("openai:some-model", ("openai-key", "https://openai.example")),
    ("mistral:whatever", ("", "")),
    (None, ("", "")),
])
def test_credentials_are_chosen_by_provider_prefix(tmp_path, model, expected):
    settings = ServerSettings(
        _env_file=_write_env(tmp_path, ""),
        anthropic_api_key="anthropic-key", anthropic_base_url="https://anthropic.example",
        openai_api_key="openai-key", openai_base_url="https://openai.example")

    assert credentials_for(model, settings) == expected
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd ai && uv run pytest tests/api/test_settings.py -q`
Expected: `ModuleNotFoundError: No module named 'wiki_api.settings'`

- [ ] **Step 3: 모듈을 만든다**

`ai/src/wiki_api/settings.py`:

```python
"""서버 설정 한 곳.

**설정은 가장자리에서 한 번 읽고 인자로 내린다.** 안쪽 코드가 `os.environ` 을 보지
않는다 — 그렇게 하면 설정 출처가 갈리고, 어디에 넣어야 먹는지 아무도 모르게 된다.
실제로 그 상태였다: `.env` 에 API 키를 넣어도 조용히 무시됐다.

우선순위는 `초기화 인자 > 환경변수 > .env > 기본값` 이고 **pydantic-settings 의 기본
동작이 그것이다.** `load_dotenv` 로 `os.environ` 에 올리지 않는다 — 전역 가변 상태를
만들고, 그것이 애초의 문제다.

`.env` 파일은 `wiki_mcp/config.py` 와 같은 `src/.env` 다. 파일 하나를 두 층이 읽고
각 층은 자기 필드만 선언한다. 남의 변수는 `extra="ignore"` 로 통과한다.
"""

from __future__ import annotations

from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_ENV_FILE = Path(__file__).resolve().parent.parent / ".env"

RUNTIMES = ("claude-code", "deepagents")


class ServerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=str(_ENV_FILE), extra="ignore")

    internal_api_key: str = Field("", validation_alias="INTERNAL_API_KEY")
    backend_base_url: str = Field("", validation_alias="BACKEND_BASE_URL")

    runtime: str = Field("claude-code", validation_alias="AI_RUNTIME")
    # 정확한 이름을 쓴다 (`anthropic:claude-opus-4-6`). 별칭은 시점에 따라 다른 모델로
    # 해석돼 두 측정의 비교를 조용히 깨뜨린다.
    model: str | None = Field(None, validation_alias="AI_MODEL")
    model_fast: str | None = Field(None, validation_alias="AI_MODEL_FAST")
    model_quality: str | None = Field(None, validation_alias="AI_MODEL_QUALITY")

    anthropic_api_key: str = Field("", validation_alias="ANTHROPIC_API_KEY")
    anthropic_base_url: str = Field("", validation_alias="ANTHROPIC_BASE_URL")
    openai_api_key: str = Field("", validation_alias="OPENAI_API_KEY")
    openai_base_url: str = Field("", validation_alias="OPENAI_BASE_URL")

    @field_validator("runtime")
    @classmethod
    def _known_runtime(cls, value: str) -> str:
        """오타를 여기서 막는다.

        `argparse` 의 `choices` 는 기본값을 검사하지 않아 `AI_RUNTIME=deepagent` 가
        조용히 통과했다. 그러면 `deepagents` 로 뜬 줄 알고 배포한 채 첫 변환 요청에서
        실패한다.
        """
        if value not in RUNTIMES:
            raise ValueError(
                f"AI_RUNTIME 값이 올바르지 않다: {value!r} — "
                f"{', '.join(RUNTIMES)} 중 하나여야 한다")
        return value


# 프로바이더 접두사 → 설정 필드 이름. 여기서 프로바이더 목록을 관리하지 않는다 —
# 모르는 접두사는 빈 값을 돌려주고 SDK 의 기본 동작에 맡긴다.
_CREDENTIAL_FIELDS = {
    "anthropic": ("anthropic_api_key", "anthropic_base_url"),
    "openai": ("openai_api_key", "openai_base_url"),
}


def credentials_for(model: str | None, settings: ServerSettings) -> tuple[str, str]:
    """모델 문자열의 `provider:name` 접두사로 `(api_key, base_url)` 을 고른다.

    빈 값을 그대로 돌려주는 것이 의도다. 호출자가 빈 값을 SDK 에 넘기지 않는다 —
    빈 문자열을 넘기면 SDK 가 「빈 키」로 읽어 자기 폴백조차 막는다.
    """
    provider = (model or "").split(":", 1)[0]
    fields = _CREDENTIAL_FIELDS.get(provider)
    if fields is None:
        return "", ""
    return getattr(settings, fields[0]), getattr(settings, fields[1])
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `cd ai && uv run pytest tests/api/test_settings.py -q`
Expected: PASS

- [ ] **Step 5: 커밋한다**

```bash
git add ai/src/wiki_api/settings.py ai/tests/api/test_settings.py
git commit -m "feat(ai): 서버 설정을 한 곳에서 선언한다

.env 에 API 키를 넣어도 조용히 무시됐다. LangChain 은 os.environ 을 보는데
.env 는 pydantic Settings 의 선언된 필드로만 들어갔기 때문이다.

우선순위는 초기화 인자 > 환경변수 > .env > 기본값이고 pydantic-settings 의
기본 동작을 그대로 쓴다. load_dotenv 로 os.environ 에 올리지 않는다 -
전역 가변 상태를 만들고 그것이 애초의 문제다.

환경변수 이름은 기존 것을 그대로 쓴다. 배포 설정을 깨뜨리지 않는다."
```

---

### Task 2: `deep_agents.py` — 환경을 보지 않는다

**Files:**
- Modify: `ai/src/agent_runtime/deep_agents.py`
- Modify: `ai/pyproject.toml`
- Test: `ai/tests/api/test_deepagents_complete.py`

**Interfaces:**
- Consumes: 없음 (설정 객체를 임포트하지 않는다 — 값만 받는다)
- Produces: `DeepAgentsRuntime(model: str = DEFAULT, *, fast_model: str | None = None, quality_model: str | None = None, api_key: str = "", base_url: str = "")`

**`DeepAgentsRuntime()` 의 인자 없는 호출을 유지해야 한다.** 기존 테스트가 그렇게 부른다 (`tests/api/test_deepagents_complete.py`).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/api/test_deepagents_complete.py` 의 클래스·모듈 끝에 추가한다. 기존 테스트는 건드리지 않는다.

```python
def test_tier_models_come_from_constructor_arguments():
    """`os.environ` 을 몽키패치하지 않고 티어 모델을 정할 수 있어야 한다."""
    from agent_runtime.deep_agents import FAST, QUALITY, DeepAgentsRuntime

    runtime = DeepAgentsRuntime(fast_model="openai:fast-x",
                                quality_model="openai:quality-y")

    assert runtime._model_for(FAST) == "openai:fast-x"
    assert runtime._model_for(QUALITY) == "openai:quality-y"


def test_tier_models_fall_back_to_the_measured_defaults():
    from agent_runtime.deep_agents import DEFAULT_TIER_MODELS, FAST, DeepAgentsRuntime

    assert DeepAgentsRuntime()._model_for(FAST) == DEFAULT_TIER_MODELS[FAST]


def test_credentials_are_passed_to_the_model_explicitly(monkeypatch):
    """LangChain 이 `os.environ` 을 읽게 두지 않는다. 인자로 넘긴다."""
    from agent_runtime.deep_agents import FAST, DeepAgentsRuntime

    seen = {}

    def _fake_init_chat_model(name, **kwargs):
        seen["name"] = name
        seen["kwargs"] = kwargs
        raise RuntimeError("stop here — 인자만 확인한다")

    monkeypatch.setattr("langchain.chat_models.init_chat_model", _fake_init_chat_model)

    runtime = DeepAgentsRuntime(fast_model="openai:fast-x",
                                api_key="key-1", base_url="https://gw.example")
    with pytest.raises(RuntimeError):
        runtime.complete([{"role": "user", "content": "안녕"}], tier=FAST, timeout=5)

    assert seen["name"] == "openai:fast-x"
    assert seen["kwargs"]["api_key"] == "key-1"
    assert seen["kwargs"]["base_url"] == "https://gw.example"


def test_empty_credentials_are_not_passed(monkeypatch):
    """빈 문자열을 넘기면 SDK 가 「빈 키」로 읽어 자기 폴백조차 막는다."""
    from agent_runtime.deep_agents import FAST, DeepAgentsRuntime

    seen = {}

    def _fake_init_chat_model(name, **kwargs):
        seen.update(kwargs)
        raise RuntimeError("stop here")

    monkeypatch.setattr("langchain.chat_models.init_chat_model", _fake_init_chat_model)

    with pytest.raises(RuntimeError):
        DeepAgentsRuntime().complete([{"role": "user", "content": "안녕"}],
                                     tier=FAST, timeout=5)

    assert "api_key" not in seen
    assert "base_url" not in seen
```

파일 상단에 `import pytest` 가 없으면 추가한다.

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd ai && uv run pytest tests/api/test_deepagents_complete.py -q`
Expected: FAIL — `DeepAgentsRuntime()` 이 `fast_model` 인자를 받지 못한다 (`TypeError`).

deepagents extra 가 없어 스킵되면 먼저 설치한다: `cd ai && uv sync --extra deepagents`

- [ ] **Step 3: 생성자와 모델 조립을 고친다**

`DeepAgentsRuntime.__init__` 를 바꾼다.

```python
    def __init__(self, model: str = "anthropic:claude-opus-4-6", *,
                 fast_model: str | None = None, quality_model: str | None = None,
                 api_key: str = "", base_url: str = ""):
        """모델과 자격증명을 **인자로 받는다.** `os.environ` 을 읽지 않는다.

        읽던 시절에는 이 클래스를 테스트하려면 환경변수를 몽키패치해야 했고, 어떤
        설정에 의존하는지가 시그니처에 드러나지 않았다. 설정을 고르는 일은 조립
        지점(`wiki_api/serve.py`)의 몫이다.
        """
        self.model = model
        self._tier_models = {FAST: fast_model, QUALITY: quality_model}
        self._api_key = api_key
        self._base_url = base_url
```

`_model_for` 에서 `os.environ` 을 걷어낸다.

```python
    def _model_for(self, tier: str) -> str:
        """tier → 모델 이름. 생성자 인자가 있으면 그것을 쓴다.

        모르는 tier 를 조용히 기본 모델로 떨어뜨리지 않는다 — 오타 하나가 측정을
        무의미하게 만드는 것보다 즉시 터지는 쪽이 낫다.
        """
        if tier not in DEFAULT_TIER_MODELS:
            raise ValueError(f"모르는 tier: {tier!r} (가능: {sorted(DEFAULT_TIER_MODELS)})")
        return self._tier_models.get(tier) or DEFAULT_TIER_MODELS[tier]
```

자격증명을 kwargs 로 만드는 헬퍼를 클래스에 넣는다.

```python
    def _credential_kwargs(self) -> dict:
        """값이 있을 때만 넣는다. 빈 문자열을 넘기면 SDK 가 「빈 키」로 읽어
        자기 폴백조차 막는다."""
        kwargs = {}
        if self._api_key:
            kwargs["api_key"] = self._api_key
        if self._base_url:
            kwargs["base_url"] = self._base_url
        return kwargs
```

`complete` 의 `init_chat_model` 호출에 얹는다.

```python
        model = init_chat_model(requested,
                                timeout=timeout or DEFAULT_COMPLETE_TIMEOUT,
                                max_retries=0,
                                **self._credential_kwargs())
```

`TIER_ENV` 상수와 그것을 쓰는 코드, 그리고 이 파일에서 더 이상 쓰이지 않는 `os` 임포트를 지운다. **`os` 가 다른 곳에서 쓰이면 남긴다 — 지우기 전에 파일 안에서 확인한다.**

에이전트 경로(`_run` 의 `create_deep_agent`)도 같은 자격증명을 써야 한다. 그 호출이 모델 문자열을 넘기고 있으면, `init_chat_model(self.model, **self._credential_kwargs())` 로 만든 인스턴스를 대신 넘긴다 — `create_deep_agent` 은 모델 인스턴스를 받는다 (LangChain 문서). **문자열만 넘기면 에이전트 경로에서 자격증명이 빠진다.**

- [ ] **Step 4: `langchain-openai` 를 의존성에 넣는다**

`ai/pyproject.toml` 의 deepagents extra 에 한 줄 추가한다. 기존 항목 순서를 유지한다.

```toml
    "langchain-openai",
```

Run: `cd ai && uv sync --extra deepagents`

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `cd ai && uv run pytest tests/api/test_deepagents_complete.py tests/api/test_prompt_cache.py -q`
Expected: PASS. 기존 테스트 포함 전부.

- [ ] **Step 6: 커밋한다**

```bash
git add ai/src/agent_runtime/deep_agents.py ai/pyproject.toml ai/uv.lock \
        ai/tests/api/test_deepagents_complete.py
git commit -m "feat(ai): deepagents 런타임이 모델과 키를 인자로 받는다

os.environ 을 읽던 것을 걷어낸다. 읽던 시절에는 이 클래스를 테스트하려면
환경변수를 몽키패치해야 했고, 어떤 설정에 의존하는지가 시그니처에 드러나지
않았다.

모델 키도 init_chat_model 에 명시해 넘긴다. 빈 값은 넣지 않는다 - 빈
문자열을 넘기면 SDK 가 빈 키로 읽어 자기 폴백조차 막는다.

OpenAI 로 돌 수 있게 langchain-openai 를 의존성에 넣는다."
```

---

### Task 3: `load_runtime` — 설정을 통과시킨다

**Files:**
- Modify: `ai/src/agent_runtime/__init__.py`
- Test: `ai/tests/runtime/test_runtime_guards.py`

**Interfaces:**
- Consumes: Task 2 의 `DeepAgentsRuntime(model, *, fast_model, quality_model, api_key, base_url)`
- Produces: `load_runtime(name, model=None, *, effort=None, fast_model=None, quality_model=None, api_key="", base_url="") -> Runtime`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/runtime/test_runtime_guards.py` 끝에 추가한다.

```python
def test_load_runtime_passes_model_settings_to_deepagents():
    from agent_runtime import load_runtime

    runtime = load_runtime("deepagents", "openai:main",
                           fast_model="openai:fast-x", quality_model="openai:quality-y",
                           api_key="key-1", base_url="https://gw.example")

    assert runtime.model == "openai:main"
    assert runtime._model_for("fast") == "openai:fast-x"


def test_load_runtime_rejects_model_credentials_for_claude_code():
    """CLI 는 로그인 세션으로 과금한다. 키를 받아 조용히 무시하면 그 키로 도는 줄 안다."""
    import pytest

    from agent_runtime import load_runtime

    with pytest.raises(ValueError):
        load_runtime("claude-code", api_key="key-1")
```

`"fast"` 가 실제 티어 상수 값인지 확인하고 다르면 `agent_runtime.deep_agents.FAST` 를 임포트해 쓴다.

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd ai && uv run pytest tests/runtime/test_runtime_guards.py -q`
Expected: FAIL — `load_runtime()` 이 `fast_model` 을 받지 못한다.

- [ ] **Step 3: 시그니처를 넓힌다**

```python
def load_runtime(name: str, model: str | None = None, *,
                 effort: str | None = None,
                 fast_model: str | None = None, quality_model: str | None = None,
                 api_key: str = "", base_url: str = "") -> Runtime:
```

`claude-code` 분기 앞에 거부를 넣는다.

```python
    if name == "claude-code":
        if api_key or base_url:
            raise ValueError(
                "claude-code 런타임은 로그인 세션으로 과금한다 — 모델 API 키를 "
                "넘기지 않는다. 그 키로 도는 줄 알게 된다")
        return ClaudeCodeRuntime(model=model or DEFAULT_CLI_MODEL, effort=effort)
```

`deepagents` 분기는 받은 값을 그대로 넘긴다.

```python
        return DeepAgentsRuntime(model=model or DEFAULT_DEEPAGENTS_MODEL,
                                 fast_model=fast_model, quality_model=quality_model,
                                 api_key=api_key, base_url=base_url)
```

기존 `effort` 거부는 그대로 둔다.

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `cd ai && uv run pytest tests/runtime -q`
Expected: PASS

- [ ] **Step 5: 커밋한다**

```bash
git add ai/src/agent_runtime/__init__.py ai/tests/runtime/test_runtime_guards.py
git commit -m "feat(ai): load_runtime 이 모델 설정을 런타임으로 통과시킨다

claude-code 에 모델 API 키를 넘기면 거부한다. 그 런타임은 로그인 세션으로
과금하므로 키를 받아 조용히 무시하면 그 키로 도는 줄 알게 된다 - effort 에서
이미 같은 실수를 했다."
```

---

### Task 4: `serve.py` — 조립 지점

**Files:**
- Modify: `ai/src/wiki_api/serve.py`
- Test: `ai/tests/api/test_serve.py`

**Interfaces:**
- Consumes: Task 1 의 `ServerSettings`·`credentials_for`·`RUNTIMES`, Task 3 의 `load_runtime`
- Produces: `settings_from_args(args) -> ServerSettings`, `build_app(settings)` (시그니처 변경)

**핵심:** argparse 기본값을 전부 `None` 으로 만들어 **파서가 환경을 읽지 않게** 한다. 지금 `--runtime` 의 기본값이 `_runtime_from_environment()` 호출이라 파서를 만드는 시점에 환경을 읽는다. 그것을 남겨두면 `.env` 의 `AI_RUNTIME` 이 또 무시된다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/api/test_serve.py` 에서 **기존 테스트 중 `resolve_api_key`·`_runtime_from_environment` 를 부르는 것들을 아래로 교체한다.** 그 함수들이 사라지기 때문이다. 나머지 테스트는 그대로 둔다.

```python
def test_the_parser_does_not_read_the_environment(monkeypatch):
    """파서 기본값이 환경을 읽으면 `.env` 병합이 그 뒤라 또 무시된다."""
    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagents")
    args = serve.build_parser().parse_args([])
    assert args.runtime is None
    assert args.internal_api_key is None


def test_settings_take_the_runtime_from_the_environment(monkeypatch):
    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagents")
    settings = serve.settings_from_args(serve.build_parser().parse_args([]))
    assert settings.runtime == "deepagents"


def test_the_command_line_beats_the_environment(monkeypatch):
    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagents")
    monkeypatch.setenv("INTERNAL_API_KEY", "from-env")
    args = serve.build_parser().parse_args(
        ["--runtime", "claude-code", "--internal-api-key", "explicit"])
    settings = serve.settings_from_args(args)

    assert settings.runtime == "claude-code"
    assert settings.internal_api_key == "explicit"


def test_build_app_takes_settings_and_injects_the_runtime():
    from wiki_api import serve
    from wiki_api.settings import ServerSettings

    settings = ServerSettings(runtime="claude-code", model="claude-opus-4-6",
                              internal_api_key="k")
    app = serve.build_app(settings)

    assert app.state.runtime.name == "claude-code"
    assert app.state.api_key == "k"
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd ai && uv run pytest tests/api/test_serve.py -q`
Expected: FAIL — `settings_from_args` 가 없다.

- [ ] **Step 3: 조립 지점으로 고친다**

`_runtime_from_environment`·`resolve_api_key`·`resolve_backend_base_url` 를 지운다. 런타임 값 검증은 `ServerSettings` 로 갔다.

파서의 기본값을 전부 `None` 으로 만든다. `--runtime` 의 `choices=RUNTIMES` 는 남기되 **`None` 이 기본이라 `choices` 검사에 걸리지 않는다.**

```python
def build_parser() -> argparse.ArgumentParser:
    """**파서는 환경을 읽지 않는다.** 기본값이 전부 `None` 이고 병합은 `ServerSettings`
    가 한다 — 그래야 `초기화 인자 > 환경변수 > .env > 기본값` 이 한 규칙으로 성립한다.

    이전 판본은 `--runtime` 의 기본값에서 `os.environ` 을 읽었다. 그 시점이 설정 로딩보다
    빨라서 `.env` 의 값이 무시됐다 — 이 티켓이 고치려는 것과 똑같은 함정이다.
    """
    parser = argparse.ArgumentParser(description="AJT AI 서버 (FastAPI 내부 API)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--runtime", default=None, choices=RUNTIMES,
                        help="기본은 claude-code. 배포는 deepagents 다 "
                             "(AI_RUNTIME 환경변수나 .env 로도 정할 수 있다)")
    parser.add_argument("--model", default=None,
                        help="정확한 이름을 쓴다 (anthropic:claude-opus-4-6). "
                             "별칭은 비교를 깬다")
    parser.add_argument("--internal-api-key", default=None,
                        help="기본은 INTERNAL_API_KEY (환경변수 또는 .env)")
    parser.add_argument("--backend-base-url", default=None,
                        help="Spring 의 Wiki 조회 창구 주소 (기본은 BACKEND_BASE_URL)")
    parser.add_argument("--log-level", default="info")
    return parser


# CLI 인자 이름 → 설정 필드 이름. 여기 없는 인자는 설정으로 가지 않는다
# (`--host`·`--port`·`--log-level` 은 uvicorn 것이다).
_CLI_TO_SETTING = {
    "runtime": "runtime", "model": "model",
    "internal_api_key": "internal_api_key", "backend_base_url": "backend_base_url",
}


def settings_from_args(args: argparse.Namespace) -> ServerSettings:
    """CLI 로 준 값만 초기화 인자로 얹는다.

    pydantic-settings 가 초기화 인자를 최우선으로 치므로 `CLI > 환경변수 > .env >
    기본값` 이 별도 병합 코드 없이 성립한다.
    """
    overrides = {field: getattr(args, name)
                 for name, field in _CLI_TO_SETTING.items()
                 if getattr(args, name) is not None}
    return ServerSettings(**overrides)
```

`build_app` 이 설정 객체를 받게 바꾼다.

```python
def build_app(settings: ServerSettings):
    assert_runtime_is_usable(settings.runtime)
    app = create_app(api_key=settings.internal_api_key,
                     backend_base_url=settings.backend_base_url)
    api_key, base_url = credentials_for(settings.model or "", settings)
    # 런타임은 프로세스 하나에 하나다. 요청마다 만들지 않는다 — 모델·설정이 요청 사이에
    # 흔들리면 두 측정의 비교가 조용히 깨진다.
    app.state.runtime = load_runtime(
        settings.runtime, settings.model,
        fast_model=settings.model_fast, quality_model=settings.model_quality,
        api_key=api_key, base_url=base_url)
    return app
```

**`claude-code` 는 자격증명을 받으면 거부한다** (Task 3). 그 런타임에서는 `credentials_for` 결과를 넘기지 않도록 분기한다 — `settings.runtime == "claude-code"` 면 `("", "")` 를 쓴다. 모델 문자열이 `anthropic:` 로 시작하고 `.env` 에 Anthropic 키가 있으면 그냥 넘겼을 때 기동이 죽는다.

`main()` 을 그에 맞춘다.

```python
def main() -> None:
    import uvicorn

    args = build_parser().parse_args()
    settings = settings_from_args(args)
    if not settings.internal_api_key:
        print("경고: 내부 API 키가 없다 — 모든 요청이 401 이다 "
              "(--internal-api-key 또는 INTERNAL_API_KEY)", file=sys.stderr)

    app = build_app(settings)
    print(f"AI 서버 — 런타임 {app.state.runtime.name} "
          f"({getattr(app.state.runtime, 'model', '?')}), "
          f"http://{args.host}:{args.port}")
    uvicorn.run(app, host=args.host, port=args.port, log_level=args.log_level)
```

`RUNTIMES` 는 `settings.py` 에서 임포트한다. `serve.py` 의 중복 정의를 지운다. 쓰이지 않게 된 `os` 임포트도 지운다 — **지우기 전에 파일 안에서 다른 용처가 없는지 확인한다.**

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `cd ai && uv run pytest tests/api -q`
Expected: PASS

- [ ] **Step 5: 커밋한다**

```bash
git add ai/src/wiki_api/serve.py ai/tests/api/test_serve.py
git commit -m "refactor(ai): serve 를 설정 조립 지점으로 바꾼다

argparse 기본값을 전부 None 으로 두고 병합을 ServerSettings 에 맡긴다.
이전에는 --runtime 의 기본값이 파서 생성 시점에 os.environ 을 읽어서
.env 의 AI_RUNTIME 이 무시됐다 - 이 작업이 고치려는 것과 같은 함정이다.

런타임 값 검증은 설정 객체로 옮겼다. build_app 이 설정 객체를 받는다."
```

---

### Task 5: `.env.example` 재작성

**Files:**
- Modify: `ai/src/.env.example`

지금 파일은 변수마다 "이 변수는 `.env` 가 아니라 프로세스 환경으로 준다" 는 예외를 달고 있다. **그 예외가 통째로 사라진다.**

- [ ] **Step 1: 다시 쓴다**

```
# ai 서버 환경변수 템플릿. 복사해서 같은 디렉터리에 .env 로 쓴다.
#   cp src/.env.example src/.env
#
# 우선순위: 명령줄 플래그 > 프로세스 환경변수 > 이 파일 > 코드의 기본값.
# 여기 적은 값은 전부 실제로 먹는다. 예외 없다.
#
# 이 파일은 커밋하지 않는다 (.env 만. .env.example 은 커밋한다).

# ---- 서버 -------------------------------------------------------------------
# 내부 API 인증 키. Spring 이 X-Internal-Api-Key 헤더로 보내는 값과 같아야 한다.
# 비어 있으면 /internal/v1 의 모든 요청이 401 이다 (deps.py).
INTERNAL_API_KEY=

# Spring 의 Wiki 조회 창구 주소. AI 가 호출자인 유일한 경로다.
# 비어 있으면 요청이 wikiCapability 를 실어 와도 창구를 쓰지 않고 요청 본문의
# selectedWikis 로 도는 과도기 push 경로다 (session.py 의 _federated).
BACKEND_BASE_URL=

# ---- 위키 저장소 -------------------------------------------------------------
# 작업 공간 루트. sources/·wiki/·.llmwiki/index.db 를 담는다.
# 프로세스 1개 = 작업 공간 1개 = 스코프 1개.
WORKSPACE_PATH=.

# 툴 응답의 "[View](...)" 딥링크를 만들 때만 쓴다. 에이전트가 이 주소를 호출하지는 않는다.
APP_URL=http://localhost:3000

# ---- 런타임 ------------------------------------------------------------------
# claude-code | deepagents. 기본은 claude-code 이고 그것은 로그인 세션으로 과금하므로
# 배포에서는 성립하지 않는다. 배포는 deepagents 다.
# 오타는 기동 시점에 터진다 — 조용히 기본값으로 떨어지지 않는다.
AI_RUNTIME=claude-code

# 에이전트 모델. 정확한 이름을 쓴다 — 별칭은 시점에 따라 다른 모델로 해석돼
# 두 측정의 비교를 조용히 깨뜨린다.
# AI_MODEL=anthropic:claude-opus-4-6

# 티어별 모델 (단발 호출용). 비우면 코드의 측정된 기본값을 쓴다.
# AI_MODEL_FAST=anthropic:claude-haiku-4-5-20251001
# AI_MODEL_QUALITY=anthropic:claude-sonnet-4-6

# ---- 모델 자격증명 (deepagents 런타임에만 필요하다) ----------------------------
# claude-code 는 로그인 세션으로 과금하므로 아래 값이 없어도 된다.
# 오히려 claude-code 로 뜨는 동안에는 이 키가 쓰이지 않는다.
#
# SSAFY GMS 게이트웨이는 키 하나로 Anthropic·OpenAI 를 모두 프록시한다.
# BASE_URL 패턴은 https://gms.ssafy.io/gmsapi/<업스트림 호스트>
#
# GMS 에서 확인된 Anthropic 모델 (2026-07-29):
#   claude-haiku-4-5-20251001   claude-sonnet-4-6
#   claude-sonnet-4-5-20250929  claude-opus-4-6
# 없는 것: claude-sonnet-5, claude-sonnet-4-5, claude-haiku-4-5, claude-opus-4-5
ANTHROPIC_API_KEY=
ANTHROPIC_BASE_URL=https://gms.ssafy.io/gmsapi/api.anthropic.com

# OpenAI 로 돌릴 때. 배선 확인용이고 품질 측정용이 아니다 —
# 모델이 다르면 비교가 깨진다 (experiments/INDEX.md 의 대조 규칙).
# 개인 키를 직접 쓸 때는 OPENAI_BASE_URL 을 비운다.
OPENAI_API_KEY=
OPENAI_BASE_URL=
```

- [ ] **Step 2: 실제 `.env` 가 깨지지 않았는지 확인한다**

Run: `cd ai && uv run python -c "from wiki_api.settings import ServerSettings; s = ServerSettings(); print(s.runtime, bool(s.internal_api_key))"`
Expected: 오류 없이 런타임 이름과 `True`/`False` 가 찍힌다. **키 값 자체를 출력하지 않는다.**

- [ ] **Step 3: 커밋한다**

```bash
git add ai/src/.env.example
git commit -m "docs(ai): .env.example 을 우선순위 한 규칙으로 다시 쓴다

변수마다 붙어 있던 '이 변수는 .env 로 안 된다' 예외가 사라진다. 이제
여기 적은 값은 전부 실제로 먹는다.

OpenAI 항목과 티어 모델 예시를 넣는다."
```

---

### Task 6: 전체 회귀와 실기동

**Files:**
- 없음 (검증 전용)

- [ ] **Step 1: 전체 테스트**

Run: `cd ai && uv run pytest -m "not ocr" -q`
Expected: 기존 실패 0. develop 기준선은 `588 passed, 2 skipped`.

- [ ] **Step 2: `ai/` 밖이 안 섞였는지 확인한다**

Run: `git status --short`
Expected: `ai/` 밖 변경 없음. `.env` 가 목록에 없어야 한다.

- [ ] **Step 3: `.env` 만으로 기동한다**

`src/.env` 에 `AI_RUNTIME=claude-code` 만 두고 (`INTERNAL_API_KEY` 는 아무 값):

Run: `cd ai && uv run python -m wiki_api.serve --port 8010`
Expected: 경고 없이 뜨고 런타임 이름이 찍힌다. **명령줄에 키를 안 줬는데 401 경고가 안 나오면 `.env` 가 실제로 먹은 것이다.** 확인 후 종료한다.

- [ ] **Step 4: OpenAI 로 실제 한 번 돌린다**

`src/.env` 에 넣는다 (키는 사람이 넣는다):

```
AI_RUNTIME=deepagents
AI_MODEL=openai:<모델>
AI_MODEL_FAST=openai:<모델>
AI_MODEL_QUALITY=openai:<모델>
OPENAI_API_KEY=<키>
OPENAI_BASE_URL=
```

문서 1건으로 변환을 돌리고 위키가 나오는지 본다. **이 티켓의 목적이 이것이다** — 단위 테스트가 아니라 배선이 끝까지 도는지.

깨지는 지점을 전부 기록한다. 고칠 수 있는 것은 고치고, 이 티켓 범위 밖이면 그대로 적어 둔다.

- [ ] **Step 5: 결과를 남긴다**

`ai/experiments/INDEX.md` 에 **수치를 넣지 않는다.** 모델도 하네스도 달라 비교가 깨진다. 대신 배선 확인 결과를 MR 본문에 적는다 — 무엇이 돌았고 무엇이 깨졌는지.

---

## 완료 판정

- [ ] `cd ai && uv run pytest -m "not ocr"` 기존 실패 0
- [ ] `.env` 에만 넣은 값이 실제로 먹는다
- [ ] 환경변수가 `.env` 를 이기고, CLI 플래그가 둘 다 이긴다
- [ ] 잘못된 `AI_RUNTIME` 이 기동 시점에 터진다
- [ ] `claude-code` 에 모델 키를 넘기면 거부한다
- [ ] `deep_agents.py` 에 `os.environ` 참조가 없다
- [ ] `load_dotenv` 를 부르는 코드가 없다
- [ ] OpenAI 로 문서 1건이 실제로 돈다
- [ ] `ai/` 밖 변경 없음. `.env` 미커밋

## 범위 밖

| 항목 | 이유 |
| --- | --- |
| 품질 측정 | 배선 확인용이다. OpenAI 수치를 `INDEX.md` 에 넣지 않는다 |
| 창구(federated) 경로 | S15P11B106-151·152·154 |
| `wiki_mcp/config.py`·`local_server.py` | 별도 프로세스의 자기 조립 지점이다 |
| `experiments/backend_sim.py` 의 설정 경로 | 측정용 별도 진입점. 필요해지면 별건 |
| 백엔드·프론트 | `ai/` 밖 |
