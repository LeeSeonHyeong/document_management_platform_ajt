"""챗봇 2단계 — 답변 생성과 출처 복원.

`sources` 는 모델의 자기 신고다. **지어낸 ID 는 막지만 "안 쓴 자료를 출처로 신고하는 것"은
막지 못한다.** 그 한계를 알고 쓴다 — 각주 강제로 기계 검증할 수 있지만 계약에 답변 본문의
각주 표기 형식이 없다.

JSON 이 깨져도 답변을 버리지 않는다. 내용은 맞고 포맷만 틀린 경우가 흔하고, 버리면 재호출에
같은 크레딧을 또 쓴다. `answer` 가 실제로 비었을 때만 실패시킨다.
"""

from fastapi.testclient import TestClient

from agent_runtime.base import CompletionResult
from wiki_api.answer import parse_answer
from wiki_api.app import create_app

API_KEY = "secret-key"
PATH = "/internal/v1/answers"

WIKIS = {"101": "휴가 규정", "102": "보상 체계"}
SCHEDULES = {"31": "8월 휴가 일정"}

REQUEST = {
    "questionId": "500",
    "conversationId": "chat-123",
    "questionType": "mixed",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [
        {"role": "user", "content": "연차 신청 방법을 알려줘."},
        {"role": "assistant", "content": "연차 신청 절차는 다음과 같습니다."},
    ],
    "selectedWikis": [
        {"wikiId": "101", "title": "휴가 규정", "contentMarkdown": "# 휴가 규정\n연차 15일"}
    ],
    "selectedSchedules": [
        {"scheduleId": "31", "title": "8월 휴가 일정", "content": "개발부 휴가",
         "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z"}
    ],
}

GOOD = ('{"answer": "연차는 15일입니다.", "sources": '
        '[{"type": "wiki", "wikiId": "101", "title": "휴가 규정"}]}')


class FakeRuntime:
    name = "fake-answer"

    def __init__(self, text: str, raises: Exception | None = None):
        self.text = text
        self.raises = raises
        self.messages: list[list[dict]] = []
        self.tiers: list[str] = []

    def complete(self, messages, *, tier="quality", timeout=None):
        self.messages.append(messages)
        self.tiers.append(tier)
        if self.raises:
            raise self.raises
        return CompletionResult(text=self.text)


def _post(runtime, body=None):
    app = create_app(api_key=API_KEY)
    app.state.runtime = runtime
    client = TestClient(app, raise_server_exceptions=False)
    return client.post(PATH, json=body or REQUEST,
                       headers={"X-Internal-API-Key": API_KEY})


# ---- 파싱 ------------------------------------------------------------------

def test_받은_ID_만_출처가_된다():
    text = ('{"answer": "답", "sources": ['
            '{"type": "wiki", "wikiId": "101", "title": "휴가 규정"},'
            '{"type": "wiki", "wikiId": "999", "title": "지어낸 것"}]}')
    answer, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert answer == "답"
    assert [s.wikiId for s in sources] == ["101"]


def test_제목은_요청값을_쓴다():
    """모델이 제목을 바꿔 쓰면 answer_source.source_title 에 다른 문자열이 저장된다."""
    text = ('{"answer": "답", "sources": '
            '[{"type": "wiki", "wikiId": "101", "title": "엉뚱한 제목"}]}')
    _, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert sources[0].title == "휴가 규정"


def test_type_은_어느_배열에서_왔는지로_정한다():
    text = ('{"answer": "답", "sources": '
            '[{"type": "wiki", "scheduleId": "31", "title": "x"}]}')
    _, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert sources[0].type == "schedule"
    assert sources[0].scheduleId == "31"
    assert sources[0].wikiId is None


def test_ID_가_양쪽에_있으면_버린다():
    """모호한 출처는 없는 출처보다 나쁘다."""
    text = ('{"answer": "답", "sources": '
            '[{"type": "wiki", "wikiId": "101", "scheduleId": "31", "title": "x"}]}')
    _, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert sources == []


def test_중복_출처를_지운다():
    text = ('{"answer": "답", "sources": ['
            '{"type": "wiki", "wikiId": "101", "title": "휴가 규정"},'
            '{"type": "wiki", "wikiId": "101", "title": "휴가 규정"}]}')
    _, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert len(sources) == 1


def test_JSON_이_깨지면_전문을_답변으로_쓴다():
    text = "연차는 15일입니다. 8월 3일에 개발부 휴가가 있습니다."
    answer, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert answer == text
    assert sources == []


def test_sources_가_배열이_아니면_빈_배열이다():
    text = '{"answer": "연차는 15일입니다.", "sources": "휴가 규정"}'
    answer, sources = parse_answer(text, WIKIS, SCHEDULES)
    assert answer == "연차는 15일입니다."
    assert sources == []


def test_answer_키가_없으면_전문을_쓴다():
    text = '{"sources": [{"type": "wiki", "wikiId": "101", "title": "휴가 규정"}]}'
    answer, _ = parse_answer(text, WIKIS, SCHEDULES)
    assert answer == text


def test_빈_답변은_None_을_돌려준다():
    answer, sources = parse_answer('{"answer": "   ", "sources": []}', WIKIS, SCHEDULES)
    assert answer is None


# ---- 엔드포인트 ------------------------------------------------------------

def test_계약_모양으로_응답한다():
    response = _post(FakeRuntime(GOOD))
    assert response.status_code == 200, response.text
    assert response.json() == {
        "answer": "연차는 15일입니다.",
        "sources": [{"type": "wiki", "wikiId": "101", "title": "휴가 규정"}],
    }


def test_quality_tier_로_부른다():
    runtime = FakeRuntime(GOOD)
    _post(runtime)
    assert runtime.tiers == ["quality"]


def test_자료가_0개여도_200_이다():
    """400 이 아니다. 계약의 400 은 형식 오류를 뜻한다."""
    runtime = FakeRuntime('{"answer": "관련 자료가 없어 답변할 수 없습니다.", "sources": []}')
    body = dict(REQUEST, questionType="wiki", selectedWikis=[], selectedSchedules=[])
    response = _post(runtime, body)
    assert response.status_code == 200
    assert response.json()["sources"] == []
    assert response.json()["answer"]


def test_빈_답변은_계약_500_코드다():
    response = _post(FakeRuntime('{"answer": "", "sources": []}'))
    assert response.status_code == 500
    assert response.json()["code"] == "ANSWER_GENERATION_FAILED"


def test_런타임_실패는_계약_500_코드다():
    response = _post(FakeRuntime("", raises=RuntimeError("게이트웨이 거부")))
    assert response.status_code == 500
    assert response.json()["code"] == "ANSWER_GENERATION_FAILED"


def test_형식_오류는_계약_400_코드다():
    response = _post(FakeRuntime(GOOD), dict(REQUEST, questionType="general"))
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_GENERATION_REQUEST"


def test_questionType_별로_쓰는_배열이_다르다():
    """wiki 질문에 일정 자료를 프롬프트에 넣으면 모델이 무관한 근거를 끌어온다."""
    runtime = FakeRuntime(GOOD)
    _post(runtime, dict(REQUEST, questionType="wiki"))
    prompt = runtime.messages[0][0]["content"]
    assert "휴가 규정" in prompt
    assert "일정 자료" not in prompt


def test_입력이_상한을_넘으면_400_이고_모델을_부르지_않는다():
    from wiki_api.answer import MAX_INPUT_BYTES

    huge = "가" * (MAX_INPUT_BYTES // 3 + 100)
    runtime = FakeRuntime(GOOD)
    body = dict(REQUEST, selectedWikis=[
        {"wikiId": "101", "title": "휴가 규정", "contentMarkdown": huge}])
    response = _post(runtime, body)
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_GENERATION_REQUEST"
    assert runtime.messages == []
