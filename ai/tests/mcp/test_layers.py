"""The two-tier store: the agent writes to a job, the live tree waits.

This is the property DR-007/008 and NFR-REL-001 ask for and the reason the store
was rewritten. If any of these fail, an unverified page is being served.
"""

import pytest

from .conftest import JOB_ID, SCOPE
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
