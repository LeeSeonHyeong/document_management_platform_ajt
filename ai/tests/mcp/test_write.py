"""The write tools.

The footnote behaviours are why upstream was ported rather than rewritten; the
rest is this project's model — an allocated address, a required category, and an
explicit merge.
"""

from wiki_mcp.tools.write import (
    append_markdown_section,
    build_frontmatter,
    parse_frontmatter,
    renumber_colliding_footnotes,
    split_trailing_footnotes,
)
from wiki_mcp.tools.write import WriteHandler


# ----- footnote handling, from upstream -------------------------------------

def test_footnotes_stay_at_end_when_appending():
    existing = "본문 하나[^1].\n\n[^1]: 인사규정.pdf, 3장 휴가"
    result = append_markdown_section(existing, "## 새 절\n\n추가 내용.")
    body, footnotes = split_trailing_footnotes(result)
    assert footnotes == "[^1]: 인사규정.pdf, 3장 휴가"
    # Without the split the new section lands after the definition and the
    # citation stops rendering.
    assert result.index("## 새 절") < result.index("[^1]:")
    assert "## 새 절" in body


def test_colliding_footnote_ids_get_renumbered():
    existing = "가[^1] 나[^2].\n\n[^1]: a.pdf\n[^2]: b.pdf"
    result = renumber_colliding_footnotes(existing, "다[^1].\n\n[^1]: c.pdf")
    assert "[^1]" not in result
    assert "[^3]" in result


def test_non_colliding_footnote_ids_are_left_alone():
    addition = "나[^9].\n\n[^9]: b.pdf"
    assert renumber_colliding_footnotes("가[^1].\n\n[^1]: a.pdf", addition) == addition


def test_split_ignores_mid_document_definitions():
    content = "가[^1].\n\n[^1]: a.pdf\n\n## 뒤에 온 절\n\n산문."
    body, footnotes = split_trailing_footnotes(content)
    assert footnotes == ""
    assert body.endswith("산문.")


# ----- frontmatter ----------------------------------------------------------

def test_generated_description_drops_footnote_markers():
    """A marker in `description` renders as a literal `[^1]` in the index."""
    content = "연차는 입사일 기준으로 산정한다[^1].\n\n[^1]: 인사규정.pdf"
    built = build_frontmatter(content, "연차", "휴가 정책", ["휴가", "인사"], "2026-07-27")
    meta = parse_frontmatter(built)
    assert "[^" not in meta["description"]
    assert meta["category"] == "휴가 정책"


def test_existing_frontmatter_is_left_alone():
    content = "---\ntitle: 이미 있음\ncategory: 휴가\ntags: [a, b]\n---\n\n본문."
    assert build_frontmatter(content, "다른 제목", "다른 카테고리", ["x"], "") == content


# ----- tools ----------------------------------------------------------------

async def test_create_allocates_the_address(vault, scope_row):
    """The agent does not choose a path, so it cannot misfile a page and the
    category cannot leak into one (DR-019)."""
    _, scope_id, fs = vault
    handler = WriteHandler(fs, scope_row)
    response = await handler.create("연차 휴가", "## 산정\n\n입사일 기준.",
                                    ["휴가", "인사"], "휴가 정책", "")
    assert "pages/" in response
    pages = [d for d in await fs.list_documents(scope_id) if d["kind"] == "page"]
    assert len(pages) == 1
    assert pages[0]["category"] == "휴가 정책"


async def test_create_without_a_category_is_refused(vault, scope_row):
    _, scope_id, fs = vault
    handler = WriteHandler(fs, scope_row)
    assert "category" in await handler.create("연차", "본문.", ["휴가"], "", "")


async def test_create_reads_the_category_from_frontmatter(vault, scope_row):
    _, scope_id, fs = vault
    handler = WriteHandler(fs, scope_row)
    content = "---\ntitle: 연차\ncategory: 근태\ntags: [휴가, 인사]\n---\n\n본문."
    await handler.create("연차", content, [], "", "")
    pages = [d for d in await fs.list_documents(scope_id) if d["kind"] == "page"]
    assert pages[0]["category"] == "근태"


async def test_edit_refuses_an_ambiguous_match(vault, scope_row):
    _, scope_id, fs = vault
    handler = WriteHandler(fs, scope_row)
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "같은 문장.\n\n같은 문장.",
                   title="x", category="c", tags=["a"])
    assert "2곳에서 일치" in await handler.edit(address, "같은 문장.", "다른 문장.")


async def test_write_reports_the_impact_surface(vault, scope_row):
    """The feedback loop. Without it a single-turn ingest writes blind."""
    _, scope_id, fs = vault
    handler = WriteHandler(fs, scope_row)
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, "## 산정\n\n입사일 기준으로 센다.",
                   title="연차", category="휴가", tags=["휴가"])
    await handler.append("index.md", f"- [연차]({address})")

    response = await handler.edit(address, "입사일 기준으로 센다.", "회계연도 기준으로 센다.")
    assert "이 페이지를 참조하는 페이지 1건" in response
    assert "index.md" in response


async def test_merge_folds_pages_and_records_why(vault, scope_row):
    """FR-AI-009 wants the summary to say 병합, and nothing can infer that from a
    removal — so the agent declares it."""
    _, scope_id, fs = vault
    handler = WriteHandler(fs, scope_row)
    keep = await fs.allocate_page(scope_id)
    drop = await fs.allocate_page(scope_id)
    await fs.write(scope_id, keep, "본문 A", title="A", category="휴가", tags=["휴가"])
    await fs.write(scope_id, drop, "본문 B", title="B", category="휴가", tags=["휴가"])

    response = await handler.merge(keep, [drop], "합친 본문 A+B")
    assert drop in response
    assert (await fs.get(scope_id, drop)) is None
    assert (await fs.get(scope_id, keep))["content"] == "합친 본문 A+B"


async def test_merge_refuses_to_absorb_the_survivor(vault, scope_row):
    _, scope_id, fs = vault
    handler = WriteHandler(fs, scope_row)
    keep = await fs.allocate_page(scope_id)
    await fs.write(scope_id, keep, "본문", title="A", category="휴가", tags=["휴가"])
    assert "흡수 목록" in await handler.merge(keep, [keep], "본문")


async def test_merge_refuses_an_unknown_page(vault, scope_row):
    _, scope_id, fs = vault
    handler = WriteHandler(fs, scope_row)
    keep = await fs.allocate_page(scope_id)
    await fs.write(scope_id, keep, "본문", title="A", category="휴가", tags=["휴가"])
    assert "찾을 수 없는" in await handler.merge(keep, ["pages/deadbeef.md"], "본문")
