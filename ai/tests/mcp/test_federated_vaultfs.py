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

A101 = "pages/a3f2c1d4.md"   # PAGES[0] 의 wikiPath 에서 나오는 주소
A108 = "pages/b7e1f2a9.md"   # PAGES[1]


class FakeClient:
    """WikiQueryClient 와 같은 표면만 흉내 낸다."""

    def __init__(self, *, scope_key="D1-D2", bodies=None, pages=None,
                 relations_items=None):
        self.scope_key = scope_key
        # 페이지 목록도 갈아끼울 수 있게 한다 — 「빈 범위」같은 경우를 모듈 전역
        # `PAGES` 로는 시험할 수 없다.
        self.pages = [dict(p) for p in (PAGES if pages is None else pages)]
        # 본문을 갈아끼울 수 있게 한다 — 링크가 있는 본문으로 fan-out 을 재현한다.
        self.bodies = dict(BODIES if bodies is None else bodies)
        # 범위 관계도 갈아끼울 수 있게 한다. 뒤집기 대상 표 자체이므로 시나리오마다
        # 달라야 한다.
        self.relations_items = [] if relations_items is None else relations_items
        self.scope_version = 47
        self.body_fetches = 0
        self.searched: list[str] = []
        self.scope_change = None
        self.scope_relations_calls = 0
        self.relations_calls = 0
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
        return [dict(page) for page in self.pages]

    async def index_markdown(self):
        return "# 목차\n\n- [휴가 규정](pages/a3f2c1d4.md) — 연차와 반차 사용 기준\n"

    async def categories(self):
        return [{"wikiCategoryId": "9", "name": "휴가 및 근태", "wikiCount": 1}]

    async def scope_relations(self):
        self.scope_relations_calls += 1
        return self.relations_items

    async def page_content(self, wiki_id):
        if wiki_id not in self.bodies:
            raise QueryNotFound(wiki_id)
        self.body_fetches += 1
        page = next(p for p in self.pages if p["wikiId"] == wiki_id)
        return {"scopeVersion": 47, "wikiId": wiki_id, "title": page["title"],
                "wikiPath": page["wikiPath"], "contentMarkdown": self.bodies[wiki_id],
                "contentHash": page["contentHash"]}

    async def search(self, query, limit=10):
        self.searched.append(query)
        return [{"wikiId": "101", "title": "휴가 규정",
                 "breadcrumb": "휴가 규정", "snippet": "연차는 다음 해...",
                 "chunkIndex": 0, "contentHash": "h1"}]

    async def relations(self, wiki_id):
        self.relations_calls += 1
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
    """목록 조회 API 는 본문을 주지 않는다. 하이드레이션 시점에 본문을 당기지 않는다."""
    fs, scope_id, client = federated

    docs = await fs.list_documents(scope_id)
    addresses = {doc["address"] for doc in docs}

    assert "pages/a3f2c1d4.md" in addresses
    assert "pages/b7e1f2a9.md" in addresses
    assert client.body_fetches == 0


async def test_hydration_does_not_bring_the_index_into_the_vault(federated):
    """목차는 Spring 이 DB 로 그린다 (S15P11B106-280). 올릴 이유가 없고, 올리면
    에이전트가 그것을 고치려 들다 dangling-link 로 잡을 죽인다."""
    fs, scope_id, _ = federated
    assert await fs.get(scope_id, INDEX_ADDRESS) is None


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
    """조회 API(라이브)와 내부 색인(작업층)을 모아 origin 으로 구분한다 (설계 §7.1)."""
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
    """카탈로그의 역링크는 내부 그래프를 대체하지 않고 덧붙는다.

    지연 적재라 내부 그래프는 읽은 페이지만 안다. 제거·병합은 범위 전체를 봐야 한다.
    페이지별 `relations` 대신 하이드레이션이 뒤집어 둔 `wiki_backlinks` 를 쓴다
    (S15P11B106-175) — `relations_items` 로 범위 관계를 미리 넣어 둔다.
    """
    client = FakeClient(relations_items=[
        {"wikiId": "108", "wikiRefs": ["101"], "documentRefs": []}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1",
                                           client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        # 본문을 당겨 둔다 — 역링크 조회가 body 지연 적재와 부딴이 없는지 함께 본다.
        await fs.get(scope_id, "pages/b7e1f2a9.md")
        rows = await fs.get_backlinks(scope_id, "pages/a3f2c1d4.md")
        # 실제 소비자를 태운다 — read 가 페이지마다 부르는 경로다. 원격 행에 키가
        # 빠져 있으면 여기서 KeyError 가 난다.
        summary = await backlinks_summary(fs, scope_id, "pages/a3f2c1d4.md")
    finally:
        await FederatedVaultFS.close()

    # 페이지별 relations 는 이제 부르지 않는다 (S15P11B106-175).
    assert client.relations_calls == 0
    addresses = {row["address"] for row in rows}
    # 원격이 준 역링크가 덧붙는다. 목차는 이제 카탈로그에 없으므로(S15P11B106-280) 여기 안 낀다.
    assert "pages/b7e1f2a9.md" in addresses

    # **행을 실제로 소비해 본다.** 소비자들이 대괄호로 읽는 키가 빠지면 KeyError 가
    # 나고 그건 VaultError 가 아니라 툴의 except 에도 안 걸린다
    # (tools/references.py:142-143 · search.py:175-176 · write.py:331-332).
    for row in rows:
        assert row["reference_type"] in ("cites", "links_to")
        assert row["kind"] == "page"
        _ = row["title"], row["address"]
    assert [r for r in rows if r["kind"] == "page"]
    assert "pages/b7e1f2a9.md" in summary


async def test_backlinks_come_from_catalog_without_extra_calls(tmp_path):
    """역링크는 카탈로그에서 읽는다 — 페이지별 relations 를 부르지 않는다."""
    client = FakeClient(relations_items=[
        {"wikiId": "101", "wikiRefs": ["108"], "documentRefs": []},
        {"wikiId": "108", "wikiRefs": [], "documentRefs": []}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        rows = await fs.get_backlinks(scope_id, A108)
        assert client.relations_calls == 0           # 페이지별 조회 0회
        assert [r["address"] for r in rows] == [A101]
        row = rows[0]
        # 소비자가 대괄호로 읽는 키가 다 있어야 한다 (tools/references.py·search.py·write.py).
        assert row["title"] == "휴가 규정"
        assert row["kind"] == "page"
        assert row["reference_type"] == "links_to"
        assert row["origin"] == "live"
    finally:
        await FederatedVaultFS.close()


async def test_backlinks_do_not_pull_bodies(tmp_path):
    """역링크 목록을 그리려고 남의 본문을 당기지 않는다 (S15P11B106-151)."""
    client = FakeClient(relations_items=[
        {"wikiId": "101", "wikiRefs": ["108"], "documentRefs": []}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        before = client.body_fetches
        await fs.get_backlinks(scope_id, A108)
        assert client.body_fetches == before
    finally:
        await FederatedVaultFS.close()


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


# ---- 지연 적재 fan-out (S15P11B106-151) ------------------------------------

LINKED_BODIES = {
    # 101 이 108 을 링크한다. 108 의 본문은 이 테스트에서 필요 없다 —
    # 참조 그래프는 「그 주소가 이 공간에 있나」만 알면 된다.
    "101": "---\ntitle: 휴가 규정\n---\n\n자세한 것은 [보안 규정](b7e1f2a9.md) 참고.\n",
    "108": "---\ntitle: 보안 규정\n---\n\n장비 반출은 사전 승인이 필요하다.\n",
}


async def test_reading_one_page_does_not_pull_the_pages_it_links_to(tmp_path):
    """**본문 1장을 읽는 데 링크 대상의 본문까지 당기면 안 된다.**

    `build_edges` 가 링크마다 `fs.get` 을 부르는데, 조회 API 경로에서 그 `get` 이
    본문 조회를 태운다. 사슬로 이어진 위키 100장이면 `read` 한 번이 조회 100회가 된다
    (2026-07-31 실측). `build_edges` 는 반환값의 `address` 만 쓴다 — 본문이 필요 없다.
    """
    client = FakeClient(bodies=LINKED_BODIES)
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        await fs.get(scope_id, "pages/a3f2c1d4.md")

        # 읽은 그 한 장만 당긴다. 링크 대상(108)은 당기지 않는다.
        assert client.body_fetches == 1
        # 그런데도 간선은 만들어져야 한다 — 링크를 아예 못 알아봐서 0회인 것과 구분한다.
        forward = await fs.get_forward_references(scope_id, "pages/a3f2c1d4.md")
        assert [row["address"] for row in forward] == ["pages/b7e1f2a9.md"]
    finally:
        await LocalVaultFS.close()


async def test_a_link_cycle_does_not_recurse(tmp_path):
    """A→B, B→A 에서 `RecursionError` 가 나지 않는다.

    예전에는 `hydrated_bodies` 가드가 그 고리를 끊었다. 이제는 구조적으로 없어야 한다 —
    가드를 비워도 돌아야 확인이 된다 (설계 5.3).
    """
    cycle = {
        "101": "---\ntitle: 휴가 규정\n---\n\n[보안 규정](b7e1f2a9.md)\n",
        "108": "---\ntitle: 보안 규정\n---\n\n[휴가 규정](a3f2c1d4.md)\n",
    }
    client = FakeClient(bodies=cycle)
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        await fs.get(scope_id, "pages/a3f2c1d4.md")
        fs._catalog.hydrated_bodies.clear()      # 가드를 걷어내도 돌아야 한다
        await fs.get(scope_id, "pages/a3f2c1d4.md")

        assert client.body_fetches == 2          # 가드를 지웠으니 두 번 받는다
    finally:
        await LocalVaultFS.close()


# ---- 라이브 본문을 내부 색인에 넣지 않는다 (S15P11B106-154) -------------------


async def test_a_loaded_live_body_is_not_put_in_the_local_index(federated):
    """조회 API 경로의 라이브 검색은 조회 API 가 한다. 같은 본문을 내부 색인에도 넣으면
    층을 모르는 부모 `LIMIT` 이 그 행으로 채워져 작업층 초안이 밀려난다.
    """
    fs, scope_id, _ = federated

    await fs.get(scope_id, "pages/a3f2c1d4.md")     # 본문을 당긴다

    rows = await LocalVaultFS.search_chunks(fs, scope_id, "이월할", 10, None)
    assert rows == []


async def test_a_loaded_live_body_does_not_push_out_work_drafts(federated):
    """같은 것을 결과 쪽에서 본다 — 초안 5장을 쓰고 라이브를 당겨도 5행 그대로."""
    fs, scope_id, _ = federated

    for n in range(5):
        address = await fs.allocate_page(scope_id)
        await fs.write(scope_id, address,
                       f"---\ntitle: 연차 초안 {n}\n---\n\n연차 관련 초안 {n}.\n",
                       title=f"연차 초안 {n}")

    before = await LocalVaultFS.search_chunks(fs, scope_id, "연차", 5, None)
    await fs.get(scope_id, "pages/a3f2c1d4.md")
    after = await LocalVaultFS.search_chunks(fs, scope_id, "연차", 5, None)

    assert len([r for r in before if r["layer"] == "work"]) == 5
    assert len([r for r in after if r["layer"] == "work"]) == 5


async def test_staged_sources_are_still_indexed_locally(federated):
    """조회 API 는 원본문서를 검색해 주지 않는다 — 그쪽 색인까지 끄면 안 된다."""
    fs, scope_id, _ = federated

    await fs.stage_source(scope_id, "77", "삭제된 문서의 본문. 출장비 정산 기준.",
                          original_file_name="출장비.docx")

    rows = await LocalVaultFS.search_chunks(fs, scope_id, "출장비", 10, None)
    assert [r["address"] for r in rows] == ["sources/77/parsed/content.md"]


async def test_resolve_address_never_loads_a_body(tmp_path):
    """주소 해석은 존재 확인이다. 본문 적재를 유발하지 않는다."""
    client = FakeClient()
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        found = await fs.resolve_address(scope_id, "pages/a3f2c1d4.md")
        missing = await fs.resolve_address(scope_id, "pages/nope.md")

        assert found is not None and found["address"] == "pages/a3f2c1d4.md"
        assert missing is None
        assert client.body_fetches == 0
    finally:
        await LocalVaultFS.close()


# ---- 범위 관계를 양방향 표로 뒤집는다 (S15P11B106-175) ------------------------


async def test_hydration_pulls_scope_relations_once(tmp_path):
    """범위 관계는 하이드레이션에서 딱 한 번 받는다."""
    client = FakeClient(relations_items=[
        {"wikiId": "101", "wikiRefs": ["108"], "documentRefs": ["15"]},
        {"wikiId": "108", "wikiRefs": [], "documentRefs": ["15", "16"]}])
    await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        assert client.scope_relations_calls == 1
        catalog = fs._catalog
        # 위키→위키를 뒤집는다. 101 이 108 을 가리키므로 108 의 역링크가 101 이다.
        assert catalog.wiki_backlinks[A108] == {A101}
        assert catalog.wiki_backlinks.get(A101, set()) == set()
        # 문서→위키를 뒤집는다. 카탈로그 등장 순서다.
        assert catalog.wikis_by_document["15"] == [A101, A108]
        assert catalog.wikis_by_document["16"] == [A108]
        # 정방향도 남긴다 — wiki-edits 가 근거 문서를 찾을 때 쓴다.
        assert catalog.document_ids_by_address[A108] == ["15", "16"]
        assert fs.page_count == 2
    finally:
        await FederatedVaultFS.close()


async def test_hydration_tolerates_unknown_wiki_ids_in_relations(tmp_path):
    """목록에 없는 wikiId 가 관계에 섞여 있으면 버린다 — 다른 범위이거나 그 사이 지워졌다."""
    client = FakeClient(relations_items=[
        {"wikiId": "101", "wikiRefs": ["999"], "documentRefs": []},
        {"wikiId": "999", "wikiRefs": [], "documentRefs": ["77"]}])
    await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        assert "77" not in fs._catalog.wikis_by_document
        assert fs._catalog.wiki_backlinks == {}
    finally:
        await FederatedVaultFS.close()


async def test_page_count_is_zero_on_empty_scope(tmp_path):
    """위키 0장인 범위는 정상이다 — 오류가 아니다."""
    client = FakeClient(pages=[], bodies={}, relations_items=[])
    await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        assert fs.page_count == 0
        assert client.scope_relations_calls == 1
    finally:
        await FederatedVaultFS.close()


# ---- 삭제·교체 재조정이 인용 위키를 되찾는다 (S15P11B106-175) ------------------

DOC_BODY = "연차휴가는 15일로 한다"

CITING_BODY = """---
title: 휴가 규정
---

연차는 15일이다[^1]

[^1]: 15.pdf, 3장 휴가 — "연차휴가는 15일로 한다"
"""

PLAIN_BODY = "---\ntitle: 보안 규정\n---\n\n각주 없는 본문\n"


async def test_citation_backlinks_pull_only_citing_pages(tmp_path):
    """문서를 인용한 위키만 당겨 각주를 뽑는다 — 범위 전체를 당기지 않는다."""
    client = FakeClient(
        bodies={"101": CITING_BODY, "108": PLAIN_BODY},
        relations_items=[{"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]},
                         {"wikiId": "108", "wikiRefs": [], "documentRefs": []}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        address = await fs.stage_source(scope_id, "15", DOC_BODY, "15.pdf")
        before = client.body_fetches
        rows = await fs.get_citation_backlinks(scope_id, address)
        # 인용한 1장만 당겼다. 108 은 안 당긴다.
        assert client.body_fetches - before == 1
        assert [r["address"] for r in rows] == [A101]
        assert rows[0]["footnote_label"] == "1"
        assert rows[0]["quote"] == DOC_BODY
    finally:
        await FederatedVaultFS.close()


async def test_citation_backlinks_empty_when_nobody_cites(tmp_path):
    """아무도 안 쓴 문서는 빈 목록이다. 합법이며 실패가 아니다 (설계 §4.1)."""
    client = FakeClient(
        bodies={"101": CITING_BODY, "108": PLAIN_BODY},
        relations_items=[{"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        address = await fs.stage_source(scope_id, "99", "다른 문서", "99.pdf")
        assert await fs.get_citation_backlinks(scope_id, address) == []
    finally:
        await FederatedVaultFS.close()


async def test_citation_backlinks_drop_pages_without_the_footnote(tmp_path):
    """관계 표가 인용했다고 해도 본문에 각주가 없으면 버린다 (설계 §3.1 대조).

    `documentRefs` 는 백엔드가 관리하는 JSON 이라 낡을 수 있다. 당긴 본문에 각주가
    없으면 `sync_references` 가 간선을 만들지 않으므로 부모 SELECT 가 그 행을 내지
    않는다 — 대조는 별도 코드가 아니라 이 성질이다.
    """
    client = FakeClient(
        bodies={"101": PLAIN_BODY, "108": PLAIN_BODY},
        relations_items=[{"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        address = await fs.stage_source(scope_id, "15", DOC_BODY, "15.pdf")
        assert await fs.get_citation_backlinks(scope_id, address) == []
        # 그래도 당기기는 했다 — 관계 표가 인용했다고 말했으므로 확인이 필요했다.
        assert client.body_fetches >= 1
    finally:
        await FederatedVaultFS.close()


async def test_the_catalog_carries_the_one_line_summary_without_pulling_bodies(
        federated):
    """조회 API 목록 응답의 `summary` 를 목록에 실어 준다 (2026-08-05 도구 검토).

    예전에는 `title`·`categoryName` 만 읽고 버렸다. 그러면 `search(mode="list")` 가 주소와
    제목뿐이라 에이전트가 어느 페이지가 무엇을 다루는지 검색으로 알아내려 한다 — 실측(job 33)
    에서 `search` 35회, 대부분 0건이었다.

    **본문을 당기지 않는다.** 이미 받은 응답의 필드라 조회가 늘지 않는 것이 요점이다.
    """
    fs, scope_id, client = federated
    docs = await fs.list_documents(scope_id)

    pages = {d["address"]: d for d in docs if d["kind"] == "page"}
    assert pages, docs
    assert any(d.get("summary") for d in pages.values()), pages
    assert pages["pages/a3f2c1d4.md"]["summary"] == "연차와 반차 사용 기준"
    # 본문은 여전히 비어 있어야 한다 — 요약이 지연 적재를 유발하면 안 된다.
    assert all(not (d.get("content") or "") for d in pages.values()), pages
    assert client.body_fetches == 0
