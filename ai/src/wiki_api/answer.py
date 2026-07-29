"""챗봇 2단계 — 답변 생성 (`POST /internal/v1/answers`).

**쓰기가 없다.** 그래서 에이전트도 MCP 도 필요 없다. 챗봇에 에이전트를 쓰지 않는 이유는
권한이 아니라 쓸 것이 없어서다.

`sources` 는 모델이 답변과 함께 내고 우리가 받은 ID 집합으로 거른다. 한계를 명시한다 —
**"지어낸 ID"는 막지만 "안 쓴 자료를 출처로 신고하는 것"은 막지 못한다.** 각주 강제(위키
에이전트 방식)로 기계 검증할 수 있지만 계약에 답변 본문의 각주 표기 형식이 없다.

JSON 이 깨져도 답변을 버리지 않는다. 모델이 내용은 맞게 답하고 포맷만 틀리는 경우가 흔하고,
버리고 재호출하면 같은 크레딧을 다시 쓴다. `answer` 가 실제로 비었을 때만 실패시킨다 —
그때는 살릴 것이 없다. `ai_answer.content` 가 `TEXT NOT NULL` 이라는 것은 근거가 아니다:
MySQL 의 NOT NULL 은 빈 문자열을 막지 않는다.
"""

from __future__ import annotations

import json

from agent_runtime.base import QUALITY, answer_instruction

from .completion import complete, input_bytes
from .errors import InternalError
from .schemas import ANSWER_PATH, AnswerRequest, AnswerResponse, AnswerSource

# 본문을 읽고 답변을 만든다. 1단계보다 길게 준다.
ANSWER_TIMEOUT_SECONDS = 60

ERROR_CODE = "ANSWER_GENERATION_FAILED"
VALIDATION_CODE = "INVALID_ANSWER_GENERATION_REQUEST"

# 토큰 60,000 을 보수적 상계 0.5 토큰/바이트로 환산. 5페이지 최악(50KB)의 2.4배다.
MAX_INPUT_BYTES = 120_000


def _json_object(text: str) -> dict | None:
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _sources_of(raw, wikis: dict[str, str],
                schedules: dict[str, str]) -> list[AnswerSource]:
    """모델이 낸 출처 목록 → 계약 모양. 받은 ID 만, 중복 없이.

    `type` 과 `title` 은 모델 선언을 쓰지 않는다 — 어느 배열에 실재하는지로 종류를 정하고
    제목은 요청이 실어 온 값을 쓴다. 모델이 제목을 바꿔 쓰면 `answer_source.source_title`
    에 실제와 다른 문자열이 저장된다.
    """
    if not isinstance(raw, list):
        return []
    out: list[AnswerSource] = []
    seen: set[tuple[str, str]] = set()
    for item in raw:
        if not isinstance(item, dict):
            continue
        wiki_id = str(item.get("wikiId") or "").strip()
        schedule_id = str(item.get("scheduleId") or "").strip()
        in_wikis = wiki_id in wikis
        in_schedules = schedule_id in schedules
        if in_wikis and in_schedules:
            # 모호한 출처는 없는 출처보다 나쁘다.
            continue
        if in_wikis:
            key = ("wiki", wiki_id)
            if key in seen:
                continue
            seen.add(key)
            out.append(AnswerSource(type="wiki", wikiId=wiki_id, title=wikis[wiki_id]))
        elif in_schedules:
            key = ("schedule", schedule_id)
            if key in seen:
                continue
            seen.add(key)
            out.append(AnswerSource(type="schedule", scheduleId=schedule_id,
                                    title=schedules[schedule_id]))
    return out


def parse_answer(text: str, wikis: dict[str, str], schedules: dict[str, str]
                 ) -> tuple[str | None, list[AnswerSource]]:
    """모델 응답 → (answer, sources). `answer` 가 비면 `None` 을 돌려준다."""
    payload = _json_object(text or "")
    if payload is None or not isinstance(payload.get("answer"), str):
        # 포맷만 틀린 경우다. 전문을 답변으로 살리고 출처를 포기한다.
        body = (text or "").strip()
        return (body or None), []
    body = payload["answer"].strip()
    if not body:
        return None, []
    return body, _sources_of(payload.get("sources"), wikis, schedules)


async def generate_answer(runtime, payload: AnswerRequest, *,
                          request_id: str = "") -> AnswerResponse:
    messages = answer_instruction(
        payload.question,
        [m.model_dump() for m in payload.conversationMessages],
        [w.model_dump() for w in payload.selectedWikis],
        [s.model_dump() for s in payload.selectedSchedules],
        payload.questionType,
    )
    size = input_bytes(messages)
    if size > MAX_INPUT_BYTES:
        raise InternalError(
            VALIDATION_CODE,
            f"입력이 상한을 넘었습니다 ({size} > {MAX_INPUT_BYTES} 바이트).",
            status=400,
            field_errors=[{"field": "selectedWikis",
                           "reason": f"조립된 입력 {size} 바이트가 상한 "
                                     f"{MAX_INPUT_BYTES} 를 초과합니다."}])

    result = await complete(runtime, messages, tier=QUALITY,
                            timeout=ANSWER_TIMEOUT_SECONDS,
                            error_code=ERROR_CODE, path=ANSWER_PATH,
                            request_id=request_id)
    answer, sources = parse_answer(
        result.text,
        {w.wikiId: w.title for w in payload.selectedWikis},
        {s.scheduleId: s.title for s in payload.selectedSchedules})
    if answer is None:
        raise InternalError(ERROR_CODE, "모델이 빈 답변을 냈습니다.")
    return AnswerResponse(answer=answer, sources=sources)
