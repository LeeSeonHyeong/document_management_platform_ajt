"""라이브 층을 밖에서 채워 넣는 VaultFS — 2단 SQLite 저장 계층.

2단 SQLite 는 `local.py` 것을 그대로 쓴다. 이 파일이 더하는 것은 라이브 층에 행을
직접 꽂는 `_insert_live` 와 그 뒤처리(`_sync_page_references`·`_clear_staleness`),
그리고 각주 단위 인용 역링크(`get_citation_backlinks`)다. 툴 10개·프롬프트·`lint` 는
이 파일을 모른다.

**S15P11B106-175 에서 push 하이드레이션이 사라졌다.** v1.1.0~1.8.0 사이에는
`open(pages=…, index_markdown=…)` 이 요청 본문의 `selectedWikis`·`currentIndex` 로
라이브 층을 채웠다. 이제 라이브 층의 유일한 출처는 Wiki 조회 API 이고 그 하이드레이션은
`FederatedVaultFS.open` 이 한다 — 이 클래스는 그 아래 저장 계층으로만 남는다.

임시 루트에 SQLite 와 파일을 만든다. `/data/ajt` 를 만지지 않는다 — 요청이 끝나면 임시
디렉터리를 버리고, 반영은 Spring 이 `work/{jobId}` 에서 수행한다 (DR-007·008).

메타데이터 추출은 `tools/write.py`의 `parse_frontmatter`/`extract_frontmatter_field`/
`extract_frontmatter_tags`/`extract_metadata`를 그대로 쓴다 — 그쪽 시그니처가 이 파일이
기대하는 모양(제목/카테고리/태그/날짜를 바로 주는 dict)과 달라서, `write.py`를 고치지 않고
이 파일에서 호출 방식을 맞췄다: `extract_metadata`는 파싱된 frontmatter dict를 받아
`(date_str, metadata)`만 돌려주므로, title/category/tags는 `extract_frontmatter_field`·
`extract_frontmatter_tags`로 따로 뽑는다.

**Task 4: pull 경로 제거.** `client` 위치 인자와 그것으로만 도는 `_hydrate`·`load_page`·
`load_source`(전부 HTTP 로 Spring 을 되물었다)는 `api/session.py`가 `pages=`로 옮기면서
함께 지웠다. `springclient.py` 자체도 지웠다(`docs/AI호출명세서.json`과 함께).
"""

from __future__ import annotations

import json
import re
import uuid
from pathlib import Path

from wiki_mcp.services.chunker import store_chunks

from .local import SOURCES_PREFIX, LocalVaultFS, kind_for

# `tools.write` imports `tools.references`, which imports `vaultfs` (for the
# `VaultFS` type) — and `vaultfs/__init__.py` imports this module. A top-level
# `from wiki_mcp.tools.write import ...` here would therefore run while `vaultfs` is
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


_INDEX_LINK_RE = re.compile(r"\(pages/([^)\s]+?)\.md\)")


def rewrite_index_links(index_markdown: str,
                        address_by_wiki_id: dict[str, str]) -> str:
    """공간 목차의 `pages/{wikiId}.md` 링크를 실제 페이지 주소로 바꾼다.

    백엔드 `WikiIndex` 는 목차를 **항상 wikiId** 로 링크하지만, 페이지는 본문 교차링크에
    맞춰 **pageKey(해시)** 주소로 하이드레이션된다. 한 vault 에서 두 이름이 만나면 목차
    링크가 페이지 주소와 어긋나 `lint` 가 dangling-link 로 죽는다 (두 번째 문서부터 잡
    실패). 목차 링크를 페이지 주소로 맞춰 vault 안을 한 이름으로 통일한다. 지도에 없는
    링크(이미 pageKey 이거나 알 수 없는 대상)는 건드리지 않는다.
    """
    def _replace(match: "re.Match[str]") -> str:
        address = address_by_wiki_id.get(match.group(1))
        return f"({address})" if address else match.group(0)

    return _INDEX_LINK_RE.sub(_replace, index_markdown or "")


class SpringVaultFS(LocalVaultFS):
    @staticmethod
    async def open(root: str | Path, scope_key: str, job_id: str | None) -> str:
        """임시 색인을 열고 scope_id 를 돌려준다. 라이브 층은 채우지 않는다.

        S15P11B106-175 이전에는 `pages`·`index_markdown` 을 받아 요청이 실어 온 위키로
        라이브 층을 채웠다. 그 경로가 사라졌으므로 여기는 색인만 연다 — 조회 API 하이드레이션은
        `FederatedVaultFS.open` 이 한다.
        """
        return await LocalVaultFS.open(root, scope_key, job_id)

    async def _sync_page_references(self, scope_id: str) -> None:
        """라이브 페이지·목차 전부의 참조 그래프를 다시 채운다.

        라이브 층에는 위키 페이지만 들어온다 — 각주가 가리키는 원본문서는 `stage_source`가
        나중에 따로 넣는다. 그래서 이 메서드는 원본문서가 라이브에 새로 들어올 때마다
        (`stage_source`) 다시 불린다 — 그 시점에야 이미 올라와 있던 페이지의 각주가 비로소
        풀린다. 매번 전체를 다시 쓰므로 (`replace_references`) 몇 번을 불러도 안전하다.
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
        chunks = (self._chunks_for_index(content)
                  if self._indexes_live_locally(kind) else [])
        # 빈 목록으로도 부른다 — `store_chunks` 가 먼저 지운다. 안 부르면 이 행이 예전에
        # 색인됐을 때 그 청크가 남는다.
        await store_chunks(db, doc_id, chunks)
        await db.commit()
        return doc_id

    def _indexes_live_locally(self, kind: str) -> bool:
        """이 종류의 라이브 본문을 내부 청크 색인에 넣나.

        이 계층만 쓰는 경우(테스트·재색인)에는 전부 넣는다 — 검색할 곳이 여기뿐이다.
        Wiki 조회 API 모드는 다르다 (`FederatedVaultFS` 가 재정의한다).
        """
        return True
