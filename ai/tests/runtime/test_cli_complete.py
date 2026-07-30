"""테스트 경로의 단발 호출.

CLI 는 문자열 하나만 받으므로 messages 목록을 렌더한다. **`cache_control` 을 버린다** —
CLI 는 구독 청구라 크레딧과 무관하고, 캐싱은 배포 경로(deepagents)의 예산 문제다.

`run` 은 손대지 않는다. `experiments/` 의 규칙이 "CLI 수치는 CLI 수치끼리만 비교"이므로
그 경로를 바꾸면 기존 측정과 대조가 깨진다.
"""

import json

import pytest

from agent_runtime.base import DEFAULT_COMPLETE_TIMEOUT, FAST, QUALITY, CompletionResult
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
    assert result.cache_read_tokens == 40
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
        assert name not in ("claude-sonnet", "claude-haiku", "claude-opus")
