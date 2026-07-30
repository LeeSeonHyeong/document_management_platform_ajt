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

import asyncio
import logging
import tempfile
import time
import sys
from pathlib import Path

from wiki_mcp.telemetry import read_counts

from .base import DEFAULT_COMPLETE_TIMEOUT, FAST, QUALITY, CompletionResult, RunResult

logger = logging.getLogger("llmwiki.deepagents")

# tier → 모델. 게이트웨이가 가진 것에 묶인다 — GMS 실측(2026-07-29)으로 확인된 이름만
# 쓴다. 짧은 별칭(`claude-haiku-4-5`)은 400 이고 `claude-sonnet-5` 는 GMS 에 없다.
DEFAULT_TIER_MODELS = {
    FAST: "anthropic:claude-haiku-4-5-20251001",
    QUALITY: "anthropic:claude-sonnet-4-6",
}

# DeepAgents' built-ins. Every one of these is a way around the MCP server.
EXCLUDED_BUILTIN_TOOLS = frozenset({
    "write_todos", "ls", "read_file", "write_file", "edit_file",
    "glob", "grep", "execute", "task",
})

SERVER_MODULE = "wiki_mcp.local_server"

# NFR-PERF-002: a document over ten minutes must be failed. The CLI runtime gets
# this from a subprocess timeout; here it has to be enforced in the loop.
CALL_TIMEOUT_SECONDS = 600

# A turn cap is a runaway guard, not a quality lever. Measured ingests run 14–19
# turns, so this is well clear of normal work while still bounded.
MAX_TURNS = 60


def _server_config(root: Path, scope_key: str, job_id: str,
                   tool_log: Path | None = None) -> dict:
    """Same stdio server the CLI runtime launches, described for MCP adapters.

    `sys.executable`, not `uv run --project`. The CLI runtime hit this first
    (`claude_code.py` trap 5): `uv run` re-resolves the project on every spawn and
    the MCP client gave up before the handshake finished — the server showed as
    `pending`, no tools arrived, and the agent flailed with its own file tools
    while the run still reported no error. The same spawn is used here, so the
    same fix applies. **Unverified on this runtime** — DeepAgents is not installed
    in the measurement environment yet (plan Task 7).
    """
    args = [
        "-m", SERVER_MODULE,
        "--root", str(root), "--scope", scope_key, "--job-id", job_id,
    ]
    if tool_log:
        args += ["--tool-log", str(tool_log)]
    return {"wiki": {"transport": "stdio", "command": sys.executable, "args": args}}


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

    # `_server_config` 가 MCP 서버를 별도 프로세스로 띄운다 — 창구 모드에서 쓸 수 없는
    # 이유와 기본값은 `base.py::spawns_mcp_server` 에 있다.
    spawns_mcp_server = True

    def __init__(self, model: str = "anthropic:claude-opus-4-6", *,
                 fast_model: str | None = None, quality_model: str | None = None,
                 credentials: dict[str, tuple[str, str]] | None = None):
        """모델과 자격증명을 **인자로 받는다.** `os.environ` 을 읽지 않는다.

        읽던 시절에는 이 클래스를 테스트하려면 환경변수를 몽키패치해야 했고, 어떤
        설정에 의존하는지가 시그니처에 드러나지 않았다. 설정을 고르는 일은 조립
        지점(`wiki_api/serve.py`)의 몫이다.

        `credentials` 는 단일 `(api_key, base_url)` 쌍이 아니라 **프로바이더 이름 →
        (api_key, base_url) 표**다. 에이전트 모델(`model`)과 티어 모델(`fast_model`·
        `quality_model`)의 프로바이더가 다를 수 있기 때문이다 — 단일 쌍을 쓰면 한
        프로바이더의 키가 다른 프로바이더의 클라이언트로 간다 (벤더 교차 문제).
        `_credential_kwargs` 가 호출할 모델의 접두사로 그때그때 조회한다.
        """
        self.model = model
        self._tier_models = {FAST: fast_model, QUALITY: quality_model}
        self._credentials = credentials or {}

    def _model_for(self, tier: str) -> str:
        """tier → 모델 이름. 생성자 인자가 있으면 그것을 쓴다.

        모르는 tier 를 조용히 기본 모델로 떨어뜨리지 않는다 — 오타 하나가 측정을
        무의미하게 만드는 것보다 즉시 터지는 쪽이 낫다.
        """
        if tier not in DEFAULT_TIER_MODELS:
            raise ValueError(f"모르는 tier: {tier!r} (가능: {sorted(DEFAULT_TIER_MODELS)})")
        return self._tier_models.get(tier) or DEFAULT_TIER_MODELS[tier]

    def _credential_kwargs(self, model: str) -> dict:
        """**호출할 모델의** `provider:name` 접두사로 자격증명 표를 조회한다.

        `complete()` 는 티어 모델로, `_run`(에이전트 경로)은 `self.model` 로 부른다 —
        단일 자격증명을 두 경로에 공유하면 에이전트와 티어의 프로바이더가 갈릴 때 한쪽이
        틀린 벤더의 키를 받는다.

        값이 있을 때만 넣는다. 빈 문자열을 넘기면 SDK 가 「빈 키」로 읽어 자기 폴백조차
        막는다.
        """
        provider = (model or "").split(":", 1)[0]
        api_key, base_url = self._credentials.get(provider, ("", ""))
        kwargs = {}
        if api_key:
            kwargs["api_key"] = api_key
        if base_url:
            kwargs["base_url"] = base_url
        return kwargs

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
                                max_retries=0,
                                **self._credential_kwargs(requested))
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
        from deepagents import (
            GeneralPurposeSubagentProfile,
            HarnessProfile,
            create_deep_agent,
            register_harness_profile,
        )
        from langchain.chat_models import init_chat_model
        from langchain_mcp_adapters.client import MultiServerMCPClient

        client = MultiServerMCPClient(_server_config(root, scope_key, job_id, tool_log))
        tools = await client.get_tools()

        # `register_harness_profile` keys its lookup off the model *name*, so it
        # still gets the string — only `create_deep_agent` needs the instance to
        # carry credentials (a string there means `os.environ` again).
        register_harness_profile(
            self.model,
            HarnessProfile(
                excluded_tools=EXCLUDED_BUILTIN_TOOLS,
                general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False),
            ),
        )
        model = init_chat_model(self.model, **self._credential_kwargs(self.model))
        agent = create_deep_agent(
            model=model,
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
