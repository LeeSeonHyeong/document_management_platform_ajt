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


async def test_타임아웃을_InternalError_로_바꾼다(monkeypatch):
    monkeypatch.setattr("wiki_api.completion._OUTER_GRACE_SECONDS", 0)
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


async def test_실패한_호출도_관측_로그에_남는다(tmp_path, monkeypatch):
    """실패해도 입력 토큰만큼 과금된다. 성공만 기록하면 예산 대조에서 그만큼이 사라진다."""
    log = tmp_path / "completions.jsonl"
    monkeypatch.setenv(TELEMETRY_ENV, str(log))
    runtime = SyncRuntime(raises=RuntimeError("게이트웨이 거부"))
    with pytest.raises(InternalError):
        await complete(runtime, MESSAGES, tier="fast", timeout=5,
                       error_code="X_FAILED", path="/p", request_id="rid-2")
    row = json.loads(log.read_text(encoding="utf-8").strip())
    assert row["requestId"] == "rid-2"
    assert "게이트웨이 거부" in row["error"]
    # 토큰은 모른다 — 응답을 못 받았다. 건수가 그 줄의 값이다.
    assert row["inputTokens"] == 0


async def test_타임아웃도_관측_로그에_남는다(tmp_path, monkeypatch):
    log = tmp_path / "completions.jsonl"
    monkeypatch.setenv(TELEMETRY_ENV, str(log))
    monkeypatch.setattr("wiki_api.completion._OUTER_GRACE_SECONDS", 0)
    with pytest.raises(InternalError):
        await complete(SyncRuntime(sleep=0.5), MESSAGES, tier="fast", timeout=0,
                       error_code="X_FAILED", path="/p", request_id="rid-3")
    row = json.loads(log.read_text(encoding="utf-8").strip())
    assert "timeout" in row["error"]


async def test_결과_필드_오류도_관측_로그에_남는다(tmp_path, monkeypatch):
    """런타임이 예외가 아니라 `error` 필드로 실패를 돌려주는 경우 (CLI 는 종료 코드 0)."""
    from agent_runtime.base import RunResult

    log = tmp_path / "completions.jsonl"
    monkeypatch.setenv(TELEMETRY_ENV, str(log))

    class ErrorFieldRuntime:
        name = "fake-error-field"

        def complete(self, messages, *, tier="quality", timeout=None):
            return RunResult(text="", error="claude 실패 (코드 1)")

    with pytest.raises(InternalError):
        await complete(ErrorFieldRuntime(), MESSAGES, tier="fast", timeout=5,
                       error_code="X_FAILED", path="/p", request_id="rid-4")
    row = json.loads(log.read_text(encoding="utf-8").strip())
    assert "claude 실패" in row["error"]


async def test_렌더링_정본이_하나다():
    """`render_messages` 가 두 곳에 복제돼 있으면 한쪽만 고쳐 조용히 갈린다."""
    from agent_runtime import base, claude_code
    from wiki_api import completion

    assert completion.render_messages is base.render_messages
    assert claude_code.render_messages is base.render_messages
