"""작업 층 diff 를 계약 응답으로 바꾼다.

계약 모양을 아는 유일한 곳이다. `mcp/` 는 계약을 모르고 이 파일은 SQLite 를 모른다 —
`pending_changes()` 반환값이 둘 사이의 유일한 접점이다.

`pageKey` ↔ `tempWikiId`: 둘 다 "DB 행이 생기기 전에 이름을 발급한다"는 같은 발상이고,
계약은 그것을 파일명이 아니라 응답 안 참조로 표현한다. 그래서 매핑이 1:1 이고 커밋 시
파일명을 바꿀 일이 없다.
"""

from __future__ import annotations

import re

from .schemas import (CategoryChange, Evidence, IndexEntry, RelationChange,
                      TransformResponse, WikiChange)

_PAGE_LINK = re.compile(r"^\s*[-*]\s*\[([^\]]+)\]\(([^)]+?\.md)\)\s*(?:[-–—]\s*(.+))?$")


def _page_key(address: str) -> str | None:
    if address.startswith("pages/") and address.endswith(".md"):
        return address[len("pages/"):-3]
    return None


class TempRefs:
    """`pageKey` → 계약 참조. 신규는 `wiki-temp-N`, 기존은 실제 `wikiId`."""

    def __init__(self) -> None:
        self._new: dict[str, str] = {}
        self._existing: dict[str, str] = {}

    def bind_existing(self, page_key: str, wiki_id: str) -> str:
        self._existing[page_key] = wiki_id
        return wiki_id

    def for_new_page(self, page_key: str) -> str:
        if page_key not in self._new:
            self._new[page_key] = f"wiki-temp-{len(self._new) + 1}"
        return self._new[page_key]

    def ref_for(self, page_key: str) -> str | None:
        return self._existing.get(page_key) or self._new.get(page_key)


def parse_index_entries(index_markdown: str, refs: TempRefs) -> list[IndexEntry]:
    """에이전트가 쓴 목차 마크다운에서 구조를 뽑는다.

    계약이 `indexEntries` 배열을 요구한다. 없는 페이지를 가리키는 줄은 버린다 — Spring 에
    매달린 참조를 넘기면 반영 시점에 깨진다.
    """
    entries: list[IndexEntry] = []
    for line in index_markdown.splitlines():
        match = _PAGE_LINK.match(line)
        if not match:
            continue
        title, target, summary = match.groups()
        target_address = f"pages/{target.split('/')[-1]}"
        key = _page_key(target_address)
        ref = refs.ref_for(key) if key else None
        if not ref:
            continue
        entries.append(IndexEntry(wikiRef=ref, order=len(entries) + 1,
                                  title=title.strip(),
                                  summary=(summary or "").strip() or None))
    return entries


class CategoryRefs:
    """카테고리 이름 → 계약 참조.

    이미 있는 이름은 그 `wikiCategoryId` 를 가리키고 `categoryChanges` 에 넣지 않는다.
    새 이름만 `create` + `tempCategoryId` 다.

    12건 측정에서 카테고리가 5개로 수렴했다. 기존 이름을 매번 `create` 로 내면 Spring 이
    같은 이름을 반복 생성해 그 수렴이 응답 층에서 무너진다. 카테고리는 에이전트가 관리하고
    관리자는 조회만 하므로(FR-WIKI-014, DR-019) 중복을 걸러낼 사람이 없다.
    """

    def __init__(self, current: dict[str, str] | None = None):
        self._current = {name: str(cid) for name, cid in (current or {}).items() if cid}
        self._created: dict[str, CategoryChange] = {}

    def ref_for(self, name: str) -> str:
        existing = self._current.get(name)
        if existing:
            return existing
        change = self._created.get(name)
        if change is None:
            change = CategoryChange(
                action="create", tempCategoryId=f"category-temp-{len(self._created) + 1}",
                name=name)
            self._created[name] = change
        return change.tempCategoryId

    def changes(self) -> list[CategoryChange]:
        return list(self._created.values())


async def build_response(fs, scope_id: str, *, summary: str,
                         current_categories: dict[str, str] | None = None,
                         ) -> TransformResponse:
    """Wiki-원본문서 관계(`wiki_document`)는 여기서 더 이상 relationChanges 로 내지 않는다
    (S15P11B106-157).

    Spring 은 evidence 를 `document_refs` 에 추가한다. 인용이 끊어진 항목을 걷어내는
    경로는 Spring 에 아직 없다 (별건) — 하지만 `wikiChanges[].evidence` 가 갱신된 인용
    목록을 그대로 실어 매 요청마다 이번 문서와의 연결을 다시 알리므로, 떨어진 각주를
    별도 `remove` 로 알릴 필요는 없다.
    """
    changes = await fs.pending_changes(scope_id)

    refs = TempRefs()
    # 먼저 참조를 전부 확정한다 — relationChanges 가 다른 변경을 가리킬 수 있다.
    for change in changes:
        key = _page_key(change["address"])
        if not key:
            continue
        if change.get("wikiId"):
            refs.bind_existing(key, str(change["wikiId"]))
        else:
            refs.for_new_page(key)
    # 라이브에만 있는 페이지도 목차가 가리킬 수 있다.
    for row in await fs.list_documents(scope_id):
        key = _page_key(row["address"])
        if key and row.get("wiki_id") and not refs.ref_for(key):
            refs.bind_existing(key, str(row["wiki_id"]))

    wiki_changes: list[WikiChange] = []
    relations: list[RelationChange] = []
    categories = CategoryRefs(current_categories)

    for change in changes:
        key = _page_key(change["address"])
        if key is None:
            continue                      # index.md 는 indexEntries 로 나간다
        ref = refs.ref_for(key)
        is_new = not change.get("wikiId")
        evidence = [Evidence(**e) for e in change.get("evidence") or []]

        row = await fs.get(scope_id, change["address"])
        category_ref = (categories.ref_for(change["category"])
                        if change.get("category") else None)
        wiki_changes.append(WikiChange(
            action=change["type"],
            tempWikiId=ref if is_new else None,
            wikiId=None if is_new else ref,
            # 신규에만 실어 보낸다 (I1). 기존 위키의 실제 `wiki_path` 는 Spring 만 알고,
            # 여기 있는 값은 이번 세션의 하이드레이션 주소(`pages/{wikiId}.md`)일 뿐이다 —
            # 그것을 되돌려주면 Spring 이 DR-016 컬럼을 덮어써 그 위키 파일이 404 가 된다.
            wikiPath=(change.get("wikiPath") if change["type"] == "create" else None),
            title=change.get("title"),
            contentMarkdown=(row or {}).get("content"),
            wikiCategoryRef=category_ref,
            mergedIntoRef=(refs.ref_for(_page_key(change["mergedInto"]) or "")
                           if change.get("mergedInto") else None),
            evidence=evidence,
        ))

        action = "remove" if change["type"] in ("remove", "merge") else "add"
        # Wiki-원본문서 관계(`wiki_document`)는 더 이상 여기서 내지 않는다
        # (S15P11B106-157) — `wikiChanges[].evidence` 가 같은 정보를 나르고
        # `originDocumentId` 를 항상 포함해 evidence 가 비어도 이번 문서와의 연결이
        # 보장된다. `relationChanges` 는 이제 위키↔위키(`wiki_wiki`) 전용이다.
        for edge in await fs.get_forward_references(scope_id, change["address"]):
            if edge["reference_type"] != "links_to":
                continue
            # `get_forward_references()` 는 **대상** 문서 행을 조인해 주므로 그 주소의 컬럼
            # 이름은 `address` 다 (`mcp/vaultfs/local.py` 의 SELECT). `target_address` 는
            # `document_references` 테이블의 컬럼명이고 이 결과에는 없다 — 그 이름을 읽던
            # 앞 판본은 **인라인 위키 링크가 있는 페이지마다 KeyError** 로 조립을 500 으로
            # 만들었다(`failureStage: assemble`). `get_backlinks`·`search.py`·
            # `references.py` 도 전부 `address` 를 읽는다.
            target = refs.ref_for(_page_key(edge["address"]) or "")
            if target:
                relations.append(RelationChange(action=action, type="wiki_wiki",
                                                sourceWikiRef=ref, targetWikiRef=target))

    index_row = await fs.get(scope_id, "index.md")
    return TransformResponse(
        summary=summary,
        categoryChanges=categories.changes(),
        wikiChanges=wiki_changes,
        relationChanges=_dedupe(relations),
        indexEntries=parse_index_entries((index_row or {}).get("content") or "", refs),
    )


def _dedupe(relations: list[RelationChange]) -> list[RelationChange]:
    seen, out = set(), []
    for relation in relations:
        key = (relation.action, relation.type, relation.sourceWikiRef,
               relation.targetWikiRef)
        if key not in seen:
            seen.add(key)
            out.append(relation)
    return out
