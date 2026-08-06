"""동일 도구 호출 반복 가드 (S15P11B106-311).

2026-08-07 삭제 데드락에서 모델이 같은 페이지 전문 `read` 를 53연속 반복(텍스트 0자)하다
턴 상한에서 죽었다 — 동일 호출이 동일 본문을 컨텍스트에 계속 쌓는 자기강화 루프다.
가드는 그 재료를 끊는다: **같은 호출이 같은 결과를 받으며 연속되면 3회째부터 결과 대신
경고를 돌려준다.** 결과가 달라지면(수정 후 재읽기) 정당한 재호출이므로 리셋한다.

`search` 의 결과집합 감지(S15P11B106-264)와 달리 도구를 가리지 않는다 — 래핑 지점은
`telemetry.count_tool_calls` 와 같은 디스패처(`ToolManager.call_tool`)다. 개별 도구
함수를 감싸면 `from __future__ import annotations` 때문에 인자 모델이 깨져 서버가
도구 0개로 뜬다(그 사고 기록은 telemetry docstring).
"""

from mcp.server.fastmcp import FastMCP

from wiki_mcp.repeat_guard import REPEAT_THRESHOLD, guard_repeated_calls, reset_repeat_memory
from wiki_mcp.tools import register
from wiki_mcp.vaultfs import LocalVaultFS

from ..conftest import JOB_ID, SCOPE

EXPECTED_TOOLS = {
    "append", "create", "delete", "edit", "guide", "lint", "list_scopes",
    "merge", "read", "search",
}


def _server() -> FastMCP:
    reset_repeat_memory()
    mcp = FastMCP(name="test")
    register(mcp, lambda ctx: SCOPE, lambda key: LocalVaultFS(key, JOB_ID))
    guard_repeated_calls(mcp)
    return mcp


def _text(result) -> str:
    blocks = result[0] if isinstance(result, tuple) else result
    if isinstance(blocks, str):
        return blocks
    return "".join(getattr(block, "text", "") for block in blocks)


async def test_가드를_켜도_모든_도구가_등록된다(vault):
    """텔레메트리 사고와 같은 회귀 — 래핑이 인자 모델을 깨면 서버가 도구 0개로 뜬다."""
    assert {t.name for t in await _server().list_tools()} == EXPECTED_TOOLS


async def test_동일_호출_동일_결과는_3회째부터_경고를_받는다(vault):
    mcp = _server()

    first = _text(await mcp.call_tool("guide", {}))
    second = _text(await mcp.call_tool("guide", {}))
    third = _text(await mcp.call_tool("guide", {}))

    assert "위키" in first
    assert second == first, "2회째까지는 원 결과 그대로 — 한 번 더 확인은 정상이다"
    assert third != first
    assert "같은 호출을 같은 결과로" in third
    # 경고는 다음 행동을 지시해야 한다 — 상황 설명만으로는 루프를 못 끊는다.
    assert "다음 행동" in third


async def test_사이에_다른_호출이_끼면_리셋된다(vault):
    mcp = _server()

    await mcp.call_tool("guide", {})
    await mcp.call_tool("guide", {})
    await mcp.call_tool("list_scopes", {})
    third = _text(await mcp.call_tool("guide", {}))

    assert "같은 호출을 같은 결과로" not in third


async def test_결과가_달라지면_리셋된다_수정_후_재읽기(vault):
    """어제 사고의 반대편 — 정당한 재호출을 가드가 막으면 안 된다."""
    mcp = _server()
    created = _text(await mcp.call_tool("create", {
        "scope": SCOPE, "title": "가드 테스트",
        "content": "가드 검증용 페이지다.\n\n둘째 문단은 처음 상태다.",
        "tags": ["테스트"], "category": "테스트",
    }))
    # create 가 발급한 주소를 그대로 쓴다 (pageKey 는 서버가 정한다).
    import re
    path = re.search(r"pages/[0-9a-f]+\.md", created).group(0)
    read_args = {"scope": SCOPE, "path": path}

    await mcp.call_tool("read", read_args)
    await mcp.call_tool("read", read_args)
    # 내용을 바꾸면 같은 인자의 read 라도 결과가 달라진다 → 리셋.
    await mcp.call_tool("edit", {
        "scope": SCOPE, "path": path,
        "old_text": "둘째 문단은 처음 상태다.", "new_text": "둘째 문단을 고쳤다.",
    })
    after_edit = _text(await mcp.call_tool("read", read_args))

    assert "둘째 문단을 고쳤다." in after_edit
    assert "같은 호출을 같은 결과로" not in after_edit


async def test_경고가_반복_횟수를_센다(vault):
    """경고 문구가 매번 조금씩 달라야(N번째) 경고 자체의 반복 강화도 피한다."""
    mcp = _server()
    for _ in range(REPEAT_THRESHOLD - 1):
        await mcp.call_tool("guide", {})

    third = _text(await mcp.call_tool("guide", {}))
    fourth = _text(await mcp.call_tool("guide", {}))

    assert "같은 호출을 같은 결과로" in third
    assert "같은 호출을 같은 결과로" in fourth
    assert third != fourth
