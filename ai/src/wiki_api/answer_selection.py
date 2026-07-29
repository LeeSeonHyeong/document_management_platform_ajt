"""챗봇 1단계 — 질문 분류와 자료 선택 (`POST /internal/v1/answer-context-selections`).

**에이전트가 아니다.** 입력이 목차·일정 요약·질문뿐이라 툴이 필요 없고, 출력은 ID 목록과
분류 하나다. `wiki_api/selection.py` 와 같은 문제이며 방어도 같다 — 모델이 낸 ID 를 믿지
않고 화이트리스트로 거른다.

**화이트리스트 출처가 위키와 일정에서 다르다.** 위키는 목차 링크(`pages/{wikiId}.md`)가
존재 증명이지만 일정에는 목차가 없다. 요청이 실어 온 `scheduleSummaries` 의 ID 집합이
유일한 근거다.

`questionType` 도 모델 선언을 믿지 않는다 — 실제로 고른 결과로 교정한다. 모델이 `mixed`
라 하고 위키만 고르면 Spring 이 2단계에 빈 `selectedSchedules` 를 싣고, 답변 프롬프트가
있지도 않은 일정을 언급하려 든다. DB `chk_ai_question_type` 이 세 값만 허용하는 것도
이유다.
"""

from __future__ import annotations

import json
import re

from agent_runtime.base import FAST, answer_context_instruction

from .completion import complete, input_bytes
from .errors import InternalError
from .schemas import (
    SELECTION_PATH,
    AnswerContextRequest,
    AnswerContextResponse,
    WikiIndexEntry,
)

# 계약: "Wiki와 일정은 각각 최대 5개를 관련도 순서로 선택합니다."
MAX_PICKS = 5

# 목차에서 ID 5개를 고르는 일이다. 넘으면 진행이 아니라 고장이다.
SELECTION_TIMEOUT_SECONDS = 30

ERROR_CODE = "ANSWER_CONTEXT_SELECTION_FAILED"
VALIDATION_CODE = "INVALID_ANSWER_CONTEXT_REQUEST"

# 조립된 입력 전체의 상한. 토큰 20,000 을 보수적 상계 0.5 토큰/바이트로 환산한 값이다
# (실측 최대 0.488). 자르지 않고 거절하는 이유는 조용한 열화가 최악이기 때문이다 — 목차를
# 자르면 잘린 뒤의 페이지가 화이트리스트에서 사라져 정답을 놓치는데 응답은 성공으로 보인다.
MAX_INPUT_BYTES = 40_000

_INDEX_LINK_RE = re.compile(r"pages/([A-Za-z0-9_-]+)\.md")
_TOKEN_RE = re.compile(r"[A-Za-z0-9_-]+")
_TYPES = ("wiki", "schedule", "mixed")


def index_wiki_ids(indexes: list[WikiIndexEntry]) -> list[str]:
    """모든 스코프 목차의 페이지 링크에 실재하는 wikiId — 등장 순서, 중복 제거."""
    found: list[str] = []
    for entry in indexes:
        for wiki_id in _INDEX_LINK_RE.findall(entry.indexMarkdown or ""):
            if wiki_id not in found:
                found.append(wiki_id)
    return found


def resolve_question_type(declared: str, wiki_ids: list[str],
                          schedule_ids: list[str]) -> str:
    """모델 선언보다 **실제 선택 결과**를 믿는다."""
    if wiki_ids and schedule_ids:
        return "mixed"
    if wiki_ids:
        return "wiki"
    if schedule_ids:
        return "schedule"
    return declared if declared in _TYPES else "wiki"


def _pick(candidates: list[str], allowed: list[str]) -> list[str]:
    picked: list[str] = []
    for candidate in candidates:
        if candidate in allowed and candidate not in picked:
            picked.append(candidate)
            if len(picked) == MAX_PICKS:
                break
    return picked


def _json_object(text: str) -> dict | None:
    """텍스트 안의 첫 JSON 객체. 코드펜스·앞뒤 설명을 견딘다."""
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def parse_answer_context(text: str, wiki_allowed: list[str],
                         schedule_allowed: list[str]
                         ) -> tuple[str, list[str], list[str], str]:
    """모델 응답 → (questionType, wikiIds, scheduleIds, reason).

    JSON 이 정본이지만 그것에 의존하지 않는다. 모델이 설명을 덧붙이거나 코드펜스를 두르는
    일이 흔하고, 그때 선택을 통째로 버리면 사용자가 답을 못 받는다 — 자유 텍스트에서도
    ID 를 줍는다. 어느 경로든 마지막 관문은 같다: 화이트리스트에 있는 ID 만.
    """
    payload = _json_object(text or "")
    reason = ""
    declared = ""
    wiki_candidates: list[str] | None = None
    schedule_candidates: list[str] | None = None

    if payload is not None:
        raw_reason = payload.get("reason")
        if isinstance(raw_reason, (str, int, float)):
            reason = str(raw_reason).strip()
        raw_type = payload.get("questionType")
        declared = str(raw_type).strip().lower() if isinstance(raw_type, str) else ""
        if isinstance(payload.get("wikiIds"), list):
            wiki_candidates = [str(x).strip() for x in payload["wikiIds"]]
        if isinstance(payload.get("scheduleIds"), list):
            schedule_candidates = [str(x).strip() for x in payload["scheduleIds"]]

    loose = _TOKEN_RE.findall(text or "")
    if wiki_candidates is None:
        wiki_candidates = loose
    if schedule_candidates is None:
        schedule_candidates = loose
    if not reason:
        reason = (text or "").strip()

    wiki_ids = _pick(wiki_candidates, wiki_allowed)
    schedule_ids = _pick(schedule_candidates, schedule_allowed)
    return (resolve_question_type(declared, wiki_ids, schedule_ids),
            wiki_ids, schedule_ids, reason)


async def select_answer_context(runtime, payload: AnswerContextRequest, *,
                                request_id: str = "") -> AnswerContextResponse:
    messages = answer_context_instruction(
        payload.question,
        [m.model_dump() for m in payload.conversationMessages],
        [e.model_dump() for e in payload.wikiIndexes],
        [s.model_dump() for s in payload.scheduleSummaries],
    )
    size = input_bytes(messages)
    if size > MAX_INPUT_BYTES:
        # 모델을 부르지 않는다 — 크레딧이 나가고, 컨텍스트를 넘기면 어차피 실패한다.
        raise InternalError(
            VALIDATION_CODE,
            f"입력이 상한을 넘었습니다 ({size} > {MAX_INPUT_BYTES} 바이트).",
            status=400,
            field_errors=[{"field": "wikiIndexes",
                           "reason": f"조립된 입력 {size} 바이트가 상한 "
                                     f"{MAX_INPUT_BYTES} 를 초과합니다."}])

    result = await complete(runtime, messages, tier=FAST,
                            timeout=SELECTION_TIMEOUT_SECONDS,
                            error_code=ERROR_CODE, path=SELECTION_PATH,
                            request_id=request_id)
    question_type, wiki_ids, schedule_ids, reason = parse_answer_context(
        result.text, index_wiki_ids(payload.wikiIndexes),
        [s.scheduleId for s in payload.scheduleSummaries])
    if not reason:
        reason = ("관련된 자료를 찾지 못했습니다."
                  if not (wiki_ids or schedule_ids) else "선택 근거가 없습니다.")
    return AnswerContextResponse(questionType=question_type, wikiIds=wiki_ids,
                                 scheduleIds=schedule_ids, reason=reason)
