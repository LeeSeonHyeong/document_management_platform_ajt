"""내부 API 인증과 요청 추적.

`API_컨벤션.md` 8.1: AI 서버 내부 API 는 사용자 JWT 대상이 아니고 서버 간 인증으로 분리한다.
사용자 역할·부서·공개 범위 검증은 Spring 이 하며 AI 서버는 하지 않는다.
"""

from __future__ import annotations

import uuid

from fastapi import Header, Request

from .errors import InternalError


def make_api_key_guard(expected: str):
    async def require_api_key(x_internal_api_key: str = Header(default="")) -> None:
        if not expected or x_internal_api_key != expected:
            raise InternalError("INVALID_INTERNAL_API_KEY",
                                "내부 API 키가 올바르지 않습니다.", status=401)

    return require_api_key


async def request_id(request: Request,
                     x_request_id: str = Header(default="")) -> str:
    """Spring 이 준 requestId. 없으면 만든다 (API_컨벤션 6.4).

    **미들웨어(`app.py`)가 이미 발급한 값을 재사용한다.** 둘이 각자 만들면 응답 헤더의
    `X-Request-Id` 와 Spring 읽기 4개에 붙는 값이 달라진다 — 요청 단위 열람 허가가 그
    값 위에 서 있으므로(설계 4.1) 어긋나면 Spring 이 정당한 되묻기를 404 로 거부한다.
    """
    rid = getattr(request.state, "request_id", "") or x_request_id \
        or f"ai-{uuid.uuid4().hex[:16]}"
    request.state.request_id = rid
    return rid
