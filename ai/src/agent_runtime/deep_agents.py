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

# 제공자별 티어 기본값. **에이전트 모델의 제공자를 따라간다** — 하나만 지정한 배포가
# 첫 단발 호출에서 다른 벤더의 키를 찾다 죽는 것을 막는다 (2026-07-31 실측).
#
# 이름은 게이트웨이가 가진 것에 묶인다 — GMS 실측(2026-07-29)으로 확인된 이름만 쓴다.
# 짧은 별칭(`claude-haiku-4-5`)은 400 이고 `claude-sonnet-5` 는 GMS 에 없다.
DEFAULT_TIER_MODELS = {
    "anthropic": {
        FAST: "anthropic:claude-haiku-4-5-20251001",
        QUALITY: "anthropic:claude-sonnet-4-6",
    },
    "openai": {
        FAST: "openai:gpt-4o-mini",
        QUALITY: "openai:gpt-4o-mini",
    },
}
TIERS = (FAST, QUALITY)

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
                 credentials: dict[str, tuple[str, str]] | None = None,
                 chat_model=None):
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
        # 완성된 모델 객체를 그대로 쓰는 자리. **가짜 모델을 끼우는 지점이다** — 모델 판단만
        # 가짜고 루프·도구·응답 조립은 진짜로 돈다. 배포에서는 넘기지 않는다.
        self._chat_model_override = chat_model

    def _model_for(self, tier: str) -> str:
        """tier → 모델 이름. 생성자 인자가 있으면 그것을 쓴다.

        인자가 없으면 **에이전트 모델과 같은 제공자의** 기본값을 쓴다. 모르는 제공자면
        에이전트 모델을 그대로 쓴다 — 다른 벤더로 새는 것보다 낫다.

        모르는 tier 를 조용히 기본 모델로 떨어뜨리지 않는다 — 오타 하나가 측정을
        무의미하게 만드는 것보다 즉시 터지는 쪽이 낫다.
        """
        if tier not in TIERS:
            raise ValueError(f"모르는 tier: {tier!r} (가능: {sorted(TIERS)})")
        chosen = self._tier_models.get(tier)
        if chosen:
            return chosen
        provider = (self.model or "").split(":", 1)[0]
        return DEFAULT_TIER_MODELS.get(provider, {}).get(tier, self.model)

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

    def _chat_model(self, timeout: int):
        """에이전트 모델 객체. 생성자에 `chat_model=` 이 오면 그것을 그대로 쓴다.

        자격증명은 `_credential_kwargs` 로 **호출할 모델의 제공자** 몫만 넣는다 —
        `complete()` 와 같은 이유다 (벤더 교차 방지).
        """
        if self._chat_model_override is not None:
            return self._chat_model_override
        from langchain.chat_models import init_chat_model

        return init_chat_model(self.model, timeout=timeout, max_retries=0,
                               **self._credential_kwargs(self.model))

    async def arun_with_tools(self, guide: str, question: str, *, tools: list,
                              max_turns: int, timeout: int,
                              response_format=None) -> RunResult:
        """MCP 없이 도구를 직접 붙여 돈다. 챗봇 전용이다. **호출자의 루프에서 돈다.**

        `run` 과 달리 임시 디렉터리도 MCP 클라이언트도 만들지 않는다 — 쓰기가 없다.

        **비동기가 정본이고 `run_with_tools` 가 껍데기다.** 뒤집으면(요청마다 `asyncio.run`)
        모델 클라이언트의 연결 풀이 **닫힌 루프에 묶여** 두 번째 요청부터 전부
        `RuntimeError: Event loop is closed` 로 죽는다 — 그것이 `APIConnectionError` 로 감싸
        여 올라와 네트워크 장애처럼 보인다. Spring 실연동에서 확인했다 (2026-07-31): 서버
        기동 후 첫 질문만 답하고 그다음은 전부 실패했다.
        """
        started = time.monotonic()
        text, calls, structured, hit_limit = await self._run_with_tools(
            guide, question, tools, max_turns, timeout, response_format)
        return RunResult(text=text, tool_calls=calls, turns=sum(calls.values()),
                         structured=structured,
                         error="turn_limit" if hit_limit else None,
                         elapsed_seconds=round(time.monotonic() - started, 2))

    def run_with_tools(self, guide: str, question: str, *, tools: list,
                       max_turns: int, timeout: int,
                       response_format=None) -> RunResult:
        """`arun_with_tools` 의 sync 껍데기. **돌고 있는 루프 안에서는 부르지 않는다** —
        스크립트와 테스트용이다. 서버는 `arun_with_tools` 를 그대로 await 한다.
        """
        return asyncio.run(self.arun_with_tools(
            guide, question, tools=tools, max_turns=max_turns, timeout=timeout,
            response_format=response_format))

    async def _run_with_tools(self, guide: str, question: str, tools: list,
                              max_turns: int, timeout: int, response_format):
        from deepagents import create_deep_agent
        from langchain.agents.middleware import ModelCallLimitMiddleware
        from langchain.agents.middleware.model_call_limit import (
            ModelCallLimitExceededError,
        )

        # 턴 상한을 미들웨어로 건다. `recursion_limit` 만 쓰면 GraphRecursionError 가 나고
        # 그것이 일반 예외로 잡혀 「모델 호출 실패」로 뭉개진다 — 상한 도달과 고장을
        # 구분할 수 없게 된다.
        #
        # `exit_behavior="error"` 다. `"end"` 는 영어 안내문(`Model call limits
        # exceeded: ...`)을 마지막 AI 메시지로 끼워 정상 종료처럼 끝내므로, 상한 도달을
        # 알아내려면 그 문장을 문자열로 대조해야 한다 — 그 문장이 바뀌면 조용히 상한이
        # 「빈 답변」이나 심지어 사용자에게 나가는 영어 답변으로 새어나간다.
        limit = ModelCallLimitMiddleware(thread_limit=max_turns, exit_behavior="error")
        # **도구 호출은 우리가 센다.** 상한 예외로 끝나면 그래프 상태를 못 받아 「몇 번
        # 읽었나」가 사라지고, 호출자는 상한 도달과 「모델 호출이 매번 실패했다」를 구분할 수
        # 없게 된다 (그 둘은 이름이 달라야 한다).
        counted: dict[str, int] = {}
        agent = create_deep_agent(
            model=self._chat_model(timeout),
            tools=langchain_tools(tools, counted),
            subagents=[],
            system_prompt=guide,          # 지침은 여기에만. 질문과 겹쳐 싣지 않는다.
            middleware=[limit],
            response_format=response_format,
        )
        hit_limit = False
        try:
            result = await asyncio.wait_for(
                agent.ainvoke({"messages": [{"role": "user", "content": question}]}),
                timeout=timeout,
            )
        except ModelCallLimitExceededError:
            # 상한 도달은 고장이 아니라 상한 도달이다. 호출자가 자기 오류 이름으로 바꾼다.
            # 그때까지 실제로 읽은 횟수를 함께 낸다 — 도구를 한 번도 못 불렀다면 그것은
            # 상한이 아니라 모델 호출 실패이고, 이름이 달라야 한다.
            return "", dict(counted), None, True
        messages = result.get("messages") or []
        # 구조화 응답은 **도구 호출로 온다** (LangChain `ToolStrategy`): 모델이 스키마
        # 이름의 도구를 부르고 그 인자가 응답이 된다. 그것은 조회가 아니므로 세지 않는다 —
        # 세면 위키도 일정도 읽지 않은 실행이 「조회했다」로 통과한다 (2026-07-31 확인).
        # `counted` 는 우리가 감싼 도구만 세므로 그 문제가 애초에 없다.
        calls = dict(counted)
        text = _text_of(messages[-1].content) if messages else ""
        structured = result.get("structured_response")
        # pydantic 인스턴스로 온다. 계약 조립 쪽은 dict 를 다루므로 여기서 맞춘다 —
        # `agent_runtime` 이 `wiki_api` 의 모델을 알 필요가 없게 하는 경계이기도 하다.
        if hasattr(structured, "model_dump"):
            structured = structured.model_dump()
        return text, calls, structured, hit_limit

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


def langchain_tools(tools: list, counter: dict | None = None) -> list:
    """`AgentTool` 목록을 LangChain 도구로 감싼다.

    LangChain 임포트를 이 파일 안에 둔다 — 배포 의존성을 깔지 않은 설치에서도
    `agent_runtime.tools` 는 임포트돼야 한다.

    `counter` 를 주면 **호출될 때마다 이름별로 센다.** 그래프 상태에서 세지 않는 이유는
    상한 예외로 끝난 실행에서는 그 상태를 받지 못하기 때문이다.
    """
    from langchain_core.tools import StructuredTool

    def wrap(tool):
        if counter is None:
            return tool.call

        def called(**kwargs):
            counter[tool.name] = counter.get(tool.name, 0) + 1
            return tool.call(**kwargs)

        return called

    return [StructuredTool.from_function(
        func=wrap(tool), name=tool.name, description=tool.description,
        args_schema=tool.input_schema) for tool in tools]


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
