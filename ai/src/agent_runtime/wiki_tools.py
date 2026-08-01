"""위키 편집 도구를 런타임에 직접 붙인다 — 하위 프로세스가 없다 (S15P11B106-152).

**구현과 전송을 나눈다** (설계 §3.7). 도구 구현은 `wiki_mcp/tools/*` 하나이고 전송만
둘이다.

| 소비자 | 전송 |
| --- | --- |
| 사람 (터미널 Claude Code) | stdio MCP 서버 (`wiki_mcp/local_server.py`) |
| AI 서버의 자동 실행 | 이 파일 (in-process) |

자동 실행이 하위 프로세스를 그만두는 이유는 두 가지가 그 경계를 못 넘어서다.

  * **열람 허가값.** 창구 모드(`FederatedVaultFS`)는 `X-Wiki-Capability` 가 있어야
    본문을 당긴다. CLI 인자는 `/proc/<pid>/cmdline` 이 world-readable 이라 못 쓰고,
    계약이 마스킹을 요구하는 값이다
  * **중단 신호.** 범위 변경(`ScopeChangedError`)을 프로세스 밖으로 넘길 길이 없다

**잃는 것**: 프로세스 경계가 주던 「프로세스 1개 = 스코프 1개 = 작업 1개」 격리가
규율로 내려온다. 도구를 그 스코프·그 작업에 묶는 일을 **이 함수 한 곳에서만** 하고,
`tests/agent_runtime/test_wiki_tools.py` 가 그것을 고정한다. 사람용 MCP 는 프로세스
격리를 그대로 유지한다.

이 모듈은 `wiki_mcp` 를 임포트한다 — 의존 방향(`wiki_api → agent_runtime → wiki_mcp`)
그대로다. 반대로 `wiki_mcp` 가 `AgentTool` 을 알면 그 방향이 깨진다.
"""

from __future__ import annotations

from collections.abc import Callable

from .tools import AgentTool


def wiki_agent_tools(fs, scope_key: str, *,
                     on_scope: Callable[[str], None] | None = None) -> list[AgentTool]:
    """`fs` 하나에 묶인 위키 편집 도구 전부.

    `on_scope` 는 도구가 저장소를 고를 때마다 그 스코프를 받는다 — 테스트가 격리 규율을
    확인하는 통로다. 운영에서는 안 넘긴다.
    """
    from mcp.server.fastmcp import FastMCP

    from wiki_mcp.tools import register

    mcp = FastMCP(name="LLM Wiki (in-process)")

    # **스코프와 저장소는 호출자가 정한다.** MCP 서버판(`local_server.py`)이 프로세스
    # 인자로 하던 일을 여기서 클로저로 한다 — 모델이 다른 스코프를 달라고 할 수단이 없다.
    def get_scope_key(ctx) -> str:      # noqa: ARG001 - 전송 표면이 요구하는 인자
        if on_scope is not None:
            on_scope(scope_key)
        return scope_key

    register(mcp, get_scope_key, lambda key: fs)   # noqa: ARG005 - key 는 위에서 고정됐다

    # `list_tools()` 는 코루틴인데 이 함수는 sync 다. 툴 목록은 등록 직후 고정이고 I/O 가
    # 없으므로 매니저에서 직접 읽는다 — 도구 조립 때문에 호출자를 async 로 만들지 않는다.
    return [_as_agent_tool(mcp, spec) for spec in mcp._tool_manager.list_tools()]


def _as_agent_tool(mcp, spec) -> AgentTool:
    async def call(**kwargs) -> str:
        # **`call_tool` 의 반환 모양이 두 가지다.** 보통은 (콘텐츠 블록, 구조화 결과)
        # 튜플인데 `structured_output=False` 인 도구(`read`)는 블록 목록만 준다.
        # 튜플로 단정하고 풀면 그 도구에서 `ValueError: not enough values to unpack`
        # 이 난다 — Spring 실연동에서 밟았다 (2026-08-01).
        result = await mcp.call_tool(spec.name, kwargs)
        if isinstance(result, tuple) and len(result) == 2:
            blocks, structured = result
            if isinstance(structured, dict) and "result" in structured:
                return str(structured["result"])
        else:
            blocks = result
        return "\n".join(str(getattr(b, "text", "")) for b in blocks)

    return AgentTool(name=spec.name, description=spec.description or "",
                     input_schema=spec.parameters, call=call)
