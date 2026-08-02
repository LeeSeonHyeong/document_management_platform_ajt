"""원본문서 파싱 — `POST /internal/v1/source-parses` (계약 v1.3.0).

Spring Boot 의 Wiki 비동기 작업과 일정 동기 처리가 공용으로 부른다. 파싱 자체는
`document_parser` 가 하고, 이 모듈은 **경계만** 맡는다: multipart 를 받아 파서에
넘기고, 파서의 결과와 오류를 계약이 정한 응답·오류 코드로 바꾼다.

계약이 이 경로에 허용한 상태는 400·401·500 셋뿐이다. 그래서 실패를 두 갈래로 나눈다.

  * **400** — 요청이 잘못됐다. 미지원 형식, 알 수 없는 `sourceType`, 빈 파일,
    메타데이터 누락. Spring 이 요청을 고쳐야 하는 경우이므로 재시도해도 같다.
  * **500 `DOCUMENT_PARSE_FAILED`** — 요청은 맞는데 내용을 읽지 못했다. 손상된 파일,
    OCR 실패. 문서 상태를 실패로 기록할 대상이다.

형식 판단에 쓰는 이름은 `originalFileName` 이다. multipart 의 파일명은 Spring 이 만든
임시 이름일 수 있어서 확장자를 믿을 수 없다.
"""

from __future__ import annotations

import tempfile
from pathlib import Path

from document_parser import ParseOptions, parse
from fastapi import APIRouter, Depends, FastAPI, File, Form, UploadFile

from ..deps import make_api_key_guard, request_id
from ..errors import InternalError
from ..schemas import SourceParseResponse

# 계약의 「정책」 절. 일정은 CSV·XLSX 도 받아야 하지만 파서가 아직 지원하지 않는다 —
# 지원 형식만 여기 적어 두고, 나머지는 미지원 형식과 같은 400 으로 돌려보낸다.
_ALLOWED_SUFFIXES = {
    "wiki": {".txt", ".md", ".pdf", ".docx"},
    "schedule": {".txt", ".md", ".pdf", ".docx"},
}

# 파서가 형식·입력을 문제 삼은 경우. 나머지 오류 코드는 추출 실패로 본다.
_REQUEST_ERROR_CODES = {"unsupported_file_type", "file_not_found", "decode_failed"}

_BAD_REQUEST = "INVALID_SOURCE_PARSE_REQUEST"
_PARSE_FAILED = "DOCUMENT_PARSE_FAILED"


def _bad_request(message: str, field: str | None = None) -> InternalError:
    fields = [{"field": field, "reason": message}] if field else []
    return InternalError(_BAD_REQUEST, "파싱 요청 파일 또는 메타데이터가 올바르지 않습니다.",
                         status=400, field_errors=fields)


def build_router(app: FastAPI) -> APIRouter:
    guard = make_api_key_guard(app.state.api_key)
    router = APIRouter(prefix="/internal/v1", dependencies=[Depends(guard)])

    @router.post("/source-parses", response_model=SourceParseResponse)
    async def parse_source(
        file: UploadFile = File(...),
        requestId: str = Form(...),
        sourceType: str = Form(...),
        sourceId: str = Form(...),
        originalFileName: str = Form(...),
        mimeType: str = Form(default=""),
        rid: str = Depends(request_id),
    ) -> SourceParseResponse:
        del mimeType, rid  # Spring 이 이미 검증했다. 추적 ID 는 미들웨어가 헤더로 붙인다.

        if sourceType not in _ALLOWED_SUFFIXES:
            raise _bad_request(
                f"sourceType은 {' 또는 '.join(sorted(_ALLOWED_SUFFIXES))}이어야 합니다.",
                "sourceType")

        suffix = Path(originalFileName).suffix.lower()
        if suffix not in _ALLOWED_SUFFIXES[sourceType]:
            raise _bad_request(
                f"{sourceType} 원본문서가 지원하지 않는 형식입니다: "
                f"{suffix or originalFileName}", "originalFileName")

        content = await file.read()
        if not content:
            raise _bad_request("빈 파일입니다.", "file")

        # 스캔 PDF OCR 엔진(GMS 비전). serve 가 설정에서 만들어 올린다. 없으면(테스트에서
        # create_app 만 쓰거나 설정이 없을 때) None → parse_pdf 가 로컬 Tesseract 로 폴백.
        result = _parse_bytes(content, suffix, getattr(app.state, "vision_ocr", None))

        if result.error is not None:
            if result.error.code in _REQUEST_ERROR_CODES:
                raise _bad_request(result.error.message, "file")
            raise InternalError(_PARSE_FAILED, result.error.message, status=500)

        return SourceParseResponse(
            requestId=requestId,
            sourceType=sourceType,
            sourceId=sourceId,
            parsedMarkdown=result.text,
            warnings=list(result.warnings),
        )

    return router


def _parse_bytes(content: bytes, suffix: str, ocr_engine=None):
    """파서는 경로를 받는다. 업로드 본문을 임시 파일로 떨어뜨려 넘기고 바로 지운다 —
    AI 서버는 서비스 파일에 쓰지 않는다 (계약 「정책」)."""
    with tempfile.TemporaryDirectory(prefix="ajt-parse-") as tmp:
        path = Path(tmp) / f"source{suffix}"
        path.write_bytes(content)
        return parse(path, ParseOptions(ocr_language="kor+eng",
                                        ocr_engine=ocr_engine))
