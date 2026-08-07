"""Read a page or a source. From lucas-llmwiki `mcp/tools/read.py`.

Changed here: addresses instead of filesystem paths, and images, spreadsheets,
webclip assets and the highlight appendix are gone — no annotation feature and
OCR is a separate spike. Page-range reading stays, because a parsed PDF lands in
`document_pages` and a citation needs the page number.

A source read shows its original filename, because the path no longer carries it
(DR-016) and a footnote has to name it.
"""

from __future__ import annotations

import logging

from mcp.server.fastmcp import Context, FastMCP

from wiki_mcp.vaultfs import VaultError, VaultFS

from .helpers import MATCH_ALL, deep_link, glob_match, label, normalize_address, parse_page_range
from .references import backlinks_summary

logger = logging.getLogger(__name__)

MAX_BATCH_CHARS = 120_000


def _extract_sections(content: str, wanted: list[str]) -> str:
    sections: list[tuple[str, str]] = []
    current: str | None = None
    lines: list[str] = []
    for line in content.split("\n"):
        if line.startswith("#"):
            if current and lines:
                sections.append((current, "\n".join(lines)))
            current = line.lstrip("#").strip()
            lines = [line]
        elif current:
            lines.append(line)
    if current and lines:
        sections.append((current, "\n".join(lines)))

    names = {s.lower() for s in wanted}
    matched = [text for name, text in sections if name.lower() in names]
    if matched:
        return "\n\n".join(matched)
    # 미스 응답에 실제 절 목록을 싣는다 (S15P11B106-315). "없다"만 돌려주면 모델이 이름을
    # 다시 추측하거나 전문 재읽기로 후퇴한다 — 2026-08-07 read 53연속 반복 사고의 직전
    # 행동이 정확히 그것이었다. 목록이 있으면 한 번에 교정한다.
    if not sections:
        return f"{wanted}에 해당하는 절이 없다. 이 페이지에는 절 제목이 없다 — 전문을 읽는다."
    available = ", ".join(name for name, _ in sections)
    return f"{wanted}에 해당하는 절이 없다. 이 페이지의 절: {available}"


class ReadHandler:
    def __init__(self, fs: VaultFS, scope: dict):
        self.fs = fs
        self.scope_id = str(scope["id"])
        self.scope_key = scope["scope_key"]

    async def read(self, path: str, pages: str, sections: list[str] | None) -> str:
        if "*" in path or "?" in path:
            return await self._batch(path)
        return await self._single(normalize_address(path), pages, sections)

    async def _single(self, address: str, pages: str, sections: list[str] | None) -> str:
        doc = await self.fs.get(self.scope_id, address)
        if not doc:
            # A citation names a filename, so a lookup by name has to work too.
            doc = await self.fs.find_source(self.scope_id, address)
        if not doc:
            return f"`{address}`를 {self.scope_key} 범위에서 찾을 수 없다."

        header = self._header(doc)
        if doc["kind"] == "source" and pages and (doc.get("page_count") or 0) > 0:
            return header + await self._pages(doc, pages)

        content = doc.get("content") or ""
        if sections:
            content = _extract_sections(content, sections)
        return header + content + await backlinks_summary(
            self.fs, self.scope_id, doc["address"])

    async def _batch(self, pattern: str) -> str:
        docs = await self.fs.list_documents(self.scope_id, with_content=True)
        if pattern not in MATCH_ALL:
            docs = [d for d in docs if glob_match(d["address"], pattern)]
        if not docs:
            return f"`{pattern}`에 해당하는 것이 {self.scope_key} 범위에 없다."

        parts: list[str] = []
        used = 0
        truncated = skipped = 0
        for doc in docs:
            if used >= MAX_BATCH_CHARS:
                skipped += 1
                continue
            content = doc.get("content") or ""
            if not content:
                skipped += 1
                continue
            remaining = MAX_BATCH_CHARS - used
            if len(content) > remaining:
                content = content[:remaining] + "\n\n... (잘림)"
                truncated += 1
            link = deep_link(self.scope_key, doc["address"])
            parts.append(f"### [{doc['address']}]({link}) — {label(doc)}\n\n{content}")
            used += len(content)

        head = f"**{len(parts)}건** — `{pattern}`"
        if truncated:
            head += f" ({MAX_BATCH_CHARS:,}자 예산에 맞춰 일부 잘림)"
        if skipped:
            head += f"\n*{skipped}건 제외 — 개별로 읽는다*"
        return head + "\n\n---\n\n" + "\n\n---\n\n".join(parts)

    async def _pages(self, doc: dict, pages_str: str) -> str:
        max_page = doc.get("page_count") or 1
        nums = parse_page_range(pages_str, max_page)
        if not nums:
            return f"잘못된 쪽 범위: {pages_str} (문서는 {max_page}쪽)"
        rows = await self.fs.get_source_pages(doc["id"], nums)
        if not rows:
            return f"{pages_str}쪽 데이터가 없다."
        return "\n\n".join(f"**— {r['page']}쪽 —**\n\n{r['content']}" for r in rows)

    def _header(self, doc: dict) -> str:
        link = deep_link(self.scope_key, doc["address"])
        lines = [f"**{label(doc)}**"]
        if doc["kind"] == "source":
            # The name a footnote must use, spelled out.
            lines.append(
                f"원본문서 | 문서 ID: {doc.get('source_id') or '-'} | "
                f"각주에 쓸 문서명: `{doc.get('original_file_name') or '-'}`"
            )
            if doc.get("page_count"):
                lines.append(f"{doc['page_count']}쪽")
        else:
            tags = ", ".join(doc.get("tags") or []) or "없음"
            lines.append(
                f"주소: `{doc['address']}` | 카테고리: {doc.get('category') or '-'} | "
                f"태그: {tags} | 버전: {doc.get('version', 0)}"
            )
        lines.append(f"[보기]({link})")
        return "\n".join(lines) + "\n\n---\n\n"


def register(mcp: FastMCP, get_scope_key, fs_factory) -> None:

    @mcp.tool(
        name="read",
        description=(
            "원본문서나 위키 페이지의 본문을 읽는다.\n\n"
            "주소 하나 또는 glob:\n"
            '- `path="sources/101/parsed/content.md"` — 원본문서 (문서명으로도 찾는다)\n'
            '- `path="pages/a3f2c1d4.md"` — 위키 페이지\n'
            '- `path="index.md"` — 목차\n'
            '- `path="pages/*"` — 위키 페이지 전부\n\n'
            "**glob 읽기를 아끼지 말 것.** 위키 전체를 한 번에 보는 게 목록만 여러 번 보는 것보다 낫다 "
            f"({MAX_BATCH_CHARS:,}자 예산 안에서 자동으로 자른다).\n\n"
            'PDF는 `pages`로 쪽 범위를 지정한다 (예: `"1-50"`). 각주에 쪽 번호를 적어야 하니 '
            "어느 쪽에서 읽었는지 기억한다.\n"
            "`sections`로 특정 `##` 절만 뽑을 수 있다.\n"
            "위키 페이지를 읽으면 그 페이지를 참조하는 곳 목록이 끝에 붙는다."
        ),
        structured_output=False,
    )
    async def read(ctx: Context, scope: str, path: str, pages: str = "",
                   sections: list[str] | None = None) -> str:
        fs = fs_factory(get_scope_key(ctx))
        row = await fs.resolve_scope(scope)
        if not row:
            return f"범위 '{scope}'를 찾을 수 없다."
        try:
            return await ReadHandler(fs, row).read(path, pages, sections)
        except VaultError as exc:
            return f"오류: {exc}"
