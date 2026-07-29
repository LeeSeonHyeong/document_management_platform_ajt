"""챗봇 계약 스키마와 오류 코드 등록.

**오류 코드는 딕셔너리 두 곳에 각각 등록해야 한다.** 하나를 빼먹으면 계약 위반이 조용히
나간다 — 검증 누락은 `INVALID_REQUEST`, 실패 누락은 `INTERNAL_SERVER_ERROR` 이고 둘 다
계약에 없는 이름이라 Spring 의 code 분기가 깨진다.
"""

import pytest
from pydantic import ValidationError

from wiki_api.errors import _FAILURE_CODES, _VALIDATION_CODES, failure_code_for
from wiki_api.schemas import (
    ANSWER_PATH,
    SELECTION_PATH,
    AnswerContextRequest,
    AnswerContextResponse,
    AnswerRequest,
    AnswerResponse,
    AnswerSource,
)

CONTEXT_BODY = {
    "questionId": "500",
    "conversationId": "chat-123",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [
        {"role": "user", "content": "연차 신청 방법을 알려줘."},
        {"role": "assistant", "content": "연차 신청 절차는 다음과 같습니다."},
    ],
    "wikiIndexes": [
        {"scopeKey": "D1-D2", "indexMarkdown": "# 사내 규정\n- [휴가 규정](pages/101.md)"}
    ],
    "scheduleSummaries": [
        {"scheduleId": "31", "title": "8월 휴가 일정",
         "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z",
         "targetText": "개발부", "location": "본사"}
    ],
}

ANSWER_BODY = {
    "questionId": "500",
    "conversationId": "chat-123",
    "questionType": "mixed",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [],
    "selectedWikis": [
        {"wikiId": "101", "title": "휴가 규정", "contentMarkdown": "# 휴가 규정\n..."}
    ],
    "selectedSchedules": [
        {"scheduleId": "31", "title": "8월 휴가 일정", "content": "개발부 휴가 일정",
         "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z",
         "targetText": "개발부", "location": "본사"}
    ],
}


def test_계약_예시가_그대로_통과한다():
    request = AnswerContextRequest(**CONTEXT_BODY)
    assert request.wikiIndexes[0].scopeKey == "D1-D2"
    assert request.scheduleSummaries[0].scheduleId == "31"
    assert request.conversationMessages[1].role == "assistant"


def test_답변_요청_계약_예시가_통과한다():
    request = AnswerRequest(**ANSWER_BODY)
    assert request.questionType == "mixed"
    assert request.selectedWikis[0].wikiId == "101"
    assert request.selectedSchedules[0].location == "본사"


def test_목록은_비어도_된다():
    """관련 자료가 없는 것은 정상이다. 400 이 아니다."""
    body = dict(CONTEXT_BODY, wikiIndexes=[], scheduleSummaries=[],
                conversationMessages=[])
    assert AnswerContextRequest(**body).wikiIndexes == []


def test_모르는_필드는_거절한다():
    """계약 밖 필드를 조용히 먹으면 계약 드리프트가 보이지 않는다."""
    with pytest.raises(ValidationError):
        AnswerContextRequest(**dict(CONTEXT_BODY, extraField="x"))


def test_questionType_은_세_값뿐이다():
    """DB `chk_ai_question_type` 이 WIKI·SCHEDULE·MIXED 만 허용한다."""
    with pytest.raises(ValidationError):
        AnswerRequest(**dict(ANSWER_BODY, questionType="general"))
    with pytest.raises(ValidationError):
        AnswerContextResponse(questionType="general", wikiIds=[], scheduleIds=[],
                              reason="")


def test_role_은_두_값뿐이다():
    body = dict(CONTEXT_BODY,
                conversationMessages=[{"role": "admin", "content": "x"}])
    with pytest.raises(ValidationError):
        AnswerContextRequest(**body)


def test_출처는_한쪽_ID_만_갖는다():
    wiki = AnswerSource(type="wiki", wikiId="101", title="휴가 규정")
    assert wiki.scheduleId is None
    schedule = AnswerSource(type="schedule", scheduleId="31", title="8월 휴가 일정")
    assert schedule.wikiId is None


def test_응답이_계약_모양으로_직렬화된다():
    response = AnswerResponse(
        answer="연차 규정과 다음 휴가 일정은 다음과 같습니다.",
        sources=[AnswerSource(type="wiki", wikiId="101", title="휴가 규정"),
                 AnswerSource(type="schedule", scheduleId="31", title="8월 휴가 일정")])
    dumped = response.model_dump(exclude_none=True)
    assert dumped["sources"][0] == {"type": "wiki", "wikiId": "101",
                                    "title": "휴가 규정"}
    assert dumped["sources"][1] == {"type": "schedule", "scheduleId": "31",
                                    "title": "8월 휴가 일정"}


def test_검증_코드가_두_경로에_등록됐다():
    assert _VALIDATION_CODES[SELECTION_PATH][0] == "INVALID_ANSWER_CONTEXT_REQUEST"
    assert _VALIDATION_CODES[ANSWER_PATH][0] == "INVALID_ANSWER_GENERATION_REQUEST"


def test_실패_코드가_두_경로에_등록됐다():
    assert failure_code_for(SELECTION_PATH) == "ANSWER_CONTEXT_SELECTION_FAILED"
    assert failure_code_for(ANSWER_PATH) == "ANSWER_GENERATION_FAILED"
    assert SELECTION_PATH in _FAILURE_CODES
    assert ANSWER_PATH in _FAILURE_CODES


def test_경로_상수가_계약과_같다():
    assert SELECTION_PATH == "/internal/v1/answer-context-selections"
    assert ANSWER_PATH == "/internal/v1/answers"
