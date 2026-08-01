"""일정 추출 — `POST /internal/v1/schedule-extractions` (계약 v1.3.1 신설, v1.8.0 까지 동일).

이 파일의 백엔드 참조는 **줄번호가 아니라 메서드 이름**으로 적는다 — 줄번호는 상대
파일이 커밋 한 번만 받아도 썩고, 썩은 참조는 없는 참조보다 나쁘다.

Spring 의 일정 원본문서 업로드가 파싱 직후 동기로 부른다. 상태가 없다 — 요청 본문의
`parsedMarkdown` 하나만 보고 결과를 낸다.

이 모듈은 **경계만** 맡는다: 계약 요청을 추출기 입력으로, 추출기 결과를 계약 응답으로
바꾸고, 공개 범위를 붙이고, status 를 정한다. 프롬프트·시각 변환은 `schedule_extractor` 다.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, FastAPI

from schedule_extractor import ExtractedSchedule, extract_schedules
from schedule_extractor.date_probe import has_date_pattern

from ..deps import make_api_key_guard, request_id
from ..errors import InternalError
from ..schemas import (ExtractedScheduleOut, ScheduleExtractionRequest,
                       ScheduleExtractionResponse)

logger = logging.getLogger("llmwiki.api")

_FAILED = "SCHEDULE_EXTRACTION_FAILED"


def _rfc3339(value: datetime) -> str:
    """RFC 3339 UTC. 마이크로초는 있을 때만 적는다 — 종일 일정의 종료가 그렇다.

    `Instant.parse` 가 둘 다 받는다
    (`ScheduleExtractionResponse.ExtractedSchedule.startAtInstant`). 저장 컬럼도
    `schedule.start_at`·`end_at` 이 `DATETIME(6)` 이라 마이크로초가 깎이지 않는다
    (`docs/db/erd.sql`).
    """
    utc = value.astimezone(timezone.utc)
    base = utc.strftime("%Y-%m-%dT%H:%M:%S")
    fraction = f".{utc.microsecond:06d}" if utc.microsecond else ""
    return f"{base}{fraction}Z"


def _to_contract(schedule: ExtractedSchedule,
                 payload: ScheduleExtractionRequest) -> ExtractedScheduleOut:
    """공개 범위는 요청값으로 채운다.

    백엔드가 응답의 이 값을 안 믿고 요청값을 쓰지만(`ScheduleSourceService.toDrafts`)
    검증은 비어 있으면 거부한다(`RestClientAiClient.isInvalid`). 추출기가 공개 범위를
    아예 모르게 두면 모델이 그것을 바꿀 여지가 없다.
    """
    return ExtractedScheduleOut(
        order=schedule.order,
        title=schedule.title,
        content=schedule.content,
        targetText=schedule.target_text,
        location=schedule.location,
        visibilityType=payload.visibilityType,
        departmentIds=list(payload.departmentIds),
        startAt=_rfc3339(schedule.start_at),
        endAt=_rfc3339(schedule.end_at),
    )


def build_router(app: FastAPI) -> APIRouter:
    guard = make_api_key_guard(app.state.api_key)
    router = APIRouter(prefix="/internal/v1", dependencies=[Depends(guard)])

    @router.post("/schedule-extractions", response_model=ScheduleExtractionResponse)
    async def extract(payload: ScheduleExtractionRequest,
                      rid: str = Depends(request_id)) -> ScheduleExtractionResponse:
        del rid  # 추적 ID 는 미들웨어가 헤더로 붙인다.

        try:
            result = await extract_schedules(
                payload.parsedMarkdown,
                now=datetime.now(timezone.utc),
                provider=app.state.schedule_provider)
        except Exception as error:  # noqa: BLE001 — 아래 주석
            # ProviderError 만 잡으면 어댑터가 흘린 httpx·json 예외가 errors.py:141 의
            # 마지막 그물로 가고, 그 그물은 예외 문자열을 응답에 싣는다. URL·모델명·API
            # 키 조각이 Spring 로그로 넘어갈 수 있다 (설계 §3.6).
            logger.exception("일정 추출 실패")
            raise InternalError(_FAILED, "일정 추출에 실패했습니다.", status=500) from error

        _reject_if_the_extraction_collapsed(result, payload.parsedMarkdown)

        schedules = [_to_contract(item, payload) for item in result.schedules]
        _assert_consistent(schedules)
        return ScheduleExtractionResponse(
            status="extracted" if schedules else "no_schedule",
            schedules=schedules,
            warnings=list(result.warnings),
        )

    return router


def _reject_if_the_extraction_collapsed(result, markdown: str) -> None:
    """살아남은 일정이 0건일 때 `no_schedule` 과 실패를 가른다 (설계 §3.3).

    `no_schedule` 은 백엔드가 원본·파싱 파일을 지우고 관리자에게 "일정이 없다" 고
    답하는 신호다. 모델이 흘렸을 뿐인데 그렇게 답하면 관리자는 재시도할 이유를
    얻지 못한다.
    """
    if result.schedules:
        return
    if result.raw_count > 0:
        raise InternalError(_FAILED, "추출한 일정을 모두 사용할 수 없었습니다.", status=500)
    if has_date_pattern(markdown):
        raise InternalError(
            _FAILED, "문서에 날짜가 있으나 일정을 추출하지 못했습니다.", status=500)


def _assert_consistent(schedules: list[ExtractedScheduleOut]) -> None:
    """조립 결과를 스스로 본다.

    백엔드 검증(`RestClientAiClient.validateResponse`·`isInvalid`)은 필드 형식만 본다 —
    `order` 중복·양수·연속을 확인하지 않는다. 여기서 어긋나면 조립 버그이므로 500 이다.

    `normalize` 가 항상 1..N 으로 다시 매기므로 지금은 도달하지 않는다. 방어로 남긴다 —
    조립 경로가 하나 더 생기면 여기가 먼저 잡는다.
    """
    orders = [item.order for item in schedules]
    if orders != list(range(1, len(schedules) + 1)):
        raise InternalError(_FAILED, "일정 순서를 조립하지 못했습니다.", status=500)
