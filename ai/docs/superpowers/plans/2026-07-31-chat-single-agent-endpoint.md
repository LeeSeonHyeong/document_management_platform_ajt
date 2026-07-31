# 챗봇 단일 에이전트 엔드포인트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 챗봇의 2단계 호출(`answer-context-selections` + `answers`)을 에이전트가 스스로 조회하는 단일 엔드포인트(`answers`)로 합친다.

**Architecture:** Spring 은 질문·이전 대화·범위별 목차만 보낸다. 에이전트는 런타임에 직접 붙인 다섯 개 읽기 도구로 위키와 일정을 조회한다. MCP 서버도 작업 공간도 쓰지 않는다 — 쓰기가 없기 때문이다. 응답의 출처와 질문 유형은 모델의 말이 아니라 **도구가 실제로 읽은 기록**에서 만든다.

**Tech Stack:** Python 3.12+, FastAPI, pydantic v2, httpx, deepagents(LangChain), pytest.

## Global Constraints

- 설계 정본: `docs/superpowers/specs/2026-07-31-agent-endpoint-merge-design.md`. 이 계획과 어긋나면 설계가 정답이다.
- 기준 런타임은 `deepagents` 다. `claude-code` 는 모델 키가 없어 임시로 쓰는 시험용이고, **그쪽 특성을 설계 근거로 삼지 않는다.**
- 모델 제공자는 **OpenAI** 다. 모델 문자열은 `openai:` 접두사를 붙인다.
- 예산이 3달러다. **동작 확인까지만 한다.** 성능·정확도 측정은 하지 않는다.
- 계약이 허용한 응답 상태는 **400 · 401 · 500** 뿐이다. 그 밖의 상태를 내면 Spring 이 `UNEXPECTED_STATUS` 로 뭉갠다.
- 계약에 없는 오류 코드 이름을 임의로 확정하지 않는다. 코드에 넣되 MR 본문에 협의 항목으로 적는다 (`ai/CLAUDE.md`).
- 이전 대화 상한: **12개 · 4,000자**.
- 턴 상한 **5**, 시간 상한 **25초**. 둘 다 근거 없는 시작점이지만 **채팅 체감이 천장이다** (설계 §6.6).
- 도구 응답 상한: 위키 검색 20건, 일정 목록 50건. 자르면 잘랐다고 응답에 적는다.
- 응답 세 조각은 **모델에게 받고 출처만 읽은 기록으로 검사**한다 (설계 §6.4). `questionType` 은
  모델이 질문 맥락으로 판단한다 — 읽은 자료의 종류가 아니다 (FR-QNA-002).
- **근거를 찾아봤지만 없으면 200 + 빈 `sources`** 다 (FR-QNA-007). 도구를 아예 부르지 않은 실행만
  실패다 (설계 §6.4.1).
- 지시문에 **오늘 날짜(KST)** 를 싣는다. 없으면 일정 질문이 조용히 다 틀린다.
- `ai/` 밖의 파일은 수정하지 않는다. 계약(`../docs/api/`) 변경은 Task 10 에서 제안만 하고 승인을 받는다.
- 커밋은 `ai/` 하위 경로만 stage 한다. `git add -A` 금지.
- **기준선이 0 이 아니다.** 2026-07-31 현재 `uv run pytest -m "not ocr"` 이 **12건 실패**한다
  (`tests/api/test_answer_contract_conformance.py`) — 계약은 1단계를 지웠는데 구현이 아직 2단계라
  생긴 것이고 **Task 9 가 닫는다.** 그 전 태스크에서 「실패 0」을 기대하지 말고 이 12건이
  늘지 않는지만 본다.

---

## File Structure

**새로 만드는 것**

| 파일 | 책임 |
| --- | --- |
| `src/agent_runtime/tools.py` | 도구 1개를 나타내는 타입(`AgentTool`). 런타임과 `wiki_api` 사이의 유일한 접점 |
| `src/wiki_api/answer_tools.py` | 다섯 도구의 구현 — Spring 조회 클라이언트, 읽은 것 장부, 상한 |
| `src/wiki_api/answer_guide.py` | 챗봇 에이전트 지침 (한국어 산문) |
| `tests/api/test_answer_tools.py` | 도구·장부·상한 단위 테스트 |
| `tests/api/test_answer_agent.py` | 응답 조립(출처·질문유형·실패) 단위 테스트 |
| `tests/agent_runtime/test_run_with_tools.py` | 런타임의 도구 실행 경로 |

**고치는 것**

| 파일 | 무엇 |
| --- | --- |
| `src/agent_runtime/__init__.py` | 티어 모델 기본값을 에이전트 모델 제공자에 맞춘다 |
| `src/agent_runtime/base.py` | `Runtime` 프로토콜에 `run_with_tools` 추가 |
| `src/agent_runtime/deep_agents.py` | `run_with_tools` 구현 |
| `src/agent_runtime/claude_code.py` | `run_with_tools` 는 명확한 메시지로 거절 |
| `src/wiki_api/schemas.py` | `AnswerRequest`·`AnswerResponse` 개정, `AnswerContext*` 삭제 |
| `src/wiki_api/answer.py` | 단발 호출 → 에이전트 실행. 응답 조립 |
| `src/wiki_api/routers/answer.py` | 1단계 엔드포인트 삭제 |
| `src/wiki_api/serve.py` | 제공자 불일치를 기동 시점에 막는다 |
| `src/wiki_api/settings.py` | (Task 1 에서 필요하면) 티어 기본값 관련 주석 |

**지우는 것**

| 파일 | 이유 |
| --- | --- |
| `src/wiki_api/answer_selection.py` | 1단계가 없어진다 |
| `tests/api/test_answer_selection.py` | 위와 같음 |

---

### Task 1: 모델 제공자 기본값 결함 고치기

에이전트 모델을 `openai:...` 로 지정해도 단발 호출은 `DEFAULT_TIER_MODELS` 의 Anthropic 모델로 간다. 키가 없으면 「인증 방법을 못 찾았다」로 실패한다. 2026-07-31 실측으로 확인했다.

**Files:**
- Modify: `src/agent_runtime/deep_agents.py` (`DEFAULT_TIER_MODELS`, `_model_for`)
- Modify: `src/wiki_api/serve.py` (기동 검사)
- Test: `tests/api/test_deepagents_complete.py`, `tests/api/test_serve.py`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `DeepAgentsRuntime._model_for(tier) -> str` 이 에이전트 모델과 같은 제공자를 돌려준다. `serve.py` 의 `check_model_credentials(settings) -> None` 이 불일치 시 `SystemExit` 를 낸다.

- [ ] **Step 1: 티어 기본값이 제공자를 따라가는지 보는 실패 테스트를 쓴다**

`tests/api/test_deepagents_complete.py` 에 추가:

```python
def test_tier_models_follow_the_agent_provider():
    """에이전트 모델이 openai 면 티어 기본값도 openai 여야 한다.

    기본값이 anthropic 으로 박혀 있으면 OpenAI 만 설정한 배포가 첫 단발 호출에서
    「인증 방법을 못 찾았다」로 죽는다 (2026-07-31 실측).
    """
    from agent_runtime.base import FAST, QUALITY
    from agent_runtime.deep_agents import DeepAgentsRuntime

    runtime = DeepAgentsRuntime(model="openai:gpt-4o-mini")

    assert runtime._model_for(QUALITY).startswith("openai:")
    assert runtime._model_for(FAST).startswith("openai:")


def test_explicit_tier_models_still_win():
    from agent_runtime.base import FAST
    from agent_runtime.deep_agents import DeepAgentsRuntime

    runtime = DeepAgentsRuntime(model="openai:gpt-4o-mini",
                               fast_model="anthropic:claude-haiku-4-5-20251001")

    assert runtime._model_for(FAST) == "anthropic:claude-haiku-4-5-20251001"
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_deepagents_complete.py -k tier -v`
Expected: FAIL — `test_tier_models_follow_the_agent_provider` 가 `anthropic:claude-sonnet-4-6` 를 받아 `assert` 에서 떨어진다.

- [ ] **Step 3: 제공자별 티어 기본값 표로 바꾼다**

`src/agent_runtime/deep_agents.py` 의 `DEFAULT_TIER_MODELS` 를 대체한다:

```python
# 제공자별 티어 기본값. **에이전트 모델의 제공자를 따라간다** — 하나만 지정한 배포가
# 첫 단발 호출에서 다른 벤더의 키를 찾다 죽는 것을 막는다 (2026-07-31 실측).
DEFAULT_TIER_MODELS = {
    "anthropic": {
        FAST: "anthropic:claude-haiku-4-5-20251001",
        QUALITY: "anthropic:claude-sonnet-4-6",
    },
    "openai": {
        FAST: "openai:gpt-4o-mini",
        QUALITY: "openai:gpt-4o-mini",
    },
}
TIERS = (FAST, QUALITY)
```

`_model_for` 를 대체한다:

```python
    def _model_for(self, tier: str) -> str:
        """tier → 모델 이름. 생성자 인자가 있으면 그것을 쓴다.

        인자가 없으면 **에이전트 모델과 같은 제공자의** 기본값을 쓴다. 모르는 제공자면
        에이전트 모델을 그대로 쓴다 — 다른 벤더로 새는 것보다 낫다.

        모르는 tier 를 조용히 기본 모델로 떨어뜨리지 않는다 — 오타 하나가 측정을
        무의미하게 만드는 것보다 즉시 터지는 쪽이 낫다.
        """
        if tier not in TIERS:
            raise ValueError(f"모르는 tier: {tier!r} (가능: {sorted(TIERS)})")
        chosen = self._tier_models.get(tier)
        if chosen:
            return chosen
        provider = (self.model or "").split(":", 1)[0]
        return DEFAULT_TIER_MODELS.get(provider, {}).get(tier, self.model)
```

- [ ] **Step 4: 통과를 확인한다**

Run: `uv run pytest tests/api/test_deepagents_complete.py -v`
Expected: PASS (기존 테스트 포함)

- [ ] **Step 5: 기동 검사 실패 테스트를 쓴다**

`tests/api/test_serve.py` 에 추가:

```python
def test_startup_rejects_model_without_its_credential():
    """OpenAI 모델을 지정했는데 OpenAI 키가 없으면 기동에서 막는다.

    첫 요청에서 「인증 방법을 못 찾았다」로 죽는 것보다 기동에서 죽는 쪽이 낫다.
    `claude-code` CLI 부재를 기동에서 막는 것과 같은 이유다.
    """
    import pytest

    from wiki_api.serve import check_model_credentials
    from wiki_api.settings import ServerSettings

    settings = ServerSettings(internal_api_key="k", runtime="deepagents",
                              model="openai:gpt-4o-mini", openai_api_key="")

    with pytest.raises(SystemExit) as raised:
        check_model_credentials(settings)

    assert "openai" in str(raised.value).lower()


def test_startup_passes_when_the_credential_is_there():
    from wiki_api.serve import check_model_credentials
    from wiki_api.settings import ServerSettings

    settings = ServerSettings(internal_api_key="k", runtime="deepagents",
                              model="openai:gpt-4o-mini", openai_api_key="sk-test")

    check_model_credentials(settings)   # 예외가 없으면 통과
```

- [ ] **Step 6: 실패를 확인한다**

Run: `uv run pytest tests/api/test_serve.py -k credential -v`
Expected: FAIL — `ImportError: cannot import name 'check_model_credentials'`

- [ ] **Step 7: 기동 검사를 구현한다**

`src/wiki_api/serve.py` 에 추가하고, 런타임을 만드는 곳 **앞에서** 부른다:

```python
def check_model_credentials(settings) -> None:
    """모델의 제공자와 자격증명이 맞는지 기동 시점에 본다.

    `claude-code` 는 로그인 세션으로 과금하므로 검사 대상이 아니다.
    """
    if settings.runtime != "deepagents":
        return
    needed = {
        (settings.model or "").split(":", 1)[0],
        (settings.model_fast or "").split(":", 1)[0],
        (settings.model_quality or "").split(":", 1)[0],
    } - {""}
    keys = {"anthropic": settings.anthropic_api_key,
            "openai": settings.openai_api_key}
    for provider in sorted(needed):
        if provider in keys and not keys[provider]:
            raise SystemExit(
                f"{provider} 모델을 쓰도록 설정했는데 {provider} API 키가 없습니다. "
                f"src/.env 의 {provider.upper()}_API_KEY 를 채우거나 모델을 바꾸십시오.")
```

- [ ] **Step 8: 통과를 확인한다**

Run: `uv run pytest tests/api/test_serve.py tests/api/test_deepagents_complete.py -v`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add ai/src/agent_runtime/deep_agents.py ai/src/wiki_api/serve.py \
        ai/tests/api/test_deepagents_complete.py ai/tests/api/test_serve.py
git commit -m "fix(ai): 티어 모델 기본값을 에이전트 제공자에 맞추고 기동에서 검사한다"
```

---

### Task 2: 이전 대화 상한

지금은 실려 온 이전 대화를 통째로 넘긴다. 설계 문서에 12개·4천 자로 적혀 있는데 구현되지 않았다.

**Files:**
- Modify: `src/wiki_api/answer.py` (`trim_history` 추가)
- Test: `tests/api/test_answer_agent.py` (새 파일)

**Interfaces:**
- Consumes: 없음
- Produces: `trim_history(messages: list[dict], *, max_items: int = 12, max_chars: int = 4000) -> list[dict]` — 최근 것부터 남기고 오래된 것을 버린다. 순서는 원래대로 유지한다.

- [ ] **Step 1: 실패 테스트를 쓴다**

`tests/api/test_answer_agent.py` 를 새로 만든다:

```python
"""챗봇 에이전트 — 이전 대화 상한과 응답 조립."""

from wiki_api.answer import trim_history


def test_history_keeps_the_most_recent_items():
    messages = [{"role": "user", "content": f"질문 {i}"} for i in range(20)]

    kept = trim_history(messages, max_items=12, max_chars=4000)

    assert len(kept) == 12
    assert kept[0]["content"] == "질문 8"      # 오래된 8개가 버려진다
    assert kept[-1]["content"] == "질문 19"    # 순서는 원래대로


def test_history_also_respects_the_character_limit():
    messages = [{"role": "user", "content": "가" * 1500} for _ in range(6)]

    kept = trim_history(messages, max_items=12, max_chars=4000)

    assert len(kept) == 2                       # 1500 * 2 = 3000, 3개면 4500 으로 넘는다
    assert sum(len(m["content"]) for m in kept) <= 4000


def test_history_shorter_than_the_limit_is_untouched():
    messages = [{"role": "user", "content": "짧다"}]

    assert trim_history(messages) == messages


def test_a_single_oversized_message_is_kept():
    """하나만 있고 그것이 상한을 넘으면 버리지 않는다 — 버리면 대화가 통째로 사라진다."""
    messages = [{"role": "user", "content": "가" * 9000}]

    assert trim_history(messages, max_chars=4000) == messages
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_answer_agent.py -v`
Expected: FAIL — `ImportError: cannot import name 'trim_history'`

- [ ] **Step 3: 구현한다**

`src/wiki_api/answer.py` 에 추가:

```python
# 이전 대화 상한. 설계 문서에 적혀 있었으나 구현되지 않았던 값이다.
MAX_HISTORY_ITEMS = 12
MAX_HISTORY_CHARS = 4_000


def trim_history(messages: list[dict], *,
                 max_items: int = MAX_HISTORY_ITEMS,
                 max_chars: int = MAX_HISTORY_CHARS) -> list[dict]:
    """이전 대화를 최근 것부터 남긴다. **요청을 거절하지 않는다.**

    대화가 길어지면 상한을 넘는 것이 당연하다 — 백엔드 잘못이 아니므로 조용히 자르고
    최근 것으로 답한다. 에이전트는 여기에 도구 결과까지 쌓으므로 상한이 필요하다.

    하나만 남았는데 그것이 글자 상한을 넘으면 버리지 않는다. 버리면 대화가 통째로
    사라져서 대명사("그거")를 풀 수 없다.
    """
    kept: list[dict] = []
    total = 0
    for message in reversed(messages[-max_items:]):
        size = len(str(message.get("content") or ""))
        if kept and total + size > max_chars:
            break
        kept.append(message)
        total += size
    kept.reverse()
    return kept
```

- [ ] **Step 4: 통과를 확인한다**

Run: `uv run pytest tests/api/test_answer_agent.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add ai/src/wiki_api/answer.py ai/tests/api/test_answer_agent.py
git commit -m "feat(ai): 챗봇 이전 대화에 12개·4천자 상한을 넣는다"
```

---

### Task 3: 도구 타입과 읽은 것 장부

런타임과 `wiki_api` 사이의 접점을 정한다. `agent_runtime` 은 `wiki_api` 를 임포트하면 안 되므로(의존 방향 단방향) **타입은 `agent_runtime` 이 정의하고 채우는 것은 `wiki_api` 가 한다.**

**Files:**
- Create: `src/agent_runtime/tools.py`
- Create: `src/wiki_api/answer_tools.py` (장부만. 도구는 Task 4·5)
- Test: `tests/api/test_answer_tools.py`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `agent_runtime.tools.AgentTool` — `name: str`, `description: str`, `input_schema: dict`, `call: Callable[..., str]`
  - `wiki_api.answer_tools.ReadLedger` — `note_wiki(wiki_id: str, title: str) -> None`, `note_schedule(schedule_id: str, title: str) -> None`, `wikis: list[tuple[str, str]]`, `schedules: list[tuple[str, str]]`, `is_empty() -> bool`, `title_of_wiki(wiki_id) -> str | None`, `title_of_schedule(schedule_id) -> str | None`

**`question_type()` 은 두지 않는다.** 질문 유형은 모델이 질문 맥락을 보고 판단한다 (FR-QNA-002, 설계 §6.4). 장부는 「무엇을 읽었나」만 안다 — 그것으로 유형을 정하면 일정 질문을 위키로 답했을 때 유형이 뒤집힌다.

- [ ] **Step 1: 실패 테스트를 쓴다**

`tests/api/test_answer_tools.py` 를 새로 만든다:

```python
"""챗봇 도구 — 장부, 상한, 조회 클라이언트."""

from wiki_api.answer_tools import ReadLedger


def test_ledger_records_in_order_without_duplicates():
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")
    ledger.note_wiki("101", "휴가 규정")
    ledger.note_wiki("108", "육아휴직")

    assert ledger.wikis == [("101", "휴가 규정"), ("108", "육아휴직")]


def test_ledger_keeps_titles_for_the_contract():
    """`sources[].title` 은 백엔드가 `answer_source.source_title`(NOT NULL)에 저장한다."""
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")
    ledger.note_schedule("31", "8월 워크샵")

    assert ledger.title_of_wiki("101") == "휴가 규정"
    assert ledger.title_of_schedule("31") == "8월 워크샵"
    assert ledger.title_of_wiki("999") is None      # 안 읽은 것은 제목도 없다


def test_empty_ledger_is_visible_to_the_caller():
    assert ReadLedger().is_empty() is True
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_answer_tools.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'wiki_api.answer_tools'`

- [ ] **Step 3: 도구 타입을 만든다**

`src/agent_runtime/tools.py`:

```python
"""런타임에 직접 붙이는 도구 1개의 표현.

**이 타입이 `agent_runtime` 과 `wiki_api` 사이의 유일한 접점이다.** 타입을 아래쪽
(`agent_runtime`)에 두는 이유는 의존 방향이 `wiki_api → agent_runtime → wiki_mcp` 단방향
이어서다 — 런타임이 `wiki_api` 를 임포트하면 그 방향이 깨진다.

LangChain 을 여기서 임포트하지 않는다. 도구를 LangChain 형태로 감싸는 것은
`deep_agents.py` 안에서만 일어난다 — 배포 의존성이 없는 설치에서도 이 모듈이 임포트돼야
한다.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class AgentTool:
    """`input_schema` 는 JSON Schema 의 object 하나다. `call` 은 문자열을 돌려준다 —
    모델이 읽을 것이므로 사람이 읽을 수 있는 형태여야 한다."""

    name: str
    description: str
    input_schema: dict
    call: Callable[..., str]
```

- [ ] **Step 4: 장부를 만든다**

`src/wiki_api/answer_tools.py`:

```python
"""챗봇 도구 — Spring 조회와 읽은 것 장부.

**장부는 출처의 화이트리스트다.** 모델이 「이걸 썼다」고 신고한 것을 이 기록과 대조해 걸러낸다
(`answer.build_response`). 그래서 안 읽은 페이지를 출처로 신고해도 통과하지 못하고, 읽었지만
답에 쓰지 않은 페이지도 섞이지 않는다.

**질문 유형은 여기서 정하지 않는다.** 모델이 질문 맥락을 보고 판단한다 (FR-QNA-002).
"""

from __future__ import annotations


class ReadLedger:
    """한 요청 동안 무엇을 읽었는지. 순서를 지키고 중복을 지운다."""

    def __init__(self) -> None:
        self._wikis: dict[str, str] = {}
        self._schedules: dict[str, str] = {}

    def note_wiki(self, wiki_id: str, title: str) -> None:
        self._wikis.setdefault(str(wiki_id), title)

    def note_schedule(self, schedule_id: str, title: str) -> None:
        self._schedules.setdefault(str(schedule_id), title)

    @property
    def wikis(self) -> list[tuple[str, str]]:
        return list(self._wikis.items())

    @property
    def schedules(self) -> list[tuple[str, str]]:
        return list(self._schedules.items())

    def is_empty(self) -> bool:
        return not self._wikis and not self._schedules

    def title_of_wiki(self, wiki_id: str) -> str | None:
        return self._wikis.get(str(wiki_id))

    def title_of_schedule(self, schedule_id: str) -> str | None:
        return self._schedules.get(str(schedule_id))
```

- [ ] **Step 5: 통과를 확인한다**

Run: `uv run pytest tests/api/test_answer_tools.py -v`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add ai/src/agent_runtime/tools.py ai/src/wiki_api/answer_tools.py \
        ai/tests/api/test_answer_tools.py
git commit -m "feat(ai): 챗봇 도구 타입과 읽은 것 장부를 만든다"
```

---

### Task 4: 위키 조회 도구 세 개

**Files:**
- Modify: `src/wiki_api/answer_tools.py`
- Test: `tests/api/test_answer_tools.py`

**Interfaces:**
- Consumes: `ReadLedger` (Task 3), `AgentTool` (Task 3)
- Produces:
  - `ChatQueryClient(base_url, *, api_key, question_id, capabilities: dict[str, str], transport=None)`
  - `build_wiki_tools(client: ChatQueryClient, ledger: ReadLedger) -> list[AgentTool]` — 이름은 `search_wiki` · `read_wiki` · `read_wiki_index`
  - 상한 상수 `MAX_WIKI_SEARCH_ROWS = 20`

- [ ] **Step 1: 실패 테스트를 쓴다**

`tests/api/test_answer_tools.py` 에 추가:

```python
import httpx

from wiki_api.answer_tools import (
    MAX_WIKI_SEARCH_ROWS,
    ChatQueryClient,
    ReadLedger,
    build_wiki_tools,
)


def _client(handler) -> ChatQueryClient:
    return ChatQueryClient("http://backend", api_key="k", question_id="500",
                           capabilities={"ALL": "cap-all", "D1": "cap-d1"},
                           transport=httpx.MockTransport(handler))


def test_read_wiki_sends_the_capability_of_that_scope():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["capability"] = request.headers.get("X-Wiki-Capability")
        return httpx.Response(200, json={"wikiId": "101", "title": "휴가 규정",
                                         "contentMarkdown": "# 휴가 규정\n연차 15일"})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_wiki_tools(_client(handler), ledger)}

    text = tools["read_wiki"].call(scopeKey="D1", wikiId="101")

    assert "연차 15일" in text
    assert seen["capability"] == "cap-d1"          # 범위마다 다른 값을 쓴다
    assert "scopeKey=D1" in seen["url"]
    assert ledger.wikis == [("101", "휴가 규정")]    # 읽은 것이 장부에 남는다


def test_search_wiki_caps_rows_and_says_it_truncated():
    rows = [{"wikiId": str(i), "title": f"페이지 {i}", "snippet": "..."}
            for i in range(50)]

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"items": rows})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_wiki_tools(_client(handler), ledger)}

    text = tools["search_wiki"].call(scopeKey="ALL", query="연차")

    assert text.count("wikiId") <= MAX_WIKI_SEARCH_ROWS
    assert "더 있습니다" in text                     # 잘랐다고 알린다
    assert ledger.wikis == []                       # 검색은 읽은 것이 아니다


def test_search_result_is_not_a_source():
    """검색 결과에 제목이 보였다는 것만으로 출처가 되면 안 된다 — 본문을 읽어야 출처다."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"items": [{"wikiId": "101",
                                                    "title": "휴가 규정",
                                                    "snippet": "연차"}]})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_wiki_tools(_client(handler), ledger)}
    tools["search_wiki"].call(scopeKey="ALL", query="연차")

    assert ledger.is_empty() is True


def test_unknown_scope_is_refused_without_calling_the_backend():
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("허가값이 없는 범위는 부르지 않아야 한다")

    tools = {t.name: t for t in build_wiki_tools(_client(handler), ReadLedger())}

    text = tools["read_wiki"].call(scopeKey="SECRET", wikiId="1")

    assert "볼 수 없는 범위" in text
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_answer_tools.py -v`
Expected: FAIL — `ImportError: cannot import name 'ChatQueryClient'`

- [ ] **Step 3: 조회 클라이언트와 위키 도구를 구현한다**

`src/wiki_api/answer_tools.py` 에 추가:

```python
import json

import httpx

from agent_runtime.tools import AgentTool

# 위키 검색 상한. 에이전트가 실제로 보는 것은 이 행들이므로 문맥이 여기서 부푼다.
MAX_WIKI_SEARCH_ROWS = 20
QUERY_TIMEOUT_SECONDS = 15


class QueryFailed(RuntimeError):
    """백엔드 조회가 실패했다. 호출자가 오류 코드로 바꾼다."""


class ChatQueryClient:
    """챗봇 전용 조회 클라이언트. **위키 편집용 클라이언트를 쓰지 않는다** —
    참조 그래프 재동기화와 범위 버전 대조가 딸려오고, 읽기에는 둘 다 필요 없다.

    `capabilities` 는 범위 → 허가값이다. 요청에 실려 온 목차 행마다 하나씩 온다.
    **허가값이 없는 범위는 부르지 않는다** — 백엔드가 거절할 것을 미리 막아 왕복을 아낀다.
    """

    def __init__(self, base_url: str, *, api_key: str, question_id: str,
                 capabilities: dict[str, str], transport=None) -> None:
        self.question_id = question_id
        self._capabilities = dict(capabilities)
        self._http = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"X-Internal-API-Key": api_key},
            timeout=QUERY_TIMEOUT_SECONDS,
            transport=transport,
        )

    def close(self) -> None:
        self._http.close()

    def capability_for(self, scope_key: str) -> str | None:
        return self._capabilities.get(scope_key)

    def get(self, path: str, *, scope_key: str | None = None,
            params: dict | None = None) -> dict:
        headers = {}
        if scope_key is not None:
            capability = self._capabilities.get(scope_key)
            if not capability:
                raise QueryFailed(f"허가값이 없는 범위입니다: {scope_key}")
            headers["X-Wiki-Capability"] = capability
        response = self._http.get(path, params=params or {}, headers=headers)
        if response.status_code >= 400:
            # 본문을 그대로 싣지 않는다 — 허가값이 되돌아올 여지를 남기지 않는다.
            raise QueryFailed(f"조회 실패 {response.status_code} — {path}")
        return response.json()


def _rows_text(rows: list[dict], keys: tuple[str, ...], limit: int) -> str:
    shown = rows[:limit]
    lines = [json.dumps({k: row.get(k) for k in keys}, ensure_ascii=False)
             for row in shown]
    if len(rows) > limit:
        lines.append(f"({len(rows) - limit}건 더 있습니다. 조건을 좁혀 다시 부르십시오)")
    return "\n".join(lines) if lines else "결과가 없습니다."


def build_wiki_tools(client: ChatQueryClient, ledger: ReadLedger) -> list[AgentTool]:
    scope_schema = {"type": "string", "description": "위키 범위 (목차에 실려 온 scopeKey)"}

    def search_wiki(scopeKey: str, query: str) -> str:
        try:
            body = client.get("/internal/v1/wiki-search", scope_key=scopeKey,
                              params={"scopeKey": scopeKey, "query": query,
                                      "limit": MAX_WIKI_SEARCH_ROWS})
        except QueryFailed as failed:
            return f"볼 수 없는 범위이거나 조회에 실패했습니다: {failed}"
        return _rows_text(body.get("items") or [], ("wikiId", "title", "snippet"),
                          MAX_WIKI_SEARCH_ROWS)

    def read_wiki(scopeKey: str, wikiId: str) -> str:
        try:
            body = client.get(f"/internal/v1/wikis/{wikiId}/content",
                              scope_key=scopeKey, params={"scopeKey": scopeKey})
        except QueryFailed as failed:
            return f"볼 수 없는 범위이거나 조회에 실패했습니다: {failed}"
        title = str(body.get("title") or wikiId)
        ledger.note_wiki(wikiId, title)
        return f"# {title}\n\n{body.get('contentMarkdown') or ''}"

    def read_wiki_index(scopeKey: str) -> str:
        try:
            body = client.get(f"/internal/v1/wiki-spaces/{scopeKey}/index",
                              scope_key=scopeKey)
        except QueryFailed as failed:
            return f"볼 수 없는 범위이거나 조회에 실패했습니다: {failed}"
        return str(body.get("indexMarkdown") or "목차가 비어 있습니다.")

    return [
        AgentTool(
            name="search_wiki",
            description="위키 본문을 검색한다. 목차에서 찾지 못했을 때 쓴다. "
                        "결과는 제목과 일부 문장뿐이므로 답하기 전에 read_wiki 로 본문을 "
                        "읽어야 한다.",
            input_schema={"type": "object",
                          "properties": {"scopeKey": scope_schema,
                                         "query": {"type": "string",
                                                   "description": "찾을 말"}},
                          "required": ["scopeKey", "query"]},
            call=search_wiki,
        ),
        AgentTool(
            name="read_wiki",
            description="위키 한 장의 본문을 읽는다. 답변의 근거가 되는 유일한 방법이다.",
            input_schema={"type": "object",
                          "properties": {"scopeKey": scope_schema,
                                         "wikiId": {"type": "string"}},
                          "required": ["scopeKey", "wikiId"]},
            call=read_wiki,
        ),
        AgentTool(
            name="read_wiki_index",
            description="범위 하나의 목차를 다시 읽는다. 요청에 실려 온 목차로 충분하면 "
                        "부르지 않아도 된다.",
            input_schema={"type": "object",
                          "properties": {"scopeKey": scope_schema},
                          "required": ["scopeKey"]},
            call=read_wiki_index,
        ),
    ]
```

- [ ] **Step 4: 통과를 확인한다**

Run: `uv run pytest tests/api/test_answer_tools.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add ai/src/wiki_api/answer_tools.py ai/tests/api/test_answer_tools.py
git commit -m "feat(ai): 챗봇 위키 조회 도구 세 개를 만든다"
```

---

### Task 5: 일정 조회 도구 두 개

백엔드에 아직 입구가 없다. **모양은 설계 §7 이 제안한 것으로 구현하고 가짜 서버로 테스트한다.** 백엔드가 다른 모양으로 만들면 이 파일만 고친다.

**Files:**
- Modify: `src/wiki_api/answer_tools.py`
- Test: `tests/api/test_answer_tools.py`

**Interfaces:**
- Consumes: `ChatQueryClient`, `ReadLedger`, `AgentTool`
- Produces: `build_schedule_tools(client, ledger) -> list[AgentTool]` — 이름은 `list_schedules` · `read_schedule`. 상한 `MAX_SCHEDULE_ROWS = 50`

- [ ] **Step 1: 실패 테스트를 쓴다**

`tests/api/test_answer_tools.py` 에 추가:

```python
from wiki_api.answer_tools import MAX_SCHEDULE_ROWS, build_schedule_tools


def test_list_schedules_passes_period_keyword_and_question_id():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"items": [
            {"scheduleId": "31", "title": "8월 워크샵",
             "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T09:00:00Z",
             "targetText": "전사", "location": "본사"}], "truncated": False})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_schedule_tools(_client(handler), ledger)}

    text = tools["list_schedules"].call(**{"from": "2026-08-01", "to": "2026-08-31",
                                          "keyword": "워크샵"})

    assert "8월 워크샵" in text
    assert seen["params"]["questionId"] == "500"     # 권한 판정 근거
    assert seen["params"]["keyword"] == "워크샵"
    assert ledger.is_empty() is True                 # 목록은 읽은 것이 아니다


def test_list_schedules_tells_the_agent_when_the_backend_truncated():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"items": [], "truncated": True})

    tools = {t.name: t for t in build_schedule_tools(_client(handler), ReadLedger())}

    assert "더 있습니다" in tools["list_schedules"].call(**{"from": "2026-01-01",
                                                          "to": "2026-12-31"})


def test_read_schedule_records_the_source():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"scheduleId": "31", "title": "8월 워크샵",
                                         "content": "전사 워크샵 안내",
                                         "startAt": "2026-08-03T01:00:00Z",
                                         "endAt": "2026-08-03T09:00:00Z",
                                         "targetText": "전사", "location": "본사"})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_schedule_tools(_client(handler), ledger)}

    text = tools["read_schedule"].call(scheduleId="31")

    assert "전사 워크샵 안내" in text
    assert ledger.schedules == [("31", "8월 워크샵")]
    assert ledger.title_of_schedule("31") == "8월 워크샵"


def test_schedule_rows_are_capped_locally_too():
    rows = [{"scheduleId": str(i), "title": f"일정 {i}", "startAt": "",
             "endAt": "", "targetText": "", "location": ""} for i in range(120)]

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"items": rows, "truncated": False})

    tools = {t.name: t for t in build_schedule_tools(_client(handler), ReadLedger())}

    text = tools["list_schedules"].call(**{"from": "2026-01-01", "to": "2026-12-31"})

    assert text.count("scheduleId") <= MAX_SCHEDULE_ROWS
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_answer_tools.py -k schedule -v`
Expected: FAIL — `ImportError: cannot import name 'MAX_SCHEDULE_ROWS'`

- [ ] **Step 3: 구현한다**

`src/wiki_api/answer_tools.py` 에 추가:

```python
# 일정 목록 상한. 백엔드도 자르지만(응답의 `truncated`) 우리 쪽에서도 막는다 — 상한이
# 한 곳에만 있으면 그 한 곳이 바뀔 때 조용히 늘어난다.
MAX_SCHEDULE_ROWS = 50

_SCHEDULE_LIST_KEYS = ("scheduleId", "title", "startAt", "endAt", "targetText",
                       "location")


def build_schedule_tools(client: ChatQueryClient,
                         ledger: ReadLedger) -> list[AgentTool]:
    def list_schedules(**kwargs) -> str:
        # `from` 은 파이썬 예약어라 이름을 그대로 쓸 수 없다. 계약 필드명을 지키기 위해
        # kwargs 로 받는다.
        params = {"questionId": client.question_id,
                  "from": kwargs.get("from"), "to": kwargs.get("to"),
                  "limit": MAX_SCHEDULE_ROWS}
        if kwargs.get("keyword"):
            params["keyword"] = kwargs["keyword"]
        try:
            body = client.get("/internal/v1/schedules", params=params)
        except QueryFailed as failed:
            return f"일정 조회에 실패했습니다: {failed}"
        rows = body.get("items") or []
        text = _rows_text(rows, _SCHEDULE_LIST_KEYS, MAX_SCHEDULE_ROWS)
        if body.get("truncated") and "더 있습니다" not in text:
            text += "\n(기간 안에 일정이 더 있습니다. 기간을 좁혀 다시 부르십시오)"
        return text

    def read_schedule(scheduleId: str) -> str:
        try:
            body = client.get(f"/internal/v1/schedules/{scheduleId}",
                              params={"questionId": client.question_id})
        except QueryFailed as failed:
            return f"일정 조회에 실패했습니다: {failed}"
        title = str(body.get("title") or scheduleId)
        ledger.note_schedule(scheduleId, title)
        return json.dumps({k: body.get(k) for k in (*_SCHEDULE_LIST_KEYS, "content")},
                          ensure_ascii=False)

    return [
        AgentTool(
            name="list_schedules",
            description="기간 안의 일정 목록을 본다. 제목·시각·대상만 오고 내용은 오지 "
                        "않는다. 질문에 맞는 기간을 직접 정해서 부른다 — 「다음」은 오늘 "
                        "이후, 「지난」은 오늘 이전이다. keyword 를 주면 제목으로 좁힌다.",
            input_schema={"type": "object",
                          "properties": {
                              "from": {"type": "string",
                                       "description": "시작일 (YYYY-MM-DD)"},
                              "to": {"type": "string",
                                     "description": "종료일 (YYYY-MM-DD)"},
                              "keyword": {"type": "string",
                                          "description": "제목에 들어갈 말 (선택)"}},
                          "required": ["from", "to"]},
            call=list_schedules,
        ),
        AgentTool(
            name="read_schedule",
            description="일정 하나의 내용을 읽는다. 일정을 근거로 답하려면 이것을 불러야 "
                        "한다.",
            input_schema={"type": "object",
                          "properties": {"scheduleId": {"type": "string"}},
                          "required": ["scheduleId"]},
            call=read_schedule,
        ),
    ]
```

- [ ] **Step 4: 통과를 확인한다**

Run: `uv run pytest tests/api/test_answer_tools.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add ai/src/wiki_api/answer_tools.py ai/tests/api/test_answer_tools.py
git commit -m "feat(ai): 챗봇 일정 조회 도구 두 개를 만든다"
```

---

### Task 6: 런타임에 도구 실행 경로를 넣는다

**Files:**
- Modify: `src/agent_runtime/base.py`
- Modify: `src/agent_runtime/deep_agents.py`
- Modify: `src/agent_runtime/claude_code.py`
- Create: `tests/agent_runtime/__init__.py`, `tests/agent_runtime/test_run_with_tools.py`

> ⚠️ 테스트 디렉터리 이름에 `mcp` 를 쓰지 않는다. PyPI `mcp` 패키지를 가려 `No module named 'mcp.server'` 가 난다. `tests/__init__.py` 도 지우지 않는다 (`ai/CLAUDE.md` 함정).

**Interfaces:**
- Consumes: `AgentTool` (Task 3)
- Produces: `Runtime.run_with_tools(guide: str, question: str, *, tools: list[AgentTool], max_turns: int, timeout: int, response_format=None) -> RunResult` — `RunResult.text` 가 마지막 글, `RunResult.tool_calls` 가 도구별 호출 수. 턴 상한 도달은 `RunResult.error == "turn_limit"` 으로 구분된다.

**지침과 질문을 나눠 받는다.** 한 문자열을 `system_prompt` 와 첫 user 메시지에 둘 다 넣으면 목차가 두 번 실려 입력이 두 배가 된다 (설계 §8-2 가 걱정한 그 덩이다).

- [ ] **Step 1: 실패 테스트를 쓴다**

`tests/agent_runtime/__init__.py` 는 빈 파일로 만든다. `tests/agent_runtime/test_run_with_tools.py`:

```python
"""도구를 직접 붙이는 실행 경로.

모델을 부르지 않는다 — LangChain 조립까지만 보고, 모델 호출은 실기동(Task 10)에서 본다.
"""

import pytest

from agent_runtime.tools import AgentTool


def _tool() -> AgentTool:
    return AgentTool(name="echo", description="받은 말을 돌려준다",
                     input_schema={"type": "object",
                                   "properties": {"text": {"type": "string"}},
                                   "required": ["text"]},
                     call=lambda text: f"들었다: {text}")


def test_claude_code_refuses_with_a_clear_reason():
    from agent_runtime.claude_code import ClaudeCodeRuntime

    runtime = ClaudeCodeRuntime(model="claude-opus-4-6")

    with pytest.raises(NotImplementedError) as raised:
        runtime.run_with_tools("지침", "질문", tools=[_tool()], max_turns=5, timeout=25)

    assert "deepagents" in str(raised.value)


def test_turn_limit_is_reported_as_itself_not_as_a_model_failure():
    """`recursion_limit` 으로만 걸면 GraphRecursionError 가 일반 예외로 잡혀
    `MODEL_CALL_FAILED` 로 나간다 — `AGENT_TURN_LIMIT_REACHED` 를 계약에 올려놓고 한 번도
    내지 않게 된다. 가짜 모델로 상한을 넘겨 이름이 구분되는지 고정한다."""
    from langchain_core.messages import AIMessage
    from langchain_core.language_models.fake_chat_models import GenericFakeChatModel

    from agent_runtime.deep_agents import DeepAgentsRuntime

    calling = AIMessage(content="", tool_calls=[
        {"name": "echo", "args": {"text": "또"}, "id": "1", "type": "tool_call"}])
    # 끝내지 않고 계속 도구만 부른다.
    model = GenericFakeChatModel(messages=iter([calling] * 20))
    runtime = DeepAgentsRuntime(model="openai:gpt-4o-mini", chat_model=model)

    run = runtime.run_with_tools("지침", "질문", tools=[_tool()], max_turns=3, timeout=25)

    assert run.error == "turn_limit"


def test_deepagents_wraps_agent_tools_for_langchain():
    """`AgentTool` 이 LangChain 도구로 감싸지고 이름·스키마가 보존되는지."""
    from agent_runtime.deep_agents import langchain_tools

    wrapped = langchain_tools([_tool()])

    assert [t.name for t in wrapped] == ["echo"]
    assert wrapped[0].invoke({"text": "안녕"}) == "들었다: 안녕"
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/agent_runtime/test_run_with_tools.py -v`
Expected: FAIL — `AttributeError: 'ClaudeCodeRuntime' object has no attribute 'run_with_tools'`

- [ ] **Step 3: 프로토콜에 더한다**

`src/agent_runtime/base.py` 의 `Runtime` 프로토콜에 추가:

```python
    def run_with_tools(self, guide: str, question: str, *, tools: list,
                       max_turns: int, timeout: int,
                       response_format=None) -> RunResult: ...
    # MCP 없이 도구를 직접 붙여 도는 실행. **쓰기가 없는 작업(챗봇)만 쓴다** — 작업 공간도
    # 스코프 격리도 없다. `tools` 는 `agent_runtime.tools.AgentTool` 목록이다.
    # `guide` 는 시스템 지침, `question` 은 첫 사용자 메시지다. **한 문자열을 양쪽에 넣지
    # 않는다** — 목차가 두 번 실려 입력이 두 배가 된다.
    # 상한은 턴 수(`max_turns`)가 주 장치이고 `timeout` 은 한 턴이 이상하게 오래 걸릴 때
    # 끊는 안전장치다. 턴 상한에 걸린 실행은 `RunResult.error == "turn_limit"` 이다 —
    # 호출자가 그것으로 `AGENT_TURN_LIMIT_REACHED` 를 낸다.
```

- [ ] **Step 4: `claude-code` 는 거절하게 한다**

`src/agent_runtime/claude_code.py` 에 추가:

```python
    def run_with_tools(self, guide: str, question: str, *, tools: list,
                       max_turns: int, timeout: int,
                       response_format=None) -> RunResult:
        """CLI 는 도구를 MCP 로만 받는다. 직접 붙이는 경로가 없다.

        기준 런타임은 `deepagents` 이고 CLI 는 모델 키가 없을 때 쓰는 시험용이다.
        조용히 다른 경로로 돌리지 않는다 — 무엇으로 돌았는지 모르게 된다.
        """
        raise NotImplementedError(
            "claude-code 런타임은 도구를 직접 붙일 수 없습니다. "
            "챗봇 에이전트는 deepagents 런타임에서만 돕니다 (AI_RUNTIME=deepagents).")
```

- [ ] **Step 5: `deepagents` 에 구현한다**

`src/agent_runtime/deep_agents.py` 에 추가:

```python
def langchain_tools(tools: list) -> list:
    """`AgentTool` 목록을 LangChain 도구로 감싼다.

    LangChain 임포트를 이 파일 안에 둔다 — 배포 의존성을 깔지 않은 설치에서도
    `agent_runtime.tools` 는 임포트돼야 한다.
    """
    from langchain_core.tools import StructuredTool

    return [StructuredTool.from_function(
        func=tool.call, name=tool.name, description=tool.description,
        args_schema=tool.input_schema) for tool in tools]
```

`DeepAgentsRuntime` 에 메서드를 추가한다:

```python
    def run_with_tools(self, guide: str, question: str, *, tools: list,
                       max_turns: int, timeout: int,
                       response_format=None) -> RunResult:
        """MCP 없이 도구를 직접 붙여 돈다. 챗봇 전용이다.

        `run` 과 달리 임시 디렉터리도 MCP 클라이언트도 만들지 않는다 — 쓰기가 없다.
        """
        started = time.monotonic()
        text, calls, structured, hit_limit = asyncio.run(
            self._run_with_tools(guide, question, tools, max_turns, timeout,
                                 response_format))
        return RunResult(text=text, tool_calls=calls, turns=sum(calls.values()),
                         structured=structured,
                         error="turn_limit" if hit_limit else None,
                         elapsed_seconds=round(time.monotonic() - started, 2))

    async def _run_with_tools(self, guide: str, question: str, tools: list,
                              max_turns: int, timeout: int, response_format):
        from langchain.agents.middleware import ModelCallLimitMiddleware
        from deepagents import create_deep_agent

        # 턴 상한을 미들웨어로 건다. `recursion_limit` 만 쓰면 GraphRecursionError 가 나고
        # 그것이 일반 예외로 잡혀 「모델 호출 실패」로 뭉개진다 — 상한 도달과 고장을
        # 구분할 수 없게 된다.
        limit = ModelCallLimitMiddleware(thread_limit=max_turns, exit_behavior="end")
        agent = create_deep_agent(
            model=self._chat_model(timeout),
            tools=langchain_tools(tools),
            subagents=[],
            system_prompt=guide,          # 지침은 여기에만. 질문과 겹쳐 싣지 않는다.
            middleware=[limit],
            response_format=response_format,
        )
        result = await asyncio.wait_for(
            agent.ainvoke({"messages": [{"role": "user", "content": question}]}),
            timeout=timeout,
        )
        messages = result.get("messages") or []
        calls: dict[str, int] = {}
        for message in messages:
            for call in getattr(message, "tool_calls", None) or []:
                name = call.get("name") if isinstance(call, dict) else None
                if name:
                    calls[name] = calls.get(name, 0) + 1
        text = _text_of(messages[-1].content) if messages else ""
        # 상한으로 끝났으면 마지막 메시지가 도구 호출이고 답변 글이 없다.
        hit_limit = sum(calls.values()) >= max_turns and not text.strip()
        return text, calls, result.get("structured_response"), hit_limit
```

> `_chat_model(timeout)` 은 기존 자격증명 분기(`init_chat_model` + `_credential_kwargs`)를 감싼
> 것이다. 생성자에 `chat_model=` 이 오면 그것을 그대로 쓴다 — **가짜 모델을 끼우는 자리다**
> (Task 10). 실제 상한 판정과 `structured_response` 키 이름은 Step 6 에서 실물로 확인한다.

> `RunResult` 의 필드는 확인했다 — `text` · `tool_calls` · `input_tokens` · `output_tokens` · `turns` · `cost_usd` · `elapsed_seconds` · `error` · `detail`. **`structured` 는 없으므로 이 태스크에서 더한다** (`structured: dict | None = None`). 모델이 낸 구조화 응답(쓴 자료 신고·질문 유형)을 담는 자리다. `detail` 에 섞지 않는다 — 그쪽은 측정·진단용이고 계약 응답에 실리지 않는 값이다.

- [ ] **Step 6: 통과를 확인한다**

Run: `uv run pytest tests/agent_runtime/test_run_with_tools.py -v`
Expected: PASS

- [ ] **Step 7: 전체 테스트로 회귀를 본다**

Run: `uv run pytest -m "not ocr" -q`
Expected: 기존 실패 0

- [ ] **Step 8: 커밋**

```bash
git add ai/src/agent_runtime/base.py ai/src/agent_runtime/deep_agents.py \
        ai/src/agent_runtime/claude_code.py ai/tests/agent_runtime/
git commit -m "feat(ai): 런타임에 도구를 직접 붙이는 실행 경로를 넣는다"
```

---

### Task 7: 챗봇 지침

**Files:**
- Create: `src/wiki_api/answer_guide.py`
- Test: `tests/api/test_answer_agent.py`

**Interfaces:**
- Consumes: 없음
- Produces: `chat_guide(history: list[dict], indexes: list[dict], *, today: date) -> str` — 시스템 지침. 질문은 담지 않는다 (Task 6 이 첫 사용자 메시지로 따로 보낸다).

**오늘 날짜를 받는다.** 모델은 오늘이 언제인지 모른다 — 훈련 시점 날짜로 조회한다. `list_schedules` 가 기간을 필수로 받으므로 이것이 빠지면 일정 질문이 조용히 다 틀린다. 사용자 감각과 맞춰야 하므로 **KST 기준**이고, 인자로 받아 테스트에서 고정한다.

- [ ] **Step 1: 실패 테스트를 쓴다**

`tests/api/test_answer_agent.py` 에 추가:

```python
from datetime import date

from wiki_api.answer_guide import chat_guide


def test_guide_carries_history_and_indexes_but_not_the_question():
    text = chat_guide(
        [{"role": "user", "content": "안녕"}],
        [{"scopeKey": "ALL", "indexMarkdown": "- [휴가 규정](pages/101.md)"}],
        today=date(2026, 7, 31),
    )

    assert "안녕" in text
    assert "휴가 규정" in text
    assert "ALL" in text


def test_guide_tells_the_model_what_day_it_is():
    """모델은 오늘을 모른다. 없으면 「다음 워크샵」이 훈련 시점 기준으로 조회된다."""
    text = chat_guide([], [], today=date(2026, 7, 31))

    assert "2026-07-31" in text


def test_guide_states_the_hard_rules():
    """지침에서 빠지면 안 되는 규칙 — 읽지 않고 답하지 않기, 모르면 모른다고 하기."""
    text = chat_guide([], [], today=date(2026, 7, 31))

    assert "read_wiki" in text          # 본문을 읽어야 한다는 것을 도구 이름으로 못박는다
    assert "read_schedule" in text
    assert "모른다" in text
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_answer_agent.py -k instruction -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'wiki_api.answer_guide'`

- [ ] **Step 3: 구현한다**

`src/wiki_api/answer_guide.py`:

```python
"""챗봇 에이전트 지침.

**위키 지침(`wiki_mcp/tools/guide.py`)과 나란한 문서다.** 위키 쪽은 도구로 읽히지만 챗봇은
한 번 도는 실행이라 처음부터 시스템 지침에 싣는다 — 지침을 읽으라고 한 턴을 쓰는 것이 아깝다.

**질문은 여기 담지 않는다.** 지침은 시스템 자리, 질문은 첫 사용자 메시지다. 한 문자열을 양쪽에
넣으면 목차가 두 번 실려 입력이 두 배가 된다.

문장이 모델마다 다르게 먹는다. 이 지침은 **OpenAI 기준으로 조율한다** (설계 §6.1.1).
"""

from __future__ import annotations

from datetime import date

GUIDE = """너는 사내 위키와 일정에 답하는 사내 안내원이다.

## 일하는 순서

1. **아래 목차를 먼저 본다.** 질문이 위키 얘기인지, 일정 얘기인지, 둘 다인지 판단한다.
2. **위키 질문이면** 목차에서 관련 페이지를 골라 `read_wiki` 로 본문을 읽는다. 목차에서
   못 찾으면 `search_wiki` 로 찾고, 찾은 페이지의 본문을 `read_wiki` 로 읽는다.
3. **일정 질문이면** `list_schedules` 로 기간을 정해 목록을 보고, 필요한 것을
   `read_schedule` 로 읽는다.
4. **읽은 것으로 답한다.**

## 반드시 지킬 것

- **읽지 않고 답하지 않는다.** 목차의 제목이나 검색 결과의 한 줄만 보고 내용을 추측하면
  안 된다. `read_wiki` 또는 `read_schedule` 로 본문을 읽은 것만 근거다.
- **모르면 모른다고 한다.** 위키와 일정에 없는 것을 일반 지식으로 답하지 않는다. 찾아봤지만
  없으면 그렇게 답하는 것이 맞는 답이다 — 오류가 아니다.
- **일정 기간은 스스로 정한다.** 「다음」은 오늘 이후, 「지난」은 오늘 이전이다. 결과가
  없으면 기간을 넓혀 한 번 더 본다.
- **목록이 잘렸다고 하면 전부 본 것이 아니다.** 기간이나 조건을 좁혀 다시 부른다.
- **답변 본문에 출처 목록을 적지 않는다.** 대신 정해진 형식으로 `usedWikiIds`·
  `usedScheduleIds`·`questionType` 을 낸다. 읽었지만 답변의 근거가 되지 않은 것은 넣지 않는다.
- **`questionType` 은 질문을 보고 정한다.** 위키 얘기면 `wiki`, 일정 얘기면 `schedule`, 둘 다면
  `mixed`. 무엇을 읽었는지가 아니라 **무엇을 물었는지**다.

## 대답하는 방식

- 결론을 먼저 쓴다. 짧게 쓴다.
- 위키에 적힌 표현을 그대로 옮긴다. 바꿔 말하면서 뜻이 달라지면 안 된다.
"""


def chat_guide(history: list[dict], indexes: list[dict], *,
               today: date) -> str:
    """시스템 지침 한 덩이. 목차는 범위별로 이어 붙인다. 질문은 담지 않는다."""
    parts = [GUIDE, f"\n## 오늘\n\n{today.isoformat()} (KST)\n",
             "\n## 볼 수 있는 위키 목차\n"]
    if indexes:
        for entry in indexes:
            parts.append(f"\n### 범위 `{entry.get('scopeKey')}`\n\n"
                         f"{entry.get('indexMarkdown') or '(빈 목차)'}\n")
    else:
        parts.append("\n(목차가 없다. 위키 질문에는 답할 수 없다.)\n")

    if history:
        parts.append("\n## 이전 대화\n")
        for message in history:
            who = "사용자" if message.get("role") == "user" else "너"
            parts.append(f"\n{who}: {message.get('content')}")

    return "".join(parts)
```

**모델이 낼 형식.** `create_deep_agent(response_format=...)` 에 넘긴다 — 글을 파싱하지 않는다.

```python
class AnswerReport(BaseModel):
    """모델이 낼 신고. **출처의 최종 판정은 우리가 한다** — 여기 적힌 ID 중 실제로 읽은
    것만 응답에 들어간다 (`answer.build_response`)."""

    answer: str
    usedWikiIds: list[str] = Field(default_factory=list)
    usedScheduleIds: list[str] = Field(default_factory=list)
    questionType: Literal["wiki", "schedule", "mixed"]
```

- [ ] **Step 4: 통과를 확인한다**

Run: `uv run pytest tests/api/test_answer_agent.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add ai/src/wiki_api/answer_guide.py ai/tests/api/test_answer_agent.py
git commit -m "feat(ai): 챗봇 에이전트 지침을 쓴다"
```

---

### Task 8: 응답 조립과 오류 이름

**Files:**
- Modify: `src/wiki_api/answer.py` (전면 개정)
- Test: `tests/api/test_answer_agent.py`

**Interfaces:**
- Consumes: `trim_history`(Task 2), `ReadLedger`·`ChatQueryClient`·`build_wiki_tools`·`build_schedule_tools`(Task 3~5), `run_with_tools`(Task 6), `chat_guide`·`AnswerReport`(Task 7)
- Produces: `generate_answer(runtime, payload, *, backend_base_url: str, api_key: str, request_id: str = "") -> AnswerResponse`. 오류 코드 이름 상수 8개.

- [ ] **Step 1: 실패 테스트를 쓴다**

`tests/api/test_answer_agent.py` 에 추가:

```python
import pytest

from agent_runtime.base import RunResult
from wiki_api.answer import (
    NO_SOURCE_CODE,
    TURN_LIMIT_CODE,
    build_response,
)
from wiki_api.answer_tools import ReadLedger
from wiki_api.errors import InternalError


def _report(answer="연차는 15일입니다.", wikis=(), schedules=(), kind="wiki"):
    return {"answer": answer, "usedWikiIds": list(wikis),
            "usedScheduleIds": list(schedules), "questionType": kind}


def test_sources_are_what_the_model_used_and_actually_read():
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")
    ledger.note_schedule("31", "8월 워크샵")
    run = RunResult(text="", tool_calls={"read_wiki": 1, "read_schedule": 1},
                    structured=_report(wikis=["101"], schedules=["31"], kind="mixed"))

    response = build_response(run, ledger)

    assert response.answer == "연차는 15일입니다."
    assert response.questionType == "mixed"
    assert {(s.type, s.wikiId or s.scheduleId, s.title) for s in response.sources} == {
        ("wiki", "101", "휴가 규정"), ("schedule", "31", "8월 워크샵")}


def test_a_source_the_model_named_but_never_read_is_dropped():
    """읽은 기록이 화이트리스트다. 「취업 규칙을 썼다」고 신고해도 안 읽었으면 빠진다."""
    ledger = ReadLedger()
    ledger.note_wiki("108", "육아휴직")
    run = RunResult(text="", tool_calls={"read_wiki": 1},
                    structured=_report(wikis=["108", "999"]))

    response = build_response(run, ledger)

    assert [s.wikiId for s in response.sources] == ["108"]


def test_a_page_read_but_not_used_is_also_dropped():
    """3장 읽고 1장으로 답했으면 출처는 1장이다 — 「읽은 것 전부」와 다른 점이다."""
    ledger = ReadLedger()
    for wiki_id, title in [("101", "휴가 규정"), ("108", "육아휴직"), ("120", "출장")]:
        ledger.note_wiki(wiki_id, title)
    run = RunResult(text="", tool_calls={"read_wiki": 3},
                    structured=_report(wikis=["101"]))

    assert [s.wikiId for s in build_response(run, ledger).sources] == ["101"]


def test_no_report_falls_back_to_everything_read():
    """신고를 빼먹었을 때 출처를 0개로 만드는 것보다 과다 포함이 낫다."""
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")
    run = RunResult(text="연차는 15일입니다.", tool_calls={"read_wiki": 1})

    assert [s.wikiId for s in build_response(run, ledger).sources] == ["101"]


def test_looked_but_found_nothing_is_a_normal_answer():
    """FR-QNA-007 — 근거를 못 찾으면 정보 부족을 안내한다. 오류가 아니다."""
    run = RunResult(text="", tool_calls={"search_wiki": 1},
                    structured=_report(answer="위키에서 찾지 못했습니다."))

    response = build_response(run, ReadLedger())

    assert response.sources == []
    assert "찾지 못" in response.answer
    assert response.questionType == "wiki"


def test_never_even_looking_is_a_failure():
    """찾아보지도 않은 것은 고장이다 — 도구 호출이 0건이다."""
    run = RunResult(text="아마 15일일 것입니다.", tool_calls={})

    with pytest.raises(InternalError) as raised:
        build_response(run, ReadLedger())

    assert raised.value.code == NO_SOURCE_CODE
    assert raised.value.status == 500


def test_empty_answer_with_sources_is_also_a_failure():
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")

    with pytest.raises(InternalError):
        build_response(RunResult(text="   ", tool_calls={"read_wiki": 1}), ledger)


def test_turn_limit_has_its_own_name():
    """이름만 보고 무슨 일이 있었는지 알 수 있어야 한다."""
    assert TURN_LIMIT_CODE == "AGENT_TURN_LIMIT_REACHED"
    assert NO_SOURCE_CODE == "NO_WIKI_OR_SCHEDULE_WAS_READ"
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_answer_agent.py -k build_response -v`
Expected: FAIL — `ImportError: cannot import name 'build_response'`

- [ ] **Step 3: `answer.py` 를 개정한다**

기존 `generate_answer` 와 JSON 파싱 헬퍼(`_json_object` 등)를 지우고 아래로 대체한다. `trim_history` 는 Task 2 에서 넣은 것을 그대로 둔다.

```python
"""챗봇 — 에이전트 하나가 조회하고 답한다 (`POST /internal/v1/answers`).

**출처는 모델의 신고와 읽은 기록의 교집합이다.** 모델이 「이걸 썼다」고 적고, 우리가 도구
기록으로 검사한다 — 안 읽은 것을 신고해도 통과하지 못하고(지어내기 불가), 읽었지만 답에
쓰지 않은 것도 섞이지 않는다. 이전 구현은 모델이 낸 ID 를 **요청에 실려 온** 집합으로만
걸러서 「안 읽은 자료를 출처로 신고하는 것」을 막지 못했다.

**질문 유형은 모델이 질문 맥락으로 판단한다** (FR-QNA-002). 읽은 자료의 종류로 정하면 일정
질문을 위키 공지로 답했을 때 유형이 뒤집힌다.

**근거를 못 찾은 것은 실패가 아니다** (FR-QNA-007). 찾아봤는데 없으면 빈 출처로 안내하고,
도구를 아예 부르지 않은 실행만 실패로 낸다 — 그것은 「모른다」가 아니라 고장이다.

**MCP 도 작업 공간도 쓰지 않는다.** 쓰기가 없기 때문이다 — 그래서 지연 적재 fan-out
(S15P11B106-151)과 프로세스 밖 권한값 전달(152)이 이 경로에 걸리지 않는다.
"""

from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from agent_runtime.base import RunResult

from .answer_guide import AnswerReport, chat_guide
from .answer_tools import (
    ChatQueryClient,
    QueryFailed,
    ReadLedger,
    build_schedule_tools,
    build_wiki_tools,
)
from .errors import InternalError
from .schemas import AnswerRequest, AnswerResponse, AnswerSource

# 상한. 둘 다 근거 없는 시작점이다 (설계 §6.6). 턴 수가 주 장치, 시간은 안전장치.
# 8턴·60초에서 내렸다 — 채팅에서 1분 침묵은 사용자가 창을 닫고, 그 값에서는 시간이 먼저
# 걸려 턴 상한이 무의미해진다.
MAX_TURNS = 5
TIMEOUT_SECONDS = 25

# 오류 이름. **이름만 읽고 무슨 일이 있었는지 알 수 있게 짓는다** — 계약이 허용한 상태는
# 400·401·500 셋뿐이라 이름이 상태를 대신 설명해야 한다. 계약에 없는 이름이므로 MR 본문에
# 협의 항목으로 적는다 (`ai/CLAUDE.md`).
VALIDATION_CODE = "INVALID_ANSWER_REQUEST"
NO_SOURCE_CODE = "NO_WIKI_OR_SCHEDULE_WAS_READ"
WIKI_QUERY_CODE = "WIKI_QUERY_FAILED"
SCHEDULE_QUERY_CODE = "SCHEDULE_QUERY_FAILED"
TURN_LIMIT_CODE = "AGENT_TURN_LIMIT_REACHED"
TIMEOUT_CODE = "AGENT_TIMED_OUT"
MODEL_CODE = "MODEL_CALL_FAILED"
EMPTY_ANSWER_CODE = "ANSWER_WAS_EMPTY"


def build_response(run: RunResult, ledger: ReadLedger) -> AnswerResponse:
    """모델의 신고를 읽은 기록으로 검사해 응답을 만든다.

    **도구를 아예 부르지 않은 실행만 실패다.** 찾아봤지만 근거가 없었던 것은 정상 응답이다
    (FR-QNA-007) — 실패로 내면 사용자에게 「처리 중 문제가 생겼습니다」가 나가고, 지침의
    「모르면 모른다고 한다」와 정면으로 부딪친다.
    """
    if not run.tool_calls:
        raise InternalError(
            NO_SOURCE_CODE,
            "위키도 일정도 조회하지 않아 근거가 없습니다.", status=500)

    report = run.structured or {}
    answer = (str(report.get("answer") or "") or (run.text or "")).strip()
    if not answer:
        raise InternalError(EMPTY_ANSWER_CODE, "모델이 빈 답변을 냈습니다.", status=500)

    # 신고가 없으면 읽은 것 전부로 떨어진다 — 출처를 0개로 만드는 것보다 과다 포함이 낫다.
    if report:
        used_wikis = [str(i) for i in report.get("usedWikiIds") or []]
        used_schedules = [str(i) for i in report.get("usedScheduleIds") or []]
    else:
        used_wikis = [wiki_id for wiki_id, _ in ledger.wikis]
        used_schedules = [schedule_id for schedule_id, _ in ledger.schedules]

    # 읽은 기록이 화이트리스트다. 안 읽은 것을 신고해도 여기서 빠진다.
    sources = [AnswerSource(type="wiki", wikiId=wiki_id,
                            title=ledger.title_of_wiki(wiki_id))
               for wiki_id in used_wikis if ledger.title_of_wiki(wiki_id)]
    sources += [AnswerSource(type="schedule", scheduleId=schedule_id,
                             title=ledger.title_of_schedule(schedule_id))
                for schedule_id in used_schedules
                if ledger.title_of_schedule(schedule_id)]

    kind = report.get("questionType")
    if kind not in ("wiki", "schedule", "mixed"):
        # 신고가 없거나 값이 이상하면 읽은 것으로 채운다. 이때만 결과로 정한다.
        kind = ("mixed" if ledger.wikis and ledger.schedules
                else "schedule" if ledger.schedules else "wiki")
    return AnswerResponse(answer=answer, sources=sources, questionType=kind)


async def generate_answer(runtime, payload: AnswerRequest, *,
                          backend_base_url: str, api_key: str,
                          request_id: str = "") -> AnswerResponse:
    """에이전트를 한 번 돌리고 응답을 조립한다.

    `asyncio.to_thread` 로 띄우는 이유는 런타임이 sync 이기 때문이다. 그 스레드는 취소할
    수 없으므로 **실제 상한은 런타임에 넘긴 `timeout`** 이다 (`completion.py` 와 같은 이유).
    """
    import asyncio

    if not backend_base_url:
        raise InternalError(
            VALIDATION_CODE,
            "백엔드 주소가 설정되지 않아 위키·일정을 조회할 수 없습니다.", status=500)

    capabilities = {entry.scopeKey: entry.wikiCapability
                    for entry in payload.wikiIndexes if entry.wikiCapability}
    ledger = ReadLedger()
    client = ChatQueryClient(backend_base_url, api_key=api_key,
                             question_id=payload.questionId,
                             capabilities=capabilities)
    try:
        tools = [*build_wiki_tools(client, ledger),
                 *build_schedule_tools(client, ledger)]
        guide = chat_guide(
            trim_history([m.model_dump() for m in payload.conversationMessages]),
            [entry.model_dump() for entry in payload.wikiIndexes],
            # 사용자 감각과 맞춰야 한다. 모델은 오늘을 모른다.
            today=datetime.now(ZoneInfo("Asia/Seoul")).date(),
        )
        try:
            run = await asyncio.to_thread(
                runtime.run_with_tools, guide, payload.question,
                tools=tools, max_turns=MAX_TURNS, timeout=TIMEOUT_SECONDS,
                response_format=AnswerReport)
        except TimeoutError as timed_out:
            raise InternalError(TIMEOUT_CODE,
                                "제한 시간 안에 답변을 만들지 못했습니다.",
                                status=500) from timed_out
        except QueryFailed as failed:
            raise InternalError(WIKI_QUERY_CODE, "위키·일정 조회가 실패했습니다.",
                                status=500) from failed
        except NotImplementedError:
            raise
        except Exception as broke:                     # noqa: BLE001
            raise InternalError(MODEL_CODE, "모델 호출이 실패했습니다.",
                                status=500) from broke
        if run.error == "turn_limit":
            # 반쯤 읽고 만든 답변은 근거가 빠져 있다. 이름을 구분해 내보낸다 — 이것이
            # `MODEL_CALL_FAILED` 로 뭉개지면 상한을 조정할 근거를 못 얻는다.
            raise InternalError(TURN_LIMIT_CODE,
                                "정해진 횟수 안에 답변을 만들지 못했습니다.", status=500)
        return build_response(run, ledger)
    finally:
        client.close()
```

> `InternalError` 의 서명은 확인했다 — `(code, message, failure_stage=None, status=500, field_errors=None)`. `status=500` 이 기본값이지만 위 코드는 명시한다. `AnswerSource` 의 필드도 확인했다 — `type` · `wikiId` · `scheduleId` · `title`.
>
> `failure_stage` 를 쓸지는 백엔드와 확인이 필요하다. 지금 일정·턴 상한에 해당하는 단계 이름이 계약에 없다. 확인 전까지 넘기지 않는다 — 없는 값을 임의로 만들지 않는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `uv run pytest tests/api/test_answer_agent.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add ai/src/wiki_api/answer.py ai/tests/api/test_answer_agent.py
git commit -m "feat(ai): 챗봇 응답을 읽은 기록으로 조립하고 오류 이름을 나눈다"
```

---

### Task 9: 엔드포인트 합치기

**Files:**
- Modify: `src/wiki_api/schemas.py`
- Modify: `src/wiki_api/routers/answer.py`
- Delete: `src/wiki_api/answer_selection.py`, `tests/api/test_answer_selection.py`
- Modify: `tests/api/test_answer.py`, `tests/api/test_answer_schemas.py`, `tests/api/test_answer_contract_conformance.py`, `tests/api/test_answer_scale_ceiling.py`, `tests/api/test_chat_sim.py`
- Test: `tests/api/test_answer_agent.py`

**Interfaces:**
- Consumes: `generate_answer`(Task 8)
- Produces: `WikiIndexEntry` 에 `wikiCapability: str` (필수). `AnswerRequest` 는 `questionId`·`conversationId`·`question`·`conversationMessages`·`wikiIndexes`. `AnswerResponse` 에 `questionType`. `AnswerContextRequest`·`AnswerContextResponse`·`AnswerWiki`·`AnswerSchedule`·`ScheduleSummary` 삭제.

- [ ] **Step 1: 실패 테스트를 쓴다**

`tests/api/test_answer_agent.py` 에 추가:

```python
from fastapi.testclient import TestClient

from wiki_api.app import create_app


def test_the_first_stage_endpoint_is_gone():
    """바로 지운다 — 남겨두면 백엔드가 계속 부른다."""
    app = create_app(api_key="k", backend_base_url="http://backend")
    client = TestClient(app)

    response = client.post("/internal/v1/answer-context-selections", json={},
                           headers={"X-Internal-API-Key": "k"})

    assert response.status_code == 404


def test_request_no_longer_accepts_pushed_context():
    """`selectedWikis` 같은 옛 필드를 보내면 400 이다 — 조용히 무시하면 백엔드가
    보내고 있다고 믿는다."""
    import pytest
    from pydantic import ValidationError

    from wiki_api.schemas import AnswerRequest

    with pytest.raises(ValidationError):
        AnswerRequest(questionId="1", conversationId="c", question="q",
                      selectedWikis=[{"wikiId": "101", "title": "t",
                                      "contentMarkdown": "b"}])


def test_index_entry_carries_the_capability():
    from wiki_api.schemas import WikiIndexEntry

    entry = WikiIndexEntry(scopeKey="ALL", indexMarkdown="- 목차",
                           wikiCapability="cap-all")

    assert entry.wikiCapability == "cap-all"
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_answer_agent.py -k "gone or pushed or capability" -v`
Expected: FAIL — 1단계 경로가 아직 200/422 를 내고, `WikiIndexEntry` 에 필드가 없다.

- [ ] **Step 3: 스키마를 개정한다**

`src/wiki_api/schemas.py`:

```python
class WikiIndexEntry(Strict):
    """범위 하나의 목차와 그 범위 조회 허가값.

    **허가값을 별도 배열로 두지 않고 이 행에 넣는다.** 두 배열로 나누면 한쪽에만 있는
    범위가 생기고, 그 경우 에이전트가 목차는 읽었는데 본문은 못 읽는 상태가 된다.
    """

    scopeKey: str
    indexMarkdown: str
    # **필수다.** 없으면 그 범위의 위키를 한 장도 못 읽어 답변 근거가 사라지고, 원인이
    # 「근거 없음」으로 나와 짐작하기 어렵다. 이 변경은 이미 엔드포인트를 지우는 비호환
    # 변경이므로 「백엔드 배포 전 호환」을 위해 선택 필드로 둘 이유가 없다.
    wikiCapability: str


class AnswerRequest(Strict):
    """챗봇 요청. **본문도 일정 목록도 오지 않는다** — 에이전트가 도구로 조회한다."""

    questionId: str
    conversationId: str
    question: str
    conversationMessages: list[ConversationMessage] = Field(default_factory=list)
    wikiIndexes: list[WikiIndexEntry] = Field(default_factory=list)


class AnswerResponse(Strict):
    answer: str
    sources: list[AnswerSource] = Field(default_factory=list)
    # 1단계 응답이던 값이 여기로 옮겨왔다. 백엔드가 `question` 테이블에 저장한다.
    questionType: Literal["wiki", "schedule", "mixed"]
```

지운다: `AnswerContextRequest`, `AnswerContextResponse`, `AnswerWiki`, `AnswerSchedule`, `ScheduleSummary`, 그리고 `SELECTION_PATH` 상수.

- [ ] **Step 4: 라우터를 고친다**

`src/wiki_api/routers/answer.py` 를 대체한다:

```python
"""챗봇 답변 엔드포인트 하나.

세션을 열지 않는다 — 임시 색인도 MCP 서버도 필요 없고, 그래서 변환 큐의 직렬 잠금도
잡지 않는다. 채팅이 위키 변환을 기다릴 이유가 없다.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, FastAPI

from ..answer import generate_answer
from ..deps import make_api_key_guard, request_id
from ..schemas import AnswerRequest, AnswerResponse


def build_router(app: FastAPI) -> APIRouter:
    guard = make_api_key_guard(app.state.api_key)
    router = APIRouter(prefix="/internal/v1", dependencies=[Depends(guard)])

    # `response_model_exclude_none` — 계약 예시의 출처는 한쪽 ID 만 갖는다. 안 쓰는 쪽을
    # null 로 실어 보내면 Spring 이 answer_source 에 빈 컬럼을 쓰려 든다.
    @router.post("/answers", response_model=AnswerResponse,
                 response_model_exclude_none=True)
    async def answer(payload: AnswerRequest,
                     rid: str = Depends(request_id)) -> AnswerResponse:
        """에이전트가 목차를 보고 스스로 조회해 답한다."""
        return await generate_answer(app.state.runtime, payload,
                                     backend_base_url=app.state.backend_base_url,
                                     api_key=app.state.api_key, request_id=rid)

    return router
```

- [ ] **Step 5: 1단계를 지운다**

```bash
git rm ai/src/wiki_api/answer_selection.py ai/tests/api/test_answer_selection.py
```

- [ ] **Step 6: 남은 테스트를 새 계약에 맞춘다**

`tests/api/test_answer.py`·`test_answer_schemas.py`·`test_answer_contract_conformance.py`·`test_answer_scale_ceiling.py`·`test_chat_sim.py` 를 돌려 실패를 하나씩 고친다. 옛 필드를 쓰는 곳은 새 모양으로 바꾸고, 1단계만 검사하던 테스트는 지운다.

Run: `uv run pytest tests/api -k answer -v`

- [ ] **Step 7: 전체 테스트**

Run: `uv run pytest -m "not ocr" -q`
Expected: 실패 0

- [ ] **Step 8: 커밋**

```bash
git add ai/src/wiki_api/schemas.py ai/src/wiki_api/routers/answer.py ai/tests/api/
git commit -m "feat(ai): 챗봇 2단계를 엔드포인트 하나로 합친다"
```

---

### Task 10: 가짜 모델로 루프를 고정하고 진짜로 한 번 돌린다

> **계약은 이미 서 있다** — `1.8.0`, 2026-07-31 저녁에 반영·검증 완료. 이 태스크에서 계약을 다시 고칠 일은 실측으로 상한을 조정할 때뿐이고, 그때도 `ai/` 밖은 **먼저 제시하고 확인을 받는다** (`ai/CLAUDE.md`).

**Files:**
- Modify: `docs/superpowers/specs/2026-07-31-agent-endpoint-merge-design.md` (실측값 반영)
- Create: `tests/api/test_answer_loop_fake_model.py` (가짜 모델로 루프 고정)
- Create: `experiments/chat_agent_sim.py` (실기동 확인 스크립트)

**Interfaces:**
- Consumes: Task 1~9 전부
- Produces: 루프 회귀 테스트(0원)와 실기동 확인 결과.

- [ ] **Step 0: 가짜 모델로 루프를 고정한다 — 0원이다**

`GenericFakeChatModel`(langchain-core 에 이미 있다. 2026-07-31 확인)에 대사를 넣어 `DeepAgentsRuntime(chat_model=...)` 로 끼운다. **모델 판단만 가짜고 루프·도구·HTTP(가짜 백엔드)·장부·응답 조립은 전부 진짜다.** 도구를 직접 부르는 Task 4·5 테스트로는 못 잡는 것을 여기서 잡는다.

고정할 것 다섯:

| 무엇 | 어떻게 |
| --- | --- |
| 턴 상한이 자기 이름으로 나온다 | 끝내지 않고 도구만 계속 부르는 대사 → `AGENT_TURN_LIMIT_REACHED` |
| 출처 교집합 | 안 읽은 ID 를 신고하는 대사 → 그 ID 가 빠진다 |
| 찾아봤지만 없음 | `search_wiki` 만 부르고 끝내는 대사 → 200 + 빈 `sources` |
| 아예 안 찾아봄 | 도구 없이 바로 답하는 대사 → 500 `NO_WIKI_OR_SCHEDULE_WAS_READ` |
| 조회 실패 | 가짜 백엔드가 500 → 도구가 문장으로 알리고 에이전트가 계속 돈다 |

- [ ] **Step 1: 가짜 백엔드에 일정 두 입구를 더한다**

`experiments/query_gateway.py` 에 `GET /internal/v1/schedules` 와 `GET /internal/v1/schedules/{id}` 를 더한다. 설계 §7 의 모양을 따르고 `truncated` 를 낼 수 있게 한다.

- [ ] **Step 2: 실기동 확인 스크립트를 쓴다**

`experiments/chat_agent_sim.py` — 가짜 백엔드를 띄우고, AI 서버를 `AI_RUNTIME=deepagents`·`AI_MODEL=openai:gpt-4o-mini` 로 띄우고, 질문 **하나**를 보낸다. 확인할 것:

- 200 이 온다
- `sources` 가 비어 있지 않고 각 항목에 `title` 이 있다
- `questionType` 이 **질문**과 맞는다 (읽은 것이 아니라)
- 가짜 백엔드 로그에 조회가 도달했다
- **일정 질문에서 기간이 오늘 기준으로 잡혔다** — 지침의 오늘 날짜가 먹는지 보는 것이다

- [ ] **Step 3: 돌린다 — 질문 하나만**

```bash
INTERNAL_API_KEY=k AI_RUNTIME=deepagents AI_MODEL=openai:gpt-4o-mini \
  uv run python -m wiki_api.serve --port 8000 &
uv run python experiments/chat_agent_sim.py --api http://127.0.0.1:8000 --key k
```

**예산이 3달러다. 질문 하나로 끝낸다.** 정확도나 응답 시간 분포를 재지 않는다.

- [ ] **Step 4: 실측값을 설계 문서에 적는다**

턴 몇 번, 몇 초, 도구 몇 번 불렀는지를 §6.6 과 §8-3 에 적는다. **추정이었다고 적어 둔 값을 실측으로 바꾼다.** 값이 크게 다르면 상한을 조정한다.

- [ ] **Step 5: 상한을 바꿔야 하면 계약도 같이 본다**

실측이 25초를 크게 넘으면 상한을 올리거나 비동기 전환을 다시 검토한다. 계약 설명에 「AI는 25초 안에 응답한다」가 적혀 있으므로 **값이 바뀌면 그 줄도 바뀐다** — `ai/` 밖이므로 먼저 제시하고 확인을 받는다. 값이 그대로면 계약은 손대지 않는다.

- [ ] **Step 6: 커밋**

```bash
git add ai/experiments/query_gateway.py ai/experiments/chat_agent_sim.py \
        ai/tests/api/test_answer_loop_fake_model.py \
        ai/docs/superpowers/specs/2026-07-31-agent-endpoint-merge-design.md
git commit -m "test(ai): 가짜 모델로 챗봇 루프를 고정하고 실기동으로 확인한다"
```

---

## 남은 것 (이 계획 밖)

| 무엇 | 왜 밖인가 |
| --- | --- |
| 위키 변환·관리자 수정 합치기 | S15P11B106-151·152·154 가 먼저다 (설계 §2.1) |
| 챗봇 비동기 전환 | 응답 시간을 재기 전에는 필요한지 모른다 (§8-3) |
| 목차를 도구로 옮기기 | 100페이지쯤에서 다시 본다 (§8-2) |
| 권한 판정을 작업 번호로 통일 | 위키 쪽이 이미 돌아간다 (§3.1) |
| 프롬프트 캐싱 | OpenAI 경로에서 다시 재야 한다 (§8-2) |
