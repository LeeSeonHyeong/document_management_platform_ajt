"""단발 호출의 결과형.

`RunResult` 와 나눠 둔 이유를 테스트로 못박는다 — 이쪽에는 `tool_calls`·`turns` 가 없고
대신 `cache_read_tokens` 가 있다. 캐시 적중이 0 이면 예산 계산이 몇 배 틀리는데 그것이
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
