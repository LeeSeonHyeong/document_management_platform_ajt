"""위키 계열 엔드포인트 3개 — 문맥 선택 · 변환 · 채팅 수정.

v1.1.0 에서 `wiki-reconciliations` 가 사라지고 `wiki-transformations` 의 `changeType`
분기로 들어왔다 (설계 §1). 걷어내기 로직 자체는 옮겨온 그대로다 — 사라진 문서를 라이브
층에 얹어 참조 그래프를 붙이고, 각주 backlink 로 고칠 곳을 지시문에 적어 준다.

되묻지 않는다: 라이브 층의 재료는 요청의 `selectedWikis`·`currentIndex` 이고 원본문서
본문도 요청이 준다. HTTP 왕복 0회.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, FastAPI, Request

from agent_runtime.base import edit_instruction, ingest_instruction, reconcile_instruction
from agent_runtime.limits import time_limit_seconds

from ..changes import build_response
from ..deps import make_api_key_guard, request_id
from ..errors import FailureStage, InternalError
from ..schemas import CategoryRef, EditRequest, EditResponse, RelationChange, \
    SelectionRequest, SelectionResponse, TransformRequest, TransformResponse
from ..selection import select_wikis
from ..session import WikiSession, assert_within_ceiling


def _category_map(categories: list[CategoryRef]) -> dict[str, str]:
    """이름 → `wikiCategoryId`. 이미 있는 카테고리를 다시 만들지 않게 하는 입력이다
    (FR-WIKI-014, DR-019 — 카테고리는 에이전트가 관리하고 관리자는 조회만 한다)."""
    return {c.name: c.wikiCategoryId for c in categories if c.name and c.wikiCategoryId}


def _hydration_pages(payload: TransformRequest) -> list[dict]:
    """`selectedWikis` → `SpringVaultFS.open(pages=…)` 가 받는 dict.

    `categoryId` 를 `currentCategories` 로 이름까지 풀어 준다. 페이지 본문에 frontmatter
    가 있으면 그쪽이 이기지만(`spring.py._insert_live`), 없는 페이지도 카테고리를 잃지
    않아야 한다 — 잃으면 에이전트가 이미 있는 분류를 새로 만들자고 낸다(C4 수렴 상실).
    """
    names = {c.wikiCategoryId: c.name for c in payload.currentCategories}
    return [{
        "wikiId": wiki.wikiId,
        "title": wiki.title,
        "summary": wiki.summary,
        "contentMarkdown": wiki.contentMarkdown,
        "categoryName": names.get(wiki.categoryId or ""),
    } for wiki in payload.selectedWikis]


async def _assemble(session: WikiSession, *, summary: str,
                    current_categories: dict[str, str]) -> TransformResponse:
    """조립 실패를 `assemble` 단계로 승격한다 (설계 4.5).

    여기서 터지는 것은 에이전트 문제가 아니라 우리 매핑 문제다. 단계를 구분하지 않으면
    Spring 이 `document_results` 에 「에이전트 오류」로 적고 사람이 잘못된 곳을 본다.
    """
    try:
        return await build_response(session.fs, session.scope_id, summary=summary,
                                    current_categories=current_categories,
                                    live_citations=session.live_citations)
    except InternalError:
        raise
    except Exception as exc:
        raise InternalError(session.error_code, f"응답을 조립하지 못했습니다 — {exc}",
                            FailureStage.ASSEMBLE) from exc


def build_router(app: FastAPI) -> APIRouter:
    guard = make_api_key_guard(app.state.api_key)
    router = APIRouter(prefix="/internal/v1", dependencies=[Depends(guard)])

    @router.post("/wiki-context-selections", response_model=SelectionResponse)
    async def select(payload: SelectionRequest, request: Request,
                     rid: str = Depends(request_id)) -> SelectionResponse:
        """1단계 — 목차만 보고 이번 변환에 필요한 위키를 고른다 (설계 §5).

        세션을 열지 않는다: 임시 색인도 MCP 서버도 필요 없고, 그래서 전역 직렬
        잠금(`_SESSION_LOCK`)도 잡지 않는다 — 선택이 변환 큐를 막으면 안 된다.
        """
        return await select_wikis(app.state.runtime, payload, request_id=rid)

    @router.post("/wiki-transformations", response_model=TransformResponse)
    async def transform(payload: TransformRequest, request: Request,
                        rid: str = Depends(request_id)) -> TransformResponse:
        # removed·replaced 에 `removedParsedMarkdown` 이 있어야 한다는 것은 스키마가
        # 막는다 (`TransformRequest._removed_body_is_required` — 400 + fieldErrors).
        removed = payload.removedParsedMarkdown or ""

        # 크기 검사는 세션 밖에서 — `assert_within_ceiling` 주석 참고. 교체는 **두 본문이
        # 모두** 색인된다(옛 본문으로 backlink 를 잡고 새 본문으로 덮어쓴다) — 큰 쪽으로
        # 재지 않으면 옛 본문이 40k 여도 새 본문이 짧다는 이유로 통과한다.
        assert_within_ceiling(
            max([payload.parsedMarkdown, removed], key=len)
            if payload.changeType == "document_replaced"
            else (removed if payload.changeType == "document_removed"
                  else payload.parsedMarkdown),
            "WIKI_TRANSFORMATION_FAILED")

        async with WikiSession(
            scope_key=payload.scopeKey, job_id=payload.jobId, request_id=rid,
            runtime=app.state.runtime, pages=_hydration_pages(payload),
            index_markdown=payload.currentIndex,
            error_code="WIKI_TRANSFORMATION_FAILED",
        ) as session:
            affected: list[dict] = []
            if payload.changeType == "document_added":
                address = await session.load_source(payload.documentId,
                                                    payload.parsedMarkdown)
                instruction = ingest_instruction(address, payload.scopeKey)
                limit = time_limit_seconds(len(payload.parsedMarkdown))
            else:
                # 사라진 문서의 **옛** 본문을 먼저 얹는다. `stage_source` 가 참조 그래프를
                # 다시 채우므로(`SpringVaultFS._sync_page_references`) 하이드레이션된
                # 페이지의 각주가 바로 이 시점에 풀리고, 그래야 backlink 가 잡힌다.
                address = await session.load_source(payload.documentId, removed)
                affected = [
                    {"address": edge["address"], "footnote": edge["footnote_label"],
                     "location": edge["location"], "quote": edge["quote"]}
                    for edge in await session.fs.get_citation_backlinks(
                        session.scope_id, address)
                ]
                # 교체는 삭제와 다르다: 새 내용이 있고 에이전트가 그것을 읽어야 한다.
                # backlink 는 옛 내용 기준으로 이미 뽑았으므로(고칠 곳은 옛 근거를 인용한
                # 문단이다) 이제 같은 주소의 본문만 새 내용으로 바꿔 얹는다.
                await _restage_replacement(session, payload, address)
                instruction = reconcile_instruction(address, payload.scopeKey,
                                                    payload.changeType, affected)
                limit = time_limit_seconds(len(payload.parsedMarkdown or removed))

            result = await session.run_agent(instruction, limit)
            await session.assert_lint_clean()
            response = await _assemble(
                session, summary=result.text.strip() or "변경이 없습니다.",
                current_categories=_category_map(payload.currentCategories))
            if payload.changeType == "document_removed":
                await _append_removal_unlinks(response, session, affected,
                                              payload.documentId)
            return response

    @router.post("/wiki-edits", response_model=EditResponse)
    async def edit(payload: EditRequest, request: Request,
                   rid: str = Depends(request_id)) -> EditResponse:
        async with WikiSession(
            scope_key=payload.scopeKey, job_id=None, request_id=rid,
            runtime=app.state.runtime, pages=_edit_pages(payload),
            # 수정 요청에는 목차가 없다 (계약 그대로 — `wiki-edits` 는 변경 없음).
            # 빈 목차를 얹는다: 에이전트가 요약을 고치면 그 변경만 작업 층에 남고,
            # Spring 은 `indexEntries` 로 목차를 재구성한다 (설계 §3).
            index_markdown="", error_code="WIKI_EDIT_FAILED",
        ) as session:
            address = await session.address_for_wiki_id(payload.wikiId)
            # 요청이 원본문서를 주므로 되물을 필요가 없다. lint 원문 대조용으로 넣어둔다.
            for document in payload.evidenceDocuments:
                await session.stage_source(
                    document.documentId, document.parsedMarkdown,
                    document.originalFileName)
            # 작업량은 페이지 본문 + 근거 문서 길이다. 페이지만 세면 큰 근거 문서를 붙인
            # 수정이 부당하게 짧은 상한을 받는다.
            limit = time_limit_seconds(
                len(payload.currentWiki.contentMarkdown)
                + sum(len(d.parsedMarkdown) for d in payload.evidenceDocuments))
            result = await session.run_agent(
                edit_instruction(address, payload.scopeKey, payload.instruction,
                                 [m.model_dump() for m in payload.chatHistory]),
                limit)
            await session.assert_lint_clean()
            base = await _assemble(
                session, summary=result.text.strip() or "변경이 없습니다.",
                current_categories=_category_map(payload.currentCategories))
            return EditResponse(agentMessage=result.text.strip(), **base.model_dump())

    return router


def _edit_pages(payload: EditRequest) -> list[dict]:
    """수정 대상 페이지 1장으로 라이브 층을 채운다.

    `wiki-edits` 는 `selectedWikis` 를 받지 않는다(계약 무변경). 대신 `currentWiki` 가
    본문을 들고 오므로 그것이 곧 이번 세션의 위키다 — 주소는 `pages/{wikiId}.md` 다
    (설계 §3).

    **본문이 비면 아무것도 얹지 않는다.** 그러면 `address_for_wiki_id` 가 404 를 낸다 —
    Spring 이 위키를 읽지 못한 채(삭제됐거나 범위 밖) 요청을 보낸 경우이며, 없는 페이지를
    지어내 편집하는 것보다 없다고 답하는 쪽이 맞다.
    """
    if not (payload.currentWiki.contentMarkdown or "").strip():
        return []
    return [{"wikiId": payload.wikiId, "title": payload.currentWiki.title,
             "contentMarkdown": payload.currentWiki.contentMarkdown}]


async def _restage_replacement(session: WikiSession, payload: TransformRequest,
                               address: str) -> None:
    """교체된 새 내용을 같은 주소에 얹는다.

    이것이 없으면 `parsedMarkdown`(새 본문)이 버려져 에이전트가 새 내용을 볼 방법이 아예
    없고, 새 내용을 근거로 쓴 인용은 `lint` 원문 대조에서 error 가 된다 — 교체 재조정이
    성립하지 않는다.

    원래 파일명은 유지한다. 각주가 파일명으로 문서를 가리키므로 이름이 바뀌면 방금 풀린
    각주가 다시 미해결이 된다.
    """
    if payload.changeType != "document_replaced" or not payload.parsedMarkdown:
        return
    row = await session.fs.get(session.scope_id, address)
    await session.stage_source(payload.documentId, payload.parsedMarkdown,
                               (row or {}).get("original_file_name"))


async def _append_removal_unlinks(response: TransformResponse, session: WikiSession,
                                  affected: list[dict], document_id: str) -> None:
    """삭제된 문서를 가리키던 관계를 걷어낸다 (DR-002).

    이 관계는 문서가 사라졌다는 사실 자체에서 나온다 — 에이전트가 본문의 각주를 지웠는지와
    별개로, 사라진 문서를 가리키는 링크는 반영 시점에 끊어야 한다.
    """
    # 조립 단계가 이미 낸 unlink 는 다시 내지 않는다 — 각주를 떨어뜨린 페이지는 I4 경로에서
    # 같은 (wikiRef, documentId) 로 이미 나왔다.
    seen: set[tuple[str, str]] = {
        (relation.wikiRef, relation.documentId or "")
        for relation in response.relationChanges
        if relation.action == "unlink" and relation.type == "wiki_document"
    }
    for item in affected:
        row = await session.fs.get(session.scope_id, item["address"])
        wiki_ref = str(row["wiki_id"]) if row and row.get("wiki_id") else None
        if not wiki_ref or (wiki_ref, document_id) in seen:
            continue
        seen.add((wiki_ref, document_id))
        response.relationChanges.append(RelationChange(
            action="unlink", type="wiki_document", wikiRef=wiki_ref, documentId=document_id))
