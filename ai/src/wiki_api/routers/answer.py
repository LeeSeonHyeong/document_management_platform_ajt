"""챗봇 답변 엔드포인트 2개.

세션을 열지 않는다 — 임시 색인도 MCP 서버도 필요 없고, 그래서 변환 큐의 직렬 잠금도
잡지 않는다. 채팅이 위키 변환을 기다릴 이유가 없다.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, FastAPI

from ..answer import generate_answer
from ..answer_selection import select_answer_context
from ..deps import make_api_key_guard, request_id
from ..schemas import (
    AnswerContextRequest,
    AnswerContextResponse,
    AnswerRequest,
    AnswerResponse,
)


def build_router(app: FastAPI) -> APIRouter:
    guard = make_api_key_guard(app.state.api_key)
    router = APIRouter(prefix="/internal/v1", dependencies=[Depends(guard)])

    @router.post("/answer-context-selections", response_model=AnswerContextResponse)
    async def select_context(payload: AnswerContextRequest,
                             rid: str = Depends(request_id)) -> AnswerContextResponse:
        """1단계 — 목차와 일정 요약만 보고 필요한 자료를 고른다. 본문은 오지 않는다."""
        return await select_answer_context(app.state.runtime, payload, request_id=rid)

    # `response_model_exclude_none` — 계약 예시의 출처는 한쪽 ID 만 갖는다. 안 쓰는 쪽을
    # null 로 실어 보내면 Spring 이 answer_source 에 빈 컬럼을 쓰려 든다.
    @router.post("/answers", response_model=AnswerResponse,
                 response_model_exclude_none=True)
    async def answer(payload: AnswerRequest,
                     rid: str = Depends(request_id)) -> AnswerResponse:
        """2단계 — Spring 이 재검증하고 읽어 온 본문으로 답변을 만든다. 쓰기가 없다."""
        return await generate_answer(app.state.runtime, payload, request_id=rid)

    return router
