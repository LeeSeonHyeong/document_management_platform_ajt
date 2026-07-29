"""Runtime backed by the Claude Code CLI.

This is what runs today: it bills against the Claude Code subscription instead of
an API key. `agent_runtime/deep_agents.py` is the same interface over DeepAgents and
takes over when there is API budget.

Four traps this file exists to avoid. Each was paid for once already in the
`wiki-convert-loop` spike that preceded this repo:

1. **No `--bare`.** It refuses to read the logged-in session ("Not logged in ·
   Please run /login"), which pushes billing onto an API key and defeats the
   point. A temp cwd keeps this repo's CLAUDE.md out of the prompt instead.
2. **`--allowedTools` is not a boundary.** `--permission-mode bypassPermissions`
   waves the allowlist through, so a tool the model must not reach has to be one
   the server never registers. Here the server registers everything and the
   scope, not the tool list, is the boundary.
3. **Exit code is 0 even for a failed turn.** `is_error` in the JSON payload is
   the signal.
4. **`input_tokens` is not the input.** Claude Code's own system prompt lands in
   the cache fields — one call reported 2 with 42k cached. The three fields have
   to be summed, and the total is still not comparable to an API run. Compare CLI
   numbers only against other CLI numbers. `total_cost_usd` is the honest figure.

And one the CLI simply does not provide: **there is no tool-call list in the JSON
payload** (`num_turns` and `total_cost_usd` are there; tool uses are not). The MCP
server counts them instead — see `wiki_mcp/telemetry.py`.
"""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import time
from pathlib import Path

from wiki_mcp.telemetry import read_counts

from .base import RunResult

PROJECT_ROOT = Path(__file__).resolve().parents[2]
SERVER_MODULE = "wiki_mcp.local_server"

# NFR-PERF-002 caps one document at 10 minutes. An ingest is many tool calls in
# one turn, so it gets the whole budget plus room for the lint retry loop.
CALL_TIMEOUT_SECONDS = 900


class CliError(RuntimeError):
    pass


class ClaudeCodeRuntime:
    """Drives one ingest through `claude -p` with the wiki reachable over MCP."""

    name = "claude-code"

    def __init__(self, model: str = "sonnet"):
        self.model = model

    def _mcp_config(self, root: Path, scope_key: str, job_id: str, tool_log: Path) -> str:
        """Passed as a JSON string, not a file: the workspace differs per run and
        a shared config file would race between concurrent runs."""
        return json.dumps({
            "mcpServers": {
                "wiki": {
                    "command": "uv",
                    "args": [
                        "run", "--project", str(PROJECT_ROOT), "python", "-m", SERVER_MODULE,
                        "--root", str(root), "--scope", scope_key, "--job-id", job_id,
                        # The CLI's JSON output does not report tool calls, so
                        # the server counts them. Passed as an argument and not
                        # in `env`, which would wipe PATH — see wiki_mcp/telemetry.py.
                        "--tool-log", str(tool_log),
                    ],
                }
            }
        })

    def run(self, instruction: str, *, root: Path, scope_key: str,
            job_id: str, timeout: int | None = None) -> RunResult:
        """`timeout` 은 이 문서에 허용된 초 (`agent_runtime/limits.py`). 세션이 계산해 넘긴다 —
        `asyncio.to_thread` 로 띄운 이 호출은 취소할 수 없으므로 subprocess timeout 이
        NFR-PERF-002 를 강제하는 유일한 지점이다. 안 넘기면 기존 고정값."""
        limit = timeout or CALL_TIMEOUT_SECONDS
        started = time.monotonic()
        with tempfile.TemporaryDirectory(prefix="llmwiki-ingest-") as tmp:
            tool_log = Path(tmp) / "tools.log"
            argv = [
                "claude", "-p", instruction,
                "--model", self.model,
                "--output-format", "json",
                "--mcp-config", self._mcp_config(root, scope_key, job_id, tool_log),
                "--strict-mcp-config",
                "--permission-mode", "bypassPermissions",
            ]
            try:
                proc = subprocess.run(
                    argv, capture_output=True, text=True, timeout=limit,
                    cwd=tmp, env=os.environ.copy(),
                )
            except subprocess.TimeoutExpired:
                return RunResult(
                    text="", tool_calls=read_counts(tool_log),
                    elapsed_seconds=round(time.monotonic() - started, 1),
                    error=f"{limit}초 안에 끝나지 않았다",
                )

            # Read before the temp directory goes away.
            tool_calls = read_counts(tool_log)

        payload = _payload(proc.stdout)
        elapsed = round(time.monotonic() - started, 1)

        if proc.returncode != 0 or payload.get("is_error"):
            detail = payload.get("result") or proc.stderr.strip() or proc.stdout[:400]
            return RunResult(text="", tool_calls=tool_calls, elapsed_seconds=elapsed,
                             error=f"claude 실패 (코드 {proc.returncode}): {str(detail)[:400]}")

        usage = payload.get("usage") or {}
        return RunResult(
            text=str(payload.get("result") or ""),
            tool_calls=tool_calls,
            input_tokens=_input_tokens(usage if isinstance(usage, dict) else {}),
            output_tokens=int((usage or {}).get("output_tokens", 0) or 0),
            turns=int(payload.get("num_turns") or 0),
            cost_usd=float(payload.get("total_cost_usd") or 0.0),
            elapsed_seconds=elapsed,
        )


def _payload(stdout: str) -> dict:
    try:
        parsed = json.loads(stdout)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _input_tokens(usage: dict) -> int:
    """Everything the model read, cached or not. See trap 4 in the module docstring."""
    return sum(int(usage.get(k, 0) or 0) for k in (
        "input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens",
    ))
