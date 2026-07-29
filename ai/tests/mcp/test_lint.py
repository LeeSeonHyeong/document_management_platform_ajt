"""Lint is the gate an ingest is judged by, so every check gets a case that fails
without it.

The citation checks carry the most weight: verifying that a footnote's location
and quote are really in the cited source is what turns "a footnote is present"
into "the claim is supported" (FR-WIKI-001, NFR-AI-002). That was the first item
on the earlier spike's unverified list.
"""

from ..conftest import SCOPE
from wiki_mcp.tools.lint import LintHandler
from wiki_mcp.tools.references import sync_references

GOOD_PAGE = """\
---
title: 연차 휴가
description: 입사일 기준 연차 발생과 이월
date: 2026-07-27
tags: [휴가, 인사]
category: 휴가 정책
---

연차는 입사일을 기준으로 산정한다[^1].

| 근속 | 연차 |
|---|---|
| 1년 | 15일 |

[^1]: 인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"
"""


async def _page(fs, scope_id, content=GOOD_PAGE, *, link_from_index=True):
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, content, title="연차 휴가",
                   category="휴가 정책", tags=["휴가", "인사"])
    await sync_references(fs, scope_id, address, content)

    if link_from_index:
        index = await fs.get(scope_id, "index.md")
        index_body = (index["content"] or "") + f"\n\n- [연차 휴가]({address})"
        await fs.write(scope_id, "index.md", index_body)
        await sync_references(fs, scope_id, "index.md", index_body)
    return address


async def _lint(fs, scope_row, pattern="*"):
    return await LintHandler(fs, scope_row).run(pattern)


async def test_a_fully_ingested_page_passes(vault, scope_row):
    """What a finished ingest looks like: cited source, verified quote, linked
    from the hub. Everything below is one of those pieces missing."""
    _, scope_id, fs = vault
    await _page(fs, scope_id)
    report = await _lint(fs, scope_row)
    assert "lint 통과" in report, report


async def test_quote_not_in_the_source_is_an_error(vault, scope_row):
    """The check that catches a paraphrase dressed as a quotation."""
    _, scope_id, fs = vault
    content = GOOD_PAGE.replace(
        '"연차는 입사일을 기준으로 산정한다"', '"연차는 계약일을 기준으로 산정한다"')
    await _page(fs, scope_id, content)
    report = await _lint(fs, scope_row)
    assert "citation-quote-not-found" in report, report


async def test_quote_with_an_ellipsis_matches_each_segment(vault, scope_row):
    _, scope_id, fs = vault
    content = GOOD_PAGE.replace(
        '"연차는 입사일을 기준으로 산정한다"', '"연차는 입사일을 … 다음 해 3월까지 가능하다"')
    await _page(fs, scope_id, content)
    assert "citation-quote-not-found" not in await _lint(fs, scope_row)


async def test_location_not_in_the_source_is_an_error(vault, scope_row):
    _, scope_id, fs = vault
    content = GOOD_PAGE.replace("3장 휴가 — ", "7장 특별휴가 — ")
    await _page(fs, scope_id, content)
    assert "citation-location-not-found" in await _lint(fs, scope_row)


async def test_citation_to_a_missing_source_is_an_error(vault, scope_row):
    _, scope_id, fs = vault
    content = GOOD_PAGE.replace("인사규정.pdf", "없는문서.pdf")
    await _page(fs, scope_id, content)
    assert "unresolved-citation" in await _lint(fs, scope_row)


async def test_citation_without_a_location_is_a_warning(vault, scope_row):
    _, scope_id, fs = vault
    content = GOOD_PAGE.replace('인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"',
                                "인사규정.pdf")
    await _page(fs, scope_id, content)
    assert "citation-without-location" in await _lint(fs, scope_row)


async def test_missing_category_is_an_error(vault, scope_row):
    _, scope_id, fs = vault
    await _page(fs, scope_id, GOOD_PAGE.replace("category: 휴가 정책\n", ""))
    assert "missing-category" in await _lint(fs, scope_row)


async def test_footnote_marker_in_frontmatter_is_an_error(vault, scope_row):
    """Seen in the first real ingest: the model copied its opening sentence,
    marker and all, into `description`."""
    _, scope_id, fs = vault
    await _page(fs, scope_id, GOOD_PAGE.replace(
        "description: 입사일 기준 연차 발생과 이월",
        "description: 입사일 기준 연차 발생과 이월[^1]"))
    assert "footnote-in-frontmatter" in await _lint(fs, scope_row)


async def test_footnote_used_without_definition_is_an_error(vault, scope_row):
    _, scope_id, fs = vault
    content = GOOD_PAGE.replace(
        '[^1]: 인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"', "")
    await _page(fs, scope_id, content)
    assert "footnote-without-definition" in await _lint(fs, scope_row)


async def test_link_leaving_the_scope_is_an_error(vault, scope_row):
    _, scope_id, fs = vault
    await _page(fs, scope_id, GOOD_PAGE.replace(
        "연차는", "[없는 페이지](pages/deadbeef.md) 연차는"))
    assert "dangling-link" in await _lint(fs, scope_row)


async def test_page_the_hub_does_not_link_is_an_orphan(vault, scope_row):
    _, scope_id, fs = vault
    await _page(fs, scope_id, link_from_index=False)
    assert "orphan-page" in await _lint(fs, scope_row)


async def test_source_no_page_cites_is_an_error(vault, scope_row):
    """The data-loss detector: a document processed but reflected nowhere."""
    _, scope_id, fs = vault
    assert "uncited-source" in await _lint(fs, scope_row)


async def test_index_is_never_reported_stale(vault, scope_row):
    """index.md links to everything, so every ingest marks it stale. Seen on the
    first two-document run: warnings that would never clear."""
    _, scope_id, fs = vault
    address = await _page(fs, scope_id)
    await fs.propagate_staleness(scope_id, address)
    assert "stale-page" not in await _lint(fs, scope_row)
