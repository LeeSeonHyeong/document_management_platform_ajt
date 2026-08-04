"""위키 계열 엔드포인트 2개 — 변환 · 채팅 수정.

**요청은 위키를 실어 오지 않는다** (S15P11B106-175). 목차·카테고리·본문·근거 문서는
전부 Wiki 조회 API 로 읽고, 요청에 남는 것은 작업 번호와 이번 문서의 파싱본뿐이다.
1단계 문맥 선택(`wiki-context-selections`)은 사라졌다 — 무엇을 볼지는 에이전트가
도구로 정한다.

v1.1.0 에서 `wiki-reconciliations` 가 사라지고 `wiki-transformations` 의 `changeType`
분기로 들어왔다 (설계 §1). 걷어내기 로직 자체는 옮겨온 그대로다 — 사라진 문서를 라이브
층에 얹어 참조 그래프를 붙이고, 각주 backlink 로 고칠 곳을 지시문에 적어 준다. 그
backlink 는 하이드레이션이 받아둔 범위 관계를 뒤집어 찾는다
(`FederatedVaultFS.get_citation_backlinks`).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, FastAPI, Request

from agent_runtime.base import edit_instruction, ingest_instruction, reconcile_instruction
from agent_runtime.limits import time_limit_seconds

from ..changes import build_response
from ..deps import make_api_key_guard, request_id
from ..errors import FailureStage, InternalError
from ..schemas import EditRequest, EditResponse, TransformRequest, TransformResponse
from ..session import WikiSession, assert_within_ceiling


def _category_map(categories: list[dict]) -> dict[str, str]:
    """이름 → `wikiCategoryId`. 이미 있는 카테고리를 다시 만들지 않게 하는 입력이다
    (FR-WIKI-014, DR-019 — 카테고리는 에이전트가 관리하고 관리자는 조회만 한다).

    입력이 요청 본문에서 조회 API 응답으로 바뀌었다 (S15P11B106-175). 하이드레이션이
    `GET /wiki-spaces/{scopeKey}/categories` 를 이미 부르므로 새로 조회할 것은 없다.
    """
    return {c.get("name"): c.get("wikiCategoryId") for c in categories
            if c.get("name") and c.get("wikiCategoryId")}


def _session_args(app: FastAPI, payload) -> dict:
    """Wiki 조회 API 세션 인자. 요청에서 오는 것은 허가값 둘뿐이다.

    **`wikiCapability` 를 로그에 찍지 않는다.** 이 함수가 하는 일은 전달뿐이다.

    `query_transport` 는 테스트가 조회 API 를 `httpx.MockTransport` 로 흉내낼 때만 채워진다 —
    프로덕션에서는 `app.state` 에 없으므로 `None` 이고 진짜 소켓이 열린다.
    """
    return {
        "wiki_capability": payload.wikiCapability,
        "scope_version": payload.scopeVersion,
        "backend_base_url": getattr(app.state, "backend_base_url", "") or None,
        "internal_api_key": getattr(app.state, "api_key", "") or None,
        "query_transport": getattr(app.state, "query_transport", None),
    }


async def _assemble(session: WikiSession, *, summary: str,
                    current_categories: dict[str, str]) -> TransformResponse:
    """조립 실패를 `assemble` 단계로 승격한다 (설계 4.5).

    여기서 터지는 것은 에이전트 문제가 아니라 우리 매핑 문제다. 단계를 구분하지 않으면
    Spring 이 `document_results` 에 「에이전트 오류」로 적고 사람이 잘못된 곳을 본다.
    """
    try:
        return await build_response(session.fs, session.scope_id, summary=summary,
                                    current_categories=current_categories)
    except InternalError:
        raise
    except Exception as exc:
        raise InternalError(session.error_code, f"응답을 조립하지 못했습니다 — {exc}",
                            FailureStage.ASSEMBLE) from exc


def build_router(app: FastAPI) -> APIRouter:
    guard = make_api_key_guard(app.state.api_key)
    router = APIRouter(prefix="/internal/v1", dependencies=[Depends(guard)])

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
            runtime=app.state.runtime,
            error_code="WIKI_TRANSFORMATION_FAILED",
            requires_existing_wiki=payload.changeType != "document_added",
            **_session_args(app, payload),
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
                    for edge in await session.citation_backlinks(address)
                ]
                # 교체는 삭제와 다르다: 새 내용이 있고 에이전트가 그것을 읽어야 한다.
                # backlink 는 옛 내용 기준으로 이미 뽑았으므로(고칠 곳은 옛 근거를 인용한
                # 문단이다) 이제 같은 주소의 본문만 새 내용으로 바꿔 얹는다.
                await _restage_replacement(session, payload, address)
                instruction = reconcile_instruction(address, payload.scopeKey,
                                                    payload.changeType, affected)
                limit = time_limit_seconds(len(payload.parsedMarkdown or removed))

            result = await session.run_agent(instruction, limit)
            session.assert_the_agent_looked_at_the_wiki(result)
            await session.assert_lint_clean()
            response = await _assemble(
                session, summary=result.text.strip() or "변경이 없습니다.",
                current_categories=_category_map(session.fs.categories))
            if payload.changeType != "document_added":
                session.assert_backlinks_were_addressed(affected, response)
            # 사라진 문서를 가리키던 Wiki-원본문서 관계는 더 이상 `relationChanges` 로
            # 걷어내지 않는다 (S15P11B106-157) — 그 관계는 애초에 `wiki_document` 타입
            # 이었고, 이제 위키↔문서 연결은 오직 `wikiChanges[].evidence` 로만 나간다.
            # `affected` 목록(그 문서를 인용하던 backlink)은 재조정 지시문에 이미
            # 실려(reconcile_instruction) 에이전트가 각주를 고치므로, 고쳐진 페이지는
            # 스스로 `wikiChanges` 항목과 갱신된 evidence 를 낸다.
            return response

    @router.post("/wiki-edits", response_model=EditResponse)
    async def edit(payload: EditRequest, request: Request,
                   rid: str = Depends(request_id)) -> EditResponse:
        async with WikiSession(
            scope_key=payload.scopeKey, job_id=None, request_id=rid,
            runtime=app.state.runtime, error_code="WIKI_EDIT_FAILED",
            requires_existing_wiki=True,
            **_session_args(app, payload),
        ) as session:
            address = await session.address_for_wiki_id(payload.wikiId)
            # 근거 문서를 조회 API 로 읽어 라이브 층에 올린다. lint 가 각주 인용문을 이
            # 본문과 문자열 대조한다 (NFR-AI-002) — 없으면 정상 각주가 error 로 뜬다.
            evidence_length = await session.stage_evidence_documents(payload.wikiId)
            # 작업량은 페이지 본문 + 근거 문서 길이다. 페이지만 세면 큰 근거 문서를 붙인
            # 수정이 부당하게 짧은 상한을 받는다.
            limit = time_limit_seconds(
                await session.content_length_for(address) + evidence_length)
            result = await session.run_agent(
                edit_instruction(address, payload.scopeKey, payload.instruction,
                                 [m.model_dump() for m in payload.chatHistory]),
                limit)
            session.assert_the_agent_looked_at_the_wiki(result)
            await session.assert_lint_clean()
            base = await _assemble(
                session, summary=result.text.strip() or "변경이 없습니다.",
                current_categories=_category_map(session.fs.categories))
            return EditResponse(agentMessage=result.text.strip(), **base.model_dump())

    return router


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
