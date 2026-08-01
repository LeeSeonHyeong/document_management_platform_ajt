"""위키 편집 도구를 하위 프로세스 없이 런타임에 직접 붙인다 (S15P11B106-152).

구현은 `wiki_mcp/tools/*` 하나로 두고 전송만 둘이다 — 사람이 터미널에서 붙여 쓰는
stdio MCP 서버(`local_server.py`)와, AI 서버의 자동 실행이 쓰는 이 in-process 경로다
(설계 §3.7). 두 전송이 같은 `register()` 를 통과하는지를 여기서 고정한다.
"""

import tempfile
from pathlib import Path

import pytest

from agent_runtime.wiki_tools import wiki_agent_tools
from wiki_mcp.vaultfs import LocalVaultFS


@pytest.fixture
async def fs():
    root = Path(tempfile.mkdtemp(prefix="ajt-tools-"))
    await LocalVaultFS.open(root, "D1", "job-1")
    yield LocalVaultFS("D1", "job-1")
    await LocalVaultFS.close()


async def test_every_mcp_tool_comes_across(fs):
    """전송이 둘이어도 도구 목록은 하나여야 한다.

    빠진 도구가 있으면 자동 실행만 조용히 능력이 줄어든다 — MCP 경로로 돌린 실험과
    비교가 안 된다.
    """
    names = {tool.name for tool in wiki_agent_tools(fs, "D1")}

    assert names == {"guide", "list_scopes", "search", "read",
                     "create", "edit", "append", "merge", "delete", "lint"}


async def test_a_tool_returns_the_text_the_model_reads(fs):
    tools = {tool.name: tool for tool in wiki_agent_tools(fs, "D1")}

    text = await tools["list_scopes"].call()

    assert "D1" in text


async def test_the_schema_does_not_leak_the_mcp_context_argument(fs):
    """도구 함수의 첫 인자는 `ctx: Context` 다. 그것이 스키마에 남으면 모델이 채우려 든다."""
    tools = {tool.name: tool for tool in wiki_agent_tools(fs, "D1")}

    assert "ctx" not in tools["read"].input_schema.get("properties", {})
    assert "path" in tools["read"].input_schema["properties"]


async def test_the_scope_comes_from_the_caller_not_the_model(fs):
    """프로세스 경계가 주던 스코프 격리가 in-process 에서는 규율로 내려온다 (설계 §3.7).

    도구를 만드는 이 한 곳이 스코프를 묶는다 — 모델이 `scope` 인자에 무엇을 넣든
    저장소는 호출자가 준 것 하나다. 그 규율을 여기서 고정한다.
    """
    seen: list[str] = []
    tools = {t.name: t for t in wiki_agent_tools(fs, "D1", on_scope=seen.append)}
    await tools["list_scopes"].call()

    assert seen == ["D1"]


async def test_a_write_lands_in_the_work_layer(fs):
    """쓰기가 있는 경로다 — 도구가 실제로 저장소에 닿는지 확인한다."""
    tools = {tool.name: tool for tool in wiki_agent_tools(fs, "D1")}

    await tools["create"].call(scope="D1", title="재택근무 규정",
                               content="재택근무는 주 2일까지 가능하다.\n",
                               category="근무", tags=["근태"])

    changes = await fs.pending_changes(
        (await fs.resolve_scope("D1"))["id"])
    assert [c["type"] for c in changes] == ["create"]


async def test_a_tool_that_declares_no_structured_output_still_returns_text(fs):
    """`read` 는 `structured_output=False` 라 `call_tool` 이 블록 목록만 준다.

    다른 도구는 (블록, 구조화 결과) 튜플이다. 튜플로 단정하고 풀면 이 도구에서만
    `ValueError: not enough values to unpack` 이 나고, 에이전트는 `guide` 까지 성공한
    뒤 첫 `read` 에서 죽는다 — Spring 실연동에서 밟았다 (2026-08-01).
    """
    tools = {tool.name: tool for tool in wiki_agent_tools(fs, "D1")}

    text = await tools["read"].call(scope="D1", path="index.md")

    assert isinstance(text, str)
    assert text


async def test_every_tool_returns_a_string(fs):
    """반환 모양이 도구마다 다르다는 것을 한 번 더, 전수로 고정한다."""
    tools = wiki_agent_tools(fs, "D1")
    args = {"guide": {}, "list_scopes": {},
            "search": {"scope": "D1", "query": "연차"},
            "read": {"scope": "D1", "path": "index.md"}}

    for tool in tools:
        if tool.name not in args:
            continue
        assert isinstance(await tool.call(**args[tool.name]), str), tool.name
