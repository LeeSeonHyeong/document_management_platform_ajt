"""Guards that have to hold whichever runtime drives an ingest.

The CLI runtime gets a time limit from its subprocess and a tool count from the
server. DeepAgents ships with neither, and it is the runtime that actually
deploys — API convention 2.1 puts a FastAPI server here, and `claude -p` as a
subprocess is not that. So everything the requirements ask of a run has to work on
the DeepAgents path too, and these pin it without needing DeepAgents installed.
"""

import pytest

from agent_runtime.base import FAST
from agent_runtime.guards import bypassed_server, wrote_without_reading
from agent_runtime.claude_code import CALL_TIMEOUT_SECONDS as CLI_TIMEOUT
from agent_runtime.deep_agents import (
    CALL_TIMEOUT_SECONDS,
    EXCLUDED_BUILTIN_TOOLS,
    EXPECTED_TOOLS,
    MAX_TURNS,
    _server_config,
    verify_mcp_tools,
)

# NFR-PERF-002 — a document over ten minutes must be failed.
PERF_LIMIT_SECONDS = 600


def test_deepagents_enforces_the_document_time_limit():
    assert CALL_TIMEOUT_SECONDS <= PERF_LIMIT_SECONDS


def test_the_cli_timeout_is_a_ceiling_not_the_limit():
    """The CLI's timeout is only a runaway guard: the measured 12-document run put
    one document at 14m47s, so cutting at 600s there would have discarded work
    mid-measurement. The limit is enforced where it ships."""
    assert CLI_TIMEOUT > PERF_LIMIT_SECONDS


def test_both_runtimes_accept_a_per_document_time_limit():
    """세션이 계산한 상한(`runtime/limits.py`)이 런타임까지 가야 강제된다. `to_thread` 로
    띄운 sync 런타임은 취소할 수 없어서 런타임 자체 timeout 이 유일한 수단이다."""
    import inspect

    from agent_runtime.claude_code import ClaudeCodeRuntime
    from agent_runtime.deep_agents import DeepAgentsRuntime

    for cls in (ClaudeCodeRuntime, DeepAgentsRuntime):
        signature = inspect.signature(cls.run)
        assert "timeout" in signature.parameters, cls.__name__
        # 기본값은 기존 상수 그대로 — 안 넘기면 예전 동작이다.
        assert signature.parameters["timeout"].default is None, cls.__name__


def test_the_cli_default_timeout_is_unchanged():
    assert CLI_TIMEOUT == 900


def test_turn_cap_is_clear_of_normal_work():
    """Measured ingests run 14–19 turns. A cap below that would truncate real work;
    the cap is a runaway guard, not a quality lever."""
    assert MAX_TURNS > 19


def test_every_mcp_tool_is_required():
    """A server that registers nothing still starts, and the agent then reports
    success having written nothing."""
    with pytest.raises(RuntimeError, match="MCP 툴이 빠졌다"):
        verify_mcp_tools(EXPECTED_TOOLS - {"merge"})
    verify_mcp_tools(set(EXPECTED_TOOLS))


def test_builtin_exclusion_list_covers_every_write_path():
    """Each of these is a way to touch the store without the server seeing it."""
    for tool in ("write_file", "edit_file", "execute", "read_file", "glob", "grep", "ls"):
        assert tool in EXCLUDED_BUILTIN_TOOLS


def test_server_config_passes_scope_job_and_tool_log(tmp_path):
    config = _server_config(tmp_path, "D1-D2", "9001", tmp_path / "tools.log")
    args = config["wiki"]["args"]
    assert "--scope" in args and "D1-D2" in args
    assert "--job-id" in args and "9001" in args
    # Passed as an argument, not in `env`: an `env` block replaces the child
    # environment and `uv` then falls off PATH.
    assert "--tool-log" in args
    assert "env" not in config["wiki"]


def test_server_config_omits_the_tool_log_when_unused(tmp_path):
    assert "--tool-log" not in _server_config(tmp_path, "ALL", "9001")["wiki"]["args"]


# ----- the bypass detector --------------------------------------------------

CHANGE = [{"type": "create", "address": "pages/abc.md"}]


def test_changes_without_a_write_tool_call_is_a_bypass():
    """DeepAgents' own `write_file` would produce exactly this: pages appeared,
    the server never saw a write."""
    assert bypassed_server(CHANGE, {"guide": 1, "read": 2, "search": 1}) is True


@pytest.mark.parametrize("tool", ["create", "edit", "append", "merge", "delete"])
def test_any_write_tool_clears_it(tool):
    assert bypassed_server(CHANGE, {"guide": 1, tool: 1}) is False


def test_no_changes_is_not_a_bypass():
    assert bypassed_server([], {"guide": 1, "read": 1}) is False


def test_an_empty_tool_log_is_inconclusive_not_a_failure():
    """Counting can be off. Calling that a bypass would fail every such run."""
    assert bypassed_server(CHANGE, {}) is False


# ----- the blind-write detector ---------------------------------------------


def test_write_only_run_is_blind():
    """읽기 툴 0회는 현재 위키를 못 본 채 쓴 것이다."""
    assert wrote_without_reading({"guide": 1, "create": 3}) is True


def test_reading_either_tool_clears_the_gate():
    assert wrote_without_reading({"read": 1, "create": 1}) is False
    assert wrote_without_reading({"search": 1, "create": 1}) is False


def test_empty_log_is_not_a_verdict():
    """툴 호출을 세지 못한 런타임은 판단 불가다 — bypassed_server 와 같은 규칙."""
    assert wrote_without_reading({}) is False


def test_load_runtime_passes_model_settings_to_deepagents():
    from agent_runtime import load_runtime

    runtime = load_runtime("deepagents", "openai:main",
                           fast_model="openai:fast-x", quality_model="openai:quality-y",
                           credentials={"openai": ("key-1", "https://gw.example")})

    assert runtime.model == "openai:main"
    assert runtime._model_for(FAST) == "openai:fast-x"


def test_load_runtime_gives_each_model_its_own_providers_credentials():
    """에이전트 모델과 티어 모델의 프로바이더가 다르면 각자 맞는 키가 가야 한다
    (Important 1 — Anthropic 키가 OpenAI 클라이언트로 새던 문제)."""
    from agent_runtime import load_runtime

    runtime = load_runtime(
        "deepagents", "anthropic:claude-opus-4-6",
        fast_model="openai:gpt-5.4-mini",
        credentials={
            "anthropic": ("anthropic-key", "https://anthropic.example"),
            "openai": ("openai-key", "https://openai.example"),
        })

    assert runtime._credential_kwargs(runtime.model) == {
        "api_key": "anthropic-key", "base_url": "https://anthropic.example"}
    assert runtime._credential_kwargs(runtime._model_for(FAST)) == {
        "api_key": "openai-key", "base_url": "https://openai.example"}


def test_load_runtime_credentials_work_when_the_agent_model_is_unset():
    """`AI_MODEL` 이 비고 티어 모델만 있어도 그 티어 모델의 키가 가야 한다 — 전에는
    자격증명이 에이전트 모델 기준 하나뿐이라 이 경우 자격증명이 통째로 비었다."""
    from agent_runtime import load_runtime

    runtime = load_runtime(
        "deepagents", None, fast_model="openai:gpt-5.4-mini",
        credentials={"openai": ("openai-key", "")})

    assert runtime._credential_kwargs(runtime._model_for(FAST)) == {"api_key": "openai-key"}


def test_load_runtime_rejects_model_credentials_for_claude_code():
    """CLI 는 로그인 세션으로 과금한다. 키를 받아 조용히 무시하면 그 키로 도는 줄 안다."""
    from agent_runtime import load_runtime

    with pytest.raises(ValueError, match="로그인 세션으로 과금"):
        load_runtime("claude-code", credentials={"anthropic": ("key-1", "")})


def test_run_actually_goes_through_asyncio_run(tmp_path, monkeypatch):
    """`run()` 이 `asyncio.run(...)` 으로 `_run()` 을 실제로 구동하는지 확인한다.

    이 버그는 `import asyncio` 가 `_run` 안 지역 임포트로만 있어서 `run()` 에서
    `NameError` 가 났던 것이다 (`run()` 을 실제로 부르는 테스트가 없어서 놓쳤다). 여기서는
    `_run` 을 코루틴을 돌려주는 fake 로 갈아끼워 `asyncio.run` 경로를 실제로 태운다 —
    MCP 서버도 모델 호출도 없다.
    """
    from agent_runtime.deep_agents import DeepAgentsRuntime

    async def fake_run(self, instruction, root, scope_key, job_id, tool_log, limit):
        return "결과 텍스트", {"input_tokens": 3, "output_tokens": 5}, 2

    monkeypatch.setattr(DeepAgentsRuntime, "_run", fake_run)

    runtime = DeepAgentsRuntime()
    result = runtime.run("지시", root=tmp_path, scope_key="ALL", job_id="job-1")

    assert result.text == "결과 텍스트"
    assert result.input_tokens == 3
    assert result.output_tokens == 5
    assert result.turns == 2
    assert result.error is None


def test_load_runtime_rejects_base_url_for_claude_code():
    """base_url 단독으로도 거부해야 한다. api_key 체크만으로는 base_url 삭제 시 통과된다."""
    from agent_runtime import load_runtime

    with pytest.raises(ValueError, match="로그인 세션으로 과금"):
        load_runtime("claude-code", credentials={"anthropic": ("", "https://gw.example")})
