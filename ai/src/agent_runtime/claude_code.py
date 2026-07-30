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

from .base import (DEFAULT_COMPLETE_TIMEOUT, FAST, QUALITY, CompletionResult,
                   RunResult, render_messages)
from .stream_json import parse_stream

SERVER_MODULE = "wiki_mcp.local_server"

# NFR-PERF-002 caps one document at 10 minutes. An ingest is many tool calls in
# one turn, so it gets the whole budget plus room for the lint retry loop.
CALL_TIMEOUT_SECONDS = 900

# `--effort` 가 받는 값. CLI `--help` 기준 (2026-07-29).
EFFORT_LEVELS = ("low", "medium", "high", "xhigh", "max")

# CLI 표기는 `anthropic:` 접두사가 없다. 별칭(`sonnet`)을 쓰지 않는 이유는 시점에 따라
# 다른 모델로 해석돼 두 측정의 비교를 조용히 깨뜨리기 때문이다.
CLI_TIER_MODELS = {
    FAST: "claude-haiku-4-5-20251001",
    QUALITY: "claude-sonnet-4-6",
}


class CliError(RuntimeError):
    pass


class ClaudeCodeRuntime:
    """Drives one ingest through `claude -p` with the wiki reachable over MCP."""

    name = "claude-code"

    # 이 런타임은 MCP 서버를 **별도 프로세스**로 띄운다 (`_mcp_config`). 창구 모드에서
    # 그것이 왜 문제인지와 기본값이 왜 `True` 인지는 `base.py::spawns_mcp_server` 에 있다.
    # 명시하지 않아도 기본이 `True` 지만, 이 사실이 이 클래스의 성질이므로 적어 둔다.
    spawns_mcp_server = True

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
                "claude", "-p", render_messages(messages, label_single=True,
                                trailing_newline=True),
                "--model", self._model_for(tier),
                "--output-format", "json",
            ]
            # 임시 cwd 는 이 저장소의 CLAUDE.md 가 프롬프트에 섞이지 않게 한다
            # (모듈 docstring 함정 1 — `--bare` 는 로그인 세션을 못 읽는다).
            proc = subprocess.run(argv, capture_output=True, text=True,
                                  timeout=limit, cwd=tmp, env=os.environ.copy())

        payload = _payload(proc.stdout)
        if proc.returncode != 0 or payload.get("is_error") or not payload:
            detail = (payload.get("result") or proc.stderr.strip()
                      or proc.stdout[:400] or "출력이 비었거나 JSON 이 아니다")
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


def _payload(stdout: str) -> dict:
    """`--output-format json` 한 덩어리를 읽는다. **`complete` 전용이다.**

    `run` 은 `stream-json` 이라 `stream_json.parse_stream` 이 읽는다. `complete` 는 MCP 가
    없어 응답이 한 번에 오므로 스트림으로 받을 이득이 없다 — 턴이 하나뿐이라 턴별 내역도
    없다.

    파싱 실패에 `{}` 를 돌려주므로 **호출부가 빈 dict 를 실패로 봐야 한다.** 그러지 않으면
    아무것도 안 한 호출이 빈 본문의 성공이 된다. `run` 에서 실제로 그랬다.
    """
    try:
        parsed = json.loads(stdout)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


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
