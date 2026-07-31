"""챗봇 계약 스키마와 오류 코드 등록.

**오류 코드는 딕셔너리 두 곳에 각각 등록해야 한다.** 하나를 빼먹으면 계약 위반이 조용히
나간다 — 검증 누락은 `INVALID_REQUEST`, 실패 누락은 `INTERNAL_SERVER_ERROR` 이고 둘 다
계약에 없는 이름이라 Spring 의 code 분기가 깨진다.

계약 1.8.0 이 1단계(`answer-context-selections`)를 지웠다 — 그 경로의 상수·코드가 남아
있으면 Spring 이 도달하지 않는 분기를 계속 들고 있게 된다.
"""

import pytest
from pydantic import ValidationError

from wiki_api.errors import _FAILURE_CODES, _VALIDATION_CODES, failure_code_for
from wiki_api.schemas import (
    ANSWER_PATH,
    AnswerRequest,
    AnswerResponse,
    AnswerSource,
)

ANSWER_BODY = {
    "questionId": "500",
    "conversationId": "chat-123",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [
        {"role": "user", "content": "연차 신청 방법을 알려줘."},
        {"role": "assistant", "content": "연차 신청 절차는 다음과 같습니다."},
    ],
    "wikiIndexes": [
        {"scopeKey": "D1-D2", "indexMarkdown": "# 사내 규정\n- [휴가 규정](pages/101.md)",
         "wikiCapability": "cap-d1-d2"}
    ],
}


def test_답변_요청_계약_예시가_통과한다():
    request = AnswerRequest(**ANSWER_BODY)
    assert request.wikiIndexes[0].scopeKey == "D1-D2"
    assert request.wikiIndexes[0].wikiCapability == "cap-d1-d2"
    assert request.conversationMessages[1].role == "assistant"


def test_목차는_비어도_된다():
    """볼 수 있는 위키가 없는 것은 정상이다 — 일정만 묻는 사용자가 있다. 400 이 아니다."""
    assert AnswerRequest(**dict(ANSWER_BODY, wikiIndexes=[])).wikiIndexes == []


def test_허가값_없는_목차는_거절한다():
    """없으면 그 범위를 한 장도 못 읽어 「근거 없음」으로 끝난다 — 원인을 짚기 어렵다."""
    with pytest.raises(ValidationError):
        AnswerRequest(**dict(ANSWER_BODY, wikiIndexes=[
            {"scopeKey": "D1-D2", "indexMarkdown": "- 목차"}]))


def test_모르는_필드는_거절한다():
    """계약 밖 필드를 조용히 먹으면 계약 드리프트가 보이지 않는다."""
    with pytest.raises(ValidationError):
        AnswerRequest(**dict(ANSWER_BODY, extraField="x"))


def test_요청은_더_이상_본문을_받지_않는다():
    """`selectedWikis`·`questionType` 은 1단계 시절의 필드다."""
    with pytest.raises(ValidationError):
        AnswerRequest(**dict(ANSWER_BODY, selectedWikis=[]))
    with pytest.raises(ValidationError):
        AnswerRequest(**dict(ANSWER_BODY, questionType="mixed"))


def test_questionType_은_세_값뿐이다():
    """DB `chk_ai_question_type` 이 WIKI·SCHEDULE·MIXED 만 허용한다."""
    with pytest.raises(ValidationError):
        AnswerResponse(answer="답", sources=[], questionType="general")


def test_role_은_두_값뿐이다():
    with pytest.raises(ValidationError):
        AnswerRequest(**dict(ANSWER_BODY,
                             conversationMessages=[{"role": "admin", "content": "x"}]))


def test_출처는_한쪽_ID_만_갖는다():
    wiki = AnswerSource(type="wiki", wikiId="101", title="휴가 규정")
    assert wiki.scheduleId is None
    schedule = AnswerSource(type="schedule", scheduleId="31", title="8월 휴가 일정")
    assert schedule.wikiId is None


def test_응답이_계약_모양으로_직렬화된다():
    response = AnswerResponse(
        answer="연차 규정과 다음 휴가 일정은 다음과 같습니다.",
        sources=[AnswerSource(type="wiki", wikiId="101", title="휴가 규정"),
                 AnswerSource(type="schedule", scheduleId="31", title="8월 휴가 일정")],
        questionType="mixed")
    dumped = response.model_dump(exclude_none=True)
    assert dumped["sources"][0] == {"type": "wiki", "wikiId": "101",
                                    "title": "휴가 규정"}
    assert dumped["sources"][1] == {"type": "schedule", "scheduleId": "31",
                                    "title": "8월 휴가 일정"}
    assert dumped["questionType"] == "mixed"


def test_검증_코드가_등록됐다():
    assert _VALIDATION_CODES[ANSWER_PATH][0] == "INVALID_ANSWER_GENERATION_REQUEST"


def test_실패_코드가_등록됐다():
    assert failure_code_for(ANSWER_PATH) == "ANSWER_GENERATION_FAILED"
    assert ANSWER_PATH in _FAILURE_CODES


def test_경로_상수가_계약과_같다():
    assert ANSWER_PATH == "/internal/v1/answers"


def test_1단계_경로는_어디에도_남아_있지_않다():
    """상수를 지워도 코드 표에 남아 있으면 Spring 이 그 이름을 계속 들고 있는다."""
    stage_one = "/internal/v1/answer-context-selections"
    assert stage_one not in _VALIDATION_CODES
    assert stage_one not in _FAILURE_CODES
