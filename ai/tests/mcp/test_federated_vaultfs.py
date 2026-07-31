"""FederatedVaultFS 단위 테스트. HTTP 를 쓰지 않는다 — 가짜 클라이언트다."""

import pytest

from wiki_mcp.tools.references import backlinks_summary
from wiki_mcp.vaultfs import (INDEX_ADDRESS, FederatedVaultFS, LocalVaultFS,
                              VaultError)
from wiki_mcp.vaultfs.query_client import QueryNotFound, ScopeChangedError

PAGES = [
    {"wikiId": "101", "title": "휴가 규정", "summary": "연차와 반차 사용 기준",
     "wikiCategoryId": "9", "categoryName": "휴가 및 근태",
     "wikiPath": "wiki/D1-D2/pages/a3f2c1d4.md", "contentHash": "h1",
     "updatedAt": "2026-07-27T09:00:00Z"},
    {"wikiId": "108", "title": "보안 규정", "summary": "장비 반출 기준",
     "wikiCategoryId": "10", "categoryName": "보안",
     "wikiPath": "wiki/D1-D2/pages/b7e1f2a9.md", "contentHash": "h2",
     "updatedAt": "2026-07-27T09:00:00Z"},
]

BODIES = {
    "101": "---\ntitle: 휴가 규정\n---\n\n연차는 다음 해 3월까지 이월할 수 있다.\n",
    "108": "---\ntitle: 보안 규정\n---\n\n장비 반출은 사전 승인이 필요하다.\n",
}


class FakeClient:
    """WikiQueryClient 와 같은 표면만 흉내 낸다."""

    def __init__(self, *, scope_key="D1-D2"):
        self.scope_key = scope_key
        self.scope_version = 47
        self.body_fetches = 0
        self.searched: list[str] = []
        self.scope_change = None
        # 하이드레이션이 카탈로그 크기를 넘긴다 (`note_catalog_size`). 진짜 클라이언트는
        # 그 값으로 조회 예산을 늘린다 — 가짜는 받은 값만 기억한다.
        self.catalog_pages = 0

    def note_catalog_size(self, pages):
        self.catalog_pages = pages

    def note_scope_change(self, error):
        """진짜 클라이언트와 같은 표면. 세션이 실행 뒤 보는 중단 신호다."""
        self.scope_change = error
        return error

    async def list_pages(self):
        return [dict(page) for page in PAGES]

    async def index_markdown(self):
        return "# 목차\n\n- [휴가 규정](pages/a3f2c1d4.md) — 연차와 반차 사용 기준\n"

    async def categories(self):
        return [{"wikiCategoryId": "9", "name": "휴가 및 근태", "wikiCount": 1}]

    async def page_content(self, wiki_id):
        if wiki_id not in BODIES:
            raise QueryNotFound(wiki_id)
        self.body_fetches += 1
        page = next(p for p in PAGES if p["wikiId"] == wiki_id)
        return {"scopeVersion": 47, "wikiId": wiki_id, "title": page["title"],
                "wikiPath": page["wikiPath"], "contentMarkdown": BODIES[wiki_id],
                "contentHash": page["contentHash"]}

    async def search(self, query, limit=10):
        self.searched.append(query)
        return [{"wikiId": "101", "title": "휴가 규정",
                 "breadcrumb": "휴가 규정", "snippet": "연차는 다음 해...",
                 "chunkIndex": 0, "contentHash": "h1"}]

    async def relations(self, wiki_id):
        return {"scopeVersion": 47, "wikiId": wiki_id,
                "wikiRefs": [], "documentRefs": [], "backlinks": []}

    async def aclose(self):
        return None


@pytest.fixture
async def federated(tmp_path):
    client = FakeClient()
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1",
                                           client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    yield fs, scope_id, client
    await LocalVaultFS.close()


async def test_hydration_fills_catalog_without_bodies(federated):
    """목록 창구는 본문을 주지 않는다. 하이드레이션 시점에 본문을 당기지 않는다."""
    fs, scope_id, client = federated

    docs = await fs.list_documents(scope_id)
    addresses = {doc["address"] for doc in docs}

    assert "pages/a3f2c1d4.md" in addresses
    assert "pages/b7e1f2a9.md" in addresses
    assert INDEX_ADDRESS in addresses
    assert client.body_fetches == 0


async def test_wiki_path_becomes_the_address(federated):
    """wikiPath 접두사를 떼어 주소로 쓴다. pages/{wikiId}.md 가 아니다.

    본문의 위키 링크가 pageKey 기준이므로 그 이름을 써야 링크가 해석된다
    (설계 4절).
    """
    fs, scope_id, _ = federated

    assert await fs.get(scope_id, "pages/a3f2c1d4.md") is not None
    assert await fs.get(scope_id, "pages/101.md") is None


async def test_get_fetches_body_once(federated):
    """첫 접근에 본문을 당기고 두 번째는 당기지 않는다."""
    fs, scope_id, client = federated

    first = await fs.get(scope_id, "pages/a3f2c1d4.md")
    second = await fs.get(scope_id, "pages/a3f2c1d4.md")

    assert "이월할 수 있다" in first["content"]
    assert first["content"] == second["content"]
    assert client.body_fetches == 1


async def test_search_merges_live_and_work_with_origin(federated):
    """창구(라이브)와 내부 색인(작업층)을 모아 origin 으로 구분한다 (설계 §7.1)."""
    fs, scope_id, client = federated

    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address,
                   "---\ntitle: 재택근무 규정\n---\n\n재택근무는 주 2일까지 가능하다.\n",
                   title="재택근무 규정")

    rows = await fs.search_chunks(scope_id, "재택근무", limit=10)
    origins = {row["origin"] for row in rows}

    assert "work" in origins
    assert client.searched == ["재택근무"]


async def test_search_live_rows_carry_origin_live(federated):
    fs, scope_id, _ = federated

    rows = await fs.search_chunks(scope_id, "연차", limit=10)

    assert rows
    assert rows[0]["origin"] == "live"
    assert rows[0]["address"] == "pages/a3f2c1d4.md"


async def test_missing_body_closes_the_job(tmp_path):
    """목록에 있던 페이지의 본문이 없으면 빈 본문으로 진행하지 않는다.

    빈 페이지를 보면 에이전트가 "내용이 없다" 고 판단해 덮어쓴다.
    """
    class VanishingClient(FakeClient):
        async def page_content(self, wiki_id):
            raise QueryNotFound(wiki_id)

    client = VanishingClient()
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1",
                                           client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        with pytest.raises(ScopeChangedError):
            await fs.get(scope_id, "pages/a3f2c1d4.md")
    finally:
        await FederatedVaultFS.close()


async def test_body_without_catalog_fails_closed(tmp_path):
    """하이드레이션 없이 본문을 요구하면 조용히 빈 값을 주지 않는다.

    같은 스코프의 다른 세션이 표를 갈아치운 경우가 여기로 온다. fail-open 하면
    에이전트가 빈 페이지를 보고 라이브를 덮는다.
    """
    client = FakeClient()
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1",
                                           client=client)
    await FederatedVaultFS.close()

    # 레지스트리가 비었다. 색인을 다시 열어도 카탈로그는 없다.
    await LocalVaultFS.open(tmp_path, "D1-D2", "job-1")
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        with pytest.raises(VaultError):
            await fs.get(scope_id, "pages/a3f2c1d4.md")
    finally:
        await FederatedVaultFS.close()


async def test_lazy_body_keeps_category_and_leaves_nothing_stale(federated):
    """본문 지연 적재가 카탈로그의 카테고리를 지우지 않고 거짓 stale 도 남기지 않는다.

    본문 프론트매터에 `category:` 가 없어도 목록이 준 categoryName 이 살아 있어야
    browse 의 묶음과 FR-WIKI-014 판단 근거가 유지된다. 그리고 지연 적재는 컨텍스트를
    처음 채우는 것이므로 실제로 바뀐 페이지가 없다 — stale 이 붙으면 안 된다.
    """
    fs, scope_id, _ = federated

    page = await fs.get(scope_id, "pages/a3f2c1d4.md")

    assert page["category"] == "휴가 및 근태"
    stale = [doc["address"] for doc in await fs.list_documents(scope_id)
             if doc.get("stale_since")]
    assert stale == []


async def test_backlinks_append_remote_rows(tmp_path):
    """원격 역링크는 내부 그래프를 대체하지 않고 덧붙는다.

    지연 적재라 내부 그래프는 읽은 페이지만 안다. 제거·병합은 범위 전체를 봐야 한다.
    """
    class LinkedClient(FakeClient):
        async def relations(self, wiki_id):
            return {"scopeVersion": 47, "wikiId": wiki_id, "wikiRefs": [],
                    "documentRefs": [], "backlinks": ["108"]}

    client = LinkedClient()
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1",
                                           client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        # 본문을 하나 당기면 그때 참조 그래프가 만들어진다 — 목차가 이 페이지를
        # 링크한다는 사실이 내부 그래프에 들어온다.
        await fs.get(scope_id, "pages/b7e1f2a9.md")
        rows = await fs.get_backlinks(scope_id, "pages/a3f2c1d4.md")
        # 실제 소비자를 태운다 — read 가 페이지마다 부르는 경로다. 원격 행에 키가
        # 빠져 있으면 여기서 KeyError 가 난다.
        summary = await backlinks_summary(fs, scope_id, "pages/a3f2c1d4.md")
    finally:
        await FederatedVaultFS.close()

    addresses = {row["address"] for row in rows}
    # 내부 그래프(목차)가 남아 있고, 원격이 준 역링크가 덧붙는다.
    assert INDEX_ADDRESS in addresses
    assert "pages/b7e1f2a9.md" in addresses

    # **행을 실제로 소비해 본다.** 소비자들이 대괄호로 읽는 키가 빠지면 KeyError 가
    # 나고 그건 VaultError 가 아니라 툴의 except 에도 안 걸린다
    # (tools/references.py:142-143 · search.py:175-176 · write.py:331-332).
    for row in rows:
        assert row["reference_type"] in ("cites", "links_to")
        assert row["kind"] in ("page", "index")
        _ = row["title"], row["address"]
    assert [r for r in rows if r["kind"] in ("page", "index")]
    assert "pages/b7e1f2a9.md" in summary


async def test_work_results_do_not_crowd_out_live(federated):
    """작업층이 limit 을 넘게 나와도 라이브 몫이 남는다.

    작업층을 앞에 다 놓고 자르면 이번 작업에서 쓴 청크가 limit 을 채우는 순간
    라이브가 사라진다 — 기존 페이지를 못 찾아 전부 새로 만드는 실패로 이어진다.
    """
    fs, scope_id, _ = federated

    for n in range(6):
        address = await fs.allocate_page(scope_id)
        await fs.write(scope_id, address,
                       f"---\ntitle: 재택근무 규정 {n}\n---\n\n"
                       f"재택근무는 주 {n}일까지 가능하다.\n",
                       title=f"재택근무 규정 {n}")

    rows = await fs.search_chunks(scope_id, "재택근무", limit=4)

    assert len(rows) == 4
    assert [r for r in rows if r["origin"] == "live"]
    assert len([r for r in rows if r["origin"] == "work"]) == 3


async def test_search_tool_renders_two_sections(federated):
    """origin 이 있으면 「작업 중」과 「반영된 위키」로 나눠 보여준다."""
    from wiki_mcp.tools.search import SearchHandler

    fs, scope_id, _ = federated
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address,
                   "---\ntitle: 연차 초안\n---\n\n연차 이월 초안 문서다.\n",
                   title="연차 초안")

    # SearchHandler 는 scope dict 에서 "id" 를 읽는다 (`tools/search.py:47`).
    handler = SearchHandler(fs, {"id": scope_id, "scope_key": "D1-D2"})
    rendered = await handler.search("연차", pattern="*", tags=None, limit=10)

    assert "작업 중" in rendered
    assert "반영된 위키" in rendered
    assert rendered.index("작업 중") < rendered.index("반영된 위키")
    # 헤더와 건별 형식은 그대로여야 한다 — 섹션만 끼워 넣는 변경이다.
    assert rendered.startswith("**")
    assert "[보기](" in rendered


async def test_work_search_rows_are_capped(federated, monkeypatch):
    """상한을 넘는 작업층 결과는 잘린다. 잘린다는 사실 자체를 고정한다.

    상한을 실측값(20)으로 두면 이 테스트가 그 값에 묶여 실측을 고칠 때 함께 깨진다.
    잘라야 할 것은 "잘린다" 는 성질이므로 상한을 낮춰 성질만 고정한다.
    """
    from wiki_mcp.vaultfs import federated as module

    monkeypatch.setattr(module, "MAX_WORK_SEARCH_ROWS", 2)
    fs, scope_id, _ = federated

    for number in range(5):
        address = await fs.allocate_page(scope_id)
        await fs.write(scope_id, address,
                       f"---\ntitle: 초안 {number}\n---\n\n재택근무 초안 {number}.\n",
                       title=f"초안 {number}")

    rows = await fs.search_chunks(scope_id, "재택근무", limit=50)
    work_rows = [row for row in rows if row["origin"] == "work"]

    assert len(work_rows) <= 2
    # 상한이 없으면 5행이 그대로 나온다 — 실제로 잘렸는지 확인한다.
    assert work_rows
