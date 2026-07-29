"""공통 오류 구조. `API_컨벤션.md` 6.2 를 따른다.

두 가지가 규약과 다른 방향으로 새기 쉬워서 여기서 못 박는다:

  * FastAPI 기본 검증 오류는 `422` 다. 팀 규약은 `400` + 공통 구조다 (361행)
  * `requestId` 는 본문에 넣지 않는다. `X-Request-Id` 응답 헤더로만 나간다 (6.4)

`failureStage` 는 내부 API 한정이다. FR-AI-001 과 NFR-AI-003 이 실패 단계 저장을 요구하고
`GET /api/v1/ai-jobs/{jobId}` 응답에 `currentStage` 가 있는데, AI 서버가 그 단계를 알려줄
필드가 계약 오류 본문에 없다. 프론트 공개 API 는 이 필드를 노출하지 않는다 — Spring 이 받아
`document_results` 에 저장하고 사람이 읽을 문장으로 바꿔 내보낸다.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from enum import Enum
from http import HTTPStatus

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

logger = logging.getLogger("llmwiki.api")


class FailureStage(str, Enum):
    CONTEXT_LOAD = "context_load"
    AGENT_START = "agent_start"
    AGENT_TIMEOUT = "agent_timeout"
    AGENT_ERROR = "agent_error"
    LINT_FAILED = "lint_failed"
    ASSEMBLE = "assemble"


class InternalError(Exception):
    """계약이 정한 코드와 실패 단계를 들고 있는 오류."""

    def __init__(self, code: str, message: str,
                 failure_stage: FailureStage | None = None, status: int = 500,
                 field_errors: list[dict] | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.failure_stage = failure_stage
        self.status = status
        self.field_errors = field_errors or []


def ErrorBody(status: int, code: str, message: str, path: str, *,
              field_errors: list[dict] | None = None,
              failure_stage: FailureStage | None = None) -> dict:
    body = {
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "status": status,
        "error": HTTPStatus(status).phrase,
        "code": code,
        "message": message,
        "path": path,
        "fieldErrors": field_errors or [],
    }
    if failure_stage is not None:
        body["failureStage"] = failure_stage.value
    return body


# 경로마다 검증 실패 코드가 다르다 — 계약의 Saved Examples 를 따른다.
_VALIDATION_CODES = {
    "/internal/v1/wiki-transformations": (
        "INVALID_WIKI_TRANSFORMATION_REQUEST", "Wiki 변환 요청 구조가 올바르지 않습니다."),
    "/internal/v1/wiki-edits": (
        "INVALID_WIKI_EDIT_REQUEST", "Wiki 수정 지시 또는 문맥이 올바르지 않습니다."),
    "/internal/v1/wiki-context-selections": (
        "INVALID_WIKI_CONTEXT_SELECTION_REQUEST", "Wiki 문맥 선택 요청 구조가 올바르지 않습니다."),
    "/internal/v1/source-parses": (
        "INVALID_SOURCE_PARSE_REQUEST", "파싱 요청 파일 또는 메타데이터가 올바르지 않습니다."),
    # `/internal/v1/wiki-reconciliations` 는 없다 — v1.1.0 계약이 그 경로를 없애고
    # `changeType` 분기로 흡수했다 (설계 §1, Task 3 에서 라우터·테스트와 함께 제거).
}


# 예상 못한 예외에 붙일 코드. 경로별 실패 코드를 그대로 쓴다 — Spring 이 `code` 로 분기하고
# `document_results` 에 기록하므로, 같은 엔드포인트의 실패가 두 이름으로 나오면 안 된다.
_FAILURE_CODES = {
    "/internal/v1/wiki-context-selections": "WIKI_CONTEXT_SELECTION_FAILED",
    "/internal/v1/wiki-transformations": "WIKI_TRANSFORMATION_FAILED",
    "/internal/v1/wiki-edits": "WIKI_EDIT_FAILED",
    "/internal/v1/source-parses": "DOCUMENT_PARSE_FAILED",
    # `/internal/v1/wiki-reconciliations` 는 없다 — v1.1.0 이 그 경로를 `changeType` 분기로
    # 흡수했다. 남겨두면 아무도 도달하지 않는 코드를 Spring 이 분기에 적어 넣는다 (M1).
}


def failure_code_for(path: str) -> str:
    return _FAILURE_CODES.get(path, "INTERNAL_SERVER_ERROR")


def _field_errors(exc: RequestValidationError) -> list[dict]:
    out = []
    for err in exc.errors():
        # loc 은 ("body", "scopeKey") 꼴이다. "body" 를 떼고 점으로 잇는다.
        parts = [str(p) for p in err["loc"] if p != "body"]
        out.append({"field": ".".join(parts) or "body", "reason": err["msg"]})
    return out


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError):
        code, message = _VALIDATION_CODES.get(
            request.url.path, ("INVALID_REQUEST", "요청 구조가 올바르지 않습니다."))
        return JSONResponse(
            status_code=400,
            content=ErrorBody(400, code, message, request.url.path,
                              field_errors=_field_errors(exc)),
        )

    @app.exception_handler(InternalError)
    async def _internal(request: Request, exc: InternalError):
        return JSONResponse(
            status_code=exc.status,
            content=ErrorBody(exc.status, exc.code, exc.message, request.url.path,
                              field_errors=exc.field_errors,
                              failure_stage=exc.failure_stage),
        )

    @app.exception_handler(Exception)
    async def _unexpected(request: Request, exc: Exception):
        """마지막 그물. 없으면 예상 못한 예외가 규약 구조가 아닌 500 으로 나가고 Spring 의
        `code` 분기가 깨진다 (API_컨벤션 6.2).

        `failureStage` 를 붙이지 않는다 — 어느 단계에서 터졌는지 모르는 것이 사실이고,
        아는 단계는 `InternalError` 로 올려 보내는 쪽이 담당한다. 조립 단계는
        `api/routers/wiki.py` 가 `assemble` 로 승격해서 던진다.
        """
        logger.exception("처리하지 못한 예외: %s", request.url.path)
        return JSONResponse(
            status_code=500,
            content=ErrorBody(500, failure_code_for(request.url.path),
                              f"요청을 처리하지 못했습니다 — {exc}", request.url.path),
        )
