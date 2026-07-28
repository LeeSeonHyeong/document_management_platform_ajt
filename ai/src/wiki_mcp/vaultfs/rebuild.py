"""Rebuild the index from the file tree. CLI lives in `ai-server/rebuild.py`.

`schema.sql` claims the index is derived state, deletable and rebuildable. That
claim needs code behind it, and the first schema change proved it: changing
`document_references` left every existing workspace unopenable, because
`CREATE TABLE IF NOT EXISTS` does not migrate. Throwing the index away and
rebuilding is the migration path.

Two things cannot be recovered from files alone and are read from the old index
when one exists, then defaulted:

  * `wiki_id` — assigned by the backend at commit, not written into the page.
  * `source_id` / `original_file_name` — the id is in the source path, but the
    original filename only lives in the DB (DR-016 keeps it out of the path).

So a rebuild after the DB is deleted outright recovers the wiki but leaves source
filenames unknown, and citations naming them stop resolving. That is a real limit,
not a bug: in production the backend owns `document`, and this is a local
convenience.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from wiki_mcp.services.chunker import chunk_text, store_chunks

from .base import INDEX_ADDRESS, PAGES_PREFIX
from .local import LocalVaultFS, kind_for

logger = logging.getLogger(__name__)


async def rebuild_index(root: str | Path, scope_key: str) -> dict:
    """Drop and re-derive every row for one scope from `wiki/{scopeKey}/`.

    Returns counts. Work layers are not rebuilt — an in-flight job's output is
    unverified by definition, and DR-009 says a failed job changes nothing.
    """
    from .local import _rows_to_dicts  # local import: private helper, same module

    scope_id = await LocalVaultFS.open(root, scope_key)
    fs = LocalVaultFS(scope_key)
    db = LocalVaultFS._conn()

    cursor = await db.execute(
        "SELECT address, wiki_id, source_id, original_file_name, page_count "
        "FROM documents WHERE scope_id = ?", (scope_id,),
    )
    remembered = {r["address"]: r for r in _rows_to_dicts(cursor, await cursor.fetchall())}

    await db.execute("DELETE FROM documents WHERE scope_id = ?", (scope_id,))
    await db.execute("DELETE FROM document_references WHERE scope_id = ?", (scope_id,))
    await db.commit()

    live = fs._live_dir()
    counts = {"pages": 0, "index": 0, "sources": 0, "unknownSourceNames": 0}

    for path in sorted(live.rglob("*.md")):
        address = path.relative_to(live).as_posix()
        try:
            kind = kind_for(address)
        except Exception:
            logger.warning("주소 규칙에 맞지 않아 건너뜀: %s", address)
            continue

        content = path.read_text(encoding="utf-8")
        prior = remembered.get(address, {})

        if kind == "source":
            # sources/{documentId}/parsed/content.md
            document_id = address.split("/")[1]
            name = prior.get("original_file_name")
            if not name:
                counts["unknownSourceNames"] += 1
            await _insert(db, scope_id, address, kind, content,
                          source_id=prior.get("source_id") or document_id,
                          original_file_name=name,
                          page_count=prior.get("page_count"))
            counts["sources"] += 1
            continue

        meta = _frontmatter(content)
        await _insert(db, scope_id, address, kind, content,
                      page_key=address[len(PAGES_PREFIX):-3] if kind == "page" else None,
                      wiki_id=prior.get("wiki_id"),
                      title=meta.get("title"), category=meta.get("category"),
                      tags=meta.get("tags") or [], date=meta.get("date"))
        counts["index" if address == INDEX_ADDRESS else "pages"] += 1

    await db.commit()

    # Edges come from content, so they rebuild for free once every row exists.
    from wiki_mcp.tools.references import sync_references

    for doc in await fs.list_documents(scope_id, with_content=True):
        if doc["kind"] in ("page", "index"):
            await sync_references(fs, scope_id, doc["address"], doc.get("content") or "")

    logger.info("index 재구축: %s", counts)
    return counts


async def _insert(db, scope_id: str, address: str, kind: str, content: str, **fields) -> None:
    import uuid

    doc_id = str(uuid.uuid4())
    await db.execute(
        "INSERT INTO documents (id, scope_id, layer, address, kind, page_key, wiki_id, "
        "source_id, original_file_name, title, category, tags, content, file_type, "
        "page_count, date, version) "
        "VALUES (?, ?, 'live', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'md', ?, ?, 1)",
        (doc_id, scope_id, address, kind, fields.get("page_key"), fields.get("wiki_id"),
         fields.get("source_id"), fields.get("original_file_name"), fields.get("title"),
         fields.get("category"),
         json.dumps(fields.get("tags") or [], ensure_ascii=False),
         content, fields.get("page_count"), fields.get("date")),
    )
    await store_chunks(db, doc_id, chunk_text(content))


def _frontmatter(content: str) -> dict:
    from wiki_mcp.tools.write import (
        extract_frontmatter_field,
        extract_frontmatter_tags,
        extract_metadata,
        parse_frontmatter,
    )

    meta = parse_frontmatter(content)
    date_str, _ = extract_metadata(meta)
    return {
        "title": extract_frontmatter_field(meta, "title"),
        "category": extract_frontmatter_field(meta, "category"),
        "tags": extract_frontmatter_tags(meta),
        "date": date_str,
    }
