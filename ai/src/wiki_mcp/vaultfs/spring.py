"""라이브 층을 Spring 이 준 dict 로 채우는 VaultFS (v1.1.0 — push).

2단 SQLite 는 `local.py` 것을 그대로 쓴다. 바뀌는 것은 라이브 층의 출처뿐이다 — HTTP
왕복 대신 `open()` 이 직접 받는 `pages`(SelectedWiki 모양의 dict 목록)와
`index_markdown`. Spring 이 이미 두 번의 호출(선택·변환)로 본문을 실어 보냈으므로
AI 서버는 되묻지 않는다(설계 §1·§2). 그래서 툴 10개·프롬프트·`lint` 가 이 파일을 모른다.

임시 루트에 SQLite 와 파일을 만든다. `/data/ajt` 를 만지지 않는다 — 요청이 끝나면 임시
디렉터리를 버리고, 반영은 Spring 이 `work/{jobId}` 에서 수행한다 (DR-007·008).

**본문을 한 번에 전부 받는다.** `search` 는 청크 FTS 로 돌고 청크는 본문을 저장할 때
생긴다. 지연 적재하면 검색이 0건을 내고 에이전트가 기존 페이지를 못 찾아 전부 새로 만든다.
받는 것과 프롬프트에 넣는 것은 별개다 — 색인만 하고 에이전트는 자기가 고른 것만 읽는다.

메타데이터 추출은 `tools/write.py`의 `parse_frontmatter`/`extract_frontmatter_field`/
`extract_frontmatter_tags`/`extract_metadata`를 그대로 쓴다 — 그쪽 시그니처가 이 파일이
기대하는 모양(제목/카테고리/태그/날짜를 바로 주는 dict)과 달라서, `write.py`를 고치지 않고
이 파일에서 호출 방식을 맞췄다: `extract_metadata`는 파싱된 frontmatter dict를 받아
`(date_str, metadata)`만 돌려주므로, title/category/tags는 `extract_frontmatter_field`·
`extract_frontmatter_tags`로 따로 뽑는다.

**Task 4: pull 경로 제거.** `client` 위치 인자와 그것으로만 도는 `_hydrate`·`load_page`·
`load_source`(전부 HTTP 로 Spring 을 되물었다)는 `api/session.py`가 `pages=`로 옮기면서
함께 지웠다 — 이제 라이브 층은 `open(..., pages=..., index_markdown=...)`으로만 채워진다.
`springclient.py` 자체도 지웠다(`docs/AI호출명세서.json`과 함께).
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path

from wiki_mcp.services.chunker import Chunk, chunk_text, store_chunks

from .local import (INDEX_ADDRESS, PAGES_PREFIX, SOURCES_PREFIX, LocalVaultFS,
                    kind_for)

# `tools.write` imports `tools.references`, which imports `vaultfs` (for the
# `VaultFS` type) — and `vaultfs/__init__.py` imports this module. A top-level
# `from tools.write import ...` here would therefore run while `vaultfs` is
# still mid-initialization and fail with a circular-import error. Deferred to
# first use inside `_insert_live` instead; `tools/write.py` itself is untouched.


def address_from_wiki_path(wiki_path: str, scope_key: str) -> str:
    """`wiki/{scopeKey}/pages/a3f2c1d4.md` → `pages/a3f2c1d4.md`.

    `pageKey` 는 에이전트가 발급한 값이라 `wikiId` 에서 유도할 수 없다. `wiki.wiki_path`
    가 저장 컬럼이므로(DR-016) 그 값에서 접두사만 떼면 주소가 복원된다.
    """
    prefix = f"wiki/{scope_key}/"
    if wiki_path.startswith(prefix):
        return wiki_path[len(prefix):]
    return wiki_path.lstrip("/")


class SpringVaultFS(LocalVaultFS):
    @staticmethod
    async def open(root: str | Path, scope_key: str, job_id: str | None, *,
                   pages: list[dict] | None = None,
                   index_markdown: str | None = None) -> str:
        """임시 색인을 열고 이 범위의 위키로 채운다. scope_id 를 돌려준다.

        `pages` — SelectedWiki 모양의 dict 목록
        (`wikiId`·`title`·`summary`·`contentMarkdown`·`categoryName?`·`wikiPath?`) —
        과 `index_markdown` 을 직접 받아 채운다. HTTP 왕복이 없다. 둘 다 안 주면
        (테스트에서 `SpringVaultFS(...)` 만 생성하는 경우처럼) 아무것도 채우지 않는다.
        """
        scope_id = await LocalVaultFS.open(root, scope_key, job_id)
        fs = SpringVaultFS(scope_key, job_id)
        if pages is not None or index_markdown is not None:
            await fs._hydrate_from_pages(scope_id, pages or [], index_markdown or "")
        return scope_id

    async def _hydrate_from_pages(self, scope_id: str, pages: list[dict],
                                  index_markdown: str) -> None:
        """`pages`(SelectedWiki 모양의 dict 목록)로 라이브 층을 채운다 — v1.1.0 정본 경로.

        selectedWikis 에는 `wikiPath` 가 없다(설계 §3) — 새 코퍼스는 `wikiId` 로 주소를
        짓는다(`pages/{wikiId}.md`, `wikiId` 가 이미 있어 유일함이 보장된다). 다만 옛
        SelectedWiki 모양(`wikiPath` 를 포함하는 하위호환 입력)이 오면 그 접미사를 그대로
        쓴다 — `address_from_wiki_path` 주석 참고.
        """
        for page in pages:
            wiki_path = page.get("wikiPath")
            wiki_id = page.get("wikiId")
            address = (address_from_wiki_path(wiki_path, self.scope_key) if wiki_path
                      else f"{PAGES_PREFIX}{wiki_id}.md")
            await self._insert_live(
                scope_id, address, page.get("contentMarkdown") or "",
                wiki_id=wiki_id, title=page.get("title"),
                category=page.get("categoryName"))
        await self._insert_live(scope_id, INDEX_ADDRESS, index_markdown or "",
                               title="위키 목차", category="목차")
        # `vaultfs/rebuild.py:96-100` 과 같은 두 단계 방식: 전부 넣은 뒤에 훑는다. 페이지를
        # 하나씩 넣으며 그때그때 동기화하면, 아직 안 들어온 페이지로 가는 links_to 가
        # 매번 빠진다 — 목록 순서가 보장되지 않는다.
        await self._sync_page_references(scope_id)
        # 하이드레이션은 한 순간의 스냅샷이다 — t0 에는 아무것도 stale 일 수 없다.
        # `sync_references`가 부르는 `propagate_staleness`는 "링크 대상이 방금 바뀌었다"는
        # 신호인데, 하이드레이션 중에는 그 대상이 바뀐 적이 없다 — 그래프를 처음 채우는
        # 것뿐이다. 지우지 않으면 B가 A를 링크한다는 사실만으로 A가 거짓으로 stale 표시된다.
        await self._clear_staleness(scope_id)

    async def _sync_page_references(self, scope_id: str) -> None:
        """라이브 페이지·목차 전부의 참조 그래프를 다시 채운다.

        하이드레이션은 위키 페이지만 넣는다 — 각주가 가리키는 원본문서는 `stage_source`가
        나중에 따로 넣는다. 그래서 이 메서드는 `_hydrate_from_pages` 끝에서 한 번, 그리고
        원본문서가 라이브에 새로 들어올 때마다(`stage_source`) 다시 불린다 — 그 시점에야
        이미 하이드레이션된 페이지의 각주가 비로소 풀린다. 매번 전체를 다시 쓰므로
        (`replace_references`) 몇 번을 불러도 안전하다.
        """
        from wiki_mcp.tools.references import sync_references

        for doc in await self.list_documents(scope_id, with_content=True):
            if doc["kind"] in ("page", "index"):
                await sync_references(self, scope_id, doc["address"],
                                      doc.get("content") or "")

    async def _clear_staleness(self, scope_id: str) -> None:
        """`_sync_page_references`가 방금 켠 `stale_since`를 되돌린다.

        컨텍스트를 처음 채우는 시점(하이드레이션, 그리고 재조정에서 원본문서를 얹는
        `stage_source`)에는 페이지 내용이 실제로 바뀐 적이 없다 —
        `document_references`가 이제야 채워졌을 뿐이다. 진짜 staleness 는 에이전트가
        `write`로 실제로 고칠 때 다시 붙는다(`LocalVaultFS.write` → `propagate_staleness`
        경로는 그대로 둔다, 여긴 손대지 않는다).
        """
        db = self._conn()
        await db.execute(
            "UPDATE documents SET stale_since = NULL WHERE scope_id = ? AND layer = 'live'",
            (scope_id,),
        )
        await db.commit()

    async def write(self, scope_id: str, address: str, content: str, **kwargs) -> dict:
        """`LocalVaultFS.write` calls `chunk_text` directly with no fallback, so
        editing a hydrated page down to a still-tiny body would drop it out of
        the FTS index — the exact failure this task exists to prevent, just
        relocated from hydration to the edit path. Delegate to the parent for
        everything else, then top up the index the same way `_insert_live` does
        if the write left zero chunks behind.
        """
        doc = await super().write(scope_id, address, content, **kwargs)
        if doc.get("id"):
            db = self._conn()
            cursor = await db.execute(
                "SELECT 1 FROM document_chunks WHERE document_id = ?", (doc["id"],),
            )
            if not await cursor.fetchone():
                await store_chunks(db, doc["id"], self._chunks_for_index(content))
                await db.commit()
        return doc

    async def live_content(self, scope_id: str, address: str) -> str | None:
        """라이브 층 본문만. 겹쳐 읽기(`get`)와 달리 작업 층을 보지 않는다.

        에이전트 쓰기는 작업 층으로만 가므로, 이 값은 **에이전트 실행 전 상태**다. 게이트가
        "이 각주가 원래 있던 것인가"를 판정하는 데 쓴다 (`api/session.py._is_legacy_footnote`).
        """
        row = await self._row(scope_id, address, "live")
        return (row or {}).get("content")

    async def stage_source(self, scope_id: str, document_id: str, text: str,
                           original_file_name: str | None = None) -> str:
        """Spring 에 없는 원본문서를 라이브 층에 넣는다 (삭제된 문서의 재조정용)."""
        address = f"{SOURCES_PREFIX}{document_id}/parsed/content.md"
        await self._insert_live(scope_id, address, text, source_id=document_id,
                                original_file_name=original_file_name or f"document-{document_id}",
                                title=Path(original_file_name or document_id).stem)
        await self._sync_page_references(scope_id)
        await self._clear_staleness(scope_id)
        return address

    async def get_citation_backlinks(self, scope_id: str, address: str) -> list[dict]:
        """원본문서를 인용하는 페이지를 각주 단위로 알려준다.

        `LocalVaultFS.get_backlinks`(고치지 않는다)는 관계 종류만 돌려준다 — 재조정은 각주
        번호·위치·인용문까지 있어야 지시문을 채울 수 있어서 여기서 따로 조회한다.
        """
        db = self._conn()
        cursor = await db.execute(
            "SELECT d.address, r.footnote_label, r.location, r.quote "
            "FROM document_references r "
            "JOIN visible_documents d "
            "  ON d.scope_id = r.scope_id AND d.address = r.source_address "
            "WHERE r.scope_id = ? AND r.target_address = ? AND r.reference_type = 'cites' "
            "ORDER BY d.address",
            (scope_id, address),
        )
        cols = [c[0] for c in cursor.description]
        return [dict(zip(cols, row)) for row in await cursor.fetchall()]

    async def _insert_live(self, scope_id: str, address: str, content: str, *,
                           wiki_id: str | None = None, source_id: str | None = None,
                           original_file_name: str | None = None,
                           title: str | None = None,
                           category: str | None = None) -> str:
        """라이브 행 하나 + 청크 색인. 파일도 쓴다 — 툴이 경로를 돌려주기 때문이다."""
        kind = kind_for(address)

        tags: list[str] = []
        date_str: str | None = None
        metadata: dict | None = None
        if kind == "page":
            # 지연 임포트 — 모듈 상단 주석 참고 (순환 임포트 회피).
            from wiki_mcp.tools.write import (
                extract_frontmatter_field,
                extract_frontmatter_tags,
                extract_metadata,
                parse_frontmatter,
            )
            # 페이지는 본문 frontmatter가 정본이다 (write.py._save와 같은 우선순위).
            meta = parse_frontmatter(content)
            title = extract_frontmatter_field(meta, "title") or title
            category = extract_frontmatter_field(meta, "category") or category
            tags = extract_frontmatter_tags(meta) or []
            date_str, fm_metadata = extract_metadata(meta)
            metadata = fm_metadata or None

        page_key = address[len("pages/"):-3] if kind == "page" else None

        path = self._file_path(address, "live")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

        db = self._conn()
        existing = await self._row(scope_id, address, "live")
        doc_id = existing["id"] if existing else str(uuid.uuid4())
        if existing:
            await db.execute(
                "UPDATE documents SET content = ?, title = ?, category = ?, tags = ?, "
                "wiki_id = COALESCE(?, wiki_id), source_id = COALESCE(?, source_id), "
                "original_file_name = COALESCE(?, original_file_name), "
                "date = ?, metadata = ?, updated_at = datetime('now') WHERE id = ?",
                (content, title, category, json.dumps(tags, ensure_ascii=False),
                 wiki_id, source_id, original_file_name, date_str,
                 json.dumps(metadata, ensure_ascii=False) if metadata else None, doc_id),
            )
        else:
            await db.execute(
                "INSERT INTO documents (id, scope_id, layer, address, kind, page_key, "
                "wiki_id, source_id, original_file_name, title, category, tags, content, "
                "file_type, date, metadata, version) "
                "VALUES (?, ?, 'live', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'md', ?, ?, 1)",
                (doc_id, scope_id, address, kind, page_key, wiki_id, source_id,
                 original_file_name, title, category,
                 json.dumps(tags, ensure_ascii=False), content, date_str,
                 json.dumps(metadata, ensure_ascii=False) if metadata else None),
            )
        await store_chunks(db, doc_id, self._chunks_for_index(content))
        await db.commit()
        return doc_id

    @staticmethod
    def _chunks_for_index(content: str) -> list[Chunk]:
        """`chunk_text` drops anything under `MIN_CHUNK_TOKENS` (~32) — fine for a
        real document, but a short hydrated page or source (common in tests, and
        not unheard of for a freshly-created real one) would then index zero
        chunks, and `search` would silently return nothing for it. Fall back to
        one chunk holding the whole body so hydration never yields an
        unsearchable live row.
        """
        chunks = chunk_text(content)
        if not chunks and content and content.strip():
            chunks = [Chunk(index=0, content=content.strip(), page=None, start_char=0,
                            token_count=max(1, len(content) // 4))]
        return chunks
