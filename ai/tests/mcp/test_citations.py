"""Citation parsing and the edges it produces.

The shapes here are what models actually wrote in `runs/opus46-2docs.json` —
including `파일명 — "인용문"` with no comma, which upstream discarded as a
comment. The quote is the most useful anchor there is, so it has to survive.
"""

from ..conftest import SCOPE
from wiki_mcp.tools.references import build_edges, parse_citation, parse_wiki_links


def test_english_page_form():
    assert parse_citation("paper.pdf, p.3") == {
        "name": "paper.pdf", "page": 3, "location": "", "quote": None}


def test_korean_page_form():
    assert parse_citation("인사규정.pdf, 12쪽")["page"] == 12
    assert parse_citation("인사규정.pdf, 12페이지")["page"] == 12


def test_section_location_is_split_off_the_filename():
    """The shape upstream folded into the filename, leaving it unresolvable."""
    parsed = parse_citation("인사규정.pdf, 3장 휴가")
    assert parsed["name"] == "인사규정.pdf"
    assert parsed["location"] == "3장 휴가"


def test_quote_after_a_dash_is_kept():
    parsed = parse_citation('인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"')
    assert parsed["name"] == "인사규정.pdf"
    assert parsed["location"] == "3장 휴가"
    assert parsed["quote"] == "연차는 입사일을 기준으로 산정한다"


def test_quote_without_a_location():
    """Seen in the opus run: filename, dash, quote, no comma."""
    parsed = parse_citation('02-side-gigs.md — "We are not ok with this"')
    assert parsed["name"] == "02-side-gigs.md"
    assert parsed["location"] == ""
    assert parsed["quote"] == "We are not ok with this"


def test_curly_quotes():
    assert parse_citation("인사규정.pdf, 3장 — “입사일 기준”")["quote"] == "입사일 기준"


def test_filename_only():
    parsed = parse_citation("인사규정.pdf")
    assert parsed == {"name": "인사규정.pdf", "page": None, "location": "", "quote": None}


def test_markdown_link_is_unwrapped():
    assert parse_citation("[인사규정.pdf](sources/101), p.2")["name"] == "인사규정.pdf"


def test_wiki_links_normalize_to_addresses():
    content = "[연차](pages/abc123.md) 와 [보상](def456.md) 와 [목차](index.md)"
    assert parse_wiki_links(content) == ["pages/abc123.md", "pages/def456.md", "index.md"]


def test_external_and_image_links_are_not_wiki_links():
    content = "[외부](https://example.com) ![그림](diagram.svg) [앵커](#절)"
    assert parse_wiki_links(content) == []


async def test_one_edge_per_footnote(vault):
    """Upstream deduped by (target, type): thirteen footnotes citing one source
    collapsed to one edge and every location was lost."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    content = (
        "연차[^1]. 이월[^2]. 보상[^3].\n\n"
        '[^1]: 인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"\n'
        "[^2]: 인사규정.pdf, 3장 휴가\n"
        "[^3]: 인사규정.pdf, 4장 보상\n"
    )
    await fs.write(scope_id, address, content, title="연차", category="휴가", tags=["휴가"])
    edges = await build_edges(fs, scope_id, address, content)
    cites = [e for e in edges if e["type"] == "cites"]
    assert [e["footnote"] for e in cites] == ["1", "2", "3"]
    assert cites[0]["quote"] == "연차는 입사일을 기준으로 산정한다"
    assert cites[2]["location"] == "4장 보상"


async def test_a_citation_resolves_by_filename_not_path(vault):
    """Paths no longer carry the filename (DR-016), so this lookup is the only
    way a footnote can resolve at all."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    content = "사실[^1].\n\n[^1]: 인사규정.pdf, 3장 휴가"
    await fs.write(scope_id, address, content, title="연차", category="휴가", tags=["휴가"])
    edges = await build_edges(fs, scope_id, address, content)
    target = await fs.get(scope_id, "sources/101/parsed/content.md")
    assert edges[0]["targetAddress"] == target["address"]


async def test_a_citation_resolves_without_the_extension(vault):
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    content = "사실[^1].\n\n[^1]: 인사규정, 3장 휴가"
    await fs.write(scope_id, address, content, title="연차", category="휴가", tags=["휴가"])
    assert await build_edges(fs, scope_id, address, content)


async def test_links_between_pages_become_edges(vault):
    _, scope_id, fs = vault
    a = await fs.allocate_page(scope_id)
    b = await fs.allocate_page(scope_id)
    await fs.write(scope_id, b, "본문 B", title="B", category="휴가", tags=["휴가"])
    content = f"참고: [B]({b})"
    await fs.write(scope_id, a, content, title="A", category="휴가", tags=["휴가"])
    edges = await build_edges(fs, scope_id, a, content)
    assert edges == [{"targetAddress": b, "type": "links_to", "footnote": None,
                     "location": None, "quote": None, "page": None}]
