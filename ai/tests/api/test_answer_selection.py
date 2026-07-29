"""챗봇 1단계 — 질문을 분류하고 필요한 자료 ID 를 고른다.

확인하는 것은 모델의 안목이 아니라 **파싱의 방어력**이다. Spring 은 이 응답의 ID 로 본문을
읽어 2단계에 싣는다 — 지어낸 ID 가 새면 없는 자료를 조회하거나 다른 범위의 자료를 읽는다.

위키와 일정의 화이트리스트 출처가 다르다. 위키는 목차 링크가 존재 증명이지만 **일정에는
목차가 없어서** 요청이 실어 온 요약 목록이 유일한 근거다.
"""

import pytest
from fastapi.testclient import TestClient

from agent_runtime.base import CompletionResult
from wiki_api.answer_selection import (
    MAX_PICKS,
    index_wiki_ids,
    parse_answer_context,
    resolve_question_type,
)
from wiki_api.app import create_app
from wiki_api.schemas import WikiIndexEntry

API_KEY = "secret-key"
PATH = "/internal/v1/answer-context-selections"

INDEX_A = ("# 사내 규정\n"
           "- [휴가 규정](pages/101.md) — 연차와 반차\n"
           "- [보상 체계](pages/102.md) — 레벨과 스텝\n")
INDEX_B = ("# 개발부 위키\n"
           "- [배포 절차](pages/201.md) — 릴리스\n")

REQUEST = {
    "questionId": "500",
    "conversationId": "chat-123",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [
        {"role": "user", "content": "연차 신청 방법을 알려줘."},
        {"role": "assistant", "content": "연차 신청 절차는 다음과 같습니다."},
    ],
    "wikiIndexes": [
        {"scopeKey": "ALL", "indexMarkdown": INDEX_A},
        {"scopeKey": "D1", "indexMarkdown": INDEX_B},
    ],
    "scheduleSummaries": [
        {"scheduleId": "31", "title": "8월 휴가 일정",
         "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z"},
        {"scheduleId": "32", "title": "전사 오프사이트",
         "startAt": "2026-09-01T00:00:00Z", "endAt": "2026-09-03T00:00:00Z"},
    ],
}


class FakeRuntime:
    name = "fake-answer-select"

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


# ---- 화이트리스트 ----------------------------------------------------------

def test_여러_스코프의_목차를_합친다():
    entries = [WikiIndexEntry(scopeKey="ALL", indexMarkdown=INDEX_A),
               WikiIndexEntry(scopeKey="D1", indexMarkdown=INDEX_B)]
    assert index_wiki_ids(entries) == ["101", "102", "201"]


def test_목차에_없는_위키_ID_는_버린다():
    text = '{"questionType": "wiki", "wikiIds": ["101", "999"], "scheduleIds": []}'
    _, wiki_ids, _, _ = parse_answer_context(text, ["101", "102"], ["31"])
    assert wiki_ids == ["101"]


def test_요약에_없는_일정_ID_는_버린다():
    """일정에는 목차가 없다. 요청이 실어 온 요약 목록이 유일한 화이트리스트다."""
    text = '{"questionType": "schedule", "wikiIds": [], "scheduleIds": ["31", "77"]}'
    _, _, schedule_ids, _ = parse_answer_context(text, ["101"], ["31", "32"])
    assert schedule_ids == ["31"]


def test_중복을_지우고_다섯_개에서_자른다():
    allowed = [str(n) for n in range(101, 110)]
    picks = ", ".join(f'"{n}"' for n in allowed + ["101"])
    text = f'{{"questionType": "wiki", "wikiIds": [{picks}], "scheduleIds": []}}'
    _, wiki_ids, _, _ = parse_answer_context(text, allowed, [])
    assert wiki_ids == allowed[:MAX_PICKS]
    assert len(wiki_ids) == MAX_PICKS


def test_JSON_이_아니어도_ID_를_줍는다():
    text = "관련 위키는 101 과 102 이고 일정은 31 입니다."
    _, wiki_ids, schedule_ids, _ = parse_answer_context(text, ["101", "102"], ["31"])
    assert wiki_ids == ["101", "102"]
    assert schedule_ids == ["31"]


# ---- questionType 교정 -----------------------------------------------------

@pytest.mark.parametrize("declared,wiki,schedule,expected", [
    ("mixed", ["101"], [], "wiki"),
    ("wiki", [], ["31"], "schedule"),
    ("wiki", ["101"], ["31"], "mixed"),
    ("schedule", [], [], "schedule"),
    ("garbage", [], [], "wiki"),
])
def test_questionType_은_선택_결과로_교정된다(declared, wiki, schedule, expected):
    assert resolve_question_type(declared, wiki, schedule) == expected


# ---- 엔드포인트 ------------------------------------------------------------

def test_계약_모양으로_응답한다():
    runtime = FakeRuntime('{"questionType": "mixed", "wikiIds": ["101"], '
                          '"scheduleIds": ["31"], "reason": "휴가 규정과 일정"}')
    response = _post(runtime)
    assert response.status_code == 200, response.text
    assert response.json() == {"questionType": "mixed", "wikiIds": ["101"],
                               "scheduleIds": ["31"], "reason": "휴가 규정과 일정"}


def test_fast_tier_로_부른다():
    runtime = FakeRuntime('{"questionType": "wiki", "wikiIds": ["101"], "scheduleIds": []}')
    _post(runtime)
    assert runtime.tiers == ["fast"]


def test_목차가_프롬프트_맨_앞에_온다():
    """캐시 접두사가 흔들리지 않게. 질문이 앞에 오면 적중이 0 이 된다."""
    runtime = FakeRuntime('{"questionType": "wiki", "wikiIds": [], "scheduleIds": []}')
    _post(runtime)
    blocks = runtime.messages[0][0]["content"]
    assert isinstance(blocks, list)
    assert INDEX_A in blocks[0]["text"]
    assert "연차 규정과 다음 휴가 일정을 알려줘." not in blocks[0]["text"]


def test_이전_대화가_프롬프트에_들어간다():
    runtime = FakeRuntime('{"questionType": "wiki", "wikiIds": [], "scheduleIds": []}')
    _post(runtime)
    joined = "\n".join(b["text"] for b in runtime.messages[0][0]["content"])
    assert "연차 신청 방법을 알려줘." in joined


def test_빈_결과는_200_이다():
    """관련 자료가 없는 것은 정상이다."""
    runtime = FakeRuntime('{"questionType": "wiki", "wikiIds": [], "scheduleIds": []}')
    response = _post(runtime)
    assert response.status_code == 200
    assert response.json()["wikiIds"] == []
    assert response.json()["reason"]


def test_런타임_실패는_계약_500_코드다():
    runtime = FakeRuntime("", raises=RuntimeError("게이트웨이 거부"))
    response = _post(runtime)
    assert response.status_code == 500
    assert response.json()["code"] == "ANSWER_CONTEXT_SELECTION_FAILED"


def test_형식_오류는_계약_400_코드다():
    runtime = FakeRuntime("{}")
    response = _post(runtime, dict(REQUEST, questionId=None))
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_CONTEXT_REQUEST"
    assert response.json()["fieldErrors"]


def test_입력이_상한을_넘으면_400_이다():
    from wiki_api.answer_selection import MAX_INPUT_BYTES

    huge = "가" * (MAX_INPUT_BYTES // 3 + 100)  # 한글 1자 = UTF-8 3바이트
    runtime = FakeRuntime("{}")
    body = dict(REQUEST, wikiIndexes=[{"scopeKey": "ALL", "indexMarkdown": huge}])
    response = _post(runtime, body)
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_CONTEXT_REQUEST"
    assert response.json()["fieldErrors"]
    # 상한을 넘겼으면 모델을 부르지 않아야 한다 — 크레딧이 나간다.
    assert runtime.messages == []
