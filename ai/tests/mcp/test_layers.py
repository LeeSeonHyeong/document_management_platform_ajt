"""The two-tier store: the agent writes to a job, the live tree waits.

This is the property DR-007/008 and NFR-REL-001 ask for and the reason the store
was rewritten. If any of these fail, an unverified page is being served.
"""

import pytest

from ..conftest import JOB_ID, SCOPE
from wiki_mcp.vaultfs import INDEX_ADDRESS, ReadOnlyLayerError, VaultError
from wiki_mcp.vaultfs.local import LocalVaultFS, commit_job, discard_job


async def test_a_write_does_not_touch_the_live_tree(vault):
    root, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "본문", title="연차", category="휴가", tags=["휴가", "인사"])

    assert (root / "work" / JOB_ID / "output" / address).is_file()
    assert not (root / "wiki" / SCOPE / address).exists()


async def test_the_agent_reads_its_own_writes(vault):
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "초안", title="연차", category="휴가", tags=["휴가"])
    assert (await fs.get(scope_id, address))["content"] == "초안"

    await fs.write(scope_id, address, "고친 본문")
    assert (await fs.get(scope_id, address))["content"] == "고친 본문"


async def test_a_work_edit_shadows_the_live_page(vault):
    """Editing a committed page must show the edit, not the served version."""
    _, scope_id, fs = vault
    live = await fs.get(scope_id, INDEX_ADDRESS)
    assert live["layer"] == "live"

    await fs.write(scope_id, INDEX_ADDRESS, "새 목차 본문")
    shown = await fs.get(scope_id, INDEX_ADDRESS)
    assert shown["layer"] == "work"
    assert shown["content"] == "새 목차 본문"
    # Exactly one visible row for the address — the overlay must not double it.
    addresses = [d["address"] for d in await fs.list_documents(scope_id)]
    assert addresses.count(INDEX_ADDRESS) == 1


async def test_sources_are_not_writable(vault):
    _, scope_id, fs = vault
    with pytest.raises(ReadOnlyLayerError):
        await fs.write(scope_id, "sources/101/parsed/content.md", "고쳐본다")


async def test_index_is_not_removable(vault):
    _, scope_id, fs = vault
    with pytest.raises(ReadOnlyLayerError):
        await fs.remove(scope_id, INDEX_ADDRESS)


async def test_an_unknown_address_shape_is_refused(vault):
    _, scope_id, fs = vault
    with pytest.raises(VaultError):
        await fs.write(scope_id, "복리후생/연차.md", "본문")


async def test_a_write_cannot_escape_the_work_space(vault):
    _, scope_id, fs = vault
    with pytest.raises(VaultError):
        await fs.write(scope_id, "pages/../../escaped.md", "본문")


async def test_removing_an_uncommitted_page_leaves_nothing_to_hand_over(vault):
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "본문", title="임시", category="휴가", tags=["a"])
    assert await fs.remove(scope_id, address)
    assert await fs.get(scope_id, address) is None
    assert [c for c in await fs.pending_changes(scope_id) if c["address"] == address] == []


async def test_a_tiny_body_stays_searchable(vault):
    """~32토큰 미만 본문도 검색 색인에 남아야 한다 — `write` 의 계약 수준 테스트다.

    `chunk_text` 는 `MIN_CHUNK_TOKENS` 미만을 버리므로, 폴백 없이는 청크 0개가 돼
    `search` 가 조용히 못 찾는다. 프로덕션은 `SpringVaultFS` 의 `_chunks_for_index`
    폴백으로 top-up 했는데 `LocalVaultFS` 엔 없어 계층 간에 색인 동작이 갈렸다
    (2026-08-03 divergence 감사 #3).

    **도달 경로를 정직하게 적는다**: `create` 는 frontmatter 를 붙이므로 이 조건에
    거의 걸리지 않는다(실측: 126자 본문이 frontmatter 포함 235자·청크 1개가 된다).
    실제로 걸리는 것은 **시드 경로**이고 그것은
    `test_a_short_source_stays_searchable` 이 검증한다. 이 테스트는 `write` 자체의
    계약(짧은 본문도 색인된다)을 고정한다."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "연차 이월 규정", title="연차", category="휴가", tags=["휴가"])
    hits = await fs.search_chunks(scope_id, "연차", limit=5)
    assert any(h["address"] == address for h in hits)


async def test_a_short_source_stays_searchable(tmp_path):
    """짧은 원본문서를 시드하면 검색돼야 한다 — **하네스에서 실제로 재현된 경로다.**

    `register_source`·`bootstrap_scope` 가 `chunk_text` 를 직접 불러 짧은 원본문서가
    청크 0개로 등록되고 `search` 가 빈 결과를 냈다(2026-08-03 실측: 하네스 시드에
    "12월 24일은 휴무다." 를 넣고 `search 휴무` → 0건). 프로덕션 하이드레이션
    (`SpringVaultFS._insert_live`)은 `_chunks_for_index` 로 top-up 하므로 검색됐다.
    시드 경로도 같은 폴백을 쓰게 고쳤다."""
    from wiki_mcp.vaultfs.local import bootstrap_scope, register_source

    await LocalVaultFS.open(tmp_path, SCOPE, JOB_ID)
    try:
        await bootstrap_scope(SCOPE)
        await register_source(SCOPE, "901", "짧은공지.md", "12월 24일은 휴무다.")
        fs = LocalVaultFS(SCOPE, JOB_ID)
        scope_id = (await fs.resolve_scope(SCOPE))["id"]
        hits = await fs.search_chunks(scope_id, "휴무", limit=5)
        assert [h["address"] for h in hits] == ["sources/901/parsed/content.md"]
    finally:
        await LocalVaultFS.close()


async def test_removing_a_live_page_becomes_a_tombstone(vault):
    """The live file must survive until the backend commits the removal."""
    root, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "본문", title="연차", category="휴가", tags=["휴가"])
    await commit_job(SCOPE, JOB_ID, scope_id, lambda: 3001)
    assert (root / "wiki" / SCOPE / address).is_file()

    await LocalVaultFS.open(root, SCOPE, JOB_ID)
    fs = LocalVaultFS(SCOPE, JOB_ID)
    await fs.remove(scope_id, address)

    assert await fs.get(scope_id, address) is None          # invisible to the agent
    assert (root / "wiki" / SCOPE / address).is_file()      # still served
    changes = await fs.pending_changes(scope_id)
    assert [c["type"] for c in changes] == ["remove"]


async def test_removing_a_page_forgets_its_citations(vault):
    """remove() 는 페이지의 outgoing 참조(document_references)도 지운다. 안 지우면 삭제된
    페이지의 `cites` 엣지가 남아, 그 페이지가 유일한 인용처였던 원본문서를
    `find_uncited_sources` 가 「아직 인용됨」으로 오판해 놓친다 (하네스 실측, 2026-08-03).
    remove 는 `_save`(→sync_references) 를 안 타므로 이 정리가 remove 안에 있어야 한다."""
    from wiki_mcp.tools.references import sync_references

    _, scope_id, fs = vault
    source = "sources/101/parsed/content.md"

    async def source_is_uncited():
        rows = await fs.find_uncited_sources(scope_id)
        return source in {r["address"] for r in rows}

    assert await source_is_uncited()       # 아직 아무 페이지도 인용하지 않음

    address = await fs.allocate_page(scope_id)
    body = ('연차 산정 방식[^1].\n\n'
            '[^1]: 인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"\n')
    await fs.write(scope_id, address, body, title="연차", category="휴가", tags=["휴가"])
    await sync_references(fs, scope_id, address, body)
    assert not await source_is_uncited()   # 이제 이 페이지가 인용한다

    await fs.remove(scope_id, address)
    assert await source_is_uncited()       # 유일 인용처가 사라졌으니 다시 uncited


async def test_commit_promotes_the_work_layer_and_assigns_wiki_ids(vault):
    root, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "본문", title="연차", category="휴가", tags=["휴가", "인사"])

    committed = await commit_job(SCOPE, JOB_ID, scope_id, lambda: 4207)

    assert [c["type"] for c in committed] == ["create"]
    assert committed[0]["wikiId"] == "4207"
    # The filename does not change on commit — that is the point of allocating
    # the key up front, and why no body link has to be rewritten.
    assert committed[0]["address"] == address
    assert (root / "wiki" / SCOPE / address).read_text(encoding="utf-8") == "본문"
    assert not (root / "work" / JOB_ID).exists()
    assert await fs.pending_changes(scope_id) == []


async def test_committing_twice_keeps_the_same_wiki_id(vault):
    """A second job editing the page must not mint a new id."""
    root, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "1판", title="연차", category="휴가", tags=["휴가"])
    await commit_job(SCOPE, JOB_ID, scope_id, lambda: 4207)

    await LocalVaultFS.open(root, SCOPE, "9002")
    fs2 = LocalVaultFS(SCOPE, "9002")
    await fs2.write(scope_id, address, "2판")
    changes = await fs2.pending_changes(scope_id)
    assert changes[0]["type"] == "update"

    committed = await commit_job(SCOPE, "9002", scope_id, lambda: 9999)
    assert committed[0]["wikiId"] == "4207"


async def test_discard_leaves_the_live_tree_untouched(vault):
    """DR-009: a failed verification changes nothing that is served."""
    root, scope_id, fs = vault
    before = (root / "wiki" / SCOPE / "index.md").read_text(encoding="utf-8")
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "본문", title="연차", category="휴가", tags=["휴가"])
    await fs.write(scope_id, "index.md", "망친 목차")

    assert await discard_job(SCOPE, JOB_ID, scope_id) >= 2
    assert (root / "wiki" / SCOPE / "index.md").read_text(encoding="utf-8") == before
    assert not (root / "wiki" / SCOPE / address).exists()
    assert await fs.pending_changes(scope_id) == []


async def test_allocated_addresses_are_unique(vault):
    _, scope_id, fs = vault
    addresses = {await fs.allocate_page(scope_id) for _ in range(20)}
    assert len(addresses) == 20
    assert all(a.startswith("pages/") and a.endswith(".md") for a in addresses)


async def test_pending_changes_is_the_document_summary(vault):
    """FR-AI-009: create/update/remove with the sources each change cites."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    content = (
        "연차는 입사일을 기준으로 산정한다[^1].\n\n"
        "[^1]: 인사규정.pdf, 3장 휴가"
    )
    await fs.write(scope_id, address, content, title="연차", category="휴가", tags=["휴가", "인사"])
    from wiki_mcp.tools.references import sync_references

    await sync_references(fs, scope_id, address, content)

    changes = await fs.pending_changes(scope_id)
    created = next(c for c in changes if c["address"] == address)
    assert created["type"] == "create"
    assert created["title"] == "연차"
    assert created["category"] == "휴가"
    assert created["wikiPath"] == f"wiki/{SCOPE}/{address}"
    assert created["evidence"] == [{
        "documentId": "101", "documentName": "인사규정.pdf", "footnote": "1",
        "location": "3장 휴가", "quote": None, "page": None,
    }]


async def test_search_does_not_return_superseded_live_content(vault):
    """A live chunk whose page was rewritten in the work layer must not surface."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "연차 산정 기준은 입사일이다. " * 10,
                   title="연차", category="휴가", tags=["휴가"])
    await commit_job(SCOPE, JOB_ID, scope_id, lambda: 3001)

    await LocalVaultFS.open(fs.root(), SCOPE, "9002")
    fs2 = LocalVaultFS(SCOPE, "9002")
    await fs2.write(scope_id, address, "회계연도 기준으로 바뀌었다. " * 10)

    hits = await fs2.search_chunks(scope_id, "입사일", 10)
    assert not [h for h in hits if h["address"] == address]
    hits = await fs2.search_chunks(scope_id, "회계연도", 10)
    assert [h for h in hits if h["address"] == address]


async def test_live_content_ignores_the_work_layer(vault):
    """`live_content` 는 에이전트 실행 **전** 본문을 준다 — 작업 층을 겹쳐 읽지 않는다.

    「이 각주가 원래 있던 것인가」를 나이로 판정하는 유일한 근거다
    (`wiki_mcp/services/footnotes.py`). 겹쳐 읽으면 에이전트가 방금 쓴 각주까지 「원래 있던
    것」이 되어 지어낸 인용이 통과한다 (NFR-AI-002).

    `VaultFS` 규약에 있어야 하는 이유: 구현이 `SpringVaultFS` 에만 있던 동안
    `api/session.py` 가 그것을 무조건 불렀고, 즉 게이트가 구체 클래스에 조용히 의존했다.
    """
    _, scope_id, fs = vault

    # 1) 작업 층에만 있는 페이지는 실행 전 상태가 없다 — 그 각주는 전부 이번 작업이 쓴 것이다.
    fresh = await fs.allocate_page(scope_id)
    await fs.write(scope_id, fresh, "# 새 페이지\n\n작업 층에만 있다.\n")
    assert await fs.live_content(scope_id, fresh) is None

    # 2) 고친 페이지는 겹쳐 읽기와 실행 전 상태가 갈린다.
    before = await fs.live_content(scope_id, "index.md")
    assert before is not None
    await fs.write(scope_id, "index.md", before + "\n- [새 페이지](%s)\n" % fresh)
    assert "새 페이지" in (await fs.get(scope_id, "index.md"))["content"]
    assert await fs.live_content(scope_id, "index.md") == before
