"""Guards that have to hold whichever runtime drives an ingest.

The CLI runtime gets a time limit from its subprocess and a tool count from the
server. DeepAgents ships with neither, and it is the runtime that actually
deploys — API convention 2.1 puts a FastAPI server here, and `claude -p` as a
subprocess is not that. So everything the requirements ask of a run has to work on
the DeepAgents path too, and these pin it without needing DeepAgents installed.
"""

import pytest

from agent_runtime.guards import bypassed_server
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
