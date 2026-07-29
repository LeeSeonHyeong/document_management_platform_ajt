# 챗봇 답변 API 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계약의 `POST /internal/v1/answer-context-selections` 와 `POST /internal/v1/answers` 를 구현하고, 두 엔드포인트가 공통으로 쓰는 MCP 없는 단발 호출 계층을 만든다.

**Architecture:** 두 엔드포인트는 에이전트가 아니다 — 툴도 MCP 서버도 쓰지 않고 LLM 을 한 번 부른다. 그 능력(`complete`)이 아직 어느 런타임에도 없어서 먼저 만든다. 호출은 모두 `wiki_api/completion.py` 공통 async 어댑터를 지나며, 그 어댑터가 sync 런타임을 `asyncio.to_thread` 로 띄워 이벤트 루프를 지키고 `InternalError` 변환을 한곳에 모은다. 시간 상한은 모델 클라이언트에 걸어 실제로 연결을 끊는다.

**Tech Stack:** Python 3.12, FastAPI, pydantic v2, pytest(+pytest-asyncio), LangChain(`init_chat_model`) + `langchain-anthropic`, SSAFY GMS 게이트웨이.

**설계 근거:** `ai/docs/superpowers/specs/2026-07-28-chatbot-answer-api-design.md`. 이 계획은 그 문서를 대체하지 않는다 — 왜 그렇게 하는지는 spec 에 있고, 여기에는 무엇을 어떤 순서로 하는지만 있다.

## Global Constraints

- **계약이 정본이다.** `docs/api/AJT-FastAPI-Internal-API.postman_collection.json` (v1.3.0). 계약에 없는 상태 코드를 내지 않는다 — 400·401·500 뿐이고, 그 밖을 내면 Spring 분기에서 `UNEXPECTED_STATUS` 로 뭉개진다.
- **`ai/` 밖 파일을 수정하지 않는다.** `backend/`·`frontend/`·`docs/`·루트 파일은 담당자가 다르다. 읽기는 자유.
- **stage 는 `ai/` 하위 경로만 명시한다.** `git add -A`·`git add .` 금지. 커밋 전 `git status` 확인.
- **커밋 메시지에 `Co-Authored-By: Claude` 트레일러를 넣지 않는다.** 형식은 `<타입>(<범위>): <한국어 설명>`, Jira 키는 커밋에 쓰지 않는다.
- **`run` 경로를 바꾸지 않는다.** `experiments/` 의 기존 측정과 대조가 깨진다. `complete` 는 새 메서드로만 추가한다.
- **모델 이름은 정확히 쓴다.** GMS 가용(2026-07-29 실측): `claude-haiku-4-5-20251001`, `claude-sonnet-4-6`, `claude-sonnet-4-5-20250929`, `claude-opus-4-6`. 없는 것: `claude-sonnet-5`, `claude-haiku-4-5`, `claude-sonnet-4-5`, `claude-opus-4-5`.
- **크레딧 예산 205 이하.** 잔여 3,000 미만이고 haiku input 100토큰 ≈ 1크레딧이다. Task 1~10 은 크레딧 0 이며, API 를 부르는 것은 Task 11 뿐이다.
- **재시도를 켜지 않는다.** `max_retries=0`. 실패 1건이 조용히 2~3배 청구된다.
- **테스트 실행:** `cd ai && uv run pytest -m "not ocr"`. `tests/` 아래에 `mcp` 라는 이름의 디렉터리를 새로 만들지 않는다 (PyPI `mcp` 패키지를 가린다). `tests/__init__.py` 를 지우지 않는다.

---

## File Structure

| 파일 | 책임 |
| --- | --- |
| `src/agent_runtime/base.py` | `CompletionResult`, tier 상수, `Runtime.complete` 프로토콜, 프롬프트 조립 함수 |
| `src/agent_runtime/deep_agents.py` | 배포용 `complete` — `init_chat_model` 직호출, timeout·`max_retries=0` |
| `src/agent_runtime/claude_code.py` | 테스트용 `complete` — `claude -p`, MCP 없이 |
| `src/wiki_api/completion.py` | **신규.** 공통 async 어댑터. 이벤트 루프 보호, `InternalError` 변환, 관측 로그 |
| `src/wiki_api/telemetry.py` | **신규.** `complete` 호출 1건을 JSONL 한 줄로 기록 |
| `src/wiki_api/selection.py` | 기존 1단계. 어댑터를 쓰도록 이전 |
| `src/wiki_api/answer_selection.py` | **신규.** 챗봇 1단계 — 분류 + ID 선택 |
| `src/wiki_api/answer.py` | **신규.** 챗봇 2단계 — 답변 + 출처 |
| `src/wiki_api/routers/answer.py` | **신규.** 엔드포인트 2개 |
| `src/wiki_api/schemas.py` | 요청·응답 모델 추가 |
| `src/wiki_api/errors.py` | 두 딕셔너리에 각각 2경로 등록 |
| `experiments/chat_sim.py` | **신규.** Spring 역할 하네스 |
| `experiments/chat_questions.json` | **신규.** 질문과 기대 ID 정답 라벨 |

`answer_selection.py` 와 `answer.py` 를 한 파일에 넣지 않는다 — 단계마다 화이트리스트 규칙과 파싱 규칙이 다르고, 두 개를 합치면 어느 규칙이 어느 단계 것인지 읽어서 알 수 없다.

---

## Task 1: `CompletionResult` 와 tier 상수

**Files:**
- Modify: `ai/src/agent_runtime/base.py:42-68` (Protocol 블록과 그 위)
- Test: `ai/tests/runtime/test_completion_result.py` (create)

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `CompletionResult(text: str, input_tokens: int = 0, output_tokens: int = 0, cache_read_tokens: int = 0, cache_creation_tokens: int = 0, model: str = "", elapsed_seconds: float = 0.0)` — frozen dataclass
  - `FAST = "fast"`, `QUALITY = "quality"`, `DEFAULT_COMPLETE_TIMEOUT = 120`
  - `Runtime.complete(messages: list[dict], *, tier: str = QUALITY, timeout: int | None = None) -> CompletionResult`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/runtime/test_completion_result.py`:

```python
"""단발 호출의 결과형.

`RunResult` 와 나눠 둔 이유를 테스트로 못박는다 — 이쪽에는 `tool_calls`·`turns` 가 없고
대신 `cache_read_tokens` 가 있다. 캐시 적중이 0 이면 예산 계산이 3배 틀리는데 그것이
조용히 일어나므로, 값을 들고 다닐 자리가 결과형에 있어야 한다.
"""

import dataclasses

import pytest

from agent_runtime.base import (
    DEFAULT_COMPLETE_TIMEOUT,
    FAST,
    QUALITY,
    CompletionResult,
)


def test_텍스트만으로_만들_수_있다():
    result = CompletionResult(text="답변")
    assert result.text == "답변"
    assert result.input_tokens == 0
    assert result.output_tokens == 0
    assert result.cache_read_tokens == 0
    assert result.cache_creation_tokens == 0
    assert result.model == ""
    assert result.elapsed_seconds == 0.0


def test_불변이다():
    result = CompletionResult(text="답변")
    with pytest.raises(dataclasses.FrozenInstanceError):
        result.text = "다른 답변"


def test_에이전트_전용_필드가_없다():
    names = {f.name for f in dataclasses.fields(CompletionResult)}
    assert "tool_calls" not in names
    assert "turns" not in names
    assert "cache_read_tokens" in names


def test_tier_상수와_기본_상한():
    assert FAST == "fast"
    assert QUALITY == "quality"
    # 에이전트 루프(최대 30분)와 같은 예산을 주지 않는다.
    assert DEFAULT_COMPLETE_TIMEOUT == 120
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/runtime/test_completion_result.py -v`
Expected: FAIL — `ImportError: cannot import name 'CompletionResult' from 'agent_runtime.base'`

- [ ] **Step 3: 최소 구현**

`ai/src/agent_runtime/base.py` 의 `RunResult` 정의 **다음**, `class Runtime(Protocol):` **앞**에 추가:

```python
@dataclass(frozen=True)
class CompletionResult:
    """MCP 없는 단발 호출 1건.

    `RunResult` 와 나눠 둔 이유는 그쪽이 `tool_calls`·`turns` 처럼 에이전트에만 있는 필드를
    들고 있어서다 — 단발 호출에서 그것들은 언제나 빈 값이고, 같은 형을 쓰면 리포트의
    "툴을 0번 불렀다"가 의미 있는 관측인지 애초에 툴이 없었던 것인지 구분되지 않는다.

    `model` 은 **응답이 말한 모델**이다. 요청한 이름이 아니다. 게이트웨이(SSAFY GMS)가
    모델을 바꿔 끼울 수 있어서, 요청값을 기록하면 무엇을 쟀는지 모르게 된다.

    `cache_read_tokens` 가 여기 있는 것은 예산 때문이다. 목차처럼 여러 호출이 공유하는
    접두사에 캐시를 걸면 읽기가 10% 과금인데, 접두사 순서를 잘못 짜면 적중이 0 이 되고
    그것이 조용히 일어난다. 이 필드를 보면 알 수 있다.
    """

    text: str
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    cache_creation_tokens: int = 0
    model: str = ""
    elapsed_seconds: float = 0.0


# tier → 실제 모델 이름은 런타임이 번역한다. 호출자가 모델 문자열을 직접 주면 런타임마다
# 이름 체계가 달라 깨진다 (`anthropic:claude-…` vs CLI 표기).
FAST = "fast"
QUALITY = "quality"

# 단발 호출의 기본 상한. 에이전트 루프(최대 30분)와 같은 예산을 줄 이유가 없다 — 여기서
# 오래 걸리는 것은 진행이 아니라 고장이다. 호출자가 단계별로 더 짧게 준다.
DEFAULT_COMPLETE_TIMEOUT = 120
```

- [ ] **Step 4: 통과를 확인한다**

Run: `cd ai && uv run pytest tests/runtime/test_completion_result.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: Protocol 의 주석을 실물 시그니처로 바꾼다**

`ai/src/agent_runtime/base.py:53-68` 의 주석 블록 전체를 아래로 교체한다. 기존 블록은 `def complete(self, prompt: str, ...) -> RunResult | str` 를 주석으로 적어놓고 "두 프로덕션 런타임 모두 아직 이것을 구현하지 않는다 … 미해결로 남긴다" 로 끝난다.

```python
    def complete(self, messages: list[dict], *, tier: str = QUALITY,
                 timeout: int | None = None) -> CompletionResult: ...
    # ----- MCP 없는 단발 호출 -------------------------------------------------
    #
    # 문맥 선택과 챗봇 답변은 **에이전트가 아니다** — 입력이 목차·본문·질문뿐이라 툴이
    # 필요 없고, MCP 서버를 띄우는 것은 낭비이자 사고 위험이다(선택 호출이 위키를 고칠 수
    # 있게 된다). `bare=True` 플래그를 `run()` 에 더하는 대신 별도 이름으로 둔 이유는
    # 두 가지가 서로 다른 결과형을 내기 때문이다 (`RunResult` vs `CompletionResult`).
    #
    # `messages` 는 `{"role": ..., "content": ...}` 목록이다. `content` 는 문자열이거나
    # content block 목록이다 — 후자는 캐싱(`cache_control`)에 필요하다. CLI 런타임은
    # block 을 텍스트로 이어붙이고 `cache_control` 을 버린다.
    #
    # **`timeout` 은 실제로 강제해야 한다.** sync 구현을 `asyncio.to_thread` 로 띄우면
    # 그 스레드를 취소할 수 없으므로, `run` 과 같은 이유로 런타임 자체 상한이 유일한
    # 수단이다. 호출자 쪽 `asyncio.wait_for` 는 호출자를 풀어주기만 한다.
    #
    # **재시도를 켜지 않는다.** 실패 1건이 조용히 2~3배 청구된다.
```

옛 블록에 있던 "`claude_code.py` 가 수정 금지라" 는 문장은 **버린다.** 근거가 없다 — `NOTICE` 의 이식 범위는 `src/wiki_mcp/` 뿐이고 `agent_runtime/` 에는 이식 헤더가 없다. 실제 이유(측정 재현성)는 Task 5 의 docstring 에 적는다.

- [ ] **Step 6: 기존 테스트가 안 깨졌는지 확인한다**

Run: `cd ai && uv run pytest -m "not ocr" -q`
Expected: 기존과 같은 수가 통과. Protocol 은 런타임 검사를 하지 않으므로 `complete` 를 아직 구현하지 않은 런타임도 문제가 없다.

- [ ] **Step 7: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/src/agent_runtime/base.py ai/tests/runtime/test_completion_result.py
git commit -m "feat(ai): 단발 호출 결과형 CompletionResult 추가

MCP 없는 단발 호출을 RunResult 로 돌려주면 usage_metadata 와 응답이 말한
모델이 버려져 측정이 성립하지 않는다. 에이전트 전용 필드를 뺀 별도 형을 두고
Runtime 프로토콜의 주석을 실물 시그니처로 바꾼다."
```

---

## Task 2: 공통 async 어댑터

**Files:**
- Create: `ai/src/wiki_api/telemetry.py`
- Create: `ai/src/wiki_api/completion.py`
- Test: `ai/tests/api/test_completion_adapter.py` (create)

**Interfaces:**
- Consumes: `CompletionResult`, `QUALITY`, `DEFAULT_COMPLETE_TIMEOUT` (Task 1)
- Produces:
  - `async def complete(runtime, messages: list[dict], *, tier: str, timeout: int, error_code: str, path: str, request_id: str) -> CompletionResult`
  - `def record(path: str, request_id: str, tier: str, result: CompletionResult) -> None`
  - `TELEMETRY_ENV = "AI_COMPLETION_LOG"`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/api/test_completion_adapter.py`:

```python
"""단발 호출 어댑터 — 이벤트 루프 보호와 오류 변환.

**여기서 지키는 것은 서버 전체다.** `complete` 는 sync 메서드이므로 async 함수에서 그냥
부르면 이벤트 루프가 그 줄에서 멈추고, 그 사이 다른 모든 요청이 대기한다. 느린 질문 하나가
나머지 API 를 끌고 내려가는 사고다. 그래서 "다른 코루틴이 진행하는가"를 테스트한다.
"""

import asyncio
import json
import time

import pytest

from agent_runtime.base import CompletionResult
from wiki_api.completion import complete
from wiki_api.errors import InternalError
from wiki_api.telemetry import TELEMETRY_ENV

MESSAGES = [{"role": "user", "content": "질문"}]


class SyncRuntime:
    """sync `complete` 를 갖는 런타임. 프로덕션 두 런타임이 이 모양이다."""

    name = "fake-sync"

    def __init__(self, text="응답", sleep=0.0, raises=None):
        self.text = text
        self.sleep = sleep
        self.raises = raises
        self.calls: list[dict] = []

    def complete(self, messages, *, tier="quality", timeout=None):
        self.calls.append({"messages": messages, "tier": tier, "timeout": timeout})
        if self.sleep:
            time.sleep(self.sleep)
        if self.raises:
            raise self.raises
        return CompletionResult(text=self.text, input_tokens=10, output_tokens=2,
                                model="claude-haiku-4-5-20251001")


class AsyncRuntime:
    """async `complete` 를 갖는 런타임. 테스트 편의용 경로."""

    name = "fake-async"

    async def complete(self, messages, *, tier="quality", timeout=None):
        await asyncio.sleep(0)
        return CompletionResult(text="비동기 응답")


class NoCompleteRuntime:
    name = "fake-bare"


async def test_sync_런타임_결과를_돌려준다():
    runtime = SyncRuntime(text="고른 결과")
    result = await complete(runtime, MESSAGES, tier="fast", timeout=5,
                            error_code="X_FAILED", path="/p", request_id="rid-1")
    assert result.text == "고른 결과"
    assert runtime.calls[0]["tier"] == "fast"
    # 상한을 런타임에 그대로 넘겨야 한다 — 실제로 호출을 끊는 유일한 지점이다.
    assert runtime.calls[0]["timeout"] == 5


async def test_sync_호출_중에_이벤트_루프가_살아_있다():
    """회귀 테스트. `selection.py` 가 sync 반환을 직접 받던 시절의 사고를 막는다."""
    runtime = SyncRuntime(sleep=0.3)
    ticks = 0

    async def ticker():
        nonlocal ticks
        for _ in range(10):
            await asyncio.sleep(0.02)
            ticks += 1

    task = asyncio.create_task(ticker())
    await complete(runtime, MESSAGES, tier="fast", timeout=5,
                   error_code="X_FAILED", path="/p", request_id="rid-1")
    await task
    # 루프가 막혔다면 ticker 가 한 번도 못 돌았다.
    assert ticks >= 5


async def test_async_런타임도_받는다():
    result = await complete(AsyncRuntime(), MESSAGES, tier="fast", timeout=5,
                            error_code="X_FAILED", path="/p", request_id="rid-1")
    assert result.text == "비동기 응답"


async def test_complete_가_없으면_InternalError():
    with pytest.raises(InternalError) as caught:
        await complete(NoCompleteRuntime(), MESSAGES, tier="fast", timeout=5,
                       error_code="X_FAILED", path="/p", request_id="rid-1")
    assert caught.value.code == "X_FAILED"
    assert caught.value.status == 500


async def test_런타임_예외를_InternalError_로_바꾼다():
    runtime = SyncRuntime(raises=RuntimeError("게이트웨이 거부"))
    with pytest.raises(InternalError) as caught:
        await complete(runtime, MESSAGES, tier="fast", timeout=5,
                       error_code="X_FAILED", path="/p", request_id="rid-1")
    assert caught.value.code == "X_FAILED"
    assert "게이트웨이 거부" in caught.value.message


async def test_타임아웃을_InternalError_로_바꾼다():
    runtime = SyncRuntime(sleep=0.5)
    with pytest.raises(InternalError) as caught:
        await complete(runtime, MESSAGES, tier="fast", timeout=0,
                       error_code="X_FAILED", path="/p", request_id="rid-1")
    assert caught.value.code == "X_FAILED"
    assert caught.value.failure_stage.value == "agent_timeout"


async def test_관측_로그를_한_줄_남긴다(tmp_path, monkeypatch):
    log = tmp_path / "completions.jsonl"
    monkeypatch.setenv(TELEMETRY_ENV, str(log))
    await complete(SyncRuntime(), MESSAGES, tier="quality", timeout=5,
                   error_code="X_FAILED", path="/internal/v1/answers",
                   request_id="rid-9")
    lines = log.read_text(encoding="utf-8").strip().split("\n")
    assert len(lines) == 1
    row = json.loads(lines[0])
    assert row["requestId"] == "rid-9"
    assert row["path"] == "/internal/v1/answers"
    assert row["tier"] == "quality"
    assert row["model"] == "claude-haiku-4-5-20251001"
    assert row["inputTokens"] == 10


async def test_환경변수가_없으면_기록하지_않는다(monkeypatch):
    monkeypatch.delenv(TELEMETRY_ENV, raising=False)
    # 예외 없이 통과하면 된다 — 배포에서 파일이 무한히 자라면 안 된다.
    result = await complete(SyncRuntime(), MESSAGES, tier="fast", timeout=5,
                            error_code="X_FAILED", path="/p", request_id="rid-1")
    assert result.text == "응답"
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_completion_adapter.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'wiki_api.completion'`

- [ ] **Step 3: 관측 로그를 구현한다**

`ai/src/wiki_api/telemetry.py`:

```python
"""`complete` 호출 1건을 JSONL 한 줄로 남긴다.

계약 응답에는 토큰·비용 필드가 없고 바꿀 이유도 없다. 그런데 측정에는 그 값이 필요하고,
하네스는 서버를 HTTP 로 부르니 반환값을 볼 수 없다. `wiki_mcp/telemetry.py` 가 툴 호출에서
같은 문제를 이미 이렇게 풀었다 — 서버가 파일에 쓰고 하네스가 읽는다.

**이 로그로 업스트림 호출 횟수를 알 수는 없다.** 한 줄은 논리적 `complete` 1건이므로,
클라이언트가 내부에서 재시도하면 그것도 한 줄이다. 재시도 검증은 mock 서버가 받은 요청을
세는 쪽이다 (`tests/api/test_deepagents_complete.py`).
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path

from agent_runtime.base import CompletionResult

logger = logging.getLogger("llmwiki.api")

# 비어 있으면 기록하지 않는다. 배포에서 파일이 무한히 자라면 안 된다.
TELEMETRY_ENV = "AI_COMPLETION_LOG"


def record(path: str, request_id: str, tier: str, result: CompletionResult) -> None:
    target = os.environ.get(TELEMETRY_ENV, "")
    if not target:
        return
    row = {
        "requestId": request_id,
        "path": path,
        "tier": tier,
        "model": result.model,
        "inputTokens": result.input_tokens,
        "outputTokens": result.output_tokens,
        "cacheReadTokens": result.cache_read_tokens,
        "cacheCreationTokens": result.cache_creation_tokens,
        "elapsedSeconds": result.elapsed_seconds,
    }
    try:
        with Path(target).open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    except OSError as exc:
        # 관측이 요청을 죽이면 안 된다. 로그 경로가 잘못된 것이 500 의 이유가 될 수 없다.
        logger.warning("관측 로그를 쓰지 못했다 (%s): %s", target, exc)
```

- [ ] **Step 4: 어댑터를 구현한다**

`ai/src/wiki_api/completion.py`:

```python
"""MCP 없는 단발 호출의 유일한 입구.

**모든 호출자가 이 함수만 쓴다.** 런타임의 `complete` 를 직접 부르면 두 가지가 깨진다.

1. **이벤트 루프.** `complete` 는 sync 다. async 함수에서 그냥 부르면 그 줄에서 루프가
   멈추고 서버의 모든 요청이 대기한다. `selection.py` 가 실제로 그 모양이었다 —
   `complete` 가 아직 없어서 죽은 경로였을 뿐이다.
2. **오류 모양.** 계약은 엔드포인트마다 500 코드 이름이 다르다. 변환을 호출자마다 쓰면
   한 곳이 빠지고 그러면 `INTERNAL_SERVER_ERROR` 가 나가 Spring 분기가 깨진다.

두 겹의 상한을 둔다. `asyncio.wait_for` 는 **호출자**를 풀어주고, 런타임에 넘긴 `timeout`
이 **연결**을 끊는다. `to_thread` 로 띄운 스레드는 취소할 수 없으므로 앞의 것만으로는
호출이 계속 살아 크레딧을 쓴다 (`base.py` 의 `run` 주석과 같은 이유).
"""

from __future__ import annotations

import asyncio
import inspect

from agent_runtime.base import QUALITY, CompletionResult

from .errors import FailureStage, InternalError
from .telemetry import record

# 스레드를 취소할 수 없으므로 바깥 상한을 런타임 상한보다 조금 넉넉하게 준다. 같게 주면
# 런타임이 자기 오류를 조립할 시간에 바깥이 먼저 터져 "타임아웃"이 실제 원인을 덮는다.
_OUTER_GRACE_SECONDS = 5


async def complete(runtime, messages: list[dict], *, tier: str = QUALITY,
                   timeout: int, error_code: str, path: str,
                   request_id: str) -> CompletionResult:
    """런타임에 한 번 묻고 `CompletionResult` 를 돌려준다.

    `path` 와 `request_id` 를 필수 인자로 받는 이유는 관측 로그가 그 둘을 쓰기 때문이다.
    `ContextVar` + 미들웨어로도 되지만, 하네스가 함수를 직접 부를 때(`--dry-run`) 값이
    비어 로그가 요청과 상관되지 않는다.
    """
    method = getattr(runtime, "complete", None)
    if method is None:
        raise InternalError(
            error_code,
            f"런타임 {getattr(runtime, 'name', '?')} 에 complete 가 없습니다.",
            FailureStage.AGENT_START)

    outer = timeout + _OUTER_GRACE_SECONDS
    try:
        if inspect.iscoroutinefunction(method):
            result = await asyncio.wait_for(
                method(messages, tier=tier, timeout=timeout), timeout=outer)
        else:
            result = await asyncio.wait_for(
                asyncio.to_thread(method, messages, tier=tier, timeout=timeout),
                timeout=outer)
    except asyncio.TimeoutError as exc:
        raise InternalError(
            error_code, f"단발 호출이 제한 시간({timeout}초)을 초과했습니다.",
            FailureStage.AGENT_TIMEOUT) from exc
    except InternalError:
        raise
    except Exception as exc:
        raise InternalError(error_code, f"단발 호출에 실패했습니다 — {exc}",
                            FailureStage.AGENT_ERROR) from exc

    record(path, request_id, tier, result)
    return result
```

- [ ] **Step 5: 통과를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_completion_adapter.py -v`
Expected: PASS (8 passed)

`test_타임아웃을_InternalError_로_바꾼다` 가 실패하면 `_OUTER_GRACE_SECONDS` 때문에 `timeout=0` 의 바깥 상한이 5초라 0.5초 sleep 이 통과한 것이다. 그때는 테스트에서 `timeout=-5` 를 주거나 `SyncRuntime(sleep=6)` 으로 바꾸는 대신, `_OUTER_GRACE_SECONDS` 를 모듈 상수로 monkeypatch 한다:

```python
async def test_타임아웃을_InternalError_로_바꾼다(monkeypatch):
    monkeypatch.setattr("wiki_api.completion._OUTER_GRACE_SECONDS", 0)
    runtime = SyncRuntime(sleep=0.5)
    with pytest.raises(InternalError) as caught:
        await complete(runtime, MESSAGES, tier="fast", timeout=0,
                       error_code="X_FAILED", path="/p", request_id="rid-1")
    assert caught.value.failure_stage.value == "agent_timeout"
```

- [ ] **Step 6: 전체 테스트**

Run: `cd ai && uv run pytest -m "not ocr" -q`
Expected: 전부 통과

- [ ] **Step 7: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/src/wiki_api/completion.py ai/src/wiki_api/telemetry.py ai/tests/api/test_completion_adapter.py
git commit -m "feat(ai): 단발 호출 공통 async 어댑터 추가

sync complete 를 async 함수에서 직접 부르면 이벤트 루프가 멈춰 서버의 모든
요청이 대기한다. to_thread 로 띄우고 오류 변환과 관측 로그를 한곳에 모은다."
```

---

## Task 3: `selection.py` 를 어댑터로 이전

**Files:**
- Modify: `ai/src/wiki_api/selection.py:104-167` (`select_wikis` 와 `_complete`)
- Modify: `ai/src/wiki_api/routers/wiki.py:74-81` (`rid` 를 넘긴다)
- Modify: `ai/tests/api/test_selection.py:39-64` (`FakeRuntime` 시그니처)
- Test: 같은 파일

**Interfaces:**
- Consumes: `wiki_api.completion.complete` (Task 2)
- Produces: `async def select_wikis(runtime, payload: SelectionRequest, *, request_id: str) -> SelectionResponse`

**왜 이 태스크가 지금 필요한가:** Task 4·5 가 `complete` 를 실물로 만들면 `selection.py:129` 의 죽은 경로가 살아난다. 그 경로는 sync 반환을 그대로 받으므로 `/wiki-context-selections` 가 최대 300초 동안 서버 전체를 막는다. **회귀를 우리가 만드는 것이므로 런타임 구현보다 먼저 막는다.**

- [ ] **Step 1: 기존 테스트의 `FakeRuntime` 을 새 시그니처로 바꾼다**

`ai/tests/api/test_selection.py:39-51` 을 교체:

```python
class FakeRuntime:
    """MCP 없는 단발 호출을 받는 런타임 (`Runtime.complete`)."""

    name = "fake-select"

    def __init__(self, text: str, error: str | None = None):
        self.text = text
        self.error = error
        self.prompts: list[str] = []
        self.tiers: list[str] = []

    def complete(self, messages, *, tier="quality", timeout=None) -> CompletionResult:
        # 문맥 선택은 단일 user 메시지다. 프롬프트 검증은 그 본문을 본다.
        self.prompts.append(_text_of(messages))
        self.tiers.append(tier)
        if self.error:
            raise RuntimeError(self.error)
        return CompletionResult(text=self.text)


def _text_of(messages) -> str:
    """content 가 문자열이거나 block 목록이다. 테스트는 둘 다 문자열로 본다."""
    parts = []
    for message in messages:
        content = message.get("content")
        if isinstance(content, str):
            parts.append(content)
        else:
            parts.extend(block.get("text", "") for block in content or [])
    return "\n".join(parts)
```

임포트 줄(`ai/tests/api/test_selection.py:13`)도 바꾼다:

```python
from agent_runtime.base import CompletionResult, selection_instruction
```

`RunResult` 를 쓰는 다른 테스트가 같은 파일에 있으면 임포트에 남겨 둔다.

- [ ] **Step 2: `error` 를 예외로 바꾼 이유를 반영해 기존 오류 테스트를 고친다**

기존 파일에서 `FakeRuntime(text="", error="…")` 로 500 을 기대하는 테스트를 찾는다.

Run: `cd ai && uv run grep -n "error=" tests/api/test_selection.py`

`CompletionResult` 에는 `error` 필드가 없다 — 실패는 예외로 올린다(`RunResult.error` 는 에이전트 전용이다). 그 테스트는 그대로 두면 된다: `FakeRuntime` 이 이제 `RuntimeError` 를 던지고 어댑터가 `WIKI_CONTEXT_SELECTION_FAILED` 로 바꾼다. 기대 코드가 같으므로 어서션은 변경 불필요.

- [ ] **Step 3: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_selection.py -v`
Expected: FAIL — `select_wikis()` 가 아직 `request_id` 를 받지 않고, `_complete` 가 `prompt` 문자열을 넘기므로 `FakeRuntime.complete` 의 `messages` 자리에 문자열이 들어가 `_text_of` 가 터진다.

- [ ] **Step 4: `selection.py` 를 어댑터로 옮긴다**

`ai/src/wiki_api/selection.py` 에서 `_complete` 함수(114-167행) **전체를 삭제**하고 `select_wikis` 를 교체한다:

```python
async def select_wikis(runtime, payload: SelectionRequest, *,
                       request_id: str = "") -> SelectionResponse:
    prompt = selection_instruction(payload.parsedMarkdown, payload.currentIndex,
                                   payload.changeType, payload.removedParsedMarkdown)
    result = await complete(
        runtime, [{"role": "user", "content": prompt}],
        tier=FAST, timeout=SELECTION_TIMEOUT_SECONDS, error_code=ERROR_CODE,
        path=PATH, request_id=request_id)
    wiki_ids, reason = parse_selection(result.text, payload.currentIndex)
    if not reason:
        reason = "관련된 위키를 찾지 못했습니다." if not wiki_ids else "선택 근거가 없습니다."
    return SelectionResponse(wikiIds=wiki_ids, reason=reason)
```

파일 상단의 임포트를 정리한다 — `asyncio`·`inspect`·`tempfile`·`Path` 는 `_complete` 와 함께 사라졌다:

```python
from __future__ import annotations

import json
import re

from agent_runtime.base import FAST, selection_instruction

from .completion import complete
from .schemas import SelectionRequest, SelectionResponse
```

`FailureStage`·`InternalError` 임포트도 더 이상 쓰지 않으면 지운다. 상수에 경로를 추가한다:

```python
PATH = "/internal/v1/wiki-context-selections"
```

`SELECTION_TIMEOUT_SECONDS = 300` 은 그대로 둔다. 이 값은 계약 시절의 상한이고 챗봇 상한(30·60초)과 별개다 — 문맥 선택은 문서 본문 전체를 싣는다.

`tier` 를 `FAST` 로 준 것은 문맥 선택이 목차에서 ID 를 고르는 일이고 화이트리스트가 뒤를 받치기 때문이다. 이것이 이전에 없던 동작 변화이므로 커밋 메시지에 적는다.

- [ ] **Step 5: 라우터에서 `rid` 를 넘긴다**

`ai/src/wiki_api/routers/wiki.py:81` 의 `return await select_wikis(app.state.runtime, payload)` 를:

```python
        return await select_wikis(app.state.runtime, payload, request_id=rid)
```

`rid` 는 이미 `rid: str = Depends(request_id)` 로 받고 있다(74행) — `wiki-transformations`·`wiki-edits` 는 세션에 넘기는데(100·144행) 이 엔드포인트만 안 넘겼다.

- [ ] **Step 6: 통과를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_selection.py -v`
Expected: PASS

- [ ] **Step 7: 이벤트 루프 회귀 테스트를 이 엔드포인트에도 붙인다**

`ai/tests/api/test_selection.py` 끝에 추가:

```python
def test_선택_중에_다른_요청이_막히지_않는다():
    """sync `complete` 가 도는 동안 서버가 살아 있어야 한다.

    어댑터를 거치지 않으면 이 테스트가 실패한다 — 그것이 이 파일을 옮긴 이유다.
    """
    import threading
    import time as _time

    class SlowRuntime(FakeRuntime):
        def complete(self, messages, *, tier="quality", timeout=None):
            _time.sleep(0.4)
            return super().complete(messages, tier=tier, timeout=timeout)

    app = create_app(api_key=API_KEY)
    app.state.runtime = SlowRuntime(text='{"wikiIds": ["101"], "reason": "휴가"}')
    client = TestClient(app)
    headers = {"X-Internal-API-Key": API_KEY}

    docs_seen: list[int] = []

    def poke():
        _time.sleep(0.1)
        docs_seen.append(client.get("/openapi.json", headers=headers).status_code)

    thread = threading.Thread(target=poke)
    thread.start()
    response = client.post("/internal/v1/wiki-context-selections",
                           json=REQUEST, headers=headers)
    thread.join()

    assert response.status_code == 200
    assert docs_seen == [200]
```

- [ ] **Step 8: 통과와 전체 테스트**

Run: `cd ai && uv run pytest -m "not ocr" -q`
Expected: 전부 통과

- [ ] **Step 9: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/src/wiki_api/selection.py ai/src/wiki_api/routers/wiki.py ai/tests/api/test_selection.py
git commit -m "refactor(ai): 문맥 선택을 단발 호출 어댑터로 옮긴다

런타임에 complete 가 생기면 기존 경로가 sync 반환을 그대로 받아 이벤트 루프를
최대 300초 막는다. 어댑터를 거치게 하고 requestId 를 꿴다. 문맥 선택의 tier 는
fast 로 둔다 — 목차에서 ID 를 고르는 일이고 화이트리스트가 뒤를 받친다."
```

---

## Task 4: deepagents `complete` — 배포 경로

**Files:**
- Modify: `ai/src/agent_runtime/deep_agents.py:26-50` (임포트·상수), `:93-100` (클래스 앞부분)
- Modify: `ai/pyproject.toml:28-34` (`deepagents` extra)
- Test: `ai/tests/api/test_deepagents_complete.py` (create)

**Interfaces:**
- Consumes: `CompletionResult`, `FAST`, `QUALITY`, `DEFAULT_COMPLETE_TIMEOUT` (Task 1)
- Produces:
  - `DeepAgentsRuntime.complete(messages, *, tier=QUALITY, timeout=None) -> CompletionResult`
  - `DeepAgentsRuntime._model_for(tier: str) -> str`
  - `DEFAULT_TIER_MODELS: dict[str, str]`, `TIER_ENV: dict[str, str]`

**크레딧:** 0. 모든 테스트가 로컬 mock 서버를 향한다.

- [ ] **Step 1: mock 서버 기반 실패 테스트를 쓴다**

`ai/tests/api/test_deepagents_complete.py`:

```python
"""배포 경로의 단발 호출 — timeout 강제와 재시도 금지.

**로컬 HTTP mock 을 쓴다.** 두 가지를 정확히 재려면 지연과 실패를 우리가 정해야 한다.

  * timeout: "큰 프롬프트 + timeout=1" 은 불안정하다. 모델이 1초 안에 답하거나 입력
    오류를 즉시 돌려주면 통과해도 아무것도 증명하지 않는다
  * 재시도: 관측 로그는 논리적 호출당 한 줄이라 내부 재시도 3회도 한 줄이다. **세는 층이
    틀렸다.** mock 이 받은 요청을 세야 한다

크레딧을 쓰지 않는다. GMS 실호출은 별도 스모크다.
"""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

pytest.importorskip("langchain_anthropic")

from agent_runtime.base import FAST, QUALITY, CompletionResult  # noqa: E402
from agent_runtime.deep_agents import (  # noqa: E402
    DEFAULT_TIER_MODELS,
    TIER_ENV,
    DeepAgentsRuntime,
)

MESSAGES = [{"role": "user", "content": "연차 며칠?"}]


class _State:
    def __init__(self):
        self.requests: list[dict] = []
        self.delay = 0.0
        self.status = 200


def _make_handler(state: _State):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            state.requests.append({"path": self.path, "body": json.loads(body or b"{}")})
            if state.delay:
                time.sleep(state.delay)
            if state.status != 200:
                self.send_response(state.status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"type":"error","error":{"type":"api_error",'
                                 b'"message":"mock failure"}}')
                return
            payload = {
                "id": "msg_mock", "type": "message", "role": "assistant",
                # 게이트웨이가 모델을 바꿔 끼울 수 있다는 것을 재현한다 — 요청한 이름과
                # 다른 값을 돌려주고, 결과형이 이쪽을 기록하는지 본다.
                "model": "mock-model-substituted",
                "content": [{"type": "text", "text": "연차는 15일입니다."}],
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 123, "output_tokens": 45,
                          "cache_read_input_tokens": 100,
                          "cache_creation_input_tokens": 7},
            }
            data = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_args):
            pass

    return Handler


@pytest.fixture
def mock_anthropic(monkeypatch):
    state = _State()
    server = HTTPServer(("127.0.0.1", 0), _make_handler(state))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key-not-real")
    monkeypatch.setenv("ANTHROPIC_BASE_URL", f"http://{host}:{port}")
    yield state
    server.shutdown()
    server.server_close()


def test_결과형에_토큰과_응답_모델이_담긴다(mock_anthropic):
    runtime = DeepAgentsRuntime()
    result = runtime.complete(MESSAGES, tier=FAST, timeout=10)
    assert isinstance(result, CompletionResult)
    assert result.text == "연차는 15일입니다."
    assert result.input_tokens == 123
    assert result.output_tokens == 45
    assert result.cache_read_tokens == 100
    assert result.cache_creation_tokens == 7
    # 요청한 이름이 아니라 응답이 말한 모델이어야 한다.
    assert result.model == "mock-model-substituted"
    assert result.elapsed_seconds >= 0


def test_timeout_이_실제로_끊는다(mock_anthropic):
    mock_anthropic.delay = 3.0
    runtime = DeepAgentsRuntime()
    with pytest.raises(Exception):  # noqa: B017 — 클라이언트 예외형은 버전에 따라 다르다
        runtime.complete(MESSAGES, tier=FAST, timeout=1)


def test_재시도하지_않는다(mock_anthropic):
    """mock 이 받은 요청 수가 1이어야 한다. 관측 로그로는 이걸 알 수 없다."""
    mock_anthropic.status = 500
    runtime = DeepAgentsRuntime()
    with pytest.raises(Exception):  # noqa: B017
        runtime.complete(MESSAGES, tier=FAST, timeout=10)
    assert len(mock_anthropic.requests) == 1


def test_tier_가_모델을_고른다(mock_anthropic):
    runtime = DeepAgentsRuntime()
    runtime.complete(MESSAGES, tier=FAST, timeout=10)
    runtime.complete(MESSAGES, tier=QUALITY, timeout=10)
    sent = [r["body"]["model"] for r in mock_anthropic.requests]
    assert sent[0] == DEFAULT_TIER_MODELS[FAST].removeprefix("anthropic:")
    assert sent[1] == DEFAULT_TIER_MODELS[QUALITY].removeprefix("anthropic:")


def test_환경변수가_tier_모델을_덮는다(mock_anthropic, monkeypatch):
    monkeypatch.setenv(TIER_ENV[FAST], "anthropic:claude-sonnet-4-5-20250929")
    runtime = DeepAgentsRuntime()
    runtime.complete(MESSAGES, tier=FAST, timeout=10)
    assert mock_anthropic.requests[0]["body"]["model"] == "claude-sonnet-4-5-20250929"


def test_모르는_tier_는_터진다():
    with pytest.raises(ValueError, match="모르는 tier"):
        DeepAgentsRuntime()._model_for("cheap")


def test_기본_모델은_게이트웨이에_있는_이름이다():
    """GMS 에 없는 이름을 기본값으로 두면 배포 첫 요청이 400 이다."""
    assert DEFAULT_TIER_MODELS[FAST] == "anthropic:claude-haiku-4-5-20251001"
    assert DEFAULT_TIER_MODELS[QUALITY] == "anthropic:claude-sonnet-4-6"
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_deepagents_complete.py -v`
Expected: FAIL — `ImportError: cannot import name 'DEFAULT_TIER_MODELS'`

- [ ] **Step 3: `langchain` 의존성을 선언한다**

`ai/pyproject.toml` 의 `deepagents` extra 에 한 줄 추가:

```toml
deepagents = [
    "deepagents",
    "langchain",
    "langchain-mcp-adapters",
    "langchain-anthropic",
    "python-dotenv",
]
```

`init_chat_model` 을 직접 임포트하므로 전이 의존성이어도 명시한다 (`ai/CLAUDE.md` 함정 — uvicorn·python-multipart 에서 한 번씩 밟았다). `langchain` 을 쓰는 이유는 `anthropic:claude-…` 접두사를 `run` 과 똑같이 해석해서다.

Run: `cd ai && uv sync --extra deepagents`

- [ ] **Step 4: 구현한다**

`ai/src/agent_runtime/deep_agents.py` 임포트를 바꾼다(35행):

```python
from .base import DEFAULT_COMPLETE_TIMEOUT, FAST, QUALITY, CompletionResult, RunResult
```

`os` 를 표준 임포트에 추가한다(28-31행 근처):

```python
import logging
import os
import tempfile
import time
```

`logger = ...` 다음에 상수를 둔다:

```python
# tier → 모델. 게이트웨이가 가진 것에 묶인다 — GMS 실측(2026-07-29)으로 확인된 이름만
# 쓴다. 짧은 별칭(`claude-haiku-4-5`)은 400 이고 `claude-sonnet-5` 는 GMS 에 없다.
DEFAULT_TIER_MODELS = {
    FAST: "anthropic:claude-haiku-4-5-20251001",
    QUALITY: "anthropic:claude-sonnet-4-6",
}
# 코드 수정 없이 갈아끼우기 위한 환경변수. 게이트웨이 목록이 바뀌면 배포에서 먼저 막힌다.
TIER_ENV = {FAST: "AI_MODEL_FAST", QUALITY: "AI_MODEL_QUALITY"}
```

`DeepAgentsRuntime.__init__` 다음, `run` 앞에 메서드 둘을 넣는다:

```python
    def _model_for(self, tier: str) -> str:
        """tier → 모델 이름. 환경변수가 있으면 그것을 쓴다.

        모르는 tier 를 조용히 기본 모델로 떨어뜨리지 않는다 — 오타 하나가 측정을
        무의미하게 만드는 것보다 즉시 터지는 쪽이 낫다.
        """
        if tier not in DEFAULT_TIER_MODELS:
            raise ValueError(f"모르는 tier: {tier!r} (가능: {sorted(DEFAULT_TIER_MODELS)})")
        return os.environ.get(TIER_ENV[tier], "") or DEFAULT_TIER_MODELS[tier]

    def complete(self, messages: list[dict], *, tier: str = QUALITY,
                 timeout: int | None = None) -> CompletionResult:
        """MCP 없는 단발 호출. 에이전트도 툴도 만들지 않고 모델만 부른다.

        `timeout` 을 **모델 클라이언트에** 건다. 호출자의 `asyncio.wait_for` 는 코루틴을
        풀어주지만 이미 떠난 HTTP 요청을 끊지 못한다 — 이 인자가 실제로 연결을 끊는
        유일한 지점이다 (`run` 의 subprocess timeout 과 같은 이유).

        `max_retries=0` 은 예산 방어다. 기본값은 재시도이므로 실패 1건이 조용히 2~3배
        청구된다. 재시도가 필요하면 호출자가 명시적으로 다시 부른다.
        """
        from langchain.chat_models import init_chat_model

        requested = self._model_for(tier)
        model = init_chat_model(requested,
                                timeout=timeout or DEFAULT_COMPLETE_TIMEOUT,
                                max_retries=0)
        started = time.monotonic()
        reply = model.invoke(messages)
        usage = getattr(reply, "usage_metadata", None) or {}
        details = usage.get("input_token_details") or {}
        meta = getattr(reply, "response_metadata", None) or {}
        return CompletionResult(
            text=_text_of(reply.content),
            input_tokens=int(usage.get("input_tokens", 0) or 0),
            output_tokens=int(usage.get("output_tokens", 0) or 0),
            cache_read_tokens=int(details.get("cache_read", 0) or 0),
            cache_creation_tokens=int(details.get("cache_creation", 0) or 0),
            # 요청한 이름이 아니라 응답이 말한 모델. 게이트웨이가 바꿔 끼울 수 있다.
            model=str(meta.get("model") or meta.get("model_name") or requested),
            elapsed_seconds=round(time.monotonic() - started, 2),
        )
```

모듈 끝에 헬퍼를 둔다:

```python
def _text_of(content) -> str:
    """응답 content 를 문자열로. block 목록으로 오는 경우가 있다."""
    if isinstance(content, str):
        return content
    parts = []
    for block in content or []:
        if isinstance(block, str):
            parts.append(block)
        elif isinstance(block, dict) and block.get("type") == "text":
            parts.append(block.get("text", ""))
    return "".join(parts)
```

- [ ] **Step 5: 통과를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_deepagents_complete.py -v`
Expected: PASS (7 passed)

`test_결과형에_토큰과_응답_모델이_담긴다` 에서 `cache_read_tokens == 0` 이 나오면 `usage_metadata.input_token_details` 의 키 이름이 이 버전에서 다른 것이다. 확인:

```bash
cd ai && uv run python -c "
from langchain_anthropic import ChatAnthropic
import inspect, langchain_anthropic
print(langchain_anthropic.__version__)
"
```

키가 `cache_read` 가 아니면 실제 이름으로 `details.get(...)` 을 고치고, 테스트의 어서션은 그대로 둔다 (`CompletionResult` 필드 이름은 우리 것이다).

- [ ] **Step 6: 전체 테스트**

Run: `cd ai && uv run pytest -m "not ocr" -q`
Expected: 전부 통과

- [ ] **Step 7: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/src/agent_runtime/deep_agents.py ai/pyproject.toml ai/uv.lock ai/tests/api/test_deepagents_complete.py
git commit -m "feat(ai): deepagents 런타임에 단발 호출을 구현한다

에이전트도 MCP 도 만들지 않고 모델만 부른다. timeout 을 모델 클라이언트에
걸어 실제로 연결을 끊고, max_retries=0 으로 실패가 두세 배 청구되는 것을
막는다. 검증은 로컬 HTTP mock 이 받은 요청을 세는 방식이다."
```

---

## Task 5: claude-code `complete` — 테스트 경로

**Files:**
- Modify: `ai/src/agent_runtime/claude_code.py:54-61` (클래스 앞부분), 모듈 끝
- Test: `ai/tests/runtime/test_cli_complete.py` (create)

**Interfaces:**
- Consumes: `CompletionResult`, `FAST`, `QUALITY`, `DEFAULT_COMPLETE_TIMEOUT` (Task 1)
- Produces:
  - `ClaudeCodeRuntime.complete(messages, *, tier=QUALITY, timeout=None) -> CompletionResult`
  - `CLI_TIER_MODELS: dict[str, str]`
  - `render_messages(messages: list[dict]) -> str`

**크레딧:** 0. `subprocess.run` 을 monkeypatch 한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/runtime/test_cli_complete.py`:

```python
"""테스트 경로의 단발 호출.

CLI 는 문자열 하나만 받으므로 messages 목록을 렌더한다. **`cache_control` 을 버린다** —
CLI 는 구독 청구라 크레딧과 무관하고, 캐싱은 배포 경로(deepagents)의 예산 문제다.

`run` 은 손대지 않는다. `experiments/` 의 규칙이 "CLI 수치는 CLI 수치끼리만 비교"이므로
그 경로를 바꾸면 기존 측정과 대조가 깨진다.
"""

import json

import pytest

from agent_runtime.base import FAST, QUALITY, CompletionResult
from agent_runtime.claude_code import (
    CLI_TIER_MODELS,
    ClaudeCodeRuntime,
    render_messages,
)


class _Proc:
    def __init__(self, stdout, returncode=0, stderr=""):
        self.stdout = stdout
        self.returncode = returncode
        self.stderr = stderr


def _payload(text="연차는 15일입니다."):
    return json.dumps({
        "result": text, "is_error": False, "num_turns": 1,
        "total_cost_usd": 0.001,
        "usage": {"input_tokens": 12, "output_tokens": 30,
                  "cache_read_input_tokens": 40},
    })


def test_문자열_content_를_렌더한다():
    rendered = render_messages([
        {"role": "user", "content": "연차 신청 방법?"},
        {"role": "assistant", "content": "Deel 에서 예약합니다."},
        {"role": "user", "content": "그거 언제까지?"},
    ])
    assert "사용자: 연차 신청 방법?" in rendered
    assert "답변: Deel 에서 예약합니다." in rendered
    assert rendered.rstrip().endswith("사용자: 그거 언제까지?")


def test_block_목록을_이어붙이고_cache_control_을_버린다():
    rendered = render_messages([{
        "role": "user",
        "content": [
            {"type": "text", "text": "# 목차", "cache_control": {"type": "ephemeral"}},
            {"type": "text", "text": "질문: 연차?"},
        ],
    }])
    assert "# 목차" in rendered
    assert "질문: 연차?" in rendered
    assert "cache_control" not in rendered
    assert "ephemeral" not in rendered


def test_MCP_설정_없이_부른다(monkeypatch):
    seen = {}

    def fake_run(argv, **kwargs):
        seen["argv"] = argv
        seen["timeout"] = kwargs.get("timeout")
        return _Proc(_payload())

    monkeypatch.setattr("agent_runtime.claude_code.subprocess.run", fake_run)
    result = ClaudeCodeRuntime().complete(
        [{"role": "user", "content": "연차?"}], tier=FAST, timeout=30)

    assert isinstance(result, CompletionResult)
    assert result.text == "연차는 15일입니다."
    assert result.output_tokens == 30
    # 툴이 붙으면 단발 호출이 아니다.
    assert "--mcp-config" not in seen["argv"]
    assert "--strict-mcp-config" not in seen["argv"]
    assert seen["argv"][seen["argv"].index("--model") + 1] == CLI_TIER_MODELS[FAST]
    assert seen["timeout"] == 30


def test_기본_상한을_쓴다(monkeypatch):
    seen = {}

    def fake_run(argv, **kwargs):
        seen["timeout"] = kwargs.get("timeout")
        return _Proc(_payload())

    monkeypatch.setattr("agent_runtime.claude_code.subprocess.run", fake_run)
    ClaudeCodeRuntime().complete([{"role": "user", "content": "연차?"}], tier=QUALITY)
    from agent_runtime.base import DEFAULT_COMPLETE_TIMEOUT
    assert seen["timeout"] == DEFAULT_COMPLETE_TIMEOUT


def test_CLI_실패는_예외로_올린다(monkeypatch):
    """종료 코드가 0 이어도 is_error 가 실패다 (모듈 docstring 함정 3)."""

    def fake_run(argv, **kwargs):
        return _Proc(json.dumps({"result": "권한 오류", "is_error": True}))

    monkeypatch.setattr("agent_runtime.claude_code.subprocess.run", fake_run)
    with pytest.raises(RuntimeError, match="권한 오류"):
        ClaudeCodeRuntime().complete([{"role": "user", "content": "연차?"}], tier=FAST)


def test_모르는_tier_는_터진다():
    with pytest.raises(ValueError, match="모르는 tier"):
        ClaudeCodeRuntime()._model_for("cheap")


def test_별칭을_기본값으로_쓰지_않는다():
    """`sonnet` 같은 별칭은 시점에 따라 다른 모델로 해석돼 두 측정의 비교를 깨뜨린다."""
    for name in CLI_TIER_MODELS.values():
        assert name.startswith("claude-")
        assert "-" in name.removeprefix("claude-")
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/runtime/test_cli_complete.py -v`
Expected: FAIL — `ImportError: cannot import name 'CLI_TIER_MODELS'`

- [ ] **Step 3: 구현한다**

`ai/src/agent_runtime/claude_code.py` 임포트를 바꾼다(40행):

```python
from .base import DEFAULT_COMPLETE_TIMEOUT, FAST, QUALITY, CompletionResult, RunResult
```

`CALL_TIMEOUT_SECONDS` 다음에 상수를 둔다:

```python
# CLI 표기는 `anthropic:` 접두사가 없다. 별칭(`sonnet`)을 쓰지 않는 이유는 시점에 따라
# 다른 모델로 해석돼 두 측정의 비교를 조용히 깨뜨리기 때문이다.
CLI_TIER_MODELS = {
    FAST: "claude-haiku-4-5-20251001",
    QUALITY: "claude-sonnet-4-6",
}
```

`ClaudeCodeRuntime.__init__` 다음, `_mcp_config` 앞에 넣는다:

```python
    def _model_for(self, tier: str) -> str:
        if tier not in CLI_TIER_MODELS:
            raise ValueError(f"모르는 tier: {tier!r} (가능: {sorted(CLI_TIER_MODELS)})")
        return CLI_TIER_MODELS[tier]

    def complete(self, messages: list[dict], *, tier: str = QUALITY,
                 timeout: int | None = None) -> CompletionResult:
        """MCP 없는 단발 호출. **테스트 경로다.**

        `--mcp-config` 를 붙이지 않는다 — 툴이 없으면 에이전트 루프가 없고 응답이 한 번에
        온다. `run` 이 담고 있는 네 함정(모듈 docstring)은 여기에도 둘이 적용된다:
        종료 코드가 0 이어도 `is_error` 가 실패이고, 입력 토큰은 캐시 필드를 합해야 한다.

        `run` 을 손대지 않고 새 메서드로 둔 이유는 측정 재현성이다. `experiments/` 규칙이
        "CLI 수치는 CLI 수치끼리만 비교"이므로 기존 경로가 바뀌면 대조가 깨진다.
        """
        limit = timeout or DEFAULT_COMPLETE_TIMEOUT
        started = time.monotonic()
        with tempfile.TemporaryDirectory(prefix="llmwiki-complete-") as tmp:
            argv = [
                "claude", "-p", render_messages(messages),
                "--model", self._model_for(tier),
                "--output-format", "json",
            ]
            # 임시 cwd 는 이 저장소의 CLAUDE.md 가 프롬프트에 섞이지 않게 한다
            # (모듈 docstring 함정 1 — `--bare` 는 로그인 세션을 못 읽는다).
            proc = subprocess.run(argv, capture_output=True, text=True,
                                  timeout=limit, cwd=tmp, env=os.environ.copy())

        payload = _payload(proc.stdout)
        if proc.returncode != 0 or payload.get("is_error"):
            detail = payload.get("result") or proc.stderr.strip() or proc.stdout[:400]
            raise RuntimeError(f"claude 실패 (코드 {proc.returncode}): {str(detail)[:400]}")

        usage = payload.get("usage") or {}
        usage = usage if isinstance(usage, dict) else {}
        return CompletionResult(
            text=str(payload.get("result") or ""),
            input_tokens=_input_tokens(usage),
            output_tokens=int(usage.get("output_tokens", 0) or 0),
            cache_read_tokens=int(usage.get("cache_read_input_tokens", 0) or 0),
            cache_creation_tokens=int(usage.get("cache_creation_input_tokens", 0) or 0),
            model=self._model_for(tier),
            elapsed_seconds=round(time.monotonic() - started, 2),
        )
```

모듈 끝(`_input_tokens` 다음)에 렌더러를 둔다:

```python
def render_messages(messages: list[dict]) -> str:
    """messages 목록 → 문자열. CLI 가 문자열 하나만 받는다.

    **`cache_control` 을 버린다.** CLI 는 구독 청구라 크레딧과 무관하고, 캐싱은 배포
    경로의 예산 문제다. 이 열화를 감수하는 대신 배포 경로가 목록을 그대로 받는다.

    역할 표기를 `edit_instruction`(`base.py`)과 같은 어휘로 맞춘다 — 사용자/답변.
    """
    label = {"user": "사용자", "assistant": "답변", "system": "지시"}
    lines = []
    for message in messages:
        who = label.get(str(message.get("role")), "사용자")
        content = message.get("content")
        if isinstance(content, str):
            body = content
        else:
            body = "\n".join(
                block.get("text", "") if isinstance(block, dict) else str(block)
                for block in content or []
            )
        lines.append(f"{who}: {body}")
    return "\n\n".join(lines) + "\n"
```

- [ ] **Step 4: 통과를 확인한다**

Run: `cd ai && uv run pytest tests/runtime/test_cli_complete.py -v`
Expected: PASS (7 passed)

- [ ] **Step 5: 전체 테스트**

Run: `cd ai && uv run pytest -m "not ocr" -q`
Expected: 전부 통과

- [ ] **Step 6: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/src/agent_runtime/claude_code.py ai/tests/runtime/test_cli_complete.py
git commit -m "feat(ai): CLI 런타임에 단발 호출을 구현한다

MCP 설정을 붙이지 않아 에이전트 루프가 없다. CLI 는 문자열만 받으므로
messages 를 렌더하고 cache_control 은 버린다. run 경로는 그대로 둔다."
```

---

## Task 6: 계약 스키마와 오류 코드

**Files:**
- Modify: `ai/src/wiki_api/schemas.py` (끝에 추가)
- Modify: `ai/src/wiki_api/errors.py:69-92` (두 딕셔너리)
- Test: `ai/tests/api/test_answer_schemas.py` (create)

**Interfaces:**
- Consumes: 없음
- Produces:
  - `ConversationMessage(role: Literal["user","assistant"], content: str)`
  - `WikiIndexEntry(scopeKey: str, indexMarkdown: str)`
  - `ScheduleSummary(scheduleId: str, title: str, startAt: str, endAt: str, targetText: str | None, location: str | None)`
  - `AnswerContextRequest(questionId, conversationId, question, conversationMessages, wikiIndexes, scheduleSummaries)`
  - `AnswerContextResponse(questionType: Literal["wiki","schedule","mixed"], wikiIds: list[str], scheduleIds: list[str], reason: str)`
  - `SelectedWiki(wikiId, title, contentMarkdown)`
  - `SelectedSchedule(scheduleId, title, content, startAt, endAt, targetText, location)`
  - `AnswerRequest(questionId, conversationId, questionType, question, conversationMessages, selectedWikis, selectedSchedules)`
  - `AnswerSource(type: Literal["wiki","schedule"], wikiId: str | None, scheduleId: str | None, title: str)`
  - `AnswerResponse(answer: str, sources: list[AnswerSource])`
  - `SELECTION_PATH`, `ANSWER_PATH` 상수

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/api/test_answer_schemas.py`:

```python
"""챗봇 계약 스키마와 오류 코드 등록.

**오류 코드는 딕셔너리 두 곳에 각각 등록해야 한다.** 하나를 빼먹으면 계약 위반이 조용히
나간다 — 검증 누락은 `INVALID_REQUEST`, 실패 누락은 `INTERNAL_SERVER_ERROR` 이고 둘 다
계약에 없는 이름이라 Spring 의 code 분기가 깨진다.
"""

import pytest
from pydantic import ValidationError

from wiki_api.errors import _FAILURE_CODES, _VALIDATION_CODES, failure_code_for
from wiki_api.schemas import (
    ANSWER_PATH,
    SELECTION_PATH,
    AnswerContextRequest,
    AnswerContextResponse,
    AnswerRequest,
    AnswerResponse,
    AnswerSource,
)

CONTEXT_BODY = {
    "questionId": "500",
    "conversationId": "chat-123",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [
        {"role": "user", "content": "연차 신청 방법을 알려줘."},
        {"role": "assistant", "content": "연차 신청 절차는 다음과 같습니다."},
    ],
    "wikiIndexes": [
        {"scopeKey": "D1-D2", "indexMarkdown": "# 사내 규정\n- [휴가 규정](pages/101.md)"}
    ],
    "scheduleSummaries": [
        {"scheduleId": "31", "title": "8월 휴가 일정",
         "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z",
         "targetText": "개발부", "location": "본사"}
    ],
}

ANSWER_BODY = {
    "questionId": "500",
    "conversationId": "chat-123",
    "questionType": "mixed",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [],
    "selectedWikis": [
        {"wikiId": "101", "title": "휴가 규정", "contentMarkdown": "# 휴가 규정\n..."}
    ],
    "selectedSchedules": [
        {"scheduleId": "31", "title": "8월 휴가 일정", "content": "개발부 휴가 일정",
         "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z",
         "targetText": "개발부", "location": "본사"}
    ],
}


def test_계약_예시가_그대로_통과한다():
    request = AnswerContextRequest(**CONTEXT_BODY)
    assert request.wikiIndexes[0].scopeKey == "D1-D2"
    assert request.scheduleSummaries[0].scheduleId == "31"
    assert request.conversationMessages[1].role == "assistant"


def test_답변_요청_계약_예시가_통과한다():
    request = AnswerRequest(**ANSWER_BODY)
    assert request.questionType == "mixed"
    assert request.selectedWikis[0].wikiId == "101"
    assert request.selectedSchedules[0].location == "본사"


def test_목록은_비어도_된다():
    """관련 자료가 없는 것은 정상이다. 400 이 아니다."""
    body = dict(CONTEXT_BODY, wikiIndexes=[], scheduleSummaries=[],
                conversationMessages=[])
    assert AnswerContextRequest(**body).wikiIndexes == []


def test_모르는_필드는_거절한다():
    """계약 밖 필드를 조용히 먹으면 계약 드리프트가 보이지 않는다."""
    with pytest.raises(ValidationError):
        AnswerContextRequest(**dict(CONTEXT_BODY, extraField="x"))


def test_questionType_은_세_값뿐이다():
    """DB `chk_ai_question_type` 이 WIKI·SCHEDULE·MIXED 만 허용한다."""
    with pytest.raises(ValidationError):
        AnswerRequest(**dict(ANSWER_BODY, questionType="general"))
    with pytest.raises(ValidationError):
        AnswerContextResponse(questionType="general", wikiIds=[], scheduleIds=[],
                              reason="")


def test_role_은_두_값뿐이다():
    body = dict(CONTEXT_BODY,
                conversationMessages=[{"role": "admin", "content": "x"}])
    with pytest.raises(ValidationError):
        AnswerContextRequest(**body)


def test_출처는_한쪽_ID_만_갖는다():
    wiki = AnswerSource(type="wiki", wikiId="101", title="휴가 규정")
    assert wiki.scheduleId is None
    schedule = AnswerSource(type="schedule", scheduleId="31", title="8월 휴가 일정")
    assert schedule.wikiId is None


def test_응답이_계약_모양으로_직렬화된다():
    response = AnswerResponse(
        answer="연차 규정과 다음 휴가 일정은 다음과 같습니다.",
        sources=[AnswerSource(type="wiki", wikiId="101", title="휴가 규정"),
                 AnswerSource(type="schedule", scheduleId="31", title="8월 휴가 일정")])
    dumped = response.model_dump(exclude_none=True)
    assert dumped["sources"][0] == {"type": "wiki", "wikiId": "101",
                                    "title": "휴가 규정"}
    assert dumped["sources"][1] == {"type": "schedule", "scheduleId": "31",
                                    "title": "8월 휴가 일정"}


def test_검증_코드가_두_경로에_등록됐다():
    assert _VALIDATION_CODES[SELECTION_PATH][0] == "INVALID_ANSWER_CONTEXT_REQUEST"
    assert _VALIDATION_CODES[ANSWER_PATH][0] == "INVALID_ANSWER_GENERATION_REQUEST"


def test_실패_코드가_두_경로에_등록됐다():
    assert failure_code_for(SELECTION_PATH) == "ANSWER_CONTEXT_SELECTION_FAILED"
    assert failure_code_for(ANSWER_PATH) == "ANSWER_GENERATION_FAILED"
    assert SELECTION_PATH in _FAILURE_CODES
    assert ANSWER_PATH in _FAILURE_CODES


def test_경로_상수가_계약과_같다():
    assert SELECTION_PATH == "/internal/v1/answer-context-selections"
    assert ANSWER_PATH == "/internal/v1/answers"
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_answer_schemas.py -v`
Expected: FAIL — `ImportError: cannot import name 'AnswerContextRequest'`

- [ ] **Step 3: 기존 스키마의 관례를 확인한다**

Run: `cd ai && uv run grep -n "class Strict" -A 6 src/wiki_api/schemas.py`

`Strict` 는 이 파일의 기반 클래스이고 `extra="forbid"` 로 계약 밖 필드를 거절한다. 새 모델은 모두 `Strict` 를 상속한다. `Literal` 이 이미 임포트돼 있는지 확인하고 없으면 `from typing import Literal` 을 추가한다.

- [ ] **Step 4: 스키마를 추가한다**

`ai/src/wiki_api/schemas.py` 끝에:

```python
# ---- 챗봇 답변 (계약 v1.3.0 「답변 생성」) ------------------------------------

SELECTION_PATH = "/internal/v1/answer-context-selections"
ANSWER_PATH = "/internal/v1/answers"


class ConversationMessage(Strict):
    """이전 대화 1건. `wiki-edits` 의 `ChatMessage` 와 형태가 다르다 —
    그쪽은 `senderType: admin|agent` 이고 이쪽은 계약대로 `role: user|assistant` 다."""

    role: Literal["user", "assistant"]
    content: str


class WikiIndexEntry(Strict):
    scopeKey: str
    indexMarkdown: str


class ScheduleSummary(Strict):
    """1단계에 오는 일정 후보. **본문은 없다** — 목차·요약만 보고 고른다."""

    scheduleId: str
    title: str
    startAt: str
    endAt: str
    targetText: str | None = None
    location: str | None = None


class AnswerContextRequest(Strict):
    questionId: str
    conversationId: str
    question: str
    conversationMessages: list[ConversationMessage] = Field(default_factory=list)
    wikiIndexes: list[WikiIndexEntry] = Field(default_factory=list)
    scheduleSummaries: list[ScheduleSummary] = Field(default_factory=list)


class AnswerContextResponse(Strict):
    questionType: Literal["wiki", "schedule", "mixed"]
    wikiIds: list[str] = Field(default_factory=list)
    scheduleIds: list[str] = Field(default_factory=list)
    reason: str = ""


class SelectedWiki(Strict):
    wikiId: str
    title: str
    contentMarkdown: str


class SelectedSchedule(Strict):
    scheduleId: str
    title: str
    content: str
    startAt: str
    endAt: str
    targetText: str | None = None
    location: str | None = None


class AnswerRequest(Strict):
    questionId: str
    conversationId: str
    questionType: Literal["wiki", "schedule", "mixed"]
    question: str
    conversationMessages: list[ConversationMessage] = Field(default_factory=list)
    selectedWikis: list[SelectedWiki] = Field(default_factory=list)
    selectedSchedules: list[SelectedSchedule] = Field(default_factory=list)


class AnswerSource(Strict):
    """출처 1건. 한쪽 ID 만 채운다 — `answer_source` 가 두 컬럼을 NULL 허용으로 둔 이유다."""

    type: Literal["wiki", "schedule"]
    wikiId: str | None = None
    scheduleId: str | None = None
    title: str


class AnswerResponse(Strict):
    answer: str
    sources: list[AnswerSource] = Field(default_factory=list)
```

- [ ] **Step 5: 오류 코드를 두 딕셔너리에 등록한다**

`ai/src/wiki_api/errors.py` 의 `_VALIDATION_CODES`(69행)에 두 항목 추가:

```python
    "/internal/v1/answer-context-selections": (
        "INVALID_ANSWER_CONTEXT_REQUEST", "질문 또는 후보 자료 구조가 올바르지 않습니다."),
    "/internal/v1/answers": (
        "INVALID_ANSWER_GENERATION_REQUEST", "답변 생성 요청 문맥이 올바르지 않습니다."),
```

`_FAILURE_CODES`(85행)에 두 항목 추가:

```python
    "/internal/v1/answer-context-selections": "ANSWER_CONTEXT_SELECTION_FAILED",
    "/internal/v1/answers": "ANSWER_GENERATION_FAILED",
```

문자열을 `schemas.SELECTION_PATH` 로 참조하지 않는다 — `errors.py` 가 `schemas.py` 를 임포트하면 순환이 생긴다. 두 곳의 문자열이 같은지는 `test_경로_상수가_계약과_같다` 와 `test_검증_코드가_두_경로에_등록됐다` 가 함께 지킨다.

- [ ] **Step 6: 통과와 전체 테스트**

Run: `cd ai && uv run pytest tests/api/test_answer_schemas.py -v && uv run pytest -m "not ocr" -q`
Expected: PASS (11 passed), 전체 통과

- [ ] **Step 7: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/src/wiki_api/schemas.py ai/src/wiki_api/errors.py ai/tests/api/test_answer_schemas.py
git commit -m "feat(ai): 챗봇 답변 계약 스키마와 오류 코드를 등록한다

오류 코드는 검증용과 실패용 딕셔너리 두 곳에 각각 등록해야 한다. 한쪽을
빼먹으면 계약에 없는 이름이 나가 Spring 의 code 분기가 깨진다."
```

---

## Task 7: 1단계 — 분류와 자료 선택

**Files:**
- Create: `ai/src/wiki_api/answer_selection.py`
- Modify: `ai/src/agent_runtime/base.py` (프롬프트 함수 추가)
- Test: `ai/tests/api/test_answer_selection.py` (create)

**Interfaces:**
- Consumes: `complete` (Task 2), 스키마 (Task 6), `FAST` (Task 1)
- Produces:
  - `MAX_PICKS = 5`, `SELECTION_TIMEOUT_SECONDS = 30`, `MAX_INPUT_BYTES = 40_000`
  - `def index_wiki_ids(indexes: list[WikiIndexEntry]) -> list[str]`
  - `def parse_answer_context(text: str, wiki_allowed: list[str], schedule_allowed: list[str]) -> tuple[str, list[str], list[str], str]`
  - `def resolve_question_type(declared: str, wiki_ids: list[str], schedule_ids: list[str]) -> str`
  - `async def select_answer_context(runtime, payload: AnswerContextRequest, *, request_id: str) -> AnswerContextResponse`
  - `agent_runtime.base.answer_context_instruction(question, messages, indexes, schedules) -> list[dict]`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/api/test_answer_selection.py`:

```python
"""챗봇 1단계 — 질문을 분류하고 필요한 자료 ID 를 고른다.

확인하는 것은 모델의 안목이 아니라 **파싱의 방어력**이다. Spring 은 이 응답의 ID 로 본문을
읽어 2단계에 싣는다 — 지어낸 ID 가 새면 없는 자료를 조회하거나 다른 범위의 자료를 읽는다.

위키와 일정의 화이트리스트 출처가 다르다. 위키는 목차 링크가 존재 증명이지만 **일정에는
목차가 없어서** 요청이 실어 온 요약 목록이 유일한 근거다.
"""

import pytest
from fastapi.testclient import TestClient

from agent_runtime.base import CompletionResult
from wiki_api.answer_selection import (
    MAX_PICKS,
    index_wiki_ids,
    parse_answer_context,
    resolve_question_type,
)
from wiki_api.app import create_app
from wiki_api.schemas import WikiIndexEntry

API_KEY = "secret-key"
PATH = "/internal/v1/answer-context-selections"

INDEX_A = ("# 사내 규정\n"
           "- [휴가 규정](pages/101.md) — 연차와 반차\n"
           "- [보상 체계](pages/102.md) — 레벨과 스텝\n")
INDEX_B = ("# 개발부 위키\n"
           "- [배포 절차](pages/201.md) — 릴리스\n")

REQUEST = {
    "questionId": "500",
    "conversationId": "chat-123",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [
        {"role": "user", "content": "연차 신청 방법을 알려줘."},
        {"role": "assistant", "content": "연차 신청 절차는 다음과 같습니다."},
    ],
    "wikiIndexes": [
        {"scopeKey": "ALL", "indexMarkdown": INDEX_A},
        {"scopeKey": "D1", "indexMarkdown": INDEX_B},
    ],
    "scheduleSummaries": [
        {"scheduleId": "31", "title": "8월 휴가 일정",
         "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z"},
        {"scheduleId": "32", "title": "전사 오프사이트",
         "startAt": "2026-09-01T00:00:00Z", "endAt": "2026-09-03T00:00:00Z"},
    ],
}


class FakeRuntime:
    name = "fake-answer-select"

    def __init__(self, text: str, raises: Exception | None = None):
        self.text = text
        self.raises = raises
        self.messages: list[list[dict]] = []
        self.tiers: list[str] = []

    def complete(self, messages, *, tier="quality", timeout=None):
        self.messages.append(messages)
        self.tiers.append(tier)
        if self.raises:
            raise self.raises
        return CompletionResult(text=self.text)


def _client(runtime):
    app = create_app(api_key=API_KEY)
    app.state.runtime = runtime
    return TestClient(app)


def _post(runtime, body=None):
    return _client(runtime).post(PATH, json=body or REQUEST,
                                 headers={"X-Internal-API-Key": API_KEY})


# ---- 화이트리스트 ----------------------------------------------------------

def test_여러_스코프의_목차를_합친다():
    entries = [WikiIndexEntry(scopeKey="ALL", indexMarkdown=INDEX_A),
               WikiIndexEntry(scopeKey="D1", indexMarkdown=INDEX_B)]
    assert index_wiki_ids(entries) == ["101", "102", "201"]


def test_목차에_없는_위키_ID_는_버린다():
    text = '{"questionType": "wiki", "wikiIds": ["101", "999"], "scheduleIds": []}'
    _, wiki_ids, _, _ = parse_answer_context(text, ["101", "102"], ["31"])
    assert wiki_ids == ["101"]


def test_요약에_없는_일정_ID_는_버린다():
    """일정에는 목차가 없다. 요청이 실어 온 요약 목록이 유일한 화이트리스트다."""
    text = '{"questionType": "schedule", "wikiIds": [], "scheduleIds": ["31", "77"]}'
    _, _, schedule_ids, _ = parse_answer_context(text, ["101"], ["31", "32"])
    assert schedule_ids == ["31"]


def test_중복을_지우고_다섯_개에서_자른다():
    allowed = [str(n) for n in range(101, 110)]
    picks = ", ".join(f'"{n}"' for n in allowed + ["101"])
    text = f'{{"questionType": "wiki", "wikiIds": [{picks}], "scheduleIds": []}}'
    _, wiki_ids, _, _ = parse_answer_context(text, allowed, [])
    assert wiki_ids == allowed[:MAX_PICKS]
    assert len(wiki_ids) == MAX_PICKS


def test_JSON_이_아니어도_ID_를_줍는다():
    text = "관련 위키는 101 과 102 이고 일정은 31 입니다."
    _, wiki_ids, schedule_ids, _ = parse_answer_context(text, ["101", "102"], ["31"])
    assert wiki_ids == ["101", "102"]
    assert schedule_ids == ["31"]


# ---- questionType 교정 -----------------------------------------------------

@pytest.mark.parametrize("declared,wiki,schedule,expected", [
    ("mixed", ["101"], [], "wiki"),
    ("wiki", [], ["31"], "schedule"),
    ("wiki", ["101"], ["31"], "mixed"),
    ("schedule", [], [], "schedule"),
    ("garbage", [], [], "wiki"),
])
def test_questionType_은_선택_결과로_교정된다(declared, wiki, schedule, expected):
    assert resolve_question_type(declared, wiki, schedule) == expected


# ---- 엔드포인트 ------------------------------------------------------------

def test_계약_모양으로_응답한다():
    runtime = FakeRuntime('{"questionType": "mixed", "wikiIds": ["101"], '
                          '"scheduleIds": ["31"], "reason": "휴가 규정과 일정"}')
    response = _post(runtime)
    assert response.status_code == 200
    body = response.json()
    assert body == {"questionType": "mixed", "wikiIds": ["101"],
                    "scheduleIds": ["31"], "reason": "휴가 규정과 일정"}


def test_fast_tier_로_부른다():
    runtime = FakeRuntime('{"questionType": "wiki", "wikiIds": ["101"], "scheduleIds": []}')
    _post(runtime)
    assert runtime.tiers == ["fast"]


def test_목차가_프롬프트_맨_앞에_온다():
    """캐시 접두사가 흔들리지 않게. 질문이 앞에 오면 적중이 0 이 된다."""
    runtime = FakeRuntime('{"questionType": "wiki", "wikiIds": [], "scheduleIds": []}')
    _post(runtime)
    blocks = runtime.messages[0][0]["content"]
    assert isinstance(blocks, list)
    assert INDEX_A in blocks[0]["text"]
    assert "연차 규정과 다음 휴가 일정을 알려줘." not in blocks[0]["text"]


def test_이전_대화가_프롬프트에_들어간다():
    runtime = FakeRuntime('{"questionType": "wiki", "wikiIds": [], "scheduleIds": []}')
    _post(runtime)
    joined = "\n".join(b["text"] for b in runtime.messages[0][0]["content"])
    assert "연차 신청 방법을 알려줘." in joined


def test_빈_결과는_200_이다():
    """관련 자료가 없는 것은 정상이다."""
    runtime = FakeRuntime('{"questionType": "wiki", "wikiIds": [], "scheduleIds": []}')
    response = _post(runtime)
    assert response.status_code == 200
    assert response.json()["wikiIds"] == []
    assert response.json()["reason"]


def test_런타임_실패는_계약_500_코드다():
    runtime = FakeRuntime("", raises=RuntimeError("게이트웨이 거부"))
    response = _post(runtime)
    assert response.status_code == 500
    assert response.json()["code"] == "ANSWER_CONTEXT_SELECTION_FAILED"


def test_형식_오류는_계약_400_코드다():
    runtime = FakeRuntime("{}")
    response = _post(runtime, dict(REQUEST, questionId=None))
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_CONTEXT_REQUEST"
    assert response.json()["fieldErrors"]


def test_입력이_상한을_넘으면_400_이다():
    from wiki_api.answer_selection import MAX_INPUT_BYTES

    huge = "가" * (MAX_INPUT_BYTES // 3 + 100)  # 한글 1자 = UTF-8 3바이트
    runtime = FakeRuntime("{}")
    body = dict(REQUEST,
                wikiIndexes=[{"scopeKey": "ALL", "indexMarkdown": huge}])
    response = _post(runtime, body)
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_CONTEXT_REQUEST"
    fields = [e["field"] for e in response.json()["fieldErrors"]]
    assert any("wikiIndexes" in f or f == "body" for f in fields)
    # 상한을 넘겼으면 모델을 부르지 않아야 한다 — 크레딧이 나간다.
    assert runtime.messages == []
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_answer_selection.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'wiki_api.answer_selection'`

- [ ] **Step 3: 프롬프트 함수를 `base.py` 에 추가한다**

`selection_instruction` 다음에:

```python
def answer_context_instruction(question: str, messages: list[dict],
                               indexes: list[dict],
                               schedules: list[dict]) -> list[dict]:
    """챗봇 1단계 프롬프트. **content block 목록을 돌려준다.**

    문자열이 아니라 block 목록인 이유는 캐싱이다. 첫 block 이 목차이고 거기에
    `cache_control` 이 붙는다 — 질문마다 목차가 같으므로 접두사가 고정되고, 읽기가 10%
    과금이 된다. **목차가 맨 앞이 아니면 접두사가 매번 달라져 적중이 0 이 된다.**

    캐시가 걸리는 최소 토큰(haiku 계열은 2,048 로 알려져 있다)을 넘지 못하면 캐싱이 아예
    작동하지 않는다. 작은 스코프에서는 그럴 수 있고, 그때 비용은 캐시 없는 값이다.
    """
    index_text = "\n\n".join(
        f"### 범위 `{entry['scopeKey']}`\n\n{entry['indexMarkdown']}"
        for entry in indexes
    ) or "(권한 있는 위키가 없다)"

    schedule_text = "\n".join(
        f"- `{s['scheduleId']}` {s['title']} — {s['startAt']} ~ {s['endAt']}"
        + (f" — 대상 {s['targetText']}" if s.get("targetText") else "")
        + (f" — 장소 {s['location']}" if s.get("location") else "")
        for s in schedules
    ) or "(권한 있는 일정이 없다)"

    history = "\n".join(
        f"  {'사용자' if m['role'] == 'user' else '답변'}: {m['content']}"
        for m in messages
    )
    history_text = f"\n\n## 이전 대화\n\n{history}" if history else ""

    return [{
        "role": "user",
        "content": [
            # ① 고정 — 캐시 대상. 질문마다 같아야 한다.
            {"type": "text",
             "text": f"## 위키 목차\n\n{index_text}",
             "cache_control": {"type": "ephemeral"}},
            # ② 변동 — 캐시 경계 뒤.
            {"type": "text",
             "text": (
                 f"## 일정 목록\n\n{schedule_text}{history_text}\n\n"
                 f"## 현재 질문\n\n{question}\n\n"
                 f"위 질문에 답하는 데 필요한 자료를 고른다. 질문 종류를 판단하고 "
                 f"(`wiki`·`schedule`·`mixed`), 위키와 일정을 각각 관련도 순서로 "
                 f"**최대 5개**씩 고른다.\n\n"
                 f"위키는 목차 링크(`pages/{{wikiId}}.md`)에 실재하는 ID 로만, 일정은 위 "
                 f"목록에 있는 ID 로만 답한다. 관련된 것이 없으면 빈 배열을 낸다 — 억지로 "
                 f"채우지 않는다. 이전 대화를 참고해 현재 질문이 무엇을 가리키는지 본다.\n\n"
                 f"다른 말 없이 JSON 만 출력한다:\n"
                 f'{{"questionType": "mixed", "wikiIds": ["101"], '
                 f'"scheduleIds": ["31"], "reason": "고른 이유 한두 문장"}}\n'
             )},
        ],
    }]
```

- [ ] **Step 4: 1단계를 구현한다**

`ai/src/wiki_api/answer_selection.py`:

```python
"""챗봇 1단계 — 질문 분류와 자료 선택 (`POST /internal/v1/answer-context-selections`).

**에이전트가 아니다.** 입력이 목차·일정 요약·질문뿐이라 툴이 필요 없고, 출력은 ID 목록과
분류 하나다. `wiki_api/selection.py` 와 같은 문제이며 방어도 같다 — 모델이 낸 ID 를 믿지
않고 화이트리스트로 거른다.

**화이트리스트 출처가 위키와 일정에서 다르다.** 위키는 목차 링크(`pages/{wikiId}.md`)가
존재 증명이지만 일정에는 목차가 없다. 요청이 실어 온 `scheduleSummaries` 의 ID 집합이
유일한 근거다.

`questionType` 도 모델 선언을 믿지 않는다 — 실제로 고른 결과로 교정한다. 모델이 `mixed`
라 하고 위키만 고르면 Spring 이 2단계에 빈 `selectedSchedules` 를 싣고, 답변 프롬프트가
있지도 않은 일정을 언급하려 든다. DB `chk_ai_question_type` 이 세 값만 허용하는 것도
이유다.
"""

from __future__ import annotations

import json
import re

from agent_runtime.base import FAST, answer_context_instruction

from .completion import complete
from .errors import InternalError
from .schemas import (
    SELECTION_PATH,
    AnswerContextRequest,
    AnswerContextResponse,
    WikiIndexEntry,
)

# 계약: "Wiki와 일정은 각각 최대 5개를 관련도 순서로 선택합니다."
MAX_PICKS = 5

# 목차에서 ID 5개를 고르는 일이다. 넘으면 진행이 아니라 고장이다.
SELECTION_TIMEOUT_SECONDS = 30

ERROR_CODE = "ANSWER_CONTEXT_SELECTION_FAILED"
VALIDATION_CODE = "INVALID_ANSWER_CONTEXT_REQUEST"

# 조립된 입력 전체의 상한. 토큰 20,000 을 보수적 상계 0.5 토큰/바이트로 환산한 값이다
# (실측 최대 0.488). 자르지 않고 거절하는 이유는 조용한 열화가 최악이기 때문이다 — 목차를
# 자르면 잘린 뒤의 페이지가 화이트리스트에서 사라져 정답을 놓치는데 응답은 성공으로 보인다.
MAX_INPUT_BYTES = 40_000

_INDEX_LINK_RE = re.compile(r"pages/([A-Za-z0-9_-]+)\.md")
_TOKEN_RE = re.compile(r"[A-Za-z0-9_-]+")
_TYPES = ("wiki", "schedule", "mixed")


def index_wiki_ids(indexes: list[WikiIndexEntry]) -> list[str]:
    """모든 스코프 목차의 페이지 링크에 실재하는 wikiId — 등장 순서, 중복 제거."""
    found: list[str] = []
    for entry in indexes:
        for wiki_id in _INDEX_LINK_RE.findall(entry.indexMarkdown or ""):
            if wiki_id not in found:
                found.append(wiki_id)
    return found


def resolve_question_type(declared: str, wiki_ids: list[str],
                          schedule_ids: list[str]) -> str:
    """모델 선언보다 **실제 선택 결과**를 믿는다."""
    if wiki_ids and schedule_ids:
        return "mixed"
    if wiki_ids:
        return "wiki"
    if schedule_ids:
        return "schedule"
    return declared if declared in _TYPES else "wiki"


def _pick(candidates: list[str], allowed: list[str]) -> list[str]:
    picked: list[str] = []
    for candidate in candidates:
        if candidate in allowed and candidate not in picked:
            picked.append(candidate)
            if len(picked) == MAX_PICKS:
                break
    return picked


def _json_object(text: str) -> dict | None:
    """텍스트 안의 첫 JSON 객체. 코드펜스·앞뒤 설명을 견딘다."""
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def parse_answer_context(text: str, wiki_allowed: list[str],
                         schedule_allowed: list[str]
                         ) -> tuple[str, list[str], list[str], str]:
    """모델 응답 → (questionType, wikiIds, scheduleIds, reason).

    JSON 이 정본이지만 그것에 의존하지 않는다. 모델이 설명을 덧붙이거나 코드펜스를 두르는
    일이 흔하고, 그때 선택을 통째로 버리면 사용자가 답을 못 받는다 — 자유 텍스트에서도
    ID 를 줍는다. 어느 경로든 마지막 관문은 같다: 화이트리스트에 있는 ID 만.
    """
    payload = _json_object(text or "")
    reason = ""
    declared = ""
    wiki_candidates: list[str] | None = None
    schedule_candidates: list[str] | None = None

    if payload is not None:
        raw_reason = payload.get("reason")
        if isinstance(raw_reason, (str, int, float)):
            reason = str(raw_reason).strip()
        raw_type = payload.get("questionType")
        declared = str(raw_type).strip().lower() if isinstance(raw_type, str) else ""
        if isinstance(payload.get("wikiIds"), list):
            wiki_candidates = [str(x).strip() for x in payload["wikiIds"]]
        if isinstance(payload.get("scheduleIds"), list):
            schedule_candidates = [str(x).strip() for x in payload["scheduleIds"]]

    loose = _TOKEN_RE.findall(text or "")
    if wiki_candidates is None:
        wiki_candidates = loose
    if schedule_candidates is None:
        schedule_candidates = loose
    if not reason:
        reason = (text or "").strip()

    wiki_ids = _pick(wiki_candidates, wiki_allowed)
    schedule_ids = _pick(schedule_candidates, schedule_allowed)
    return (resolve_question_type(declared, wiki_ids, schedule_ids),
            wiki_ids, schedule_ids, reason)


def _input_bytes(messages: list[dict]) -> int:
    """조립된 메시지 전체의 UTF-8 바이트. 필드별로 세면 합계가 상한을 넘는 조합이 통과한다."""
    total = 0
    for message in messages:
        content = message.get("content")
        if isinstance(content, str):
            total += len(content.encode("utf-8"))
        else:
            for block in content or []:
                total += len(str(block.get("text", "")).encode("utf-8"))
    return total


async def select_answer_context(runtime, payload: AnswerContextRequest, *,
                                request_id: str = "") -> AnswerContextResponse:
    messages = answer_context_instruction(
        payload.question,
        [m.model_dump() for m in payload.conversationMessages],
        [e.model_dump() for e in payload.wikiIndexes],
        [s.model_dump() for s in payload.scheduleSummaries],
    )
    size = _input_bytes(messages)
    if size > MAX_INPUT_BYTES:
        # 모델을 부르지 않는다 — 크레딧이 나가고, 컨텍스트를 넘기면 어차피 실패한다.
        raise InternalError(
            VALIDATION_CODE,
            f"입력이 상한을 넘었습니다 ({size} > {MAX_INPUT_BYTES} 바이트).",
            status=400,
            field_errors=[{"field": "wikiIndexes",
                           "reason": f"조립된 입력 {size} 바이트가 상한 "
                                     f"{MAX_INPUT_BYTES} 를 초과합니다."}])

    result = await complete(runtime, messages, tier=FAST,
                            timeout=SELECTION_TIMEOUT_SECONDS,
                            error_code=ERROR_CODE, path=SELECTION_PATH,
                            request_id=request_id)
    question_type, wiki_ids, schedule_ids, reason = parse_answer_context(
        result.text, index_wiki_ids(payload.wikiIndexes),
        [s.scheduleId for s in payload.scheduleSummaries])
    if not reason:
        reason = ("관련된 자료를 찾지 못했습니다."
                  if not (wiki_ids or schedule_ids) else "선택 근거가 없습니다.")
    return AnswerContextResponse(questionType=question_type, wikiIds=wiki_ids,
                                 scheduleIds=schedule_ids, reason=reason)
```

- [ ] **Step 5: 라우터를 만들고 등록한다**

`ai/src/wiki_api/routers/answer.py`:

```python
"""챗봇 답변 엔드포인트 2개.

세션을 열지 않는다 — 임시 색인도 MCP 서버도 필요 없고, 그래서 변환 큐의 직렬 잠금도
잡지 않는다. 채팅이 위키 변환을 기다릴 이유가 없다.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, FastAPI

from ..answer_selection import select_answer_context
from ..deps import make_api_key_guard, request_id
from ..schemas import AnswerContextRequest, AnswerContextResponse


def build_router(app: FastAPI) -> APIRouter:
    guard = make_api_key_guard(app.state.api_key)
    router = APIRouter(prefix="/internal/v1", dependencies=[Depends(guard)])

    @router.post("/answer-context-selections", response_model=AnswerContextResponse)
    async def select_context(payload: AnswerContextRequest,
                             rid: str = Depends(request_id)) -> AnswerContextResponse:
        return await select_answer_context(app.state.runtime, payload, request_id=rid)

    return router
```

`ai/src/wiki_api/app.py` 의 라우터 등록에 추가:

```python
    from .routers import answer, source_parse, wiki

    app.include_router(wiki.build_router(app))
    app.include_router(source_parse.build_router(app))
    app.include_router(answer.build_router(app))
```

- [ ] **Step 6: 통과와 전체 테스트**

Run: `cd ai && uv run pytest tests/api/test_answer_selection.py -v && uv run pytest -m "not ocr" -q`
Expected: PASS

`test_형식_오류는_계약_400_코드다` 가 `INVALID_REQUEST` 를 받으면 `_VALIDATION_CODES` 의 경로 문자열에 오타가 있는 것이다 (Task 6 Step 5).

- [ ] **Step 7: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/src/wiki_api/answer_selection.py ai/src/wiki_api/routers/answer.py ai/src/wiki_api/app.py ai/src/agent_runtime/base.py ai/tests/api/test_answer_selection.py
git commit -m "feat(ai): 챗봇 1단계 분류와 자료 선택을 구현한다

모델이 낸 ID 를 화이트리스트로 거른다. 위키는 목차 링크가 근거지만 일정에는
목차가 없어 요청이 실어 온 요약 목록을 쓴다. questionType 은 선언이 아니라
실제 선택 결과로 교정한다. 목차를 프롬프트 맨 앞 블록에 두어 캐시 접두사를
고정한다."
```

---

## Task 8: 2단계 — 답변과 출처

**Files:**
- Create: `ai/src/wiki_api/answer.py`
- Modify: `ai/src/agent_runtime/base.py` (프롬프트 함수 추가)
- Modify: `ai/src/wiki_api/routers/answer.py` (엔드포인트 추가)
- Test: `ai/tests/api/test_answer.py` (create)

**Interfaces:**
- Consumes: `complete` (Task 2), 스키마 (Task 6), `QUALITY` (Task 1)
- Produces:
  - `ANSWER_TIMEOUT_SECONDS = 60`, `MAX_INPUT_BYTES = 120_000`
  - `def parse_answer(text: str, wikis: dict[str, str], schedules: dict[str, str]) -> tuple[str | None, list[AnswerSource]]` — `answer` 가 비면 `None`. 호출자가 그것을 500 으로 바꾼다
  - `async def generate_answer(runtime, payload: AnswerRequest, *, request_id: str) -> AnswerResponse`
  - `agent_runtime.base.answer_instruction(question, messages, wikis, schedules, question_type) -> list[dict]`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/api/test_answer.py`:

```python
"""챗봇 2단계 — 답변 생성과 출처 복원.

`sources` 는 모델의 자기 신고다. **지어낸 ID 는 막지만 "안 쓴 자료를 출처로 신고하는 것"은
막지 못한다.** 그 한계를 알고 쓴다 — 각주 강제로 기계 검증할 수 있지만 계약에 답변 본문의
각주 표기 형식이 없다.

JSON 이 깨져도 답변을 버리지 않는다. 내용은 맞고 포맷만 틀린 경우가 흔하고, 버리면 재호출에
같은 크레딧을 또 쓴다. `answer` 가 실제로 비었을 때만 실패시킨다.
"""

from fastapi.testclient import TestClient

from agent_runtime.base import CompletionResult
from wiki_api.answer import parse_answer
from wiki_api.app import create_app

API_KEY = "secret-key"
PATH = "/internal/v1/answers"

WIKIS = {"101": "휴가 규정", "102": "보상 체계"}
SCHEDULES = {"31": "8월 휴가 일정"}

REQUEST = {
    "questionId": "500",
    "conversationId": "chat-123",
    "questionType": "mixed",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [
        {"role": "user", "content": "연차 신청 방법을 알려줘."},
        {"role": "assistant", "content": "연차 신청 절차는 다음과 같습니다."},
    ],
    "selectedWikis": [
        {"wikiId": "101", "title": "휴가 규정", "contentMarkdown": "# 휴가 규정\n연차 15일"}
    ],
    "selectedSchedules": [
        {"scheduleId": "31", "title": "8월 휴가 일정", "content": "개발부 휴가",
         "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z"}
    ],
}

GOOD = ('{"answer": "연차는 15일입니다.", "sources": '
        '[{"type": "wiki", "wikiId": "101", "title": "휴가 규정"}]}')


class FakeRuntime:
    name = "fake-answer"

    def __init__(self, text: str, raises: Exception | None = None):
        self.text = text
        self.raises = raises
        self.messages: list[list[dict]] = []
        self.tiers: list[str] = []

    def complete(self, messages, *, tier="quality", timeout=None):
        self.messages.append(messages)
        self.tiers.append(tier)
        if self.raises:
            raise self.raises
        return CompletionResult(text=self.text)


def _post(runtime, body=None):
    app = create_app(api_key=API_KEY)
    app.state.runtime = runtime
    return TestClient(app).post(PATH, json=body or REQUEST,
                                headers={"X-Internal-API-Key": API_KEY})


# ---- 파싱 ------------------------------------------------------------------

def test_받은_ID_만_출처가_된다():
    text = ('{"answer": "답", "sources": ['
            '{"type": "wiki", "wikiId": "101", "title": "휴가 규정"},'
            '{"type": "wiki", "wikiId": "999", "title": "지어낸 것"}]}')
    answer, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert answer == "답"
    assert [s.wikiId for s in sources] == ["101"]


def test_제목은_요청값을_쓴다():
    """모델이 제목을 바꿔 쓰면 answer_source.source_title 에 다른 문자열이 저장된다."""
    text = ('{"answer": "답", "sources": '
            '[{"type": "wiki", "wikiId": "101", "title": "엉뚱한 제목"}]}')
    _, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert sources[0].title == "휴가 규정"


def test_type_은_어느_배열에서_왔는지로_정한다():
    text = ('{"answer": "답", "sources": '
            '[{"type": "wiki", "scheduleId": "31", "title": "x"}]}')
    _, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert sources[0].type == "schedule"
    assert sources[0].scheduleId == "31"
    assert sources[0].wikiId is None


def test_ID_가_양쪽에_있으면_버린다():
    """모호한 출처는 없는 출처보다 나쁘다."""
    text = ('{"answer": "답", "sources": '
            '[{"type": "wiki", "wikiId": "101", "scheduleId": "31", "title": "x"}]}')
    _, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert sources == []


def test_중복_출처를_지운다():
    text = ('{"answer": "답", "sources": ['
            '{"type": "wiki", "wikiId": "101", "title": "휴가 규정"},'
            '{"type": "wiki", "wikiId": "101", "title": "휴가 규정"}]}')
    _, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert len(sources) == 1


def test_JSON_이_깨지면_전문을_답변으로_쓴다():
    text = "연차는 15일입니다. 8월 3일에 개발부 휴가가 있습니다."
    answer, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert answer == text
    assert sources == []


def test_sources_가_배열이_아니면_빈_배열이다():
    text = '{"answer": "연차는 15일입니다.", "sources": "휴가 규정"}'
    answer, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert answer == "연차는 15일입니다."
    assert sources == []


def test_answer_키가_없으면_전문을_쓴다():
    text = '{"sources": [{"type": "wiki", "wikiId": "101", "title": "휴가 규정"}]}'
    answer, _ = parse_answer(text, WIKIS, SCHEDULES)
    assert answer == text


def test_빈_답변은_None_을_돌려준다():
    answer, sources = parse_answer('{"answer": "   ", "sources": []}', WIKIS, SCHEDULES)
    assert answer is None


# ---- 엔드포인트 ------------------------------------------------------------

def test_계약_모양으로_응답한다():
    response = _post(FakeRuntime(GOOD))
    assert response.status_code == 200
    assert response.json() == {
        "answer": "연차는 15일입니다.",
        "sources": [{"type": "wiki", "wikiId": "101", "title": "휴가 규정"}],
    }


def test_quality_tier_로_부른다():
    runtime = FakeRuntime(GOOD)
    _post(runtime)
    assert runtime.tiers == ["quality"]


def test_자료가_0개여도_200_이다():
    """400 이 아니다. 계약의 400 은 형식 오류를 뜻한다."""
    runtime = FakeRuntime('{"answer": "관련 자료가 없어 답변할 수 없습니다.", "sources": []}')
    body = dict(REQUEST, questionType="wiki", selectedWikis=[], selectedSchedules=[])
    response = _post(runtime, body)
    assert response.status_code == 200
    assert response.json()["sources"] == []
    assert response.json()["answer"]


def test_빈_답변은_계약_500_코드다():
    response = _post(FakeRuntime('{"answer": "", "sources": []}'))
    assert response.status_code == 500
    assert response.json()["code"] == "ANSWER_GENERATION_FAILED"


def test_런타임_실패는_계약_500_코드다():
    response = _post(FakeRuntime("", raises=RuntimeError("게이트웨이 거부")))
    assert response.status_code == 500
    assert response.json()["code"] == "ANSWER_GENERATION_FAILED"


def test_형식_오류는_계약_400_코드다():
    response = _post(FakeRuntime(GOOD), dict(REQUEST, questionType="general"))
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_GENERATION_REQUEST"


def test_입력이_상한을_넘으면_400_이고_모델을_부르지_않는다():
    from wiki_api.answer import MAX_INPUT_BYTES

    huge = "가" * (MAX_INPUT_BYTES // 3 + 100)
    runtime = FakeRuntime(GOOD)
    body = dict(REQUEST, selectedWikis=[
        {"wikiId": "101", "title": "휴가 규정", "contentMarkdown": huge}])
    response = _post(runtime, body)
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_GENERATION_REQUEST"
    assert runtime.messages == []
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_answer.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'wiki_api.answer'`

- [ ] **Step 3: 프롬프트 함수를 추가한다**

`ai/src/agent_runtime/base.py` 의 `answer_context_instruction` 다음에:

```python
def answer_instruction(question: str, messages: list[dict], wikis: list[dict],
                       schedules: list[dict], question_type: str) -> list[dict]:
    """챗봇 2단계 프롬프트.

    1단계와 달리 캐싱을 걸지 않는다 — 실린 본문이 질문마다 다르므로 공유 접두사가 없다.

    `questionType` 별로 쓰는 배열이 다르다 (계약 정책): `wiki` 는 위키만, `schedule` 은
    일정만, `mixed` 는 둘 다. 그래서 안 쓰는 쪽은 프롬프트에 넣지 않는다 — 넣으면 모델이
    질문과 무관한 자료를 근거로 끌어온다.

    **주어진 자료 밖으로 나가지 말라고 지시한다.** 근거 없는 답변 금지(NFR-AI-002)는
    위키 편집과 같은 규칙이고, 챗봇에서는 그것이 곧 환각 방지다.
    """
    parts = []
    if question_type in ("wiki", "mixed"):
        wiki_text = "\n\n".join(
            f"### 위키 `{w['wikiId']}` — {w['title']}\n\n{w['contentMarkdown']}"
            for w in wikis
        ) or "(위키 자료 없음)"
        parts.append(f"## 위키 자료\n\n{wiki_text}")
    if question_type in ("schedule", "mixed"):
        schedule_text = "\n\n".join(
            f"### 일정 `{s['scheduleId']}` — {s['title']}\n"
            f"- 기간: {s['startAt']} ~ {s['endAt']}\n"
            + (f"- 대상: {s['targetText']}\n" if s.get("targetText") else "")
            + (f"- 장소: {s['location']}\n" if s.get("location") else "")
            + f"\n{s['content']}"
            for s in schedules
        ) or "(일정 자료 없음)"
        parts.append(f"## 일정 자료\n\n{schedule_text}")

    history = "\n".join(
        f"  {'사용자' if m['role'] == 'user' else '답변'}: {m['content']}"
        for m in messages
    )
    if history:
        parts.append(f"## 이전 대화\n\n{history}")

    parts.append(
        f"## 현재 질문\n\n{question}\n\n"
        f"위 자료만을 근거로 한국어로 답한다. **자료에 없는 내용은 쓰지 않는다** — "
        f"자료가 없거나 답을 찾을 수 없으면 그렇다고 답한다. 이전 대화를 참고해 현재 "
        f"질문이 무엇을 가리키는지 본다.\n\n"
        f"`sources` 에는 **실제로 근거로 쓴** 자료만 넣는다. 읽었지만 답에 쓰지 않은 것은 "
        f"넣지 않는다.\n\n"
        f"다른 말 없이 JSON 만 출력한다:\n"
        f'{{"answer": "답변 본문", "sources": ['
        f'{{"type": "wiki", "wikiId": "101", "title": "휴가 규정"}}]}}\n'
    )
    return [{"role": "user", "content": "\n\n".join(parts)}]
```

- [ ] **Step 4: 2단계를 구현한다**

`ai/src/wiki_api/answer.py`:

```python
"""챗봇 2단계 — 답변 생성 (`POST /internal/v1/answers`).

**쓰기가 없다.** 그래서 에이전트도 MCP 도 필요 없다. 챗봇에 에이전트를 쓰지 않는 이유는
권한이 아니라 쓸 것이 없어서다.

`sources` 는 모델이 답변과 함께 내고 우리가 받은 ID 집합으로 거른다. 한계를 명시한다 —
**"지어낸 ID"는 막지만 "안 쓴 자료를 출처로 신고하는 것"은 막지 못한다.** 각주 강제(위키
에이전트 방식)로 기계 검증할 수 있지만 계약에 답변 본문의 각주 표기 형식이 없다.

JSON 이 깨져도 답변을 버리지 않는다. 모델이 내용은 맞게 답하고 포맷만 틀리는 경우가 흔하고,
버리고 재호출하면 같은 크레딧을 다시 쓴다. `answer` 가 실제로 비었을 때만 실패시킨다 —
그때는 살릴 것이 없다. `ai_answer.content` 가 `TEXT NOT NULL` 이라는 것은 근거가 아니다:
MySQL 의 NOT NULL 은 빈 문자열을 막지 않는다.
"""

from __future__ import annotations

import json

from agent_runtime.base import QUALITY, answer_instruction

from .completion import complete
from .errors import InternalError
from .schemas import ANSWER_PATH, AnswerRequest, AnswerResponse, AnswerSource

# 본문을 읽고 답변을 만든다. 1단계보다 길게 준다.
ANSWER_TIMEOUT_SECONDS = 60

ERROR_CODE = "ANSWER_GENERATION_FAILED"
VALIDATION_CODE = "INVALID_ANSWER_GENERATION_REQUEST"

# 토큰 60,000 을 보수적 상계 0.5 토큰/바이트로 환산. 5페이지 최악(50KB)의 2.4배다.
MAX_INPUT_BYTES = 120_000


def _json_object(text: str) -> dict | None:
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _sources_of(raw, wikis: dict[str, str],
                schedules: dict[str, str]) -> list[AnswerSource]:
    """모델이 낸 출처 목록 → 계약 모양. 받은 ID 만, 중복 없이.

    `type` 과 `title` 은 모델 선언을 쓰지 않는다 — 어느 배열에 실재하는지로 종류를 정하고
    제목은 요청이 실어 온 값을 쓴다. 모델이 제목을 바꿔 쓰면 `answer_source.source_title`
    에 실제와 다른 문자열이 저장된다.
    """
    if not isinstance(raw, list):
        return []
    out: list[AnswerSource] = []
    seen: set[tuple[str, str]] = set()
    for item in raw:
        if not isinstance(item, dict):
            continue
        wiki_id = str(item.get("wikiId") or "").strip()
        schedule_id = str(item.get("scheduleId") or "").strip()
        in_wikis = wiki_id in wikis
        in_schedules = schedule_id in schedules
        if in_wikis and in_schedules:
            # 모호한 출처는 없는 출처보다 나쁘다.
            continue
        if in_wikis:
            key = ("wiki", wiki_id)
            if key in seen:
                continue
            seen.add(key)
            out.append(AnswerSource(type="wiki", wikiId=wiki_id,
                                    title=wikis[wiki_id]))
        elif in_schedules:
            key = ("schedule", schedule_id)
            if key in seen:
                continue
            seen.add(key)
            out.append(AnswerSource(type="schedule", scheduleId=schedule_id,
                                    title=schedules[schedule_id]))
    return out


def parse_answer(text: str, wikis: dict[str, str], schedules: dict[str, str]
                 ) -> tuple[str | None, list[AnswerSource]]:
    """모델 응답 → (answer, sources). `answer` 가 비면 None 을 돌려준다."""
    payload = _json_object(text or "")
    if payload is None or not isinstance(payload.get("answer"), str):
        # 포맷만 틀린 경우다. 전문을 답변으로 살리고 출처를 포기한다.
        body = (text or "").strip()
        return (body or None), []
    body = payload["answer"].strip()
    if not body:
        return None, []
    return body, _sources_of(payload.get("sources"), wikis, schedules)


def _input_bytes(messages: list[dict]) -> int:
    total = 0
    for message in messages:
        content = message.get("content")
        if isinstance(content, str):
            total += len(content.encode("utf-8"))
        else:
            for block in content or []:
                total += len(str(block.get("text", "")).encode("utf-8"))
    return total


async def generate_answer(runtime, payload: AnswerRequest, *,
                          request_id: str = "") -> AnswerResponse:
    messages = answer_instruction(
        payload.question,
        [m.model_dump() for m in payload.conversationMessages],
        [w.model_dump() for w in payload.selectedWikis],
        [s.model_dump() for s in payload.selectedSchedules],
        payload.questionType,
    )
    size = _input_bytes(messages)
    if size > MAX_INPUT_BYTES:
        raise InternalError(
            VALIDATION_CODE,
            f"입력이 상한을 넘었습니다 ({size} > {MAX_INPUT_BYTES} 바이트).",
            status=400,
            field_errors=[{"field": "selectedWikis",
                           "reason": f"조립된 입력 {size} 바이트가 상한 "
                                     f"{MAX_INPUT_BYTES} 를 초과합니다."}])

    result = await complete(runtime, messages, tier=QUALITY,
                            timeout=ANSWER_TIMEOUT_SECONDS,
                            error_code=ERROR_CODE, path=ANSWER_PATH,
                            request_id=request_id)
    answer, sources = parse_answer(
        result.text,
        {w.wikiId: w.title for w in payload.selectedWikis},
        {s.scheduleId: s.title for s in payload.selectedSchedules})
    if answer is None:
        raise InternalError(ERROR_CODE, "모델이 빈 답변을 냈습니다.")
    return AnswerResponse(answer=answer, sources=sources)
```

- [ ] **Step 5: 라우터에 엔드포인트를 추가한다**

`ai/src/wiki_api/routers/answer.py` 의 임포트와 라우터에 추가:

```python
from ..answer import generate_answer
from ..schemas import AnswerRequest, AnswerResponse
```

```python
    @router.post("/answers", response_model=AnswerResponse)
    async def answer(payload: AnswerRequest,
                     rid: str = Depends(request_id)) -> AnswerResponse:
        return await generate_answer(app.state.runtime, payload, request_id=rid)
```

`response_model` 이 `None` 필드를 실어 보내지 않게 한다 — 계약 예시에 `wikiId` 만 있고 `scheduleId` 는 없다:

```python
    @router.post("/answers", response_model=AnswerResponse,
                 response_model_exclude_none=True)
```

- [ ] **Step 6: 통과와 전체 테스트**

Run: `cd ai && uv run pytest tests/api/test_answer.py -v && uv run pytest -m "not ocr" -q`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/src/wiki_api/answer.py ai/src/wiki_api/routers/answer.py ai/src/agent_runtime/base.py ai/tests/api/test_answer.py
git commit -m "feat(ai): 챗봇 2단계 답변 생성을 구현한다

출처는 받은 ID 집합으로 거르고 종류와 제목은 요청값으로 복원한다. JSON 이
깨져도 답변 전문을 살리고 출처만 포기한다 — 버리면 재호출에 같은 비용이
든다. 답변이 실제로 비었을 때만 실패시킨다."
```

---

## Task 9: 측정 하네스

**Files:**
- Copy: `/mnt/c/Users/wolyong/workspace/llmwiki/ai-server/backend_sim.py` → `ai/experiments/backend_sim.py`
- Copy: `/mnt/c/Users/wolyong/workspace/llmwiki/ai-server/experiments.py` → `ai/experiments/experiments.py`
- Copy: `/mnt/c/Users/wolyong/workspace/llmwiki/ai-server/compare.py` → `ai/experiments/compare.py`
- Create: `ai/experiments/chat_questions.json`
- Create: `ai/experiments/chat_sim.py`
- Test: `ai/tests/api/test_chat_sim.py` (create)

**Interfaces:**
- Consumes: 계약 엔드포인트 2개 (Task 7·8)
- Produces: `chat_sim.py` CLI — `--dry-run`, `--limit N`, `--stage {1,2,both}`, `--api URL`, `--wiki DIR`, `--out FILE`

**크레딧:** 0. 이 태스크는 `--dry-run` 까지만 한다.

- [ ] **Step 1: 이관하고 경로 이름을 확인한다**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
SRC=/mnt/c/Users/wolyong/workspace/llmwiki/ai-server
cp "$SRC/backend_sim.py" "$SRC/experiments.py" "$SRC/compare.py" ai/experiments/
cd ai && uv run grep -n "sys.path.insert\|from tools\|from vaultfs\|import mcp" experiments/backend_sim.py | head
```

옛 트리는 `mcp/`·`runtime/`·`api/` 였고 지금은 `src/wiki_mcp/`·`src/agent_runtime/`·`src/wiki_api/` 다. `sys.path.insert(0, str(ROOT / "mcp"))` 같은 줄이 있으면 지우고 임포트를 새 패키지 이름으로 바꾼다.

**이 파일들은 실행되지 않아도 커밋한다.** `experiments/INDEX.md` 가 `uv run python experiments.py` 를 명령으로 적어놨는데 실물이 없는 상태였다. 임포트가 깨져 있으면 파일 상단에 그 사실을 주석으로 남기고 `chat_sim.py` 는 그것에 의존하지 않게 만든다.

- [ ] **Step 2: 정답 라벨을 쓴다**

`ai/experiments/chat_questions.json`:

```json
{
  "wikiDir": "2026-07-27-opus46-12docs/data/wiki/ALL",
  "schedules": [
    {"scheduleId": "9001", "title": "8월 여름 휴가 기간",
     "startAt": "2026-08-03T00:00:00Z", "endAt": "2026-08-07T00:00:00Z",
     "targetText": "전사", "location": "-", "content": "8월 첫째 주 권장 휴가 기간"},
    {"scheduleId": "9002", "title": "전사 오프사이트",
     "startAt": "2026-09-01T00:00:00Z", "endAt": "2026-09-03T00:00:00Z",
     "targetText": "전사", "location": "Hedge House", "content": "연 1회 전사 오프사이트"},
    {"scheduleId": "9003", "title": "신입 온보딩 주간",
     "startAt": "2026-08-10T00:00:00Z", "endAt": "2026-08-14T00:00:00Z",
     "targetText": "신입", "location": "SF 오피스", "content": "대면 온보딩 주간"},
    {"scheduleId": "9004", "title": "보안 교육",
     "startAt": "2026-08-20T01:00:00Z", "endAt": "2026-08-20T03:00:00Z",
     "targetText": "전사", "location": "온라인", "content": "연간 보안 교육"},
    {"scheduleId": "9005", "title": "급여 리뷰 마감",
     "startAt": "2026-08-28T00:00:00Z", "endAt": "2026-08-28T09:00:00Z",
     "targetText": "매니저", "location": "-", "content": "반기 급여 리뷰 제출 마감"}
  ],
  "questions": [
    {"question": "연차 며칠까지 쓸 수 있어?",
     "expectedWikiIds": ["eddb3cec8f14"], "expectedScheduleIds": [],
     "expectedType": "wiki"},
    {"question": "책 사는 거 지원되나?",
     "expectedWikiIds": ["b63430958f86"], "expectedScheduleIds": [],
     "expectedType": "wiki"},
    {"question": "노트북 사양 어떻게 되지?",
     "expectedWikiIds": ["469b409d9597"], "expectedScheduleIds": [],
     "expectedType": "wiki"},
    {"question": "부업 해도 돼?",
     "expectedWikiIds": ["e785ad1e47c9"], "expectedScheduleIds": [],
     "expectedType": "wiki"},
    {"question": "스톡옵션 베스팅 기간이 어떻게 돼?",
     "expectedWikiIds": ["bfd285bfd962"], "expectedScheduleIds": [],
     "expectedType": "wiki"},
    {"question": "퇴사하면 스톡옵션 언제까지 행사할 수 있어?",
     "expectedWikiIds": ["bfd285bfd962", "2d0c4dd38694"], "expectedScheduleIds": [],
     "expectedType": "wiki"},
    {"question": "육아휴직 급여 보전되나?",
     "expectedWikiIds": ["177011e0ae2c"], "expectedScheduleIds": [],
     "expectedType": "wiki"},
    {"question": "오프사이트 예산이 얼마고 올해 언제야?",
     "expectedWikiIds": ["6466ec527a71"], "expectedScheduleIds": ["9002"],
     "expectedType": "mixed"}
  ]
}
```

마지막 질문이 `mixed` 를 시험하고, 여섯 번째가 두 페이지를 요구해 5개 상한에서 하나만 고르고 멈추는지를 본다.

- [ ] **Step 3: 하네스 테스트를 쓴다**

`ai/tests/api/test_chat_sim.py`:

```python
"""측정 하네스 — Spring 역할.

**`--dry-run` 이 크레딧 방어다.** 프롬프트가 처음부터 맞을 리 없고, 반복 실행이 조용히
예산을 먹는다. 호출 없이 조립 결과만 보는 경로가 있어야 한다.
"""

import json

from experiments.chat_sim import (
    build_answer_body,
    build_context_body,
    load_plan,
    score,
)

WIKI_DIR = "experiments/2026-07-27-opus46-12docs/data/wiki/ALL"


def test_계획_파일을_읽는다():
    plan = load_plan("experiments/chat_questions.json")
    assert len(plan["questions"]) == 8
    assert plan["schedules"][1]["scheduleId"] == "9002"


def test_1단계_요청을_계약_모양으로_조립한다():
    plan = load_plan("experiments/chat_questions.json")
    body = build_context_body(plan, plan["questions"][0], WIKI_DIR, index=0)
    assert body["question"] == "연차 며칠까지 쓸 수 있어?"
    assert body["wikiIndexes"][0]["scopeKey"] == "ALL"
    assert "pages/eddb3cec8f14.md" in body["wikiIndexes"][0]["indexMarkdown"]
    assert len(body["scheduleSummaries"]) == 5
    # 요약에는 본문이 없다 — 1단계는 목차·요약만 본다.
    assert "content" not in body["scheduleSummaries"][0]


def test_2단계_요청은_고른_ID_의_본문을_싣는다():
    plan = load_plan("experiments/chat_questions.json")
    body = build_answer_body(plan, plan["questions"][0], WIKI_DIR,
                             question_type="wiki",
                             wiki_ids=["eddb3cec8f14"], schedule_ids=[])
    assert body["questionType"] == "wiki"
    assert body["selectedWikis"][0]["wikiId"] == "eddb3cec8f14"
    assert body["selectedWikis"][0]["contentMarkdown"].strip()
    assert body["selectedSchedules"] == []


def test_채점은_포함_여부와_순위를_본다():
    result = score(expected=["101", "102"], actual=["999", "101", "102"])
    assert result["hit"] is True
    assert result["allHit"] is False or result["allHit"] is True
    assert result["rankOfFirstHit"] == 2
    assert result["noise"] == 1


def test_아무것도_못_맞히면_hit_이_거짓이다():
    result = score(expected=["101"], actual=["999"])
    assert result["hit"] is False
    assert result["rankOfFirstHit"] is None
```

- [ ] **Step 4: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_chat_sim.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'experiments'`

`experiments/` 가 패키지가 아니면 `ai/experiments/__init__.py` 를 만든다. **`ai/pyproject.toml` 의 `packages` 목록에는 넣지 않는다** — 측정 도구는 배포 대상이 아니다. `tool.pytest.ini_options` 의 `pythonpath` 에 `.` 을 추가해야 할 수 있다:

```toml
[tool.pytest.ini_options]
testpaths = ["tests"]
pythonpath = [".", "src"]
```

Run: `cd ai && uv run grep -n "pythonpath\|\[tool.pytest" -A 4 pyproject.toml`

- [ ] **Step 5: 하네스를 구현한다**

`ai/experiments/chat_sim.py`:

```python
"""챗봇 측정 하네스 — Spring 이 할 일을 대신한다.

**이 파일은 버리는 것이다.** 권한 검증, 목차 제공, 고른 ID 의 본문 읽기, 응답 저장은
백엔드의 일이다. Spring 이 생기면 사라진다.

`backend_sim.py` 의 `--via-api` 와 같은 자세를 따른다 — 측정 경로가 프로덕션 경로다.
계약 스키마·어댑터·타임아웃·응답 조립을 전부 실제로 통과한다.

**크레딧 방어가 이 파일의 절반이다.**

  * `--dry-run` — 호출 없이 조립된 프롬프트만 출력한다. 프롬프트 버그를 공짜로 잡는다
  * `--limit N` — 질문 2개로 먼저 확인하고 8개는 마지막 한 번
  * `--stage 1` — 1단계만. 2단계는 본문을 실어 비싸다
  * 재시도 없음 — 실패는 실패로 기록한다

사용:

    # 크레딧 0
    uv run python experiments/chat_sim.py --dry-run --limit 2

    # 서버를 띄운 뒤
    INTERNAL_API_KEY=k AI_COMPLETION_LOG=/tmp/c.jsonl \\
      uv run python -m wiki_api.serve --runtime deepagents --port 8000
    uv run python experiments/chat_sim.py --stage 1 --limit 2 \\
      --api http://127.0.0.1:8000 --key k
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent
DEFAULT_PLAN = ROOT / "chat_questions.json"


def load_plan(path: str | Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _index_markdown(wiki_dir: str | Path) -> str:
    return (Path(wiki_dir) / "index.md").read_text(encoding="utf-8")


def _page_markdown(wiki_dir: str | Path, wiki_id: str) -> str:
    return (Path(wiki_dir) / "pages" / f"{wiki_id}.md").read_text(encoding="utf-8")


def build_context_body(plan: dict, question: dict, wiki_dir: str | Path,
                       *, index: int) -> dict:
    """1단계 요청. **본문은 넣지 않는다** — 계약 정책이다."""
    summaries = [
        {k: v for k, v in schedule.items() if k != "content"}
        for schedule in plan["schedules"]
    ]
    return {
        "questionId": str(500 + index),
        "conversationId": f"chat-sim-{index}",
        "question": question["question"],
        "conversationMessages": [],
        "wikiIndexes": [{"scopeKey": "ALL",
                         "indexMarkdown": _index_markdown(wiki_dir)}],
        "scheduleSummaries": summaries,
    }


def build_answer_body(plan: dict, question: dict, wiki_dir: str | Path, *,
                      question_type: str, wiki_ids: list[str],
                      schedule_ids: list[str], index: int = 0) -> dict:
    """2단계 요청. Spring 이 하듯 고른 ID 의 본문을 파일에서 읽어 싣는다."""
    by_id = {s["scheduleId"]: s for s in plan["schedules"]}
    return {
        "questionId": str(500 + index),
        "conversationId": f"chat-sim-{index}",
        "questionType": question_type,
        "question": question["question"],
        "conversationMessages": [],
        "selectedWikis": [
            {"wikiId": wiki_id,
             "title": wiki_id,
             "contentMarkdown": _page_markdown(wiki_dir, wiki_id)}
            for wiki_id in wiki_ids
        ],
        "selectedSchedules": [by_id[sid] for sid in schedule_ids if sid in by_id],
    }


def score(expected: list[str], actual: list[str]) -> dict:
    """정답 포함률·순위·오답 유입."""
    expected_set = set(expected)
    hits = [i for i, value in enumerate(actual) if value in expected_set]
    return {
        "expected": expected,
        "actual": actual,
        "hit": bool(hits),
        "allHit": expected_set.issubset(set(actual)),
        "rankOfFirstHit": (hits[0] + 1) if hits else None,
        "noise": sum(1 for value in actual if value not in expected_set),
    }


def _post(api: str, path: str, key: str, body: dict) -> tuple[int, dict]:
    response = httpx.post(f"{api}{path}", json=body,
                          headers={"X-Internal-API-Key": key},
                          timeout=180.0)
    try:
        return response.status_code, response.json()
    except ValueError:
        return response.status_code, {"raw": response.text[:400]}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="챗봇 답변 API 측정 하네스")
    parser.add_argument("--plan", default=str(DEFAULT_PLAN))
    parser.add_argument("--api", default="http://127.0.0.1:8000")
    parser.add_argument("--key", default="")
    parser.add_argument("--wiki", default="",
                        help="위키 디렉터리. 비우면 plan 의 wikiDir 을 쓴다")
    parser.add_argument("--limit", type=int, default=0, help="질문 수 (0=전부)")
    parser.add_argument("--stage", choices=["1", "2", "both"], default="1")
    parser.add_argument("--dry-run", action="store_true",
                        help="호출하지 않고 조립된 요청만 출력한다 (크레딧 0)")
    parser.add_argument("--out", default="")
    args = parser.parse_args(argv)

    plan = load_plan(args.plan)
    wiki_dir = Path(args.wiki) if args.wiki else ROOT / plan["wikiDir"]
    questions = plan["questions"][:args.limit] if args.limit else plan["questions"]

    rows = []
    for index, question in enumerate(questions):
        context_body = build_context_body(plan, question, wiki_dir, index=index)
        if args.dry_run:
            blocks = context_body["wikiIndexes"][0]["indexMarkdown"]
            print(f"--- [{index}] {question['question']}")
            print(f"    목차 {len(blocks.encode('utf-8')):,} 바이트 · "
                  f"일정 {len(context_body['scheduleSummaries'])}건 · "
                  f"기대 위키 {question['expectedWikiIds']}")
            continue

        status, picked = _post(args.api, "/internal/v1/answer-context-selections",
                               args.key, context_body)
        row = {"question": question["question"], "stage1Status": status,
               "stage1": picked}
        if status == 200:
            row["wikiScore"] = score(question["expectedWikiIds"],
                                     picked.get("wikiIds", []))
            row["scheduleScore"] = score(question["expectedScheduleIds"],
                                         picked.get("scheduleIds", []))
            row["typeHit"] = picked.get("questionType") == question["expectedType"]

        if args.stage in ("2", "both") and status == 200:
            answer_body = build_answer_body(
                plan, question, wiki_dir,
                question_type=picked["questionType"],
                wiki_ids=picked.get("wikiIds", []),
                schedule_ids=picked.get("scheduleIds", []), index=index)
            row["stage2Status"], row["stage2"] = _post(
                args.api, "/internal/v1/answers", args.key, answer_body)

        rows.append(row)
        print(json.dumps(row, ensure_ascii=False)[:600])

    if args.dry_run:
        print("\ndry-run — 호출하지 않았다. 크레딧 소모 0.")
        return 0

    report = {"api": args.api, "stage": args.stage, "rows": rows}
    if args.out:
        Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                  encoding="utf-8")
        print(f"\n리포트: {args.out}")
    hits = sum(1 for r in rows if r.get("wikiScore", {}).get("hit"))
    print(f"\n위키 정답 포함 {hits}/{len(rows)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 6: 통과를 확인하고 dry-run 을 돌린다**

Run: `cd ai && uv run pytest tests/api/test_chat_sim.py -v`
Expected: PASS (5 passed)

Run: `cd ai && uv run python experiments/chat_sim.py --dry-run`
Expected: 질문 8개가 각각 목차 바이트 수와 기대 ID 를 출력하고 "크레딧 소모 0" 으로 끝난다. 목차가 6,869 바이트 근처로 나와야 한다 — `MAX_INPUT_BYTES`(40,000)의 6분의 1이다.

- [ ] **Step 7: 전체 테스트와 커밋**

Run: `cd ai && uv run pytest -m "not ocr" -q`

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/experiments/ ai/tests/api/test_chat_sim.py ai/pyproject.toml
git commit -m "feat(ai): 챗봇 측정 하네스와 정답 라벨을 추가한다

Spring 이 할 일(목차 제공, 고른 ID 의 본문 읽기)을 대신한다. dry-run 으로
프롬프트를 호출 없이 검사하고, limit 와 stage 로 실호출 범위를 좁힌다.
옛 작업 공간의 backend_sim·experiments·compare 도 함께 이관한다."
```

---

## Task 10: 프롬프트 캐싱 적중 확인

**Files:**
- Test: `ai/tests/api/test_prompt_cache.py` (create)
- Modify: `ai/src/agent_runtime/base.py` (필요시 `cache_control` 위치 조정)

**Interfaces:**
- Consumes: `answer_context_instruction` (Task 7), mock 서버 픽스처 (Task 4)
- Produces: 없음 (검증 태스크)

**크레딧:** 0. mock 서버로 요청 본문을 검사한다.

**왜 별도 태스크인가:** 예산의 150크레딧 항목이 캐시 적중을 전제한다. 적중이 0 이면 360 이 되고 합계가 415 로 늘어난다. **조용히 일어나므로 테스트로 못박는다.**

- [ ] **Step 1: 테스트를 쓴다**

`ai/tests/api/test_prompt_cache.py`:

```python
"""프롬프트 캐싱 — 접두사가 고정되는지.

캐시는 **접두사가 완전히 같을 때만** 맞는다. 목차가 맨 앞이 아니면 질문마다 접두사가 달라져
적중이 0 이 되고, 1단계 비용이 예산의 2.4배가 된다. 그것이 조용히 일어나므로 여기서 막는다.

두 겹으로 본다.

  * 조립 단계 — `cache_control` 이 첫 block 에 있고 그 block 이 질문마다 같은가
  * 전송 단계 — 실제로 그 모양이 요청 본문에 실리는가 (mock 서버)
"""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

pytest.importorskip("langchain_anthropic")

from agent_runtime.base import FAST, answer_context_instruction  # noqa: E402
from agent_runtime.deep_agents import DeepAgentsRuntime  # noqa: E402

INDEX = "# 목차\n- [휴가 규정](pages/101.md)\n- [보상](pages/102.md)\n"
SCHEDULES = [{"scheduleId": "31", "title": "8월 휴가",
              "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z"}]


def _messages(question: str, history=None):
    return answer_context_instruction(
        question, history or [], [{"scopeKey": "ALL", "indexMarkdown": INDEX}],
        SCHEDULES)


def test_첫_block_이_목차이고_캐시_표시가_붙는다():
    blocks = _messages("연차 며칠?")[0]["content"]
    assert blocks[0]["cache_control"] == {"type": "ephemeral"}
    assert INDEX in blocks[0]["text"]


def test_질문이_달라도_첫_block_이_같다():
    """접두사 고정. 이게 깨지면 적중이 0 이다."""
    a = _messages("연차 며칠?")[0]["content"][0]
    b = _messages("스톡옵션 베스팅?")[0]["content"][0]
    assert a == b


def test_이전_대화가_달라도_첫_block_이_같다():
    a = _messages("연차?", [])[0]["content"][0]
    b = _messages("연차?", [{"role": "user", "content": "앞선 질문"}])[0]["content"][0]
    assert a == b


def test_질문이_첫_block_에_들어가지_않는다():
    blocks = _messages("연차 며칠?")[0]["content"]
    assert "연차 며칠?" not in blocks[0]["text"]
    assert "연차 며칠?" in blocks[1]["text"]


def test_캐시_표시가_두_번_붙지_않는다():
    """`cache_control` 은 접두사의 끝을 가리킨다. 변동 block 에 붙으면 무의미하다."""
    blocks = _messages("연차 며칠?")[0]["content"]
    marked = [b for b in blocks if "cache_control" in b]
    assert len(marked) == 1
    assert marked[0] is blocks[0]


def test_전송_본문에_캐시_표시가_실린다(monkeypatch):
    """조립이 맞아도 클라이언트가 버리면 적중이 0 이다."""
    seen: list[dict] = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            seen.append(json.loads(self.rfile.read(length) or b"{}"))
            payload = {
                "id": "m", "type": "message", "role": "assistant",
                "model": "mock", "stop_reason": "end_turn",
                "content": [{"type": "text", "text": "{}"}],
                "usage": {"input_tokens": 1, "output_tokens": 1,
                          "cache_read_input_tokens": 0,
                          "cache_creation_input_tokens": 300},
            }
            data = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    host, port = server.server_address
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key-not-real")
    monkeypatch.setenv("ANTHROPIC_BASE_URL", f"http://{host}:{port}")
    try:
        result = DeepAgentsRuntime().complete(_messages("연차?"), tier=FAST, timeout=10)
    finally:
        server.shutdown()
        server.server_close()

    blocks = seen[0]["messages"][0]["content"]
    assert isinstance(blocks, list)
    assert blocks[0].get("cache_control") == {"type": "ephemeral"}
    # 캐시 생성 토큰이 결과형까지 올라와야 한다 — 적중 회귀를 이 값으로 본다.
    assert result.cache_creation_tokens == 300
```

- [ ] **Step 2: 실행한다**

Run: `cd ai && uv run pytest tests/api/test_prompt_cache.py -v`
Expected: 대부분 PASS. `test_전송_본문에_캐시_표시가_실린다` 가 실패하면 `langchain-anthropic` 이 block 의 `cache_control` 을 통과시키지 않는 것이다.

그때는 `answer_context_instruction` 이 돌려주는 block 에 `cache_control` 을 `additional_kwargs` 가 아니라 block dict 안에 그대로 두는지 확인하고, 필요하면 `ChatAnthropic` 이 요구하는 모양으로 바꾼다:

```bash
cd ai && uv run python -c "
from langchain_anthropic import ChatAnthropic
import inspect
from langchain_anthropic import chat_models
src = inspect.getsource(chat_models)
import re
for m in re.finditer(r'.{80}cache_control.{80}', src):
    print(m.group(0).replace(chr(10), ' '))
" | head -20
```

- [ ] **Step 3: 실패한 항목이 있으면 조립을 고친다**

`base.py` 의 `answer_context_instruction` 만 고친다. 테스트의 어서션은 바꾸지 않는다 — 그 모양이 예산의 근거다.

- [ ] **Step 4: 전체 테스트와 커밋**

Run: `cd ai && uv run pytest -m "not ocr" -q`

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/tests/api/test_prompt_cache.py ai/src/agent_runtime/base.py
git commit -m "test(ai): 프롬프트 캐시 접두사가 고정되는지 검증한다

목차가 첫 block 이 아니면 질문마다 접두사가 달라져 적중이 0 이 되고 1단계
비용이 예산의 2.4배가 된다. 조립과 전송 두 단계를 모두 본다."
```

---

## Task 11: 측정 실행

**Files:**
- Create: `ai/experiments/2026-07-29-chat-haiku/manifest.json`
- Create: `ai/experiments/2026-07-29-chat-haiku/report.json`
- Create: `ai/experiments/2026-07-29-chat-haiku/notes.md`
- Modify: `ai/experiments/INDEX.md` (실험 표에 한 줄)

**Interfaces:**
- Consumes: 전체
- Produces: 측정 수치. 정본은 `INDEX.md` 이고 다른 문서에 복사하지 않는다.

**크레딧: 이 태스크만 API 를 부른다. 예상 205 이하.**

- [ ] **Step 1: 환경을 확인한다**

```bash
cd ai
grep -c ANTHROPIC_API_KEY src/.env    # 1 이어야 한다
uv run python -c "
import os
from dotenv import load_dotenv
load_dotenv('src/.env')
print('키 길이', len(os.environ.get('ANTHROPIC_API_KEY','')))
print('BASE_URL', os.environ.get('ANTHROPIC_BASE_URL'))
"
```

- [ ] **Step 2: 서버를 띄운다 (별 터미널)**

```bash
cd ai
set -a && . src/.env && set +a
AI_COMPLETION_LOG=/tmp/chat-completions.jsonl \
  uv run python -m wiki_api.serve --runtime deepagents \
  --internal-api-key "$INTERNAL_API_KEY" --port 8000
```

`INTERNAL_API_KEY` 가 비어 있으면 모든 요청이 401 이다. 아무 값이나 넣는다.

- [ ] **Step 3: 스모크 1건 (예상 ~5크레딧)**

```bash
cd ai
uv run python experiments/chat_sim.py --stage 1 --limit 1 \
  --api http://127.0.0.1:8000 --key "$INTERNAL_API_KEY"
```

Expected: `stage1Status: 200`, `wikiScore.hit: true`. 실패하면 여기서 멈추고 원인을 고친다 — 8질문을 돌리기 전이다.

- [ ] **Step 4: 캐시 적중을 확인한다**

```bash
uv run python experiments/chat_sim.py --stage 1 --limit 2 \
  --api http://127.0.0.1:8000 --key "$INTERNAL_API_KEY"
tail -2 /tmp/chat-completions.jsonl | python3 -m json.tool --json-lines 2>/dev/null \
  || tail -2 /tmp/chat-completions.jsonl
```

Expected: 두 번째 줄의 `cacheReadTokens > 0`. **0 이면 멈춘다** — 8질문의 비용이 2.4배가 된다. Task 10 의 조립은 맞았는데 적중이 0 이면 원인은 둘이다: 목차 3,324토큰이 최소치(haiku 2,048)를 넘는지, 두 호출 간격이 5분 TTL 안인지.

- [ ] **Step 5: 1단계 8질문 (예상 ~150크레딧)**

```bash
cd ai
mkdir -p experiments/2026-07-29-chat-haiku
uv run python experiments/chat_sim.py --stage 1 \
  --api http://127.0.0.1:8000 --key "$INTERNAL_API_KEY" \
  --out experiments/2026-07-29-chat-haiku/report.json
```

- [ ] **Step 6: 2단계 2질문 (예상 ~50크레딧)**

```bash
uv run python experiments/chat_sim.py --stage both --limit 2 \
  --api http://127.0.0.1:8000 --key "$INTERNAL_API_KEY" \
  --out experiments/2026-07-29-chat-haiku/report-stage2.json
```

- [ ] **Step 7: `manifest.json` 을 쓴다**

```json
{
  "slug": "2026-07-29-chat-haiku",
  "purpose": "챗봇 1단계 분류·선택 정확도와 캐시 적중 (fast tier 단독)",
  "runtime": "deepagents",
  "gateway": "https://gms.ssafy.io/gmsapi/api.anthropic.com",
  "tierModels": {"fast": "anthropic:claude-haiku-4-5-20251001"},
  "wikiSource": "2026-07-27-opus46-12docs/data/wiki/ALL",
  "questions": 8,
  "stages": ["1", "2(2질문)"],
  "note": "응답이 말한 모델은 report.json 의 completions 를 본다 — 게이트웨이가 바꿔 끼울 수 있다"
}
```

`AI_COMPLETION_LOG` 의 내용을 실험 디렉터리로 옮긴다:

```bash
cp /tmp/chat-completions.jsonl experiments/2026-07-29-chat-haiku/completions.jsonl
```

- [ ] **Step 8: `notes.md` 에 판정을 쓴다**

무엇을 재려 했고 무엇이 나왔는지, 그리고 판정. 최소한 다음을 적는다:
- 위키 정답 포함률 N/8, 1순위 비율
- `questionType` 정확도 N/8
- 두 페이지를 요구한 질문(6번)에서 둘 다 골랐는지
- `mixed` 질문(8번)에서 일정을 함께 골랐는지
- 캐시 적중 토큰
- 실제 소모 크레딧 (GMS 원장과 대조)
- **haiku 로 충분한가** — 부족하면 그것이 sonnet 필요의 근거다

- [ ] **Step 9: `INDEX.md` 실험 표에 한 줄 추가**

```markdown
| `2026-07-29-chat-haiku` | 챗봇 1단계 정확도 (fast tier) | (판정을 여기에) |
```

수치를 `CLAUDE.md`·`README.md` 에 복사하지 않는다.

- [ ] **Step 10: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106
git add ai/experiments/2026-07-29-chat-haiku/ ai/experiments/INDEX.md
git commit -m "docs(ai): 챗봇 1단계 측정 결과를 기록한다"
```

---

## 남은 항목 — 이 계획 밖

| 항목 | 왜 미뤘나 |
| --- | --- |
| sonnet tier 대조 | 크레딧. haiku 품질이 부족하다는 근거가 나온 뒤 |
| deepagents **에이전트**(`run`) 스모크 | 문서 1건이 input 1.2M 토큰이다. 챗봇과 급이 다르다 |
| 목차 확장성 (200페이지) | 호출당 33k 토큰. 정답 라벨이 없어 정확도는 못 본다 |
| 위키 변환 12건 API 재현 | 14.8M 토큰 · $25 |
| `count_tokens` 를 측정 리포트에 쓰기 | GMS 과금 여부 미확인 |
| `sources` 각주 기계 검증 | 계약에 답변 본문의 각주 표기 형식이 없다 |

## 담당자 통보

| 대상 | 내용 |
| --- | --- |
| 기획·백엔드 | 티켓 S15P11B106-80 AC 가 계약과 다르다. "벡터DB 임베딩 및 검색"은 계약에 없다 — Spring 이 목차를 주고 AI 가 고른다. "권한 기반 검색"도 AI 몫이 아니다. 필드명은 `answerSources` 가 아니라 `sources` |
| 프론트 | `sources` 가 모델 자기 신고 기반이라는 한계 |
| 루트 문서 담당 | 루트 `CLAUDE.md` 저장소 구성 표에 `ai/` 항목이 없다 |
