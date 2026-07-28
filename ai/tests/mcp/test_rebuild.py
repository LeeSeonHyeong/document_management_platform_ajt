"""The index must be re-derivable from the file tree.

`schema.sql` calls the index derived state; this is the code that makes that true.
The need was concrete: changing `document_references` left every existing
workspace unopenable, because `CREATE TABLE IF NOT EXISTS` does not migrate.
"""

from .conftest import JOB_ID, SCOPE
from wiki_mcp.tools.lint import LintHandler
from wiki_mcp.tools.references import sync_references
from wiki_mcp.vaultfs import LocalVaultFS
from wiki_mcp.vaultfs.local import commit_job
from wiki_mcp.vaultfs.rebuild import rebuild_index

PAGE = """\
---
title: 연차 휴가
description: 입사일 기준 연차
date: 2026-07-27
tags: [휴가, 인사]
category: 휴가 정책
---

연차는 입사일을 기준으로 산정한다[^1].

[^1]: 인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"
"""


async def _committed_wiki(vault):
    root, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, PAGE, title="연차 휴가",
                   category="휴가 정책", tags=["휴가", "인사"])
    await sync_references(fs, scope_id, address, PAGE)

    index = await fs.get(scope_id, "index.md")
    body = (index["content"] or "") + f"\n\n- [연차 휴가]({address})"
    await fs.write(scope_id, "index.md", body)
    await sync_references(fs, scope_id, "index.md", body)

    await commit_job(SCOPE, JOB_ID, scope_id, lambda: 4207)
    return root, scope_id, address


async def test_rebuild_restores_pages_metadata_and_edges(vault):
    root, scope_id, address = await _committed_wiki(vault)
    await LocalVaultFS.close()

    counts = await rebuild_index(root, SCOPE)
    assert counts == {"pages": 1, "index": 1, "sources": 1, "unknownSourceNames": 0}

    fs = LocalVaultFS(SCOPE)
    page = await fs.get(scope_id, address)
    # Metadata comes back from frontmatter...
    assert page["title"] == "연차 휴가"
    assert page["category"] == "휴가 정책"
    assert page["tags"] == ["휴가", "인사"]
    # ...and `wiki_id`, which is nowhere in the file, from the old index.
    assert page["wiki_id"] == "4207"

    # Edges are parsed from content, so they come back for free.
    cites = await fs.get_forward_references(scope_id, address)
    assert [r["footnote_label"] for r in cites if r["reference_type"] == "cites"] == ["1"]
    assert [r["address"] for r in await fs.get_backlinks(scope_id, address)] == ["index.md"]


async def test_a_rebuilt_wiki_still_passes_lint(vault):
    """The real test of "derived": nothing verifiable is lost."""
    root, scope_id, _ = await _committed_wiki(vault)
    await LocalVaultFS.close()

    await rebuild_index(root, SCOPE)
    fs = LocalVaultFS(SCOPE)
    scope = await fs.resolve_scope(SCOPE)
    report = await LintHandler(fs, scope).run()
    assert "lint 통과" in report, report


async def test_rebuild_leaves_no_duplicate_rows(vault):
    root, scope_id, address = await _committed_wiki(vault)
    await LocalVaultFS.close()

    await rebuild_index(root, SCOPE)
    await rebuild_index(root, SCOPE)

    fs = LocalVaultFS(SCOPE)
    addresses = [d["address"] for d in await fs.list_documents(scope_id)]
    assert len(addresses) == len(set(addresses)) == 3


async def test_rebuild_reports_source_names_it_cannot_recover(vault):
    """A filename lives only in the DB (DR-016 keeps it out of the path), so a
    rebuild after the index is deleted outright cannot know it — and citations
    naming it stop resolving. Surfaced rather than hidden."""
    root, scope_id, _ = await _committed_wiki(vault)
    db = LocalVaultFS._conn()
    await db.execute("UPDATE documents SET original_file_name = NULL WHERE kind = 'source'")
    await db.commit()
    await LocalVaultFS.close()

    counts = await rebuild_index(root, SCOPE)
    assert counts["unknownSourceNames"] == 1
