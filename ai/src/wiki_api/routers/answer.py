"""챗봇 답변 엔드포인트 하나.

세션을 열지 않는다 — 임시 색인도 MCP 서버도 필요 없고, 그래서 변환 큐의 직렬 잠금도
잡지 않는다. 채팅이 위키 변환을 기다릴 이유가 없다.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, FastAPI

from ..answer import generate_answer
from ..deps import make_api_key_guard, request_id
from ..schemas import AnswerRequest, AnswerResponse


def build_router(app: FastAPI) -> APIRouter:
    guard = make_api_key_guard(app.state.api_key)
    router = APIRouter(prefix="/internal/v1", dependencies=[Depends(guard)])

    # `response_model_exclude_none` — 계약 예시의 출처는 한쪽 ID 만 갖는다. 안 쓰는 쪽을
    # null 로 실어 보내면 Spring 이 answer_source 에 빈 컬럼을 쓰려 든다.
    @router.post("/answers", response_model=AnswerResponse,
                 response_model_exclude_none=True)
    async def answer(payload: AnswerRequest,
                     rid: str = Depends(request_id)) -> AnswerResponse:
        """에이전트가 목차를 보고 스스로 조회해 답한다."""
        return await generate_answer(app.state.runtime, payload,
                                     backend_base_url=app.state.backend_base_url,
                                     api_key=app.state.api_key, request_id=rid)

    return router
