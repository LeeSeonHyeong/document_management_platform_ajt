"""Deterministic hygiene checks. From lucas-llmwiki `mcp/tools/lint.py`.

This is what makes a single-turn ingest verifiable: the agent is told to clear
every `error` before it finishes, and the harness can gate on the same output
without a human reading pages.

Kept from upstream: frontmatter completeness, footnote hygiene (duplicate,
undefined, unused, not-at-tail), citation resolution, dangling links, orphan
pages, uncited sources, stale pages.

Added here, each from a measured failure:
  * `missing-category` — `wiki.wiki_category_id` is NOT NULL, and a page with no
    category never appears in the table of contents.
  * `footnote-in-frontmatter` — a model copied its opening sentence, marker and
    all, into `description`, which then renders as a literal `[^1]`.
  * `citation-location-not-found` / `citation-quote-not-found` — **the check
    FR-WIKI-001 and NFR-AI-002 actually need.** Upstream verified only that the
    cited file existed. Verifying the location, and the verbatim quote against
    the source text, is what turns "a footnote is present" into "the claim is
    supported". It was the first item on the earlier spike's unverified list.

Dropped: the tag/date index-consistency and citation-graph-edge checks, which
guarded against an index drifting from content. Here content is the only writer.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Literal

from mcp.server.fastmcp import Context, FastMCP

from wiki_mcp.vaultfs import INDEX_ADDRESS, VaultError, VaultFS

from .helpers import MATCH_ALL, glob_match, label
from .references import parse_citation, parse_wiki_links
from .write import (
    extract_frontmatter_field,
    extract_frontmatter_tags,
    extract_metadata,
    is_footnote_suffix_line,
    parse_frontmatter,
)

_FOOTNOTE_DEF_RE = re.compile(r"^\[\^([^\]]+)\]:\s*(.+)$", re.MULTILINE)
_FOOTNOTE_USE_RE = re.compile(r"\[\^([^\]]+)\](?!:)")
_MAX_PER_GROUP = 40

# Below this a "location" is too short to check without false positives.
_MIN_LOCATION_CHARS = 2
# A quote shorter than this is a fragment, not a claim; matching it proves little
# and failing to match it would be noise.
_MIN_QUOTE_CHARS = 8

CheckScope = Literal["all", "wiki", "sources"]


@dataclass(frozen=True)
class LintIssue:
    severity: Literal["error", "warn"]
    code: str
    address: str
    message: str


class LintHandler:
    def __init__(self, fs: VaultFS, scope: dict):
        self.fs = fs
        self.scope_id = str(scope["id"])
        self.scope_key = scope["scope_key"]

    async def run(self, pattern: str = "*", check_scope: CheckScope = "all",
                  include_graph: bool = True) -> str:
        docs = await self.fs.list_documents(self.scope_id, with_content=True)
        selected = self._select(docs, pattern, check_scope)
        if not selected:
            return f"`{pattern}`에 해당하는 것이 {self.scope_key} 범위에 없다."

        wiki_docs = [d for d in docs if d["kind"] in ("page", "index")]
        issues: list[LintIssue] = []
        for doc in selected:
            if doc["kind"] in ("page", "index"):
                issues.extend(await self._lint_page(doc, wiki_docs, include_graph))

        if include_graph:
            issues.extend(i for i in await self._uncited() if self._matches(i.address, pattern))
            if check_scope in ("all", "wiki"):
                issues.extend(i for i in await self._stale() if self._matches(i.address, pattern))

        return self._report(issues, selected)

    # ----- selection --------------------------------------------------------

    def _select(self, docs: list[dict], pattern: str, check_scope: CheckScope) -> list[dict]:
        if check_scope == "wiki":
            docs = [d for d in docs if d["kind"] in ("page", "index")]
        elif check_scope == "sources":
            docs = [d for d in docs if d["kind"] == "source"]
        return [d for d in docs if self._matches(d["address"], pattern)]

    def _matches(self, address: str, pattern: str) -> bool:
        return pattern in MATCH_ALL or glob_match(address, pattern)

    # ----- per page ---------------------------------------------------------

    async def _lint_page(self, doc: dict, wiki_docs: list[dict],
                         include_graph: bool) -> list[LintIssue]:
        address = doc["address"]
        content = doc.get("content") or ""
        meta = parse_frontmatter(content)

        issues = self._lint_frontmatter(address, meta)
        issues += self._lint_footnotes(address, content)
        issues += await self._lint_citations(address, content)
        issues += self._lint_links(address, content, wiki_docs)
        if include_graph and address != INDEX_ADDRESS:
            issues += await self._lint_orphan(doc, len(wiki_docs))
        return issues

    def _lint_frontmatter(self, address: str, meta: dict) -> list[LintIssue]:
        if not meta:
            return [LintIssue("error", "missing-frontmatter", address, "frontmatter가 없다")]

        issues: list[LintIssue] = []
        title = extract_frontmatter_field(meta, "title")
        description = extract_frontmatter_field(meta, "description")
        fm_date, _ = extract_metadata(meta)
        tags = extract_frontmatter_tags(meta)
        category = extract_frontmatter_field(meta, "category")

        if not title:
            issues.append(LintIssue("error", "missing-title", address, "title이 없다"))
        if not description:
            issues.append(LintIssue("warn", "missing-description", address, "description이 없다"))
        elif _FOOTNOTE_USE_RE.search(description):
            issues.append(LintIssue("error", "footnote-in-frontmatter", address,
                                    "description에 각주 표시가 들어 있다"))
        if not fm_date:
            issues.append(LintIssue("warn", "missing-date", address, "date가 없다"))
        if tags is None:
            issues.append(LintIssue("error", "missing-tags", address, "tags가 없다"))
        elif len(tags) < 2:
            issues.append(LintIssue("warn", "too-few-tags", address, "tags를 2개 이상 단다"))
        if not category:
            issues.append(LintIssue("error", "missing-category", address,
                                    "category가 없다 — 목차에 나오지 않는다"))
        return issues

    def _lint_footnotes(self, address: str, content: str) -> list[LintIssue]:
        issues: list[LintIssue] = []
        defined = [fid for fid, _ in _FOOTNOTE_DEF_RE.findall(content)]
        used = _FOOTNOTE_USE_RE.findall(content)

        for fid in sorted({f for f in defined if defined.count(f) > 1}, key=self._sort_key):
            issues.append(LintIssue("error", "duplicate-footnote", address,
                                    f"각주 `^{fid}`가 두 번 이상 정의됐다"))
        for fid in sorted(set(used) - set(defined), key=self._sort_key):
            issues.append(LintIssue("error", "footnote-without-definition", address,
                                    f"각주 `^{fid}`를 썼는데 정의가 없다"))
        for fid in sorted(set(defined) - set(used), key=self._sort_key):
            issues.append(LintIssue("warn", "unused-footnote-definition", address,
                                    f"각주 `^{fid}` 정의가 쓰이지 않는다"))
        if self._footnotes_mid_document(content):
            issues.append(LintIssue("warn", "footnotes-not-at-tail", address,
                                    "각주 정의는 문서 끝에 모은다"))
        return issues

    async def _lint_citations(self, address: str, content: str) -> list[LintIssue]:
        """Every footnote resolves to a source, a location in it, and — when the
        footnote quotes the source — to that exact text."""
        issues: list[LintIssue] = []
        for footnote, raw in _FOOTNOTE_DEF_RE.findall(content):
            parsed = parse_citation(raw)
            target = await self.fs.find_source(self.scope_id, parsed["name"])
            if not target:
                issues.append(LintIssue(
                    "error", "unresolved-citation", address,
                    f"각주 `^{footnote}`가 `{parsed['name']}`을 가리키는데 그런 원본문서가 없다",
                ))
                continue

            source_text = (target.get("content") or "")
            if not parsed["location"] and parsed["page"] is None and not parsed["quote"]:
                issues.append(LintIssue(
                    "warn", "citation-without-location", address,
                    f"각주 `^{footnote}`에 위치가 없다 — 절 제목이나 쪽 번호를 붙인다",
                ))

            problem = await self._check_page(target, parsed)
            if problem:
                issues.append(LintIssue("error", "citation-location-not-found", address,
                                        f"각주 `^{footnote}`: {problem}"))
                continue
            if self._location_missing(parsed["location"], source_text):
                issues.append(LintIssue(
                    "error", "citation-location-not-found", address,
                    f"각주 `^{footnote}`: `{parsed['name']}`에서 `{parsed['location']}`을 "
                    "찾을 수 없다",
                ))
            if self._quote_missing(parsed["quote"], source_text):
                issues.append(LintIssue(
                    "error", "citation-quote-not-found", address,
                    f"각주 `^{footnote}`: 인용문 \"{parsed['quote'][:40]}\"이 "
                    f"`{parsed['name']}` 원문에 없다",
                ))
        return issues

    async def _check_page(self, target: dict, parsed: dict) -> str | None:
        page = parsed["page"]
        if page is None:
            return None
        page_count = target.get("page_count") or 0
        if page_count and page > page_count:
            return f"`{parsed['name']}`은 {page_count}쪽까지인데 {page}쪽을 가리킨다"
        if page_count and not await self.fs.get_source_pages(target["id"], [page]):
            return f"`{parsed['name']}`의 {page}쪽 데이터가 없다"
        return None

    def _location_missing(self, location: str, source_text: str) -> bool:
        """A section name may be worded differently, so require every word to be
        absent before calling it wrong. A false error here teaches the agent to
        ignore lint."""
        if len(location) < _MIN_LOCATION_CHARS or not source_text:
            return False
        haystack = source_text.lower()
        if location.lower() in haystack:
            return False
        words = [w for w in re.split(r"[\s·,]+", location) if len(w) >= _MIN_LOCATION_CHARS]
        return bool(words) and not all(w.lower() in haystack for w in words)

    def _quote_missing(self, quote: str | None, source_text: str) -> bool:
        """Verbatim match, whitespace-normalised.

        Not word-by-word like a location: a quote claims to be the source's own
        words. Ellipses are how a model elides, so each segment is checked
        separately.
        """
        if not quote or len(quote) < _MIN_QUOTE_CHARS or not source_text:
            return False
        haystack = re.sub(r"\s+", " ", source_text).lower()
        for segment in re.split(r"\s*(?:\.\.\.|…)\s*", quote):
            segment = re.sub(r"\s+", " ", segment).strip().lower()
            if len(segment) < _MIN_QUOTE_CHARS:
                continue
            if segment not in haystack:
                return True
        return False

    def _lint_links(self, address: str, content: str, wiki_docs: list[dict]) -> list[LintIssue]:
        """Body links resolve, and never leave this scope.

        A link is safe only because every page in one scope has identical
        visibility. One that left the scope would leak the title of a page the
        reader cannot see (FR-ACL-006, NFR-SEC-003), and a body link cannot be
        filtered per viewer. One server serves one scope, so a link resolving to
        nothing here is either broken or cross-scope — both errors.
        """
        known = {d["address"] for d in wiki_docs}
        issues: list[LintIssue] = []
        for target in parse_wiki_links(content):
            if target not in known:
                issues.append(LintIssue(
                    "error", "dangling-link", address,
                    f"본문 링크 `{target}`가 이 범위의 어떤 페이지도 가리키지 않는다",
                ))
        return issues

    async def _lint_orphan(self, doc: dict, wiki_count: int) -> list[LintIssue]:
        if wiki_count <= 1:
            return []
        if await self.fs.get_backlinks(self.scope_id, doc["address"]):
            return []
        return [LintIssue("warn", "orphan-page", doc["address"],
                          "들어오는 링크도 인용도 없다 — 목차에서 닿을 수 없다")]

    # ----- graph-wide -------------------------------------------------------

    async def _uncited(self) -> list[LintIssue]:
        """A document cited by no page is knowledge that never landed — an error,
        not a warning. It is the failure a prompt variant produced when it
        dropped a whole document silently."""
        return [
            LintIssue("error", "uncited-source", r["address"],
                      f"`{r.get('original_file_name')}`을 인용하는 위키 페이지가 없다")
            for r in await self.fs.find_uncited_sources(self.scope_id)
        ]

    async def _stale(self) -> list[LintIssue]:
        return [
            LintIssue("warn", "stale-page", r["address"],
                      f"{r.get('stale_since') or '?'} 이후 오래됐을 수 있다")
            for r in await self.fs.find_stale_pages(self.scope_id)
            if r["address"] != INDEX_ADDRESS
        ]

    # ----- report -----------------------------------------------------------

    def _report(self, issues: list[LintIssue], checked: list[dict]) -> str:
        if not issues:
            return f"**lint 통과** — {self.scope_key} 범위, {len(checked)}건 검사."
        errors = [i for i in issues if i.severity == "error"]
        warnings = [i for i in issues if i.severity == "warn"]
        lines = [
            f"**lint {len(issues)}건** — {self.scope_key} 범위 "
            f"(error {len(errors)}, warn {len(warnings)}; {len(checked)}건 검사)."
        ]
        if errors:
            lines.append("\n**Errors** — 끝내기 전에 모두 고친다")
            lines += self._lines(errors)
        if warnings:
            lines.append("\n**Warnings**")
            lines += self._lines(warnings)
        return "\n".join(lines)

    def _lines(self, issues: list[LintIssue]) -> list[str]:
        out = [f"- [{i.code}] `{i.address}` — {i.message}" for i in issues[:_MAX_PER_GROUP]]
        if len(issues) > _MAX_PER_GROUP:
            out.append(f"- ... {len(issues) - _MAX_PER_GROUP}건 더")
        return out

    def _footnotes_mid_document(self, content: str) -> bool:
        lines = content.rstrip().splitlines()
        for idx, line in enumerate(lines):
            if _FOOTNOTE_DEF_RE.match(line):
                return not all(is_footnote_suffix_line(s) for s in lines[idx + 1:])
        return False

    def _sort_key(self, value: str) -> tuple[int, str]:
        return (0, f"{int(value):08d}") if value.isdigit() else (1, value)


def register(mcp: FastMCP, get_scope_key, fs_factory) -> None:

    @mcp.tool(
        name="lint",
        description=(
            "위키를 기계적으로 점검한다.\n\n"
            "검사 항목: frontmatter 필수 항목(title·tags·category), 각주 위생(중복·정의 없음·"
            "안 쓰임·끝에 안 모임), **인용이 실제 원본문서와 그 안의 위치를 가리키는지, 인용문이 "
            "원문에 그대로 있는지**, 본문 링크가 이 범위 안에서 해결되는지, 고아 페이지, "
            "인용 안 된 원본문서, 오래된 페이지.\n\n"
            "**작업을 끝내기 전에 반드시 부르고 `error`는 전부 고친다.** `warn`은 이유가 있으면 남긴다.\n"
            '`path`로 좁힌다: `*`, `pages/*`, `pages/a3f2c1d4.md`'
        ),
    )
    async def lint(ctx: Context, scope: str, path: str = "*",
                   check_scope: CheckScope = "all", include_graph: bool = True) -> str:
        fs = fs_factory(get_scope_key(ctx))
        row = await fs.resolve_scope(scope)
        if not row:
            return f"범위 '{scope}'를 찾을 수 없다."
        try:
            return await LintHandler(fs, row).run(path, check_scope, include_graph)
        except VaultError as exc:
            return f"오류: {exc}"
