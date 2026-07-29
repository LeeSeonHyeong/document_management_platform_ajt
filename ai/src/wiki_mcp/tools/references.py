"""Parse citations and cross-links out of page content, store them as edges.

From lucas-llmwiki `mcp/tools/references.py`. Two changes, both measured:

  * **One edge per footnote.** Upstream deduped by (target, type), so a page
    citing one source at thirteen different places produced one edge and the
    locations survived only as body text. A citation preview needs the location,
    so it has to be in the graph.
  * **The location is kept.** Upstream treated everything after ` — ` as
    commentary and discarded it; opus writes the verbatim quote there, which is
    the most useful anchor there is — it lets the frontend highlight the exact
    sentence in the source, and lets lint verify the claim against it
    (FR-WIKI-001, NFR-AI-002).

The edges stay directed here (`cites`, `links_to`). DR-002's undirected
`wiki_refs` / `document_refs` JSON is the projection the backend writes from them.
"""

from __future__ import annotations

import logging
import re

from wiki_mcp.vaultfs import VaultFS

from .helpers import normalize_address

logger = logging.getLogger(__name__)

_CITATION_RE = re.compile(r"^\[\^([^\]]+)\]:\s*(.+)$", re.MULTILINE)
_WIKI_LINK_RE = re.compile(r"(?<!!)\[(?:[^\]]*)\]\(([^)]+)\)")
_PAGE_RE = re.compile(r"^(?:p\.?\s*(\d+)|(\d+)\s*(?:쪽|페이지|p))$", re.IGNORECASE)
# Double quotes only. An apostrophe must not close a quotation: with `'` in the
# character class, `"We don't think..."` parsed as `We don` and
# `"...any of PostHog's IP"` as `...any of PostHog`. English sources hit this
# almost every time, and the truncated quote then fails verification.
_QUOTE_RE = re.compile(r"[\"“”]([^\"“”]{4,})[\"“”]")


def parse_citation(raw: str) -> dict:
    """Split a footnote definition into its parts.

        인사규정.pdf, 3장 휴가 — "입사일을 기준으로 산정한다"
        -> {name: '인사규정.pdf', page: None, location: '3장 휴가',
            quote: '입사일을 기준으로 산정한다'}

    A page marker (`p.3`, `12쪽`) becomes `page`; anything else stays `location`.
    """
    text = raw.strip().strip("*")
    link_match = re.match(r"\[([^\]]+)\]\([^)]*\)(.*)$", text)
    if link_match:
        text = f"{link_match.group(1)}{link_match.group(2)}"

    quote = None
    quote_match = _QUOTE_RE.search(text)
    if quote_match:
        quote = quote_match.group(1).strip()
        text = text[: quote_match.start()].strip()
    # A dash separated the quote (or a note) from the reference. `\s*` on the
    # right, not `\s+`: once the quote is removed the dash is the last character,
    # and requiring whitespace after it left `3장 휴가 —` as the location.
    # Whitespace is still required on the left, so `3-1절` stays intact.
    text = re.split(r"\s+[-–—]\s*", text, maxsplit=1)[0].strip().rstrip(",").strip()

    name, _, tail = text.partition(",")
    location = tail.strip()

    page = None
    page_match = _PAGE_RE.match(location)
    if page_match:
        page = int(page_match.group(1) or page_match.group(2))
        location = ""
    return {"name": name.strip(), "page": page, "location": location, "quote": quote}


def parse_wiki_links(content: str) -> list[str]:
    """Addresses of internal wiki links in a body.

    Relative resolution is gone with the category directories: every page sits in
    `pages/`, so a link is `](a3f2c1d4.md)` or `](pages/a3f2c1d4.md)`.
    """
    addresses = []
    for href in _WIKI_LINK_RE.findall(content):
        if href.startswith(("http", "#", "mailto:", "data:")):
            continue
        if re.search(r"\.(png|jpg|jpeg|gif|webp|svg)$", href, re.IGNORECASE):
            continue
        address = normalize_address(href.split("#", 1)[0])
        if address:
            addresses.append(address)
    return addresses


async def build_edges(fs: VaultFS, scope_id: str, source_address: str,
                      content: str) -> list[dict]:
    """Resolve every footnote and body link to an edge. Unresolvable ones are
    dropped here and reported by lint, which can explain why."""
    edges: list[dict] = []

    for footnote, raw in _CITATION_RE.findall(content):
        parsed = parse_citation(raw)
        target = await fs.find_source(scope_id, parsed["name"])
        if not target or target["address"] == source_address:
            continue
        edges.append({
            "targetAddress": target["address"], "type": "cites", "footnote": footnote,
            "location": parsed["location"] or None, "quote": parsed["quote"],
            "page": parsed["page"],
        })

    seen = set()
    for address in parse_wiki_links(content):
        target = await fs.get(scope_id, address)
        if not target or target["address"] == source_address or target["address"] in seen:
            continue
        seen.add(target["address"])
        edges.append({"targetAddress": target["address"], "type": "links_to",
                      "footnote": None, "location": None, "quote": None, "page": None})
    return edges


async def sync_references(fs: VaultFS, scope_id: str, source_address: str,
                          content: str) -> None:
    edges = await build_edges(fs, scope_id, source_address, content)
    await fs.replace_references(scope_id, source_address, edges)
    await fs.propagate_staleness(scope_id, source_address)
    logger.info(
        "references %s: cites %d, links %d",
        source_address,
        sum(1 for e in edges if e["type"] == "cites"),
        sum(1 for e in edges if e["type"] == "links_to"),
    )


async def backlinks_summary(fs: VaultFS, scope_id: str, address: str) -> str:
    """Appended when reading a page so the incoming graph is visible."""
    rows = await fs.get_backlinks(scope_id, address)
    if not rows:
        return ""
    lines = [f"\n---\n**이 페이지를 참조하는 곳 ({len(rows)}):**"]
    for r in rows:
        kind = "인용" if r["reference_type"] == "cites" else "링크"
        lines.append(f"  - {r['address']} ({r['title'] or r['address']}) — {kind}")
    return "\n".join(lines)
