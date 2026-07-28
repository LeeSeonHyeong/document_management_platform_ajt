"""Pure functions. No DB, no state.

Addresses are relative to `wiki/{scopeKey}/` (`docs/AJT 파일 디렉터리 구조 설계.md`):

    pages/a3f2c1d4.md                    a wiki page
    index.md                             the scope's table of contents
    sources/101/parsed/content.md         a parsed upload

No category and no uploaded filename appears in a path (DR-016, DR-019).
"""

from fnmatch import fnmatch

from wiki_mcp.config import settings
from wiki_mcp.vaultfs import INDEX_ADDRESS, PAGES_PREFIX, SOURCES_PREFIX

MAX_LIST = 50
MAX_SEARCH = 20

MATCH_ALL = frozenset({"*", "**", "**/*"})


def deep_link(scope_key: str, address: str) -> str:
    return f"{settings.APP_URL}/wiki/{scope_key}/{address}"


def glob_match(address: str, pattern: str) -> bool:
    """Match an address against a glob.

    Both sides are normalised without a leading slash, because the agent will
    write `/pages/*` as readily as `pages/*` and neither should silently miss.
    """
    return fnmatch(address.lstrip("/"), pattern.lstrip("/"))


def normalize_address(raw: str) -> str:
    """Accept the shapes an agent actually types and return the canonical one.

    `/pages/x.md`, `pages/x.md` and a bare `x.md` all mean the same page; a bare
    key is what shows up in a body link like `](a3f2c1d4.md)`.
    """
    address = (raw or "").strip().lstrip("/")
    if not address:
        return address
    if address == INDEX_ADDRESS or address.startswith((PAGES_PREFIX, SOURCES_PREFIX)):
        return address
    if address.endswith(".md") and "/" not in address:
        return f"{PAGES_PREFIX}{address}"
    return address


def is_page(address: str) -> bool:
    return address.startswith(PAGES_PREFIX)


def parse_page_range(pages_str: str, max_page: int) -> list[int]:
    result = set()
    for part in pages_str.split(","):
        part = part.strip()
        if "-" in part:
            start, end = (p.strip() for p in part.split("-", 1))
            if not start.isdigit() or not end.isdigit():
                continue
            for p in range(max(1, int(start)), min(max_page, int(end)) + 1):
                result.add(p)
        elif part.isdigit():
            p = int(part)
            if 1 <= p <= max_page:
                result.add(p)
    return sorted(result)


def label(doc: dict) -> str:
    """How a document is named in tool output."""
    if doc.get("kind") == "source":
        return doc.get("original_file_name") or doc["address"]
    return doc.get("title") or doc["address"]
