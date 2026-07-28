"""Local two-tier store. Filesystem is truth, SQLite is the index.

Replaces lucas-llmwiki `mcp/vaultfs/sqlite.py`. Upstream had one writable tree;
this has two, because nothing may reach the served files until it is verified
(DR-007/008, NFR-REL-001):

    {root}/wiki/{scopeKey}/          live. read-only to the agent
        sources/{documentId}/parsed/content.md
        pages/{pageKey}.md
        index.md
    {root}/work/{jobId}/             this job
        state.json
        input/
        output/                      every agent write lands here
            pages/{pageKey}.md
            index.md

Reads overlay work over live, so the agent sees its own edits immediately and
can iterate — write, read back, lint, fix — without touching the live tree.
`pending_changes` is the handover the backend validates.

Scope isolation is structural: one process holds one scope's live directory, so
no tool can address another scope. That is the local stand-in for the permission
filter Spring applies, where an invisible page must 404 rather than 403
(FR-ACL-006, NFR-SEC-003).
"""

from __future__ import annotations

import json
import logging
import os
import re
import uuid
from datetime import date
from pathlib import Path

import aiosqlite

from wiki_mcp.services.chunker import chunk_text, store_chunks

from .base import (
    INDEX_ADDRESS,
    PAGES_PREFIX,
    SOURCES_PREFIX,
    ReadOnlyLayerError,
    VaultError,
    VaultFS,
)

logger = logging.getLogger(__name__)

_SCHEMA_PATH = Path(__file__).parent.parent / "shared" / "schema.sql"
_SOURCE_EXT_RE = re.compile(r"\.(pdf|docx?|pptx?|xlsx?|csv|html?|md|txt)$", re.IGNORECASE)
_JSON_COLUMNS = {"tags": list, "metadata": dict}

_db: aiosqlite.Connection | None = None
_root: Path | None = None

INDEX_TEMPLATE = """\
---
title: 위키 목차
description: {scope_key} 범위 위키의 허브 페이지.
date: {date}
tags: [목차, 허브]
category: 목차
---

이 페이지는 `{scope_key}` 범위 위키의 입구다. 원본문서를 처리할 때마다 갱신된다.

## 원본문서 현황

아직 처리한 원본문서가 없다.

## 핵심 내용

아직 없다.

## 최근 변경

아직 없다.\
"""


def _rows_to_dicts(cursor: aiosqlite.Cursor, rows: list[tuple]) -> list[dict]:
    cols = [d[0] for d in cursor.description]
    results = []
    for row in rows:
        d = dict(zip(cols, row))
        for col, empty in _JSON_COLUMNS.items():
            if isinstance(d.get(col), str):
                try:
                    d[col] = json.loads(d[col])
                except (json.JSONDecodeError, TypeError):
                    d[col] = empty()
        d.pop("_rank", None)
        results.append(d)
    return results


def kind_for(address: str) -> str:
    if address == INDEX_ADDRESS:
        return "index"
    if address.startswith(PAGES_PREFIX):
        return "page"
    if address.startswith(SOURCES_PREFIX):
        return "source"
    raise VaultError(
        f"알 수 없는 주소 `{address}`. `pages/...`, `index.md`, `sources/...` 중 하나여야 한다."
    )


class LocalVaultFS(VaultFS):
    """SQLite index over a live tree plus one job's work space."""

    def __init__(self, scope_key: str, job_id: str | None = None):
        self.scope_key = scope_key
        self.job_id = job_id

    # ----- lifecycle --------------------------------------------------------

    @staticmethod
    async def open(root: str | Path, scope_key: str, job_id: str | None = None) -> str:
        """Open the index and ensure the scope row and work dirs. Returns scope id."""
        global _db, _root
        _root = Path(root).resolve()
        db_path = _root / ".llmwiki" / "index.db"
        db_path.parent.mkdir(parents=True, exist_ok=True)
        _db = await aiosqlite.connect(db_path)
        await _db.execute("PRAGMA journal_mode=WAL")
        await _db.execute("PRAGMA foreign_keys=ON")
        await _db.executescript(_SCHEMA_PATH.read_text(encoding="utf-8"))
        await _db.commit()

        fs = LocalVaultFS(scope_key, job_id)
        (fs._live_dir() / "pages").mkdir(parents=True, exist_ok=True)
        (fs._live_dir() / "sources").mkdir(parents=True, exist_ok=True)
        if job_id:
            (fs._work_dir() / "output" / "pages").mkdir(parents=True, exist_ok=True)
            (fs._work_dir() / "input").mkdir(parents=True, exist_ok=True)
        return await fs._ensure_scope()

    @staticmethod
    async def open_readonly(root: str | Path, scope_key: str) -> str:
        """Open the index without writing to it. Returns scope id.

        `open` applies the schema and creates directories, both writes, so it
        cannot be used while a job holds the database — and inspecting a run
        while it is still going is exactly when a viewer is useful.
        """
        global _db, _root
        _root = Path(root).resolve()
        db_path = _root / ".llmwiki" / "index.db"
        if not db_path.is_file():
            raise VaultError(f"index가 없다: {db_path}")
        _db = await aiosqlite.connect(f"file:{db_path}?mode=ro", uri=True)

        fs = LocalVaultFS(scope_key)
        scope = await fs.resolve_scope(scope_key)
        if not scope:
            raise VaultError(f"범위 '{scope_key}'가 이 저장소에 없다")
        return scope["id"]

    @staticmethod
    async def close() -> None:
        global _db
        if _db:
            await _db.close()
            _db = None

    @staticmethod
    def _conn() -> aiosqlite.Connection:
        if _db is None:
            raise VaultError("index가 열려 있지 않다 — LocalVaultFS.open()을 먼저 부른다")
        return _db

    @staticmethod
    def root() -> Path:
        if _root is None:
            raise VaultError("저장소 루트가 설정되지 않았다")
        return _root

    # ----- paths ------------------------------------------------------------

    def _live_dir(self) -> Path:
        return self.root() / "wiki" / self.scope_key

    def _work_dir(self) -> Path:
        if not self.job_id:
            raise ReadOnlyLayerError("작업 ID가 없다 — 이 세션에서는 쓸 수 없다")
        return self.root() / "work" / self.job_id

    def _file_path(self, address: str, layer: str) -> Path:
        base = self._work_dir() / "output" if layer == "work" else self._live_dir()
        resolved = (base / address).resolve()
        # Traversal guard. `..` in an address must never escape the tier.
        if not resolved.is_relative_to(base.resolve()):
            raise VaultError(f"저장소 밖을 가리키는 주소 `{address}`")
        return resolved

    def relative_path(self, address: str) -> str:
        """The value the backend stores in `wiki.wiki_path` (DR-016)."""
        return f"wiki/{self.scope_key}/{address}"

    # ----- scope ------------------------------------------------------------

    async def _ensure_scope(self) -> str:
        db = self._conn()
        cursor = await db.execute("SELECT id FROM wiki_scope WHERE scope_key = ?", (self.scope_key,))
        row = await cursor.fetchone()
        if row:
            return row[0]
        scope_id = str(uuid.uuid4())
        await db.execute(
            "INSERT INTO wiki_scope (id, scope_key, visibility_type, index_path) VALUES (?, ?, ?, ?)",
            (scope_id, self.scope_key,
             "ALL" if self.scope_key == "ALL" else "DEPARTMENT",
             self.relative_path(INDEX_ADDRESS)),
        )
        await db.commit()
        return scope_id

    async def resolve_scope(self, scope_key: str) -> dict | None:
        db = self._conn()
        # An empty argument resolves to this process's scope, so a call that omits
        # it cannot reach a different one.
        cursor = await db.execute(
            "SELECT id, scope_key, visibility_type, index_path FROM wiki_scope WHERE scope_key = ?",
            (scope_key or self.scope_key,),
        )
        row = await cursor.fetchone()
        return _rows_to_dicts(cursor, [row])[0] if row else None

    async def list_scopes(self) -> list[dict]:
        db = self._conn()
        cursor = await db.execute(
            "SELECT s.id, s.scope_key, s.visibility_type, s.index_path, "
            "(SELECT count(*) FROM visible_documents WHERE scope_id = s.id AND kind = 'source') "
            "  AS source_count, "
            "(SELECT count(*) FROM visible_documents WHERE scope_id = s.id AND kind = 'page') "
            "  AS page_count "
            "FROM wiki_scope s ORDER BY s.scope_key",
        )
        return _rows_to_dicts(cursor, await cursor.fetchall())

    # ----- reading ----------------------------------------------------------

    _COLUMNS = (
        "id, scope_id, layer, address, kind, page_key, wiki_id, source_id, "
        "original_file_name, title, category, tags, content, file_type, page_count, "
        "date, metadata, version, stale_since, created_at, updated_at"
    )

    async def get(self, scope_id: str, address: str) -> dict | None:
        db = self._conn()
        cursor = await db.execute(
            f"SELECT {self._COLUMNS} FROM visible_documents WHERE scope_id = ? AND address = ?",
            (scope_id, address),
        )
        rows = _rows_to_dicts(cursor, await cursor.fetchall())
        return rows[0] if rows else None

    async def find_source(self, scope_id: str, name: str) -> dict | None:
        """Match a source by filename, document id, or address.

        Footnotes name the human filename and paths no longer carry it, so this
        lookup is what makes a citation checkable at all.
        """
        db = self._conn()
        key = name.strip()
        bare = _SOURCE_EXT_RE.sub("", key).lower()
        cursor = await db.execute(
            f"SELECT {self._COLUMNS} FROM visible_documents "
            "WHERE scope_id = ? AND kind = 'source' AND ("
            "  lower(original_file_name) = lower(?) "
            "  OR lower(replace(original_file_name, rtrim(original_file_name, "
            "     replace(original_file_name, '.', '')), '')) = ? "
            "  OR source_id = ? OR address = ?)",
            (scope_id, key, bare, key, key),
        )
        rows = _rows_to_dicts(cursor, await cursor.fetchall())
        if rows:
            return rows[0]
        # Extension-insensitive fallback in Python: SQLite has no clean way to
        # strip a suffix, and a citation dropping '.pdf' is common.
        cursor = await db.execute(
            f"SELECT {self._COLUMNS} FROM visible_documents "
            "WHERE scope_id = ? AND kind = 'source'", (scope_id,),
        )
        for row in _rows_to_dicts(cursor, await cursor.fetchall()):
            candidate = _SOURCE_EXT_RE.sub("", (row.get("original_file_name") or "")).lower()
            if candidate and candidate == bare:
                return row
        return None

    async def list_documents(self, scope_id: str, with_content: bool = False) -> list[dict]:
        db = self._conn()
        columns = self._COLUMNS if with_content else self._COLUMNS.replace("content, ", "")
        cursor = await db.execute(
            f"SELECT {columns} FROM visible_documents WHERE scope_id = ? ORDER BY kind, address",
            (scope_id,),
        )
        return _rows_to_dicts(cursor, await cursor.fetchall())

    async def get_source_pages(self, doc_id: str, page_nums: list[int] | None = None) -> list[dict]:
        db = self._conn()
        if page_nums:
            placeholders = ",".join("?" for _ in page_nums)
            cursor = await db.execute(
                f"SELECT page, content FROM document_pages "
                f"WHERE document_id = ? AND page IN ({placeholders}) ORDER BY page",
                [doc_id, *page_nums],
            )
        else:
            cursor = await db.execute(
                "SELECT page, content FROM document_pages WHERE document_id = ? ORDER BY page",
                (doc_id,),
            )
        return _rows_to_dicts(cursor, await cursor.fetchall())

    async def search_chunks(self, scope_id: str, query: str, limit: int,
                            kind_filter: str | None = None) -> list[dict]:
        db = self._conn()
        sql = (
            "SELECT dc.content, dc.page, dc.header_breadcrumb, dc.chunk_index, "
            "d.address, d.kind, d.title, d.category, d.original_file_name, d.tags, "
            "rank AS score "
            "FROM document_chunks dc "
            "JOIN chunks_fts fts ON dc.rowid = fts.rowid "
            # The view, not the table: a live chunk whose page was superseded in
            # the work layer must not surface.
            "JOIN visible_documents d ON dc.document_id = d.id "
            "WHERE chunks_fts MATCH ? AND d.scope_id = ? "
        )
        params: list = [query, scope_id]
        if kind_filter == "wiki":
            sql += "AND d.kind IN ('page', 'index') "
        elif kind_filter == "sources":
            sql += "AND d.kind = 'source' "
        sql += "ORDER BY rank LIMIT ?"
        params.append(limit)
        cursor = await db.execute(sql, params)
        return _rows_to_dicts(cursor, await cursor.fetchall())

    # ----- writing ----------------------------------------------------------

    async def allocate_page(self, scope_id: str) -> str:
        """A fresh page address.

        The key is generated here rather than derived from `wiki_id`, which the
        backend only assigns at commit. That is what lets the agent write a page
        and link to it in the same job without a rename afterwards.
        """
        db = self._conn()
        while True:
            key = uuid.uuid4().hex[:12]
            address = f"{PAGES_PREFIX}{key}.md"
            cursor = await db.execute(
                "SELECT 1 FROM documents WHERE scope_id = ? AND address = ?", (scope_id, address),
            )
            if not await cursor.fetchone():
                return address

    async def write(self, scope_id: str, address: str, content: str, *,
                    title: str | None = None, category: str | None = None,
                    tags: list[str] | None = None, date: str | None = None,
                    metadata: dict | None = None) -> dict:
        kind = kind_for(address)
        if kind == "source":
            raise ReadOnlyLayerError(
                "원본문서는 수정할 수 없다. 백엔드가 소유한다 — 위키 페이지만 쓴다."
            )

        db = self._conn()
        self._file_path(address, "work").parent.mkdir(parents=True, exist_ok=True)
        self._file_path(address, "work").write_text(content, encoding="utf-8")

        page_key = address[len(PAGES_PREFIX):-3] if kind == "page" else None
        live = await self._row(scope_id, address, "live")
        existing = await self._row(scope_id, address, "work")

        if existing:
            await db.execute(
                "UPDATE documents SET content = ?, title = COALESCE(?, title), "
                "category = COALESCE(?, category), tags = COALESCE(?, tags), "
                "date = COALESCE(?, date), metadata = COALESCE(?, metadata), "
                "deleted = 0, version = COALESCE(version, 0) + 1, "
                "updated_at = datetime('now') WHERE id = ?",
                (content, title, category,
                 json.dumps(tags, ensure_ascii=False) if tags is not None else None,
                 date, json.dumps(metadata, ensure_ascii=False) if metadata is not None else None,
                 existing["id"]),
            )
            doc_id = existing["id"]
        else:
            doc_id = str(uuid.uuid4())
            await db.execute(
                "INSERT INTO documents (id, scope_id, layer, address, kind, page_key, wiki_id, "
                "title, category, tags, content, file_type, date, metadata, version) "
                "VALUES (?, ?, 'work', ?, ?, ?, ?, ?, ?, ?, ?, 'md', ?, ?, 1)",
                (doc_id, scope_id, address, kind, page_key,
                 (live or {}).get("wiki_id"),
                 title or (live or {}).get("title"),
                 category or (live or {}).get("category"),
                 json.dumps(tags if tags is not None else (live or {}).get("tags") or [],
                            ensure_ascii=False),
                 content, date, json.dumps(metadata, ensure_ascii=False) if metadata else None),
            )

        await store_chunks(db, doc_id, chunk_text(content))
        await db.commit()
        return await self.get(scope_id, address) or {}

    async def remove(self, scope_id: str, address: str) -> bool:
        kind = kind_for(address)
        if kind != "page":
            raise ReadOnlyLayerError(
                "`index.md`와 원본문서는 지울 수 없다. 목차 내용은 `edit`으로 고친다."
            )
        db = self._conn()
        live = await self._row(scope_id, address, "live")
        work = await self._row(scope_id, address, "work")

        path = self._file_path(address, "work")
        if path.exists():
            path.unlink()

        if not live:
            # Never committed: drop it outright, no tombstone to hand over.
            if not work:
                return False
            await db.execute("DELETE FROM documents WHERE id = ?", (work["id"],))
            await db.commit()
            return True

        if work:
            await db.execute(
                "UPDATE documents SET deleted = 1, content = NULL, updated_at = datetime('now') "
                "WHERE id = ?", (work["id"],),
            )
        else:
            await db.execute(
                "INSERT INTO documents (id, scope_id, layer, address, kind, page_key, wiki_id, "
                "title, category, deleted) VALUES (?, ?, 'work', ?, 'page', ?, ?, ?, ?, 1)",
                (str(uuid.uuid4()), scope_id, address,
                 address[len(PAGES_PREFIX):-3], live.get("wiki_id"),
                 live.get("title"), live.get("category")),
            )
        await db.commit()
        return True

    async def _row(self, scope_id: str, address: str, layer: str) -> dict | None:
        db = self._conn()
        cursor = await db.execute(
            f"SELECT {self._COLUMNS}, deleted FROM documents "
            "WHERE scope_id = ? AND address = ? AND layer = ?",
            (scope_id, address, layer),
        )
        rows = _rows_to_dicts(cursor, await cursor.fetchall())
        return rows[0] if rows else None

    # ----- handover ---------------------------------------------------------

    async def pending_changes(self, scope_id: str) -> list[dict]:
        """The work layer, expressed as the change set FR-AI-009 asks for."""
        db = self._conn()
        cursor = await db.execute(
            f"SELECT {self._COLUMNS}, deleted FROM documents "
            "WHERE scope_id = ? AND layer = 'work' ORDER BY address",
            (scope_id,),
        )
        work_rows = _rows_to_dicts(cursor, await cursor.fetchall())

        changes: list[dict] = []
        for row in work_rows:
            live = await self._row(scope_id, row["address"], "live")
            if row.get("deleted"):
                change_type = "merge" if (row.get("metadata") or {}).get("mergedInto") else "remove"
            else:
                change_type = "update" if live else "create"

            change = {
                "type": change_type,
                "address": row["address"],
                "wikiPath": self.relative_path(row["address"]),
                "title": row.get("title"),
                "category": row.get("category"),
                "tags": row.get("tags") or [],
            }
            if row.get("wiki_id"):
                change["wikiId"] = row["wiki_id"]
            if change_type == "merge":
                change["mergedInto"] = (row.get("metadata") or {})["mergedInto"]
            if change_type in ("create", "update"):
                change["evidence"] = await self._evidence(scope_id, row["address"])
            changes.append(change)
        return changes

    async def _evidence(self, scope_id: str, address: str) -> list[dict]:
        """Which source, at which location, for each footnote.

        This is the per-change 근거 FR-AI-009 requires, and the payload a citation
        preview needs on the frontend.
        """
        rows = await self.get_forward_references(scope_id, address)
        return [
            {"documentId": r["source_id"], "documentName": r["original_file_name"],
             "footnote": r["footnote_label"], "location": r["location"],
             "quote": r["quote"], "page": r["page"]}
            for r in rows if r["reference_type"] == "cites"
        ]

    # ----- reference graph --------------------------------------------------

    async def replace_references(self, scope_id: str, source_address: str,
                                 edges: list[dict]) -> None:
        db = self._conn()
        await db.execute(
            "DELETE FROM document_references WHERE scope_id = ? AND source_address = ?",
            (scope_id, source_address),
        )
        for edge in edges:
            try:
                await db.execute(
                    "INSERT OR REPLACE INTO document_references "
                    "(scope_id, source_address, target_address, reference_type, footnote_label, "
                    " location, quote, page) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (scope_id, source_address, edge["targetAddress"], edge["type"],
                     edge.get("footnote"), edge.get("location"), edge.get("quote"),
                     edge.get("page")),
                )
            except aiosqlite.IntegrityError as exc:
                logger.warning("reference %s -> %s 실패: %s",
                               source_address, edge["targetAddress"], exc)
        await db.commit()

    async def propagate_staleness(self, scope_id: str, address: str) -> None:
        """Flag live pages that link to a page which just changed.

        `layer = 'live'` is the point. A work-layer row is a page this job is
        already editing, so flagging it says nothing — and it fired constantly:
        writing page A and then editing the B it links to, two seconds apart in
        one job, left A marked stale. Only pages committed before this job started
        are worth reporting.
        """
        db = self._conn()
        await db.execute(
            "UPDATE documents SET stale_since = datetime('now') "
            "WHERE scope_id = ? AND layer = 'live' AND stale_since IS NULL AND address IN ("
            "  SELECT source_address FROM document_references "
            "  WHERE scope_id = ? AND target_address = ? AND reference_type = 'links_to'"
            ")",
            (scope_id, scope_id, address),
        )
        await db.commit()

    async def get_backlinks(self, scope_id: str, address: str) -> list[dict]:
        db = self._conn()
        cursor = await db.execute(
            "SELECT d.address, d.title, d.kind, r.reference_type "
            "FROM document_references r "
            "JOIN visible_documents d "
            "  ON d.scope_id = r.scope_id AND d.address = r.source_address "
            "WHERE r.scope_id = ? AND r.target_address = ? ORDER BY d.address",
            (scope_id, address),
        )
        return _rows_to_dicts(cursor, await cursor.fetchall())

    async def get_forward_references(self, scope_id: str, address: str) -> list[dict]:
        db = self._conn()
        cursor = await db.execute(
            "SELECT d.address, d.title, d.kind, d.source_id, d.original_file_name, "
            "       r.reference_type, r.footnote_label, r.location, r.quote, r.page "
            "FROM document_references r "
            "JOIN visible_documents d "
            "  ON d.scope_id = r.scope_id AND d.address = r.target_address "
            "WHERE r.scope_id = ? AND r.source_address = ? "
            "ORDER BY r.reference_type, r.footnote_label, d.address",
            (scope_id, address),
        )
        return _rows_to_dicts(cursor, await cursor.fetchall())

    async def find_uncited_sources(self, scope_id: str) -> list[dict]:
        db = self._conn()
        cursor = await db.execute(
            "SELECT d.address, d.original_file_name, d.source_id FROM visible_documents d "
            "WHERE d.scope_id = ? AND d.kind = 'source' "
            "  AND d.address NOT IN (SELECT target_address FROM document_references "
            "                        WHERE scope_id = ? AND reference_type = 'cites') "
            "ORDER BY d.original_file_name",
            (scope_id, scope_id),
        )
        return _rows_to_dicts(cursor, await cursor.fetchall())

    async def find_stale_pages(self, scope_id: str) -> list[dict]:
        """Flagged stale and not touched since.

        The `updated_at <= stale_since` test matters: a page linking another page
        is flagged whenever that target changes, including inside the same job
        that wrote both. Without it a clean run still reports stale pages.
        """
        db = self._conn()
        cursor = await db.execute(
            "SELECT d.address, d.title, d.stale_since FROM visible_documents d "
            "WHERE d.scope_id = ? AND d.stale_since IS NOT NULL "
            "  AND (d.updated_at IS NULL OR d.updated_at <= d.stale_since) "
            "ORDER BY d.stale_since DESC",
            (scope_id,),
        )
        return _rows_to_dicts(cursor, await cursor.fetchall())


# ----- backend-side helpers -------------------------------------------------
#
# Everything below is the backend's job (register an upload, commit a verified
# job). It lives here only because there is no Spring yet; `backend_sim.py` is
# the only caller and both go away together.


async def bootstrap_scope(scope_key: str) -> str:
    """Ensure the live scope exists with its index page (FR-WIKI-013, DR-015)."""
    fs = LocalVaultFS(scope_key)
    scope_id = await fs._ensure_scope()
    if await fs._row(scope_id, INDEX_ADDRESS, "live"):
        return scope_id

    content = INDEX_TEMPLATE.format(scope_key=scope_key, date=date.today().isoformat())
    path = fs._live_dir() / INDEX_ADDRESS
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    else:
        content = path.read_text(encoding="utf-8")

    db = LocalVaultFS._conn()
    doc_id = str(uuid.uuid4())
    await db.execute(
        "INSERT INTO documents (id, scope_id, layer, address, kind, title, category, tags, "
        "content, file_type, date, version) "
        "VALUES (?, ?, 'live', ?, 'index', '위키 목차', '목차', ?, ?, 'md', ?, 1)",
        (doc_id, scope_id, INDEX_ADDRESS, json.dumps(["목차", "허브"], ensure_ascii=False),
         content, date.today().isoformat()),
    )
    await store_chunks(db, doc_id, chunk_text(content))
    await db.commit()
    return scope_id


async def register_source(scope_key: str, document_id: str, original_file_name: str,
                          text: str, page_count: int | None = None) -> dict:
    """Put a parsed upload where AJT 4절 says it goes and index it."""
    fs = LocalVaultFS(scope_key)
    scope_id = await fs._ensure_scope()
    address = f"{SOURCES_PREFIX}{document_id}/parsed/content.md"

    path = fs._live_dir() / address
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")

    db = LocalVaultFS._conn()
    existing = await fs._row(scope_id, address, "live")
    if existing:
        await db.execute(
            "UPDATE documents SET content = ?, original_file_name = ?, page_count = ?, "
            "updated_at = datetime('now') WHERE id = ?",
            (text, original_file_name, page_count, existing["id"]),
        )
        doc_id = existing["id"]
    else:
        doc_id = str(uuid.uuid4())
        await db.execute(
            "INSERT INTO documents (id, scope_id, layer, address, kind, source_id, "
            "original_file_name, title, content, file_type, page_count, version) "
            "VALUES (?, ?, 'live', ?, 'source', ?, ?, ?, ?, ?, ?, 1)",
            (doc_id, scope_id, address, document_id, original_file_name,
             Path(original_file_name).stem, text,
             Path(original_file_name).suffix.lstrip(".") or "md", page_count),
        )
    await store_chunks(db, doc_id, chunk_text(text))
    await db.commit()
    return {"id": doc_id, "address": address, "relativePath": fs.relative_path(address)}


async def commit_job(scope_key: str, job_id: str, scope_id: str,
                     next_wiki_id) -> list[dict]:
    """Promote a verified work layer into the live tree (DR-008).

    `next_wiki_id` stands in for the DB assigning `wiki.wiki_id`. The page file
    keeps its name — that is the whole point of allocating the key up front, and
    why no link has to be rewritten here.
    """
    fs = LocalVaultFS(scope_key, job_id)
    db = LocalVaultFS._conn()
    committed = []

    for change in await fs.pending_changes(scope_id):
        address = change["address"]
        work = await fs._row(scope_id, address, "work")
        live = await fs._row(scope_id, address, "live")

        if change["type"] in ("remove", "merge"):
            live_path = fs._file_path(address, "live")
            if live_path.exists():
                live_path.unlink()
            if live:
                await db.execute("DELETE FROM documents WHERE id = ?", (live["id"],))
            await db.execute("DELETE FROM documents WHERE id = ?", (work["id"],))
            committed.append({**change, "committed": True})
            continue

        wiki_id = live["wiki_id"] if live and live.get("wiki_id") else str(next_wiki_id())
        live_path = fs._file_path(address, "live")
        live_path.parent.mkdir(parents=True, exist_ok=True)
        live_path.write_text(work["content"] or "", encoding="utf-8")

        if live:
            await db.execute("DELETE FROM documents WHERE id = ?", (live["id"],))
        await db.execute(
            "UPDATE documents SET layer = 'live', wiki_id = ? WHERE id = ?",
            (wiki_id, work["id"]),
        )
        committed.append({**change, "wikiId": wiki_id, "committed": True})

    await db.commit()
    # DR-010: a successful work space is removed immediately after reflection.
    work_dir = fs._work_dir()
    if work_dir.exists():
        for child in sorted(work_dir.rglob("*"), reverse=True):
            child.unlink() if child.is_file() else child.rmdir()
        work_dir.rmdir()
    return committed


async def discard_job(scope_key: str, job_id: str, scope_id: str) -> int:
    """Drop a failed job's work layer. The live tree is untouched (DR-009)."""
    db = LocalVaultFS._conn()
    cursor = await db.execute(
        "DELETE FROM documents WHERE scope_id = ? AND layer = 'work'", (scope_id,)
    )
    await db.commit()
    return cursor.rowcount


def write_job_state(scope_key: str, job_id: str, state: dict) -> None:
    """`work/{jobId}/state.json` — 작업 유형, 단계, 대상 ID (AJT 8절, DR-005)."""
    path = LocalVaultFS.root() / "work" / job_id / "state.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
