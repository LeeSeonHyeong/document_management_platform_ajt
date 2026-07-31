"""챗봇 엔드포인트 하나 — 에이전트가 조회하고 답한다.

**출처는 모델의 신고와 읽은 기록의 교집합이다** (`answer.build_response`). 여기서는 그것이
HTTP 표면에서 어떻게 보이는지를 본다 — 상태 코드, 계약 모양, 실패 이름.

모델만 가짜다 (`chat_fakes.ScriptedAgentRuntime`). 도구·HTTP·장부는 진짜로 돌기 때문에
「도구를 안 부르면 출처가 없다」가 이 테스트에서도 그대로 성립한다.
"""

from fastapi.testclient import TestClient

from wiki_api.app import create_app

from .chat_fakes import ScriptedAgentRuntime, use_fake_backend, wiki_and_schedule_backend

API_KEY = "secret-key"
PATH = "/internal/v1/answers"
BACKEND = "http://backend"

REQUEST = {
    "questionId": "500",
    "conversationId": "chat-123",
    "question": "연차 규정과 다음 휴가 일정을 알려줘.",
    "conversationMessages": [
        {"role": "user", "content": "연차 신청 방법을 알려줘."},
        {"role": "assistant", "content": "연차 신청 절차는 다음과 같습니다."},
    ],
    "wikiIndexes": [
        {"scopeKey": "ALL", "indexMarkdown": "# 사내 규정\n- [휴가 규정](pages/101.md)",
         "wikiCapability": "cap-all"}
    ],
}

READ_BOTH = [("read_wiki", {"scopeKey": "ALL", "wikiId": "101"}),
             ("read_schedule", {"scheduleId": "31"})]

REPORT = {"answer": "연차는 15일입니다.", "usedWikiIds": ["101"],
          "usedScheduleIds": ["31"], "questionType": "mixed"}


def _post(monkeypatch, runtime, body=None, *, transport=None):
    use_fake_backend(monkeypatch, transport or wiki_and_schedule_backend())
    app = create_app(api_key=API_KEY, backend_base_url=BACKEND)
    app.state.runtime = runtime
    client = TestClient(app, raise_server_exceptions=False)
    return client.post(PATH, json=body or REQUEST,
                       headers={"X-Internal-API-Key": API_KEY})


def test_계약_모양으로_응답한다(monkeypatch):
    response = _post(monkeypatch, ScriptedAgentRuntime(READ_BOTH, report=REPORT))

    assert response.status_code == 200, response.text
    assert response.json() == {
        "answer": "연차는 15일입니다.",
        "sources": [{"type": "wiki", "wikiId": "101", "title": "휴가 규정"},
                    {"type": "schedule", "scheduleId": "31",
                     "title": "8월 휴가 일정"}],
        "questionType": "mixed",
    }


def test_다섯_도구를_모두_붙인다(monkeypatch):
    runtime = ScriptedAgentRuntime(READ_BOTH, report=REPORT)
    _post(monkeypatch, runtime)

    assert set(runtime.tool_names) == {"search_wiki", "read_wiki", "read_wiki_index",
                                       "list_schedules", "read_schedule"}


def test_지침에_목차와_이전_대화가_실리고_질문은_따로_간다(monkeypatch):
    """한 문자열을 지침과 질문 양쪽에 넣으면 목차가 두 번 실려 입력이 두 배가 된다."""
    runtime = ScriptedAgentRuntime(READ_BOTH, report=REPORT)
    _post(monkeypatch, runtime)

    guide = runtime.guides[0]
    assert "휴가 규정" in guide                       # 목차
    assert "연차 신청 방법을 알려줘." in guide          # 이전 대화
    assert runtime.questions[0] == REQUEST["question"]
    assert REQUEST["question"] not in guide


def test_찾아봤지만_없으면_200_이고_출처가_빈다(monkeypatch):
    """FR-QNA-007 — 근거를 못 찾은 것은 오류가 아니다."""
    runtime = ScriptedAgentRuntime(
        [("search_wiki", {"scopeKey": "ALL", "query": "연차"})],
        report={"answer": "위키에서 찾지 못했습니다.", "usedWikiIds": [],
                "usedScheduleIds": [], "questionType": "wiki"})

    response = _post(monkeypatch, runtime)

    assert response.status_code == 200
    assert response.json()["sources"] == []
    assert response.json()["questionType"] == "wiki"


def test_아예_조회하지_않으면_500_이고_이름이_구분된다(monkeypatch):
    runtime = ScriptedAgentRuntime([], text="아마 15일일 것입니다.")

    response = _post(monkeypatch, runtime)

    assert response.status_code == 500
    assert response.json()["code"] == "NO_WIKI_OR_SCHEDULE_WAS_READ"


def test_턴_상한은_자기_이름으로_나온다(monkeypatch):
    runtime = ScriptedAgentRuntime(READ_BOTH, report=REPORT, error="turn_limit")

    response = _post(monkeypatch, runtime)

    assert response.status_code == 500
    assert response.json()["code"] == "AGENT_TURN_LIMIT_REACHED"


def test_모델_고장은_모델_이름으로_나온다(monkeypatch):
    runtime = ScriptedAgentRuntime([], raises=RuntimeError("게이트웨이 거부"))

    response = _post(monkeypatch, runtime)

    assert response.status_code == 500
    assert response.json()["code"] == "MODEL_CALL_FAILED"


def test_조회가_실패해도_에이전트는_계속_돈다(monkeypatch):
    """도구가 문장으로 알려 주면 에이전트가 다른 범위를 볼 수 있다 — 500 으로 끊지 않는다.

    다만 읽은 것이 없으므로 출처는 비고, 답변은 모델이 낸 것이 나간다.
    """
    runtime = ScriptedAgentRuntime(
        [("read_wiki", {"scopeKey": "ALL", "wikiId": "101"})],
        report={"answer": "지금은 확인할 수 없습니다.", "usedWikiIds": ["101"],
                "usedScheduleIds": [], "questionType": "wiki"})

    response = _post(monkeypatch, runtime,
                     transport=wiki_and_schedule_backend(status=500))

    assert response.status_code == 200
    assert response.json()["sources"] == []


def test_허가값이_없는_요청은_400_이다(monkeypatch):
    """`wikiCapability` 없이 보내면 그 범위를 한 장도 못 읽는다 — 조용히 받지 않는다."""
    body = {**REQUEST, "wikiIndexes": [{"scopeKey": "ALL", "indexMarkdown": "- 목차"}]}

    response = _post(monkeypatch, ScriptedAgentRuntime(READ_BOTH, report=REPORT), body)

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_GENERATION_REQUEST"


def test_옛_2단계_필드를_보내면_400_이다(monkeypatch):
    body = {**REQUEST, "questionType": "mixed",
            "selectedWikis": [{"wikiId": "101", "title": "휴가 규정",
                               "contentMarkdown": "본문"}]}

    response = _post(monkeypatch, ScriptedAgentRuntime(READ_BOTH, report=REPORT), body)

    assert response.status_code == 400


def test_비동기_경로가_있으면_그것을_쓴다(monkeypatch):
    """sync 런타임을 스레드로 돌리면 그 안의 `asyncio.run` 이 요청마다 루프를 새로 만들고
    닫는다 — 모델 클라이언트 연결 풀이 죽은 루프에 묶여 **두 번째 요청부터 전부** 실패한다
    (Spring 실연동 2026-07-31). 그래서 비동기 경로가 있으면 반드시 그쪽으로 가야 한다."""
    class BothPaths(ScriptedAgentRuntime):
        def __init__(self):
            super().__init__(READ_BOTH, report=REPORT)
            self.used = []

        async def arun_with_tools(self, guide, question, **kwargs):
            self.used.append("async")
            return super().run_with_tools(guide, question, **kwargs)

        def run_with_tools(self, guide, question, **kwargs):
            self.used.append("sync")
            return super().run_with_tools(guide, question, **kwargs)

    runtime = BothPaths()
    response = _post(monkeypatch, runtime)

    assert response.status_code == 200, response.text
    assert runtime.used == ["async"]


def test_도구를_한_번도_못_부른_상한은_상한이_아니다(monkeypatch):
    """모델 호출이 매번 실패해도 호출 수는 올라가 상한 이름이 먼저 나온다. 그 이름을 믿고
    상한을 늘리려 들면 실제 고장(루프 결함·네트워크)을 못 본다."""
    runtime = ScriptedAgentRuntime([], error="turn_limit")

    response = _post(monkeypatch, runtime)

    assert response.status_code == 500
    assert response.json()["code"] == "MODEL_CALL_FAILED"
