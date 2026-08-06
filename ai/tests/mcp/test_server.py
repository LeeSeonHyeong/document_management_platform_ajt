"""The server must serve every tool, with telemetry on and off.

This exists because the failure it catches is silent. When tool registration
broke, the server still started, the agent saw no tools, and the run reported
success having written nothing — 15 turns and $0.59 for an empty wiki. Nothing in
the run output said what was wrong; the error was on the MCP server's stderr.
"""

import pytest
from mcp.server.fastmcp import FastMCP

from wiki_mcp.telemetry import count_tool_calls, read_counts
from ..conftest import JOB_ID, SCOPE
from wiki_mcp.tools import register
from wiki_mcp.vaultfs import LocalVaultFS

EXPECTED_TOOLS = {
    "append", "create", "delete", "edit", "guide", "lint", "list_scopes",
    "merge", "read", "search",
}


def _server() -> FastMCP:
    mcp = FastMCP(name="test")
    register(mcp, lambda ctx: SCOPE, lambda key: LocalVaultFS(key, JOB_ID))
    return mcp


async def test_all_tools_register():
    assert {t.name for t in await _server().list_tools()} == EXPECTED_TOOLS


async def test_tools_still_register_with_counting_enabled(tmp_path):
    """Wrapping the tool functions broke schema building; wrapping the dispatcher
    does not. Without this assertion that regression is invisible."""
    mcp = _server()
    count_tool_calls(mcp, str(tmp_path / "tools.log"))
    assert {t.name for t in await mcp.list_tools()} == EXPECTED_TOOLS


async def test_argument_schemas_survive_counting(tmp_path):
    """The break was in the argument model, not the tool list — so check a tool
    whose annotations use a module-local alias (`lint`/`CheckScope`)."""
    mcp = _server()
    count_tool_calls(mcp, str(tmp_path / "tools.log"))
    schemas = {t.name: t.inputSchema for t in await mcp.list_tools()}
    assert "check_scope" in schemas["lint"]["properties"]
    assert "absorbed" in schemas["merge"]["properties"]


async def test_calls_are_counted(vault, tmp_path):
    log = tmp_path / "tools.log"
    mcp = _server()
    count_tool_calls(mcp, str(log))

    await mcp.call_tool("list_scopes", {})
    await mcp.call_tool("lint", {"scope": SCOPE})
    await mcp.call_tool("lint", {"scope": SCOPE})

    assert read_counts(log) == {"list_scopes": 1, "lint": 2}


async def test_counting_off_by_default(vault):
    mcp = _server()
    count_tool_calls(mcp, None)
    assert await mcp.call_tool("list_scopes", {})


@pytest.mark.parametrize("tool", sorted(EXPECTED_TOOLS))
async def test_every_tool_is_callable(vault, tool):
    """A registered tool that raises on its own minimal arguments is as broken as
    one that never registered."""
    mcp = _server()
    minimal = {
        "guide": {},
        "list_scopes": {},
        "search": {"scope": SCOPE},
        "read": {"scope": SCOPE, "path": "index.md"},
        "lint": {"scope": SCOPE},
        "create": {"scope": SCOPE, "title": "테스트", "content": "본문.",
                   "tags": ["a", "b"], "category": "시험"},
        "edit": {"scope": SCOPE, "path": "index.md",
                 "old_text": "없는 문자열", "new_text": "x"},
        "append": {"scope": SCOPE, "path": "index.md", "content": "- 기록"},
        "merge": {"scope": SCOPE, "into": "pages/deadbeef.md",
                  "absorbed": ["pages/cafe.md"], "content": "본문"},
        "delete": {"scope": SCOPE, "path": "pages/deadbeef.md"},
    }[tool]
    assert await mcp.call_tool(tool, minimal)


async def test_guide_names_the_scope(vault):
    result = await _server().call_tool("guide", {})
    blocks = result[0] if isinstance(result, tuple) else result
    text = blocks[0].text
    assert "사내 위키 편집 에이전트" in text
    assert SCOPE in text
    # The addressing scheme has to be in the instructions, or the agent guesses.
    assert "pages/{키}.md" in text


async def test_repeat_search_is_flagged_through_the_registered_tool(tmp_path):
    """반복 감지가 **툴 경로**에서도 동작한다 (2026-08-06).

    이력이 프로세스 전역인 이유가 여기 있다 — 툴은 호출마다 `SearchHandler` 를 새로
    만들므로(`tools/search.py::register`) 핸들러에 상태를 두면 매번 초기화된다. 핸들러를
    직접 부르는 테스트만 있으면 그 사실이 드러나지 않는다.
    """
    from wiki_mcp.tools.search import reset_search_memory

    await LocalVaultFS.open(tmp_path, SCOPE, JOB_ID)
    try:
        fs = LocalVaultFS(SCOPE, JOB_ID)
        scope_id = (await fs.resolve_scope(SCOPE))["id"]
        await fs.write(scope_id, "pages/aaa.md", "출장비는 실비로 정산한다.",
                       title="출장비 정산", category="총무", tags=[])
        reset_search_memory()

        mcp = _server()
        first = await mcp.call_tool(
            "search", {"scope": SCOPE, "mode": "search", "query": "출장비 정산"})
        second = await mcp.call_tool(
            "search", {"scope": SCOPE, "mode": "search", "query": "출장비 실비 정산 기준"})
    finally:
        await LocalVaultFS.close()

    assert "같은 결과" not in str(first)
    assert "같은 결과" in str(second)
