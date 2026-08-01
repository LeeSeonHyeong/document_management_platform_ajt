"""`SpringVaultFS` — 라이브 층을 밖에서 채워 넣는 2단 SQLite 저장 계층.

**하이드레이션은 이제 이 클래스의 일이 아니다** (S15P11B106-175). `open()` 이
`pages`·`index_markdown` 을 받아 요청이 실어 온 위키로 라이브 층을 채우던 경로가
사라졌고, 지금 라이브 층을 채우는 것은 Wiki 조회 API(`FederatedVaultFS.open`)뿐이다.

그런데도 이 파일이 남아 있는 이유는 **그 아래 저장 계층이 그대로**여서다 —
`FederatedVaultFS` 가 이 클래스를 상속하므로 청크 색인·참조 그래프·staleness·작업 층
분리는 조회 API 경로에서도 여기 것이 돈다. 라이브 행을 넣는 일은 테스트 헬퍼
(`tests/mcp/hydration.open_with_pages`)가 대신한다.

가장 중요한 확인은 `search` 다. 본문이 색인되지 않으면 에이전트가 기존 페이지를 못 찾고
전부 새로 만든다 — 옛 spike 의 조각화(13/26)가 재발한다.
"""

import pytest

from wiki_mcp.vaultfs import LocalVaultFS
from wiki_mcp.vaultfs.spring import SpringVaultFS, address_from_wiki_path

from .hydration import open_with_pages

SCOPE = "D1-D2"
JOB_ID = "42"

PAGE = {
    "wikiId": "101",
    "title": "커뮤니케이션 가이드",
    "wikiPath": f"wiki/{SCOPE}/pages/a3f2c1d4.md",
    "summary": "비동기 우선 소통 원칙과 회의 가이드라인",
    "wikiCategoryId": "9",
    "categoryName": "근무 정책",
    "contentMarkdown": (
        "---\ntitle: 커뮤니케이션 가이드\n"
        "description: 비동기 우선 소통 원칙과 회의 가이드라인\n"
        "tags: [커뮤니케이션, 회의]\ncategory: 근무 정책\n---\n\n"
        "회의는 최후의 수단으로 쓴다. 비동기 소통을 기본으로 한다.\n"
    ),
    "updatedAt": "2026-07-27T09:00:00Z",
}

INDEX_MD = "# 목차\n\n- [커뮤니케이션 가이드](pages/a3f2c1d4.md) — 비동기 우선 소통\n"

SOURCE_MD = "# 회의 운영\n\n## 2장 정례 회의\n\n주간 회의는 30분을 넘기지 않는다.\n"


@pytest.fixture
async def spring_vault(tmp_path):
    scope_id = await open_with_pages(tmp_path, SCOPE, JOB_ID, [PAGE], INDEX_MD)
    yield scope_id, SpringVaultFS(SCOPE, JOB_ID)
    await LocalVaultFS.close()


async def test_open_does_not_hydrate_anything(tmp_path):
    """`open()` 은 색인만 연다. 라이브 층을 채우는 인자는 사라졌다 (S15P11B106-175).

    남겨두면 요청이 위키를 실어 보내는 두 번째 경로가 되살아난다."""
    import inspect

    assert set(inspect.signature(SpringVaultFS.open).parameters) == {
        "root", "scope_key", "job_id"}
    assert not hasattr(SpringVaultFS, "_hydrate_from_pages")

    scope_id = await SpringVaultFS.open(tmp_path, SCOPE, JOB_ID)
    try:
        assert await SpringVaultFS(SCOPE, JOB_ID).list_documents(scope_id) == []
    finally:
        await LocalVaultFS.close()


def test_address_comes_from_wiki_path():
    """pageKey 는 에이전트가 발급한 값이라 wikiId 에서 유도할 수 없다 (DR-016)."""
    assert address_from_wiki_path(f"wiki/{SCOPE}/pages/a3f2c1d4.md", SCOPE) \
        == "pages/a3f2c1d4.md"
    assert address_from_wiki_path(f"wiki/{SCOPE}/index.md", SCOPE) == "index.md"
    assert address_from_wiki_path("pages/a3f2c1d4.md", SCOPE) == "pages/a3f2c1d4.md"


async def test_open_hydrates_the_page(spring_vault):
    scope_id, fs = spring_vault
    row = await fs.get(scope_id, "pages/a3f2c1d4.md")

    assert row is not None
    assert row["layer"] == "live"
    assert row["wiki_id"] == "101"
    assert row["title"] == "커뮤니케이션 가이드"
    assert row["category"] == "근무 정책"
    assert "회의는 최후의 수단" in row["content"]


async def test_open_hydrates_the_index(spring_vault):
    scope_id, fs = spring_vault
    row = await fs.get(scope_id, "index.md")
    assert row is not None and row["kind"] == "index"
    assert "커뮤니케이션 가이드" in row["content"]


async def test_a_page_without_wiki_path_lands_on_the_default_address(tmp_path):
    """조회 API 목록에 `wikiPath` 가 없으면 `pages/{wikiId}.md` 로 떨어져야 한다 (설계 §3).
    `wikiId` 가 이미 있으므로 유일함이 보장된다."""
    page_without_path = {k: v for k, v in PAGE.items() if k != "wikiPath"}
    scope_id = await open_with_pages(tmp_path, SCOPE, JOB_ID, [page_without_path],
                                     INDEX_MD)
    try:
        fs = SpringVaultFS(SCOPE, JOB_ID)
        row = await fs.get(scope_id, "pages/101.md")
        assert row is not None
        assert row["wiki_id"] == "101"
        assert "회의는 최후의 수단" in row["content"]
    finally:
        await LocalVaultFS.close()


async def test_search_finds_the_hydrated_page(spring_vault):
    """이 테스트가 이 태스크의 핵심이다. 본문이 색인되지 않으면 0건이 나온다."""
    scope_id, fs = spring_vault
    hits = await fs.search_chunks(scope_id, "회의", limit=5)

    assert hits, "청크 FTS 가 비었다 — 에이전트가 기존 페이지를 못 찾는다"
    assert any(h["address"] == "pages/a3f2c1d4.md" for h in hits)


CITED_PAGE = dict(PAGE, contentMarkdown=(
    "---\ntitle: 커뮤니케이션 가이드\ndescription: 비동기 우선 소통\n"
    "tags: [커뮤니케이션, 회의]\ncategory: 근무 정책\n---\n\n"
    "회의는 최후의 수단으로 쓴다.\n\n주간 회의는 30분을 넘기지 않는다[^1].\n\n"
    '[^1]: 회의운영.pdf, 2장 정례 회의 — "주간 회의는 30분을 넘기지 않는다"\n'
))


async def test_staging_a_source_builds_the_citation_graph_without_an_explicit_sync(tmp_path):
    """라이브 행을 넣기만 하면 각주가 그래프에 안 들어가던 시절이 있었다 — `write`를 거친
    페이지만 `sync_references`가 불렸다. 그래서 손대지 않은 라이브 페이지는 orphan-page
    로 잘못 잡히고, `write.py`의 `_impact`·`references.py`의 `backlinks_summary` 가
    조용히 빈 결과를 냈다. 이 테스트는 `stage_source` 만 부르고 `sync_references`를 직접
    부르지 않는다 — 그래프가 그것만으로 채워져야 한다."""
    scope_id = await open_with_pages(tmp_path, SCOPE, JOB_ID, [CITED_PAGE], INDEX_MD)
    fs = SpringVaultFS(SCOPE, JOB_ID)
    try:
        # 라이브 층을 채운 시점에는 각주가 가리키는 원본문서(회의운영.pdf)가 아직 없다 —
        # 위키 페이지만 들어간다. 트랜스폼 경로가 늘 그렇듯, 대상 문서를 얹으면
        # (`stage_source`) 그 문서를 인용하던 기존 페이지도 이어져야 한다.
        await fs.stage_source(scope_id, "15", SOURCE_MD, "회의운영.pdf")

        edges = await fs.get_forward_references(scope_id, "pages/a3f2c1d4.md")
        assert any(e["reference_type"] == "cites" for e in edges), \
            "하이드레이션된 페이지의 각주가 그래프에 없다"

        backlinks = await fs.get_citation_backlinks(
            scope_id, "sources/15/parsed/content.md")
        assert backlinks and backlinks[0]["address"] == "pages/a3f2c1d4.md"
        assert backlinks[0]["location"] == "2장 정례 회의"
    finally:
        await LocalVaultFS.close()


LINKING_PAGE = {
    "wikiId": "202",
    "title": "온보딩 체크리스트",
    "wikiPath": f"wiki/{SCOPE}/pages/b7e91a02.md",
    "summary": "합류 첫 주 점검표",
    "wikiCategoryId": "9",
    "categoryName": "근무 정책",
    "contentMarkdown": (
        "---\ntitle: 온보딩 체크리스트\ndescription: 합류 첫 주 점검표\n"
        "tags: [온보딩]\ncategory: 근무 정책\n---\n\n"
        "회의 방식은 [커뮤니케이션 가이드](pages/a3f2c1d4.md) 를 따른다.\n"
    ),
    "updatedAt": "2026-07-27T09:00:00Z",
}


async def test_a_freshly_filled_live_layer_reports_zero_stale_pages(tmp_path):
    """라이브 층을 채운 순간은 한 순간의 스냅샷이다 — t0 에는 아무것도 stale 일 수 없다.

    `_sync_page_references` 가 부르는 `sync_references` 는 `propagate_staleness` 도
    함께 부른다. 그 부작용이 하이드레이션 도중에도 그대로 발동하면, B 가 A 를 링크한
    것만으로 (아직 아무도 안 고쳤는데) A 가 stale 로 잡힌다 — `updated_at`과
    `stale_since`가 몇 마이크로초 차이로 `find_stale_pages`의 조건을 만족해 버린다.
    """
    scope_id = await open_with_pages(tmp_path, SCOPE, JOB_ID, [PAGE, LINKING_PAGE],
                                     INDEX_MD)
    try:
        stale = await SpringVaultFS(SCOPE, JOB_ID).find_stale_pages(scope_id)
        assert stale == [], f"방금 하이드레이션했을 뿐인데 stale 로 잡혔다: {stale}"
    finally:
        await LocalVaultFS.close()


async def test_stage_source_puts_the_document_in_the_live_layer(spring_vault):
    """`stage_source` 가 이제 유일한 원본문서 주입로다 — HTTP 경유 `load_source` 는
    없다."""
    scope_id, fs = spring_vault
    address = await fs.stage_source(scope_id, "15", SOURCE_MD, "회의운영.pdf")

    assert address == "sources/15/parsed/content.md"
    row = await fs.get(scope_id, "sources/15/parsed/content.md")
    assert row["original_file_name"] == "회의운영.pdf"
    assert "주간 회의는 30분" in row["content"]


async def test_source_is_searchable_after_staging(spring_vault):
    """lint 가 각주 인용문을 원문과 대조할 수 있어야 한다."""
    scope_id, fs = spring_vault
    await fs.stage_source(scope_id, "15", SOURCE_MD, "회의운영.pdf")
    hits = await fs.search_chunks(scope_id, "정례", limit=5, kind_filter="sources")
    assert any(h["address"].startswith("sources/15/") for h in hits)


async def test_writes_still_land_in_the_work_layer(spring_vault):
    """라이브 층은 에이전트에게 읽기 전용이다 (DR-007)."""
    scope_id, fs = spring_vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "---\ntitle: 새 페이지\n---\n\n본문", title="새 페이지")

    changes = await fs.pending_changes(scope_id)
    assert [c["type"] for c in changes] == ["create"]

    live = await fs.get(scope_id, "pages/a3f2c1d4.md")
    assert "회의는 최후의 수단" in live["content"]   # 원본이 안 변했다


async def test_editing_a_hydrated_page_is_an_update_not_a_create(spring_vault):
    """라이브 페이지를 고치면 update 로 잡혀야 한다. wikiId 가 따라붙는다."""
    scope_id, fs = spring_vault
    await fs.write(scope_id, "pages/a3f2c1d4.md",
                   "---\ntitle: 커뮤니케이션 가이드\n---\n\n고친 본문", title="커뮤니케이션 가이드")

    changes = await fs.pending_changes(scope_id)
    assert changes[0]["type"] == "update"
    assert changes[0]["wikiId"] == "101"


async def test_editing_into_a_tiny_body_stays_searchable(spring_vault):
    """페이지는 라이브 층에 들어갈 때 검색되게 만들었어도, 같은 job 안에서 아주 짧은 본문으로
    수정되면 `write()`가 직접 `chunk_text`를 호출한다 — 그 경로에는 폴백이 없으면
    이 태스크가 막으려던 실패(FTS 0건)가 수정 경로에서 재발한다."""
    scope_id, fs = spring_vault
    await fs.write(scope_id, "pages/a3f2c1d4.md", "아주 짧다")

    hits = await fs.search_chunks(scope_id, "짧다", limit=5)
    assert any(h["address"] == "pages/a3f2c1d4.md" for h in hits), \
        "짧게 고친 본문이 FTS 에서 사라졌다"

    old_hits = await fs.search_chunks(scope_id, "회의", limit=5)
    assert not any(h["address"] == "pages/a3f2c1d4.md" for h in old_hits), \
        "옛 본문이 그대로 색인에 남아 있다"
