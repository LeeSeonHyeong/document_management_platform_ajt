"""Runtime backed by the Claude Code CLI.

This is what runs today: it bills against the Claude Code subscription instead of
an API key. `agent_runtime/deep_agents.py` is the same interface over DeepAgents and
takes over when there is API budget.

Six traps this file exists to avoid. The first four were paid for in the
`wiki-convert-loop` spike that preceded this repo; traps 5 and 6 are below and
were found on 2026-07-30 by the per-turn detail this file now records.

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

And one the `json` output does not provide: **no tool-call list and no per-turn
breakdown** (`num_turns` and `total_cost_usd` are there; tool uses are not).

`--output-format stream-json --verbose` does provide both, and that is what this
runs now. Two things follow:

  * The last `result` event has the **same shape** as the old `json` payload, so
    every total this file already reported keeps its meaning. Past measurements
    stay comparable.
  * The per-turn detail goes into `RunResult.detail` — see
    `agent_runtime/stream_json.py` for the event shapes and the traps.

The MCP server still counts tool calls (`wiki_mcp/telemetry.py`) and that count
is kept as the authority. The stream count is a cross-check from the other side:
a tool the CLI issued but the server never saw means the call failed before
reaching us, and only the pair can show that.

Two more traps, both found by that cross-check (2026-07-30):

5. **`uv run --project` is too slow to spawn.** It re-resolves the project on
   every MCP start, and the CLI's client gave up before the handshake finished:
   `init` reported `{"name":"wiki","status":"pending"}`, the tool list stayed
   empty, and the agent spent 19 turns on `Bash`/`Edit` before the timeout. It
   still reported no error of its own. `sys.executable -m wiki_mcp.local_server`
   is the same server without the resolution step, and it connects. No `env`
   override is needed — the interpreter running this file already has the deps,
   which is exactly the property `env` would destroy (see `telemetry.py`).
6. **The spawned CLI inherits the operator's `~/.claude` settings.** With deferred
   tool loading on, the MCP tools sit behind a search step: one run burned six
   `ToolSearch` calls and answered without touching the wiki. `--setting-sources`
   with an empty value drops user/project/local settings. Auth is unaffected —
   that is a different mechanism from `--bare` (trap 1), so subscription billing
   holds.

Both matter beyond convenience: without them a run looks like a success that did
nothing, and measurements silently depend on whose machine they ran on.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from wiki_mcp.telemetry import read_counts

from .base import RunResult
from .stream_json import parse_stream

SERVER_MODULE = "wiki_mcp.local_server"

# NFR-PERF-002 caps one document at 10 minutes. An ingest is many tool calls in
# one turn, so it gets the whole budget plus room for the lint retry loop.
CALL_TIMEOUT_SECONDS = 900

# `--effort` 가 받는 값. CLI `--help` 기준 (2026-07-29).
EFFORT_LEVELS = ("low", "medium", "high", "xhigh", "max")


class CliError(RuntimeError):
    pass


class ClaudeCodeRuntime:
    """Drives one ingest through `claude -p` with the wiki reachable over MCP."""

    name = "claude-code"

    def __init__(self, model: str = "sonnet", effort: str | None = None,
                 query_log: str | Path | None = None):
        """`effort` 는 지정하지 않으면 **전달하지 않는다** — CLI 기본값이 그대로 쓰인다.

        기본값을 여기서 정하지 않는 이유는 측정 기록이다. 지금까지 모든 측정이 CLI
        기본값으로 돌았고 `manifest.json` 에 그 값이 남지 않았다 (설계 문서 D8). 여기서
        임의로 정하면 과거 측정과의 비교가 조용히 깨진다 — 정할 근거는 Task 6 의 대조
        측정에서 나온다.

        `query_log` 는 에이전트 검색어를 남길 경로다. **기본은 끈다** — 질의에 사내 내용이
        실린다 (`wiki_mcp/telemetry.py`). 측정 하네스만 켠다. 임시 디렉터리 밖을 줘야
        실행이 끝난 뒤에도 남는다.
        """
        if effort is not None and effort not in EFFORT_LEVELS:
            raise ValueError(
                f"--effort 는 {', '.join(EFFORT_LEVELS)} 중 하나여야 한다: {effort!r}")
        self.model = model
        self.effort = effort
        self.query_log = Path(query_log) if query_log else None

    def _mcp_config(self, root: Path, scope_key: str, job_id: str, tool_log: Path) -> str:
        """Passed as a JSON string, not a file: the workspace differs per run and
        a shared config file would race between concurrent runs."""
        args = [
            "-m", SERVER_MODULE,
            "--root", str(root), "--scope", scope_key, "--job-id", job_id,
            # The stream carries tool_use blocks, but the server count stays the
            # authority: it counts what actually arrived. Passed as an argument and
            # not in `env`, which would wipe PATH — see wiki_mcp/telemetry.py.
            "--tool-log", str(tool_log),
        ]
        if self.query_log:
            args += ["--query-log", str(self.query_log)]
        # `sys.executable`, not `uv run --project`. 트랩 5 참고.
        return json.dumps({"mcpServers": {"wiki": {"command": sys.executable,
                                                   "args": args}}})

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
                # `--verbose` 는 선택이 아니다 — CLI 가 `-p` + `stream-json` 에 그것을
                # 요구한다. 빼면 스트림이 나오지 않는다.
                "--output-format", "stream-json", "--verbose",
                "--mcp-config", self._mcp_config(root, scope_key, job_id, tool_log),
                "--strict-mcp-config",
                # 트랩 6 — 운영자 `~/.claude` 를 물려받지 않는다. 빈 값이 「아무 출처도
                # 읽지 않는다」다. 인증은 이것과 무관하므로 구독 과금은 유지된다.
                "--setting-sources", "",
                "--permission-mode", "bypassPermissions",
            ]
            if self.effort:
                argv += ["--effort", self.effort]
            try:
                proc = subprocess.run(
                    argv, capture_output=True, text=True, timeout=limit,
                    cwd=tmp, env=os.environ.copy(),
                )
            except subprocess.TimeoutExpired as exc:
                # 끊긴 실행에도 어디까지 갔는지는 남긴다 — 제한 시간 초과가 D1·D8 판단의
                # 핵심 사례이고, 그때 내역이 없으면 왜 오래 걸렸는지 알 수 없다.
                partial = parse_stream(_decode(exc.stdout))
                return RunResult(
                    text="", tool_calls=read_counts(tool_log),
                    elapsed_seconds=round(time.monotonic() - started, 1),
                    detail=partial.as_dict(),
                    error=f"{limit}초 안에 끝나지 않았다",
                )

            # Read before the temp directory goes away.
            tool_calls = read_counts(tool_log)

        summary = parse_stream(proc.stdout)
        payload = summary.result
        elapsed = round(time.monotonic() - started, 1)
        detail = summary.as_dict()

        if proc.returncode != 0 or payload.get("is_error"):
            reason = payload.get("result") or proc.stderr.strip() or proc.stdout[-400:]
            return RunResult(text="", tool_calls=tool_calls, elapsed_seconds=elapsed,
                             detail=detail,
                             error=f"claude 실패 (코드 {proc.returncode}): {str(reason)[:400]}")

        if not payload:
            # `result` 이벤트가 없다. 종료 코드가 0 이어도 실패다 — 완주한 실행은 항상
            # 그것을 낸다. 없는 것은 스트림이 잘렸거나 파싱되지 않았다는 뜻이다.
            #
            # 앞 판본은 이 경우를 **성공으로 냈다.** `_payload` 가 JSONDecodeError 에
            # `{}` 를 돌려주고 `is_error` 가 falsy 라서, 아무것도 안 한 실행이 빈 본문의
            # 성공이 됐다. 세션이 그것을 「변경 없음」 200 으로 Spring 에 보내고
            # `document_results` 에 성공으로 남는다 (NFR-AI-003 상실).
            return RunResult(
                text="", tool_calls=tool_calls, elapsed_seconds=elapsed, detail=detail,
                error=("claude 출력에 result 이벤트가 없다 — 스트림이 잘렸거나 형식이 "
                       f"바뀌었다 (파싱 실패 {summary.unparsed_lines}줄)"))

        usage = payload.get("usage") or {}
        return RunResult(
            text=str(payload.get("result") or ""),
            tool_calls=tool_calls,
            input_tokens=_input_tokens(usage if isinstance(usage, dict) else {}),
            output_tokens=int((usage or {}).get("output_tokens", 0) or 0),
            turns=int(payload.get("num_turns") or 0),
            cost_usd=float(payload.get("total_cost_usd") or 0.0),
            elapsed_seconds=elapsed,
            detail=detail,
        )


def _decode(stdout: str | bytes | None) -> str:
    """`TimeoutExpired.stdout` 은 `text=True` 여도 bytes 로 오는 경우가 있다."""
    if stdout is None:
        return ""
    if isinstance(stdout, bytes):
        return stdout.decode("utf-8", errors="replace")
    return stdout


def _input_tokens(usage: dict) -> int:
    """Everything the model read, cached or not. See trap 4 in the module docstring."""
    return sum(int(usage.get(k, 0) or 0) for k in (
        "input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens",
    ))
