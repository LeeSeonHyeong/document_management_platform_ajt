"""창구 HTTP 클라이언트 단위 테스트. 네트워크를 쓰지 않는다 — MockTransport 다."""

import httpx
import pytest

from wiki_mcp.vaultfs.query_client import (QueryBudgetExceeded, QueryNotFound,
                                           ScopeChangedError, WikiQueryClient)

BASE = "http://backend.test"


def _client(handler, *, scope_version: int = 47) -> WikiQueryClient:
    return WikiQueryClient(
        BASE, api_key="k", capability="cap-1", scope_key="D1-D2",
        scope_version=scope_version, request_id="req-42",
        transport=httpx.MockTransport(handler))


async def test_search_sends_capability_and_returns_items():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["capability"] = request.headers.get("X-Wiki-Capability")
        seen["api_key"] = request.headers.get("X-Internal-API-Key")
        seen["request_id"] = request.headers.get("X-Request-Id")
        return httpx.Response(200, json={
            "scopeVersion": 47,
            "items": [{"wikiId": "101", "title": "휴가 규정",
                       "breadcrumb": "휴가 규정 > 연차", "snippet": "연차는...",
                       "chunkIndex": 3, "contentHash": "h1"}],
        })

    client = _client(handler)
    items = await client.search("연차 이월", limit=5)
    await client.aclose()

    assert [item["wikiId"] for item in items] == ["101"]
    assert seen["capability"] == "cap-1"
    assert seen["api_key"] == "k"
    assert seen["request_id"] == "req-42"
    assert "scopeKey=D1-D2" in seen["url"]
    assert "limit=5" in seen["url"]


async def test_scope_version_mismatch_raises():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"scopeVersion": 48, "items": []})

    client = _client(handler, scope_version=47)
    with pytest.raises(ScopeChangedError) as caught:
        await client.search("연차")
    await client.aclose()

    assert caught.value.expected == 47
    assert caught.value.actual == 48


async def test_404_not_found_raises_query_not_found():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"code": "WIKI_NOT_FOUND",
                                         "message": "요청한 자료를 찾을 수 없습니다."})

    client = _client(handler)
    with pytest.raises(QueryNotFound):
        await client.page_content("999")
    await client.aclose()


async def test_404_capability_expired_raises_scope_changed():
    """같은 404 인데 code 가 다르면 처리가 다르다.

    허가가 죽은 것은 "그 페이지가 없다" 와 전혀 다른 상황이다. 상태만 보고
    뭉개면 안전하게 멈출 수 없다 (계약 1.6.0).
    """
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"code": "WIKI_CAPABILITY_EXPIRED",
                                         "message": "요청한 자료를 찾을 수 없습니다."})

    client = _client(handler)
    with pytest.raises(ScopeChangedError):
        await client.list_pages()
    await client.aclose()


async def test_list_pages_follows_cursor_to_the_end():
    """목록은 커서 페이지네이션이다. nextCursor 가 null 이 될 때까지 모은다."""
    seen_cursors = []

    def handler(request: httpx.Request) -> httpx.Response:
        cursor = request.url.params.get("cursor")
        seen_cursors.append(cursor)
        if not cursor:
            return httpx.Response(200, json={
                "scopeVersion": 47, "nextCursor": "c2",
                "items": [{"wikiId": "101"}]})
        return httpx.Response(200, json={
            "scopeVersion": 47, "nextCursor": None,
            "items": [{"wikiId": "108"}]})

    client = _client(handler)
    pages = await client.list_pages()
    await client.aclose()

    assert [page["wikiId"] for page in pages] == ["101", "108"]
    assert seen_cursors == [None, "c2"]


async def test_call_budget_is_enforced(monkeypatch):
    """예산을 선언만 하고 세지 않으면 아무 효과가 없다 (설계 9.5)."""
    from wiki_mcp.vaultfs import query_client as module

    monkeypatch.setattr(module, "QUERY_CALL_BUDGET_BASE", 2)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"scopeVersion": 47, "items": []})

    client = _client(handler)
    await client.search("연차")
    await client.search("이월")
    with pytest.raises(QueryBudgetExceeded):
        await client.search("승인")
    await client.aclose()

    assert client.calls == 2


async def test_budget_scales_with_catalog_size(monkeypatch):
    """예산이 카탈로그 크기에 연동된다.

    고정 예산은 링크 있는 위키에서 정상 `read` 한 번을 죽였다 — `read` 한 번이 링크
    연결 성분 전체를 당기므로 소비량이 위키 장수에 비례한다
    (`QUERY_CALL_BUDGET_BASE` 주석의 실측).
    """
    from wiki_mcp.vaultfs import query_client as module

    monkeypatch.setattr(module, "QUERY_CALL_BUDGET_BASE", 2)
    monkeypatch.setattr(module, "QUERY_CALLS_PER_PAGE", 2)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"scopeVersion": 47, "items": []})

    client = _client(handler)
    assert client.call_budget == 2
    client.note_catalog_size(10)
    assert client.call_budget == 22

    # 고정 예산이었다면 3번째에서 죽는다. 카탈로그를 알고 나면 통과한다.
    for _ in range(5):
        await client.search("연차")
    # 재하이드레이션이 예산을 낮추지 않는다 — 이미 쓴 호출이 남아 있다.
    client.note_catalog_size(0)
    assert client.call_budget == 22
    await client.aclose()

    assert client.calls == 5


async def test_page_content_is_cached_by_hash():
    """같은 본문을 두 번 받지 않는다. 설계 9.2 검증 항목."""
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return httpx.Response(200, json={
            "scopeVersion": 47, "wikiId": "101", "title": "휴가 규정",
            "wikiPath": "wiki/D1-D2/pages/a3f2c1d4.md",
            "contentMarkdown": "본문", "contentHash": "h1"})

    client = _client(handler)
    first = await client.page_content("101")
    second = await client.page_content("101")
    await client.aclose()

    assert first["contentMarkdown"] == second["contentMarkdown"] == "본문"
    assert len(calls) == 1
    assert client.body_fetches == 1


async def test_index_and_categories_and_relations_and_parsed():
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/index"):
            return httpx.Response(200, json={"scopeVersion": 47,
                                             "scopeKey": "D1-D2",
                                             "indexMarkdown": "# 목차"})
        if path.endswith("/categories"):
            return httpx.Response(200, json={"scopeVersion": 47, "items": [
                {"wikiCategoryId": "9", "name": "휴가", "wikiCount": 12}]})
        if path.endswith("/relations"):
            return httpx.Response(200, json={"scopeVersion": 47, "wikiId": "101",
                                             "wikiRefs": ["102"],
                                             "documentRefs": ["15"],
                                             "backlinks": ["108"]})
        if path.endswith("/parsed"):
            return httpx.Response(200, json={"documentId": "15",
                                             "originalFileName": "취업규칙.pdf",
                                             "parsedMarkdown": "# 취업 규칙"})
        return httpx.Response(200, json={"scopeVersion": 47, "items": []})

    client = _client(handler)
    assert await client.index_markdown() == "# 목차"
    assert (await client.categories())[0]["wikiCount"] == 12
    assert (await client.relations("101"))["backlinks"] == ["108"]
    assert (await client.parsed_document("15"))["originalFileName"] == "취업규칙.pdf"
    assert await client.list_pages() == []
    await client.aclose()
