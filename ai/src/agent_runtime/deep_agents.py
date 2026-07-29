"""Runtime backed by DeepAgents.

Adapted from the deepagents-lucas-llmwiki spike that preceded this repo, where
DeepAgents driving the *Lucas* MCP tools was verified. **This server has not
been verified with it** — the tool names, the `scope` argument and the
`pages/{pageKey}.md` addressing are all ours, so the first run against it is a
smoke test, not a measurement.

The load-bearing detail is `EXCLUDED_BUILTIN_TOOLS`. DeepAgents ships its own
filesystem and shell tools; left enabled, the agent writes pages with `write_file`
and bypasses the MCP server entirely — and with it the permission boundary, the
reference graph, and the chunk index. The MCP tools have to be the only way to
touch anything, and `verify_tool_surface` checks that at startup rather than
trusting the profile to have been applied.

This is the runtime that ships. The CLI runtime exists because there is no API
budget yet, but `claude -p` as a subprocess is not a deployment: API convention
2.1 puts a FastAPI AI server here, and a subscription login is not available in a
server environment. So everything the requirements ask of a run — a time limit
(NFR-PERF-002), an explainable failure (NFR-AI-003), a traceable step
(NFR-MNT-002) — has to work here, and this module is where it lives.

Optional dependency: install with `uv sync --extra deepagents`.
"""

from __future__ import annotations

import logging
import tempfile
import time
from pathlib import Path

from wiki_mcp.telemetry import read_counts

from .base import RunResult

logger = logging.getLogger("llmwiki.deepagents")

# DeepAgents' built-ins. Every one of these is a way around the MCP server.
EXCLUDED_BUILTIN_TOOLS = frozenset({
    "write_todos", "ls", "read_file", "write_file", "edit_file",
    "glob", "grep", "execute", "task",
})

PROJECT_ROOT = Path(__file__).resolve().parents[2]
SERVER_MODULE = "wiki_mcp.local_server"

# NFR-PERF-002: a document over ten minutes must be failed. The CLI runtime gets
# this from a subprocess timeout; here it has to be enforced in the loop.
CALL_TIMEOUT_SECONDS = 600

# A turn cap is a runaway guard, not a quality lever. Measured ingests run 14–19
# turns, so this is well clear of normal work while still bounded.
MAX_TURNS = 60


def _server_config(root: Path, scope_key: str, job_id: str,
                   tool_log: Path | None = None) -> dict:
    """Same stdio server the CLI runtime launches, described for MCP adapters."""
    args = [
        "run", "--project", str(PROJECT_ROOT), "python", "-m", SERVER_MODULE,
        "--root", str(root), "--scope", scope_key, "--job-id", job_id,
    ]
    if tool_log:
        args += ["--tool-log", str(tool_log)]
    return {"wiki": {"transport": "stdio", "command": "uv", "args": args}}


EXPECTED_TOOLS = frozenset({
    "append", "create", "delete", "edit", "guide", "lint", "list_scopes",
    "merge", "read", "search",
})


def verify_mcp_tools(tool_names: set[str]) -> None:
    """Fail loudly if the MCP server did not serve every tool.

    Worth checking because the failure is silent: a server that registers nothing
    still starts, and the agent then reports success having written nothing. That
    cost a whole run on the CLI side before anyone noticed.

    **This cannot verify that the built-ins are excluded** — the list it receives
    comes from the MCP client, so it only ever contains MCP tools. Whether a
    built-in `write_file` is reachable is checked after the fact instead, by
    `backend_sim`: pages appeared but no write tool was logged means something
    wrote around the server.
    """
    missing = EXPECTED_TOOLS - tool_names
    if missing:
        raise RuntimeError(f"MCP 툴이 빠졌다: {sorted(missing)}")


class DeepAgentsRuntime:
    """Drives one ingest through a single DeepAgents coordinator."""

    name = "deepagents"

    def __init__(self, model: str = "anthropic:claude-opus-4-6"):
        self.model = model

    def run(self, instruction: str, *, root: Path, scope_key: str,
            job_id: str, timeout: int | None = None) -> RunResult:
        """`timeout` 은 이 문서에 허용된 초 (`agent_runtime/limits.py`). 세션이 계산해 넘긴다 —
        이 런타임이 배포 형태이므로 NFR-PERF-002 의 강제가 여기서 일어난다. 안 넘기면
        기존 고정값 `CALL_TIMEOUT_SECONDS`."""
        limit = timeout or CALL_TIMEOUT_SECONDS
        started = time.monotonic()
        with tempfile.TemporaryDirectory(prefix="llmwiki-ingest-") as tmp:
            tool_log = Path(tmp) / "tools.log"
            try:
                text, usage, turns = asyncio.run(
                    self._run(instruction, root, scope_key, job_id, tool_log, limit)
                )
            except TimeoutError:
                # The work space keeps whatever was written; the backend discards
                # it because the job never reported success (DR-009).
                return RunResult(
                    text="", tool_calls=read_counts(tool_log),
                    elapsed_seconds=round(time.monotonic() - started, 1),
                    error=f"{limit}초 안에 끝나지 않았다 (NFR-PERF-002)",
                )
            except Exception as exc:
                counts = read_counts(tool_log)
                # NFR-AI-003 wants a step, not just a message. The last tool the
                # agent reached is the closest thing to one.
                last = max(counts, key=counts.get) if counts else "시작 전"
                return RunResult(
                    text="", tool_calls=counts,
                    elapsed_seconds=round(time.monotonic() - started, 1),
                    error=f"{type(exc).__name__} (마지막 도달 단계: {last}): {exc}",
                )
            counts = read_counts(tool_log)

        return RunResult(
            text=text,
            tool_calls=counts,
            input_tokens=int(usage.get("input_tokens", 0) or 0),
            output_tokens=int(usage.get("output_tokens", 0) or 0),
            turns=turns,
            elapsed_seconds=round(time.monotonic() - started, 1),
        )

    async def _run(self, instruction: str, root: Path, scope_key: str,
                   job_id: str, tool_log: Path,
                   limit: int = CALL_TIMEOUT_SECONDS) -> tuple[str, dict, int]:
        import asyncio

        from deepagents import (
            GeneralPurposeSubagentProfile,
            HarnessProfile,
            create_deep_agent,
            register_harness_profile,
        )
        from langchain_mcp_adapters.client import MultiServerMCPClient

        client = MultiServerMCPClient(_server_config(root, scope_key, job_id, tool_log))
        tools = await client.get_tools()

        register_harness_profile(
            self.model,
            HarnessProfile(
                excluded_tools=EXCLUDED_BUILTIN_TOOLS,
                general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False),
            ),
        )
        agent = create_deep_agent(
            model=self.model,
            tools=tools,
            subagents=[],
            # Deliberately almost empty. The standards live in the `guide` tool so
            # both runtimes read the same copy — and so that a difference between
            # them is a harness difference, not a prompt difference.
            system_prompt="사내 위키 편집 에이전트다. `guide` 도구를 먼저 불러 작업 방식을 확인한다.",
        )

        verify_mcp_tools({getattr(t, "name", "") for t in tools})

        result = await asyncio.wait_for(
            agent.ainvoke(
                {"messages": [{"role": "user", "content": instruction}]},
                # Two turns per step in langgraph terms (model, then tools).
                {"recursion_limit": MAX_TURNS * 2},
            ),
            timeout=limit,
        )

        messages = result.get("messages") or []
        text = str(getattr(messages[-1], "content", "")) if messages else ""
        return text, _usage(messages), _turns(messages)


def _usage(messages: list) -> dict:
    """Sum token usage across the run.

    Unlike the CLI's numbers these are the real thing — no foreign system prompt
    inflating the cache fields — so they are comparable between DeepAgents runs and
    to the API baseline, but not to CLI runs.
    """
    total = {"input_tokens": 0, "output_tokens": 0}
    for message in messages:
        usage = getattr(message, "usage_metadata", None) or {}
        total["input_tokens"] += int(usage.get("input_tokens", 0) or 0)
        total["output_tokens"] += int(usage.get("output_tokens", 0) or 0)
    return total


def _turns(messages: list) -> int:
    """Model turns, counted the way the CLI reports `num_turns`."""
    return sum(1 for m in messages if getattr(m, "type", "") == "ai")
