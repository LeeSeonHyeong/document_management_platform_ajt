"""Browse, search, and query the reference graph. From lucas-llmwiki
`mcp/tools/search.py`.

This tool is why the wiki does not have to be injected into the prompt. The
earlier spike fed the agent the whole wiki as 300-character excerpts every step:
114k input tokens over 12 documents, and merge never fired once, because an
excerpt cannot tell you whether two pages overlap. Here the agent searches, then
reads the bodies it needs.

Changed here: addresses instead of paths, the annotation scoping arguments are
gone, and the page listing is grouped by category — the axis `wiki_category` and
the table of contents (WIKI-03) are built on.
"""

from __future__ import annotations

import logging
from typing import Literal

from mcp.server.fastmcp import Context, FastMCP

from wiki_mcp.telemetry import record_search
from wiki_mcp.vaultfs import VaultError, VaultFS

from .helpers import MATCH_ALL, MAX_LIST, MAX_SEARCH, deep_link, glob_match, label, normalize_address

logger = logging.getLogger(__name__)

_CONTEXT_CHARS = 120


def _snippet(content: str, query: str) -> str:
    if not content:
        return "(빈 내용)"
    idx = content.lower().find(query.lower())
    if idx < 0:
        return content[: _CONTEXT_CHARS * 2].strip()
    start = max(0, idx - _CONTEXT_CHARS)
    end = min(len(content), idx + len(query) + _CONTEXT_CHARS)
    text = content[start:end].strip()
    return ("..." if start > 0 else "") + text + ("..." if end < len(content) else "")


class SearchHandler:
    def __init__(self, fs: VaultFS, scope: dict):
        self.fs = fs
        self.scope_id = str(scope["id"])
        self.scope_key = scope["scope_key"]

    async def browse(self, pattern: str, tags: list[str] | None) -> str:
        docs = await self.fs.list_documents(self.scope_id)
        if pattern not in MATCH_ALL:
            docs = [d for d in docs if glob_match(d["address"], pattern)]
        if tags:
            wanted = {t.lower() for t in tags}
            docs = [d for d in docs if wanted.issubset({t.lower() for t in (d.get("tags") or [])})]
        if not docs:
            return f"`{pattern}`에 해당하는 것이 {self.scope_key} 범위에 없다."

        sources = [d for d in docs if d["kind"] == "source"]
        pages = [d for d in docs if d["kind"] in ("page", "index")]

        lines = [f"**{self.scope_key}** 범위:\n"]
        if sources:
            lines.append(f"**원본문서 ({len(sources)}):**")
            for d in sources[:MAX_LIST]:
                lines.append(f"  {d['address']} — 각주에 쓸 문서명 `{d.get('original_file_name')}`")
            if len(sources) > MAX_LIST:
                lines.append(f"  ... {len(sources) - MAX_LIST}건 더")

        if pages:
            if sources:
                lines.append("")
            lines.append(f"**위키 ({len(pages)}페이지):**")
            by_category: dict[str, list[dict]] = {}
            for d in pages[:MAX_LIST]:
                by_category.setdefault(d.get("category") or "(미분류)", []).append(d)
            for category, group in sorted(by_category.items()):
                lines.append(f"  [{category}]")
                lines.extend(f"    {d['address']} — {label(d)}" for d in group)
            if len(pages) > MAX_LIST:
                lines.append(f"  ... {len(pages) - MAX_LIST}건 더")
        return "\n".join(lines)

    async def search(self, query: str, pattern: str, tags: list[str] | None, limit: int) -> str:
        matches = await self.fs.search_chunks(
            self.scope_id, query, limit, self._kind_filter(pattern)
        )
        if pattern not in MATCH_ALL and ("*" in pattern or "?" in pattern):
            matches = [m for m in matches if glob_match(m["address"], pattern)]
        if tags:
            wanted = {t.lower() for t in tags}
            matches = [m for m in matches
                       if wanted.issubset({t.lower() for t in (m.get("tags") or [])})]
        # 질의 문자열을 남긴다 — 기본은 꺼져 있고 `--query-log` 를 준 세션에서만 쓴다
        # (`wiki_mcp/telemetry.py`). 필터를 거친 뒤 세는 이유는 에이전트가 실제로 본 건수가
        # 그것이기 때문이다. 0건도 남긴다 — 못 찾은 질의가 가장 중요한 신호다.
        record_search(query, len(matches), scope_key=self.scope_key)

        if not matches:
            return f"`{query}`에 해당하는 것이 {self.scope_key} 범위에 없다."

        lines = [f"**{len(matches)}건** — `{query}`:\n"]
        # `origin` 이 없으면 과도기 push 경로다 — 지금까지처럼 한 덩어리로 보여준다.
        # 창구 경로에서만 나눈다 (설계 §7.1). 섞으면 에이전트가 자기 초안과 라이브를
        # 구분하지 못해 남의 페이지를 자기 것으로 착각한다.
        if not any("origin" in m for m in matches):
            lines.extend(self._match_lines(matches, query))
            return "\n".join(lines)

        work = [m for m in matches if m.get("origin") == "work"]
        live = [m for m in matches if m.get("origin") != "work"]
        if work:
            lines.append(f"**작업 중 ({len(work)}건)** — 이번 작업에서 쓴 것이다.\n")
            lines.extend(self._match_lines(work, query))
        if live:
            lines.append(f"**반영된 위키 ({len(live)}건)** — 이미 서비스 중이다.\n")
            lines.extend(self._match_lines(live, query))
        return "\n".join(lines)

    def _match_lines(self, matches: list[dict], query: str) -> list[str]:
        """건별 렌더링. `search` 에 있던 루프를 그대로 옮긴 것이다 — 형식 변경 없음."""
        lines = []
        for m in matches:
            page = f" ({m['page']}쪽)" if m.get("page") else ""
            crumb = f"\n  {m['header_breadcrumb']}" if m.get("header_breadcrumb") else ""
            link = deep_link(self.scope_key, m["address"])
            lines.append(
                f"**{m['address']}**{page} — {label(m)} [보기]({link}){crumb}\n"
                f"```\n{_snippet(m.get('content', ''), query)}\n```\n"
            )
        return lines

    async def references(self, path: str, query: str) -> str:
        if query == "uncited":
            return await self._uncited()
        if query == "stale":
            return await self._stale()
        return await self._document_references(path)

    async def _uncited(self) -> str:
        """Sources no page cites.

        FR-WIKI-009: an uploaded document that landed nowhere is silent data
        loss, and this is what surfaces it. A prompt variant in the earlier spike
        dropped a whole document and nothing noticed.
        """
        rows = await self.fs.find_uncited_sources(self.scope_id)
        if not rows:
            return "모든 원본문서가 최소 1개 위키에서 인용되고 있다."
        lines = [f"**인용되지 않은 원본문서 {len(rows)}건** — 어느 위키에도 반영되지 않았다:\n"]
        lines.extend(f"  {r['address']} — `{r.get('original_file_name')}`" for r in rows)
        return "\n".join(lines)

    async def _stale(self) -> str:
        rows = await self.fs.find_stale_pages(self.scope_id)
        if not rows:
            return "오래된 페이지 없음."
        lines = [f"**오래됐을 수 있는 페이지 {len(rows)}건** — 참조하는 페이지가 그 뒤에 바뀌었다:\n"]
        lines.extend(f"  {r['address']} ({r['title'] or r['address']}) — "
                     f"{r['stale_since'] or '?'} 이후" for r in rows)
        return "\n".join(lines)

    async def _document_references(self, path: str) -> str:
        if not path or path in MATCH_ALL:
            return ('references 모드는 `path`가 필요하다. 아니면 `query="uncited"` 또는 '
                    '`query="stale"`을 쓴다.')
        address = normalize_address(path)
        doc = await self.fs.get(self.scope_id, address) or \
            await self.fs.find_source(self.scope_id, address)
        if not doc:
            return f"`{address}`를 찾을 수 없다."

        forward = await self.fs.get_forward_references(self.scope_id, doc["address"])
        backlinks = await self.fs.get_backlinks(self.scope_id, doc["address"])
        lines = [f"**{label(doc)}의 관계** (`{doc['address']}`):\n"]

        cites = [r for r in forward if r["reference_type"] == "cites"]
        links = [r for r in forward if r["reference_type"] == "links_to"]
        if cites:
            lines.append(f"**인용 ({len(cites)}건):**")
            for r in cites:
                where = r.get("location") or (f"{r['page']}쪽" if r.get("page") else "")
                quote = f' — "{r["quote"]}"' if r.get("quote") else ""
                lines.append(f"  [^{r.get('footnote_label')}] {r.get('original_file_name')}"
                             f"{', ' + where if where else ''}{quote}")
        if links:
            lines.append(f"\n**연결 ({len(links)}페이지):**")
            lines.extend(f"  {r['address']} ({r['title'] or r['address']})" for r in links)
        if not forward:
            lines.append("나가는 관계 없음.")

        lines.append("")
        if backlinks:
            lines.append(f"**이 페이지를 참조하는 곳 ({len(backlinks)}):**")
            for r in backlinks:
                kind = "인용" if r["reference_type"] == "cites" else "링크"
                lines.append(f"  {r['address']} ({r['title'] or r['address']}) — {kind}")
        else:
            lines.append("들어오는 관계 없음 (고아 페이지).")
        return "\n".join(lines)

    def _kind_filter(self, pattern: str) -> str | None:
        if pattern in MATCH_ALL:
            return None
        normalized = pattern.lstrip("/")
        if normalized.startswith(("pages", "index")):
            return "wiki"
        if normalized.startswith("sources"):
            return "sources"
        return None


def register(mcp: FastMCP, get_scope_key, fs_factory) -> None:

    @mcp.tool(
        name="search",
        description=(
            "범위 안의 원본문서와 위키를 훑거나 검색한다.\n\n"
            "모드:\n"
            "- list: 원본문서·위키 목록 (위키는 카테고리별로 묶어 보여준다)\n"
            "- search: 본문 전문 검색 (청크 단위라 어느 절에서 나왔는지까지 나온다)\n"
            "- references: 인용·연결 그래프 조회\n\n"
            "references 예:\n"
            '- `search(mode="references", path="pages/a3f2c1d4.md")` — 각주별 인용과 역참조\n'
            '- `search(mode="references", query="uncited")` — 어느 위키에도 반영 안 된 원본문서\n'
            '- `search(mode="references", query="stale")` — 오래됐을 수 있는 페이지\n\n'
            "`path`로 좁힌다: `pages/*`, `sources/*`, `index.md`.\n\n"
            "**새 원본문서를 반영하기 전에 이 도구로 겹치는 페이지를 먼저 찾는다.** "
            "목록만 보고 판단하지 말고 후보는 `read`로 본문을 읽는다."
        ),
    )
    async def search(ctx: Context, scope: str,
                     mode: Literal["list", "search", "references"] = "list",
                     query: str = "", path: str = "*", tags: list[str] | None = None,
                     limit: int = 10) -> str:
        fs = fs_factory(get_scope_key(ctx))
        row = await fs.resolve_scope(scope)
        if not row:
            return f"범위 '{scope}'를 찾을 수 없다."
        handler = SearchHandler(fs, row)
        try:
            if mode == "list":
                return await handler.browse(path, tags)
            if mode == "search":
                if not query:
                    return "search 모드는 query가 필요하다."
                return await handler.search(query, path, tags, min(limit, MAX_SEARCH))
            return await handler.references(path, query)
        except VaultError as exc:
            return f"오류: {exc}"
