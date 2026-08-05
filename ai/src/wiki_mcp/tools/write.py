"""create / edit / append / merge — all into the job's work space.

From lucas-llmwiki `mcp/tools/write.py`. Three behaviours are the reason it was
ported rather than rewritten:

  * every write reports which pages reference the one just written, so a
    single-turn ingest still gets feedback instead of writing blind;
  * append inserts before the trailing footnote block, so citations stay at EOF;
  * appended footnote ids that collide get renumbered.

Changed here:
  * `create` does not take a path. The store allocates `pages/{pageKey}.md`, so
    the agent cannot misfile a page and the category stays out of the path
    (DR-019). `category` is required metadata instead.
  * Nothing touches the live tree. Writes land in `work/{jobId}/output`
    (DR-007/008), and the backend decides what is committed (API 10.2).
  * `merge` is explicit. A merge is an update plus deletions, and no amount of
    inspection can tell that apart from an unrelated removal — but FR-AI-009
    requires the summary to name it, so the agent says so.
"""

from __future__ import annotations

import re
from datetime import date

import yaml
from mcp.server.fastmcp import Context, FastMCP

from wiki_mcp.vaultfs import INDEX_ADDRESS, VaultError, VaultFS

from .helpers import deep_link, is_page, normalize_address
from .references import sync_references

_FRONTMATTER_RE = re.compile(r"\A---[ \t]*\n(.+?\n)---[ \t]*\n", re.DOTALL)
_FOOTNOTE_DEF_RE = re.compile(r"^\[\^([^\]]+)\]:", re.MULTILINE)
_CONTEXT_LINES = 5


def parse_frontmatter(content: str) -> dict:
    m = _FRONTMATTER_RE.match(content)
    if not m:
        return {}
    try:
        meta = yaml.safe_load(m.group(1))
        return meta if isinstance(meta, dict) else {}
    except yaml.YAMLError:
        return {}


def extract_metadata(meta: dict) -> tuple[str | None, dict]:
    """Return (date, metadata). Always a dict, so stale metadata gets cleared."""
    date_str = None
    if "date" in meta:
        d = meta["date"]
        date_str = d.isoformat() if hasattr(d, "isoformat") else str(d)
    metadata: dict = {}
    if isinstance(meta.get("description"), str) and meta["description"].strip():
        metadata["description"] = meta["description"].strip()
    return date_str, metadata


def extract_frontmatter_tags(meta: dict) -> list[str] | None:
    """None when there is no `tags` key at all — distinct from an empty list."""
    if "tags" not in meta:
        return None
    raw = meta.get("tags")
    if isinstance(raw, list):
        return [str(t).strip() for t in raw if str(t).strip()]
    if isinstance(raw, str):
        return [t.strip() for t in raw.split(",") if t.strip()]
    return []


def extract_frontmatter_field(meta: dict, name: str) -> str | None:
    value = meta.get(name)
    return value.strip() if isinstance(value, str) and value.strip() else None


def is_footnote_suffix_line(line: str) -> bool:
    return line.strip() == "" or line.startswith((" ", "\t")) or bool(_FOOTNOTE_DEF_RE.match(line))


def split_trailing_footnotes(content: str) -> tuple[str, str]:
    """Split into (body, trailing footnote block).

    Footnote definitions belong at EOF. Appending after them strands citations
    mid-document, so append inserts before the block.
    """
    stripped = content.rstrip()
    if not stripped:
        return "", ""
    lines = stripped.splitlines()
    for idx, line in enumerate(lines):
        if _FOOTNOTE_DEF_RE.match(line) and all(
            is_footnote_suffix_line(suffix) for suffix in lines[idx:]
        ):
            return "\n".join(lines[:idx]).rstrip(), "\n".join(lines[idx:]).rstrip()
    return stripped, ""


def append_markdown_section(existing: str, addition: str) -> str:
    addition = renumber_colliding_footnotes(existing, addition.strip("\n"))
    body, footnotes = split_trailing_footnotes(existing)
    return "\n\n".join(part for part in (body, addition, footnotes) if part)


def tidy_footnotes(content: str) -> str:
    """Move every footnote definition to the end, in order.

    `append` already protected the tail, but `create` and `edit` did not — the
    same asymmetry upstream has. It showed up on the first two-document run: the
    agent added a cross-reference to an existing page with `edit`, the end of that
    page was the footnote block, and a sentence landed after the definitions,
    where the markdown parser stops rendering them.

    Nothing is dropped and nothing is reordered among the definitions; only the
    block moves. Definitions inside a fenced code block are examples, not
    citations, and stay exactly where they are.
    """
    lines = content.splitlines()
    body: list[str] = []
    definitions: list[str] = []
    in_fence = False
    collecting = False

    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith(("```", "~~~")):
            in_fence = not in_fence
            body.append(line)
            collecting = False
            continue
        if in_fence:
            body.append(line)
            continue

        if _FOOTNOTE_DEF_RE.match(line):
            definitions.append(line)
            collecting = True
            continue
        # A definition can wrap onto indented continuation lines; those belong to
        # it. A blank line neither ends the block nor joins it.
        if collecting and line.startswith((" ", "\t")) and line.strip():
            definitions.append(line)
            continue
        if collecting and not line.strip():
            continue
        collecting = False
        body.append(line)

    if not definitions:
        return content

    return "\n".join(body).rstrip() + "\n\n" + "\n".join(definitions).rstrip() + "\n"


def renumber_colliding_footnotes(existing: str, addition: str) -> str:
    """Keep page-local footnote ids unique when appending."""
    existing_ids = set(_FOOTNOTE_DEF_RE.findall(existing))
    incoming_ids = _FOOTNOTE_DEF_RE.findall(addition)
    if not existing_ids or not incoming_ids:
        return addition

    numeric = [int(i) for i in existing_ids.union(incoming_ids) if i.isdigit()]
    next_id = max(numeric, default=0) + 1
    used = set(existing_ids)
    replacements: dict[str, str] = {}

    for footnote_id in incoming_ids:
        if footnote_id not in used:
            used.add(footnote_id)
            continue
        while str(next_id) in used:
            next_id += 1
        replacements[footnote_id] = str(next_id)
        used.add(str(next_id))
        next_id += 1

    for old, new in replacements.items():
        addition = re.sub(rf"\[\^{re.escape(old)}\]", f"[^{new}]", addition)
    return addition


def build_frontmatter(content: str, title: str, category: str, tags: list[str],
                      date_str: str) -> str:
    """Add frontmatter when the caller supplied metadata as arguments."""
    if _FRONTMATTER_RE.match(content):
        return content
    meta = {
        "title": title,
        "description": _default_description(content, title),
        "date": date_str.strip() or date.today().isoformat(),
        "tags": [str(t).strip() for t in tags if str(t).strip()],
        "category": category,
    }
    frontmatter = yaml.safe_dump(
        meta, sort_keys=False, allow_unicode=True, default_flow_style=False
    ).strip()
    return f"---\n{frontmatter}\n---\n\n{content.lstrip(chr(10))}"


def _default_description(content: str, title: str) -> str:
    for raw_line in content.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("[^"):
            continue
        line = re.sub(r"^#+\s*", "", line).strip()
        if line:
            # Strip footnote markers: frontmatter is metadata, and a marker there
            # renders as a literal `[^1]` in search results and the index.
            return re.sub(r"\[\^[^\]]+\]", "", line)[:180].strip()
    return f"{title} 관련 내용."


class WriteHandler:
    def __init__(self, fs: VaultFS, scope: dict):
        self.fs = fs
        self.scope = scope
        self.scope_id = str(scope["id"])
        self.scope_key = scope["scope_key"]

    async def create(self, title: str, content: str, tags: list[str], category: str,
                     date_str: str) -> str:
        if not title.strip():
            return "오류: title은 필수다."
        meta = parse_frontmatter(content)
        category = extract_frontmatter_field(meta, "category") or category.strip()
        if not category:
            return "오류: category는 필수다. Wiki 카테고리 없이는 목차에 나오지 않는다."

        effective_tags = extract_frontmatter_tags(meta) or tags or []
        if not effective_tags:
            return "오류: tag가 최소 1개 필요하다."

        content = build_frontmatter(content, title.strip(), category, effective_tags, date_str)
        address = await self.fs.allocate_page(self.scope_id)
        return await self._save(address, content, "생성", title=title.strip(),
                                category=category, tags=effective_tags)

    async def edit(self, address: str, old_text: str, new_text: str) -> str:
        if not old_text:
            return "오류: old_text는 필수다."
        address = normalize_address(address)
        doc = await self.fs.get(self.scope_id, address)
        if not doc:
            return f"`{address}`를 찾을 수 없다."

        content = doc.get("content") or ""
        count = content.count(old_text)
        if count == 0:
            return "오류: old_text와 일치하는 곳이 없다."
        if count > 1:
            return f"오류: old_text가 {count}곳에서 일치한다. 앞뒤 문맥을 더 붙여 한 곳만 가리키게 한다."

        start = content.index(old_text)
        new_content = content.replace(old_text, new_text, 1)
        result = await self._save(address, new_content, "수정")
        # Read the stored version back: `_save` may have moved the footnote block,
        # so the offsets computed above no longer describe what was written.
        saved = (await self.fs.get(self.scope_id, address) or {}).get("content") or new_content
        start = saved.find(new_text) if new_text else start
        snippet = self._context(saved, max(start, 0), len(new_text))
        return f"{result}\n\n**수정 후 문맥:**\n```\n{snippet}\n```"

    async def append(self, address: str, content: str) -> str:
        address = normalize_address(address)
        doc = await self.fs.get(self.scope_id, address)
        if not doc:
            return f"`{address}`를 찾을 수 없다."
        new_content = append_markdown_section(doc.get("content") or "", content)
        return await self._save(address, new_content, "추가")

    async def merge(self, into: str, absorbed: list[str], content: str) -> str:
        """Fold pages into one. `into` keeps the merged body; the rest go away."""
        into = normalize_address(into)
        target = await self.fs.get(self.scope_id, into)
        if not target:
            return f"남길 페이지 `{into}`를 찾을 수 없다."
        if not absorbed:
            return "오류: 흡수할 페이지를 1건 이상 지정한다."

        addresses = [normalize_address(a) for a in absorbed]
        if into in addresses:
            return "오류: 남길 페이지를 흡수 목록에 넣을 수 없다."

        missing = [a for a in addresses if not await self.fs.get(self.scope_id, a)]
        if missing:
            return f"찾을 수 없는 페이지: {', '.join(missing)}"

        result = await self._save(into, content, "병합")
        for address in addresses:
            doc = await self.fs.get(self.scope_id, address)
            # Recorded on the tombstone so the work-space diff can report this as
            # a merge rather than an unrelated removal (FR-AI-009).
            await self.fs.write(
                self.scope_id, address, doc.get("content") or "",
                metadata={**(doc.get("metadata") or {}), "mergedInto": into},
            )
            await self.fs.remove(self.scope_id, address)
        return f"{result}\n흡수해 제거: {', '.join(addresses)}"

    async def _save(self, address: str, content: str, verb: str, *, title: str | None = None,
                    category: str | None = None, tags: list[str] | None = None) -> str:
        # Every write path lands here, so this is the one place the footnote
        # convention has to hold — `create` and `edit` now behave like `append`.
        content = tidy_footnotes(content)
        meta = parse_frontmatter(content)
        fm_date, fm_metadata = extract_metadata(meta)
        doc = await self.fs.write(
            self.scope_id, address, content,
            title=title or extract_frontmatter_field(meta, "title"),
            category=category or extract_frontmatter_field(meta, "category"),
            tags=tags if tags is not None else extract_frontmatter_tags(meta),
            date=fm_date, metadata=fm_metadata or None,
        )
        await sync_references(self.fs, self.scope_id, address, content)

        link = deep_link(self.scope_key, address)
        head = (
            f"**{doc.get('title') or address}** {verb} — `{address}`\n"
            f"카테고리: {doc.get('category') or '-'} | "
            f"태그: {', '.join(doc.get('tags') or []) or '-'}\n[보기]({link})"
        )
        if is_page(address):
            head += "\n\n각주에 근거를 단다: `[^1]: 인사규정.pdf, 3장 휴가 — \"원문 문장\"`"
        return head + await self._impact(address)

    async def _impact(self, address: str) -> str:
        """The feedback loop. Without it a single-turn ingest writes blind."""
        rows = await self.fs.get_backlinks(self.scope_id, address)
        pages = [r for r in rows if r["kind"] in ("page", "index")]
        if not pages:
            return ""
        lines = [f"\n**이 페이지를 참조하는 페이지 {len(pages)}건** — 같이 손볼지 확인:"]
        for r in pages:
            kind = "인용" if r["reference_type"] == "cites" else "링크"
            lines.append(f"  - `{r['address']}` ({r['title'] or r['address']}) — {kind}")
        return "\n".join(lines)

    def _context(self, content: str, start: int, length: int) -> str:
        lines = content.split("\n")
        first = self._offset_to_line(lines, start)
        last = self._offset_to_line(lines, start + length)
        lo = max(0, first - _CONTEXT_LINES)
        hi = min(len(lines), last + _CONTEXT_LINES + 1)
        return ("..." if lo > 0 else "") + "\n".join(lines[lo:hi]) + ("..." if hi < len(lines) else "")

    def _offset_to_line(self, lines: list[str], offset: int) -> int:
        count = 0
        for i, line in enumerate(lines):
            if count + len(line) >= offset:
                return i
            count += len(line) + 1
        return len(lines) - 1


def register(mcp: FastMCP, get_scope_key, fs_factory) -> None:

    async def _resolve(ctx: Context, scope: str):
        fs = fs_factory(get_scope_key(ctx))
        row = await fs.resolve_scope(scope)
        return (WriteHandler(fs, row), None) if row else (None, f"범위 '{scope}'를 찾을 수 없다.")

    def guard(fn):
        """A refusal the agent can act on, instead of a stack trace."""
        async def wrapped(*args, **kwargs):
            try:
                return await fn(*args, **kwargs)
            except VaultError as exc:
                return f"오류: {exc}"
        wrapped.__name__ = fn.__name__
        return wrapped

    @mcp.tool(
        name="create",
        description=(
            "위키 페이지를 새로 만든다.\n\n"
            "경로를 지정하지 않는다 — 서버가 `pages/{키}.md`를 발급한다. 카테고리는 경로가 아니라 "
            "`category` 인자로 준다 (카테고리를 바꿔도 파일은 움직이지 않는다).\n"
            "`category`와 `tags`는 **필수**다. `content`의 frontmatter에 이미 있으면 같은 값을 "
            "그대로 주면 된다 — 그때도 frontmatter 쪽이 정본이다.\n"
            "본문의 모든 사실에 각주로 근거를 단다: "
            "`[^1]: 인사규정.pdf, 3장 휴가 — \"입사일을 기준으로 산정한다\"`\n"
            "frontmatter가 없으면 인자로 만들어 붙인다.\n\n"
            "반환값에 발급된 주소가 있다 — 다른 페이지에서 링크할 때 그 주소를 쓴다."
        ),
    )
    async def create(ctx: Context, scope: str, title: str, content: str, tags: list[str],
                     category: str, date_str: str = "") -> str:
        handler, err = await _resolve(ctx, scope)
        return err or await guard(handler.create)(title, content, tags, category, date_str)

    @mcp.tool(
        name="edit",
        description=(
            "위키 페이지의 정확한 문자열을 교체한다.\n\n"
            "`path`는 `pages/{키}.md` 또는 `index.md`다. `old_text`는 문서 안에서 **딱 한 번** "
            "일치해야 하며, 여러 곳이면 앞뒤 문맥을 더 붙인다.\n"
            "수정 전에 `read`로 현재 본문을 본다."
        ),
    )
    async def edit(ctx: Context, scope: str, path: str, old_text: str, new_text: str) -> str:
        handler, err = await _resolve(ctx, scope)
        return err or await guard(handler.edit)(path, old_text, new_text)

    @mcp.tool(
        name="append",
        description=(
            "위키 페이지 끝에 내용을 덧붙인다.\n\n"
            "절을 추가하거나 `index.md`를 갱신할 때 쓴다. 전체를 다시 쓰지 않아도 된다.\n"
            "각주 정의 블록은 문서 끝에 유지되고, 번호가 겹치면 자동으로 다시 매긴다."
        ),
    )
    async def append(ctx: Context, scope: str, path: str, content: str) -> str:
        handler, err = await _resolve(ctx, scope)
        return err or await guard(handler.append)(path, content)

    @mcp.tool(
        name="merge",
        description=(
            "여러 위키 페이지를 하나로 통합한다.\n\n"
            "`into`에 남길 페이지 주소, `absorbed`에 흡수해 없앨 페이지 주소들, `content`에 "
            "통합한 전체 본문을 준다. 흡수된 페이지는 제거되므로 **버릴 내용이 없는지 먼저 "
            "`read`로 확인한다.**\n\n"
            "같은 주제가 여러 페이지에 흩어져 있을 때 쓴다. 단순히 낡은 페이지라면 `edit`으로 고친다."
        ),
    )
    async def merge(ctx: Context, scope: str, into: str, absorbed: list[str],
                    content: str) -> str:
        handler, err = await _resolve(ctx, scope)
        return err or await guard(handler.merge)(into, absorbed, content)
