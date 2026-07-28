"""Footnote definitions end up at the end, whichever tool wrote the page.

The case this comes from: on the first two-document run the agent used `edit` to
add a cross-reference to an existing page. The end of that page was the footnote
block, so the sentence landed after the definitions and the markdown parser
stops rendering them there. `append` already guarded the tail; `create` and
`edit` did not.
"""

from .conftest import SCOPE
from wiki_mcp.tools.write import WriteHandler, split_trailing_footnotes, tidy_footnotes


def test_prose_after_the_footnote_block_is_moved_back_above_it():
    content = (
        "본문[^1].\n\n"
        "[^1]: 인사규정.pdf, 3장 휴가\n\n"
        "> **참고:** 다른 페이지도 보라.\n"
    )
    result = tidy_footnotes(content)
    assert result.index("**참고:**") < result.index("[^1]:")
    body, footnotes = split_trailing_footnotes(result)
    assert footnotes == "[^1]: 인사규정.pdf, 3장 휴가"


def test_definition_order_is_preserved():
    content = "본문.\n\n[^2]: b.pdf\n[^1]: a.pdf\n\n뒤에 온 산문.\n"
    result = tidy_footnotes(content)
    assert result.index("[^2]:") < result.index("[^1]:")


def test_wrapped_continuation_lines_travel_with_their_definition():
    content = (
        "본문[^1].\n\n"
        "[^1]: 인사규정.pdf, 3장 휴가\n"
        "    이어지는 설명 줄.\n\n"
        "뒤에 온 산문.\n"
    )
    result = tidy_footnotes(content)
    assert result.rstrip().endswith("이어지는 설명 줄.")
    assert result.index("뒤에 온 산문.") < result.index("[^1]:")


def test_definitions_inside_a_code_fence_are_left_alone():
    """The guide shows a footnote example in a fenced block; moving it would
    rewrite the documentation the agent is reading."""
    content = (
        "설명.\n\n"
        "```\n"
        "[^1]: 인사규정.pdf, 3장 휴가\n"
        "```\n\n"
        "그 뒤 산문.\n"
    )
    assert tidy_footnotes(content) == content


def test_a_page_with_no_footnotes_is_untouched():
    content = "본문만 있다.\n\n## 절\n\n내용.\n"
    assert tidy_footnotes(content) == content


def test_an_already_tidy_page_keeps_its_shape():
    content = "본문[^1].\n\n[^1]: 인사규정.pdf, 3장 휴가\n"
    assert tidy_footnotes(content).strip() == content.strip()


async def test_edit_moves_the_block_when_a_cross_reference_lands_after_it(vault, scope_row):
    """The exact sequence from the run: create with footnotes, then edit to append
    a cross-reference at the end of the page."""
    _, scope_id, fs = vault
    handler = WriteHandler(fs, scope_row)
    other = await fs.allocate_page(scope_id)
    await fs.write(scope_id, other, "다른 페이지", title="다른", category="휴가", tags=["휴가"])

    await handler.create(
        "연차", "연차 규정[^1].\n\n[^1]: 인사규정.pdf, 3장 휴가",
        ["휴가", "인사"], "휴가 정책", "",
    )
    page = next(d for d in await fs.list_documents(scope_id)
                if d["kind"] == "page" and d["address"] != other)

    await handler.edit(page["address"], "[^1]: 인사규정.pdf, 3장 휴가",
                       f"[^1]: 인사규정.pdf, 3장 휴가\n\n> **참고:** [다른]({other}) 참고.")

    saved = (await fs.get(scope_id, page["address"]))["content"]
    assert saved.index("**참고:**") < saved.index("[^1]:")
    _, footnotes = split_trailing_footnotes(saved)
    assert footnotes.startswith("[^1]:")


async def test_staleness_ignores_pages_this_job_is_writing(vault, scope_row):
    """Writing A then editing the B it links to, inside one job, must not leave A
    marked stale — the agent is already handling both."""
    _, scope_id, fs = vault
    a = await fs.allocate_page(scope_id)
    b = await fs.allocate_page(scope_id)
    await fs.write(scope_id, b, "본문 B", title="B", category="휴가", tags=["휴가"])
    await fs.write(scope_id, a, f"참고: [B]({b})", title="A", category="휴가", tags=["휴가"])

    from wiki_mcp.tools.references import sync_references

    await sync_references(fs, scope_id, a, f"참고: [B]({b})")
    await fs.write(scope_id, b, "본문 B 개정")
    await fs.propagate_staleness(scope_id, b)

    assert await fs.find_stale_pages(scope_id) == []


async def test_staleness_still_flags_a_committed_page(vault, scope_row):
    """Across jobs it is a real signal: a served page links something that moved."""
    from wiki_mcp.vaultfs.local import LocalVaultFS, commit_job

    root, scope_id, fs = vault
    a = await fs.allocate_page(scope_id)
    b = await fs.allocate_page(scope_id)
    await fs.write(scope_id, b, "본문 B", title="B", category="휴가", tags=["휴가"])
    await fs.write(scope_id, a, f"참고: [B]({b})", title="A", category="휴가", tags=["휴가"])

    from wiki_mcp.tools.references import sync_references

    await sync_references(fs, scope_id, a, f"참고: [B]({b})")
    await commit_job(SCOPE, "9001", scope_id, lambda: 3001)

    await LocalVaultFS.open(root, SCOPE, "9002")
    fs2 = LocalVaultFS(SCOPE, "9002")
    await fs2.write(scope_id, b, "본문 B 개정")
    await fs2.propagate_staleness(scope_id, b)

    assert [r["address"] for r in await fs2.find_stale_pages(scope_id)] == [a]
