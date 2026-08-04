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


async def test_a_markdown_link_inside_the_source_quote_still_matches(vault, scope_row):
    """The source sentence itself may wrap a word in a markdown link
    (`[Vestwell](https://...)`) — common in scraped handbook text (하네스 실측,
    2026-08-02~03, 04-benefits.md: 5건). Whether the model keeps the link syntax
    or copies just the visible text, it is the same claim; only the formatting
    differs, same as quote-mark style and emphasis."""
    from wiki_mcp.vaultfs.local import register_source

    _, scope_id, fs = vault
    await register_source(SCOPE, "102", "복지.pdf",
                          "401k는 [Vestwell](https://connect-b.vestwell.com/)이 운용한다.")
    content = GOOD_PAGE.replace("인사규정.pdf, 3장 휴가", "복지.pdf, 401k").replace(
        '"연차는 입사일을 기준으로 산정한다"', '"401k는 Vestwell이 운용한다"')
    await _page(fs, scope_id, content)
    assert "citation-quote-not-found" not in await _lint(fs, scope_row)


async def test_an_inline_code_span_inside_the_source_quote_still_matches(vault, scope_row):
    """원문이 채널명·도메인·명령어를 백틱으로 감싸는 경우(`#private-xxxxx`)가 잦은데,
    모델은 그 백틱을 빼고 인용한다 — 링크·따옴표·강조와 같은 서식 차이다.

    Sonnet 으로 하네스를 돌려 실측한 사례다 (2026-08-03, 12-communication.md 에서 2건:
    `#private-xxxxx` 채널명과 `posthog.co` 도메인). 백틱을 안 벗기면 정당한 인용이
    `citation-quote-not-found` 로 뜬다."""
    from wiki_mcp.vaultfs.local import register_source

    _, scope_id, fs = vault
    await register_source(SCOPE, "103", "소통.pdf",
                          "비공개 채널은 `#private-xxxxx` 를 앞에 붙인다.")
    content = GOOD_PAGE.replace("인사규정.pdf, 3장 휴가", "소통.pdf, 채널 규칙").replace(
        '"연차는 입사일을 기준으로 산정한다"', '"비공개 채널은 #private-xxxxx 를 앞에 붙인다"')
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


# ----- 표에 근거가 없는 경우 (S15P11B106-251) --------------------------------
#
# 실측(하네스 13장)에서 표 데이터 26행·17행짜리 페이지가 각주 0개로 `error 0건` 을 통과했다.
# `_lint_citations` 는 **있는 각주가 원문과 맞는지**만 보고 **사실에 각주가 있는지**는 보지
# 않아서다. 표는 사실이 가장 밀집된 곳인데 그곳이 검사 밖이었다.
#
# **`warn` 으로 둔다.** `error` 로 하면 에이전트가 표 행마다 각주를 맞추려고 돌다 턴 상한에
# 걸린다 — 인용문 리터럴 일치를 `error` 로 강제했을 때 실제로 그랬다(2026-08-02 job 21,
# error 0인 채로 40턴·$2.76 소진). 사람이 보고 판단할 신호로만 남긴다.

BIG_TABLE_ROWS = "\n".join(f"| 도구{n} | 용도{n} |" for n in range(1, 7))
BIG_TABLE_PAGE = """\
---
title: 연차 휴가
description: 입사일 기준 연차 발생과 이월
date: 2026-07-27
tags: [휴가, 인사]
category: 휴가 정책
---

연차는 입사일을 기준으로 산정한다[^1].

## 도구

| 도구 | 용도 |
|---|---|
%s

[^1]: 인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"
""" % BIG_TABLE_ROWS


async def test_a_big_table_without_a_nearby_citation_warns(vault, scope_row):
    """표 6행에 근거가 하나도 없으면 참고용으로 알린다."""
    _, scope_id, fs = vault
    await _page(fs, scope_id, BIG_TABLE_PAGE)
    report = await _lint(fs, scope_row)
    assert "table-without-citation" in report, report
    # 막지는 않는다 — 반드시 고칠 것 0건이어야 한다
    assert "반드시 고칠 것 0건" in report, report


async def test_a_big_table_introduced_by_a_cited_sentence_does_not_warn(vault, scope_row):
    """표를 소개하는 문장에 각주가 있으면 그것으로 충분하다 — `guide` 가 요구하는 모양이다."""
    _, scope_id, fs = vault
    content = BIG_TABLE_PAGE.replace(
        "## 도구\n\n| 도구 | 용도 |",
        "## 도구\n\n부서별로 쓰는 도구는 다음과 같다[^1].\n\n| 도구 | 용도 |")
    await _page(fs, scope_id, content)
    report = await _lint(fs, scope_row)
    assert "table-without-citation" not in report, report


async def test_a_small_table_never_warns(vault, scope_row):
    """작은 표는 알리지 않는다 — 한두 행짜리 표까지 걸면 소음만 늘어난다."""
    _, scope_id, fs = vault
    await _page(fs, scope_id)          # GOOD_PAGE: 표 데이터 1행
    report = await _lint(fs, scope_row)
    assert "table-without-citation" not in report, report
