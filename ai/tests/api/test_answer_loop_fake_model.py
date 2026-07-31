"""가짜 모델로 챗봇 루프를 고정한다 — **0원이다.**

`ScriptedChatModel` 은 모델 판단만 대신한다. 에이전트 루프·도구 실행·HTTP(가짜 백엔드)·
읽은 것 장부·응답 조립은 전부 진짜로 돈다. 도구를 직접 부르는 단위 테스트
(`test_answer_tools.py`)로는 못 잡는 것을 여기서 잡는다 — 루프가 도구를 무는 방식,
구조화 응답이 돌아오는 형태, 그리고 그 둘이 응답 조립과 맞물리는 자리다.

실제로 두 가지를 여기서 잡았다 (2026-07-31):

  * 구조화 응답용 도구 호출(`AnswerReport`)이 `tool_calls` 에 섞여서, **위키도 일정도
    읽지 않은 실행이 「조회했다」로 통과**했다.
  * `structured_response` 는 dict 가 아니라 pydantic 인스턴스로 온다 — `.get()` 을 부르는
    응답 조립이 그대로 터졌다.
"""

from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

from agent_runtime.deep_agents import DeepAgentsRuntime
from wiki_api.app import create_app

from ..fake_models import ScriptedChatModel
from .chat_fakes import use_fake_backend, wiki_and_schedule_backend

API_KEY = "secret-key"
PATH = "/internal/v1/answers"

REQUEST = {
    "questionId": "500",
    "conversationId": "chat-123",
    "question": "연차 며칠까지 쓸 수 있어?",
    "conversationMessages": [],
    "wikiIndexes": [
        {"scopeKey": "ALL", "indexMarkdown": "- [휴가 규정](pages/101.md)",
         "wikiCapability": "cap-all"}
    ],
}


def _tool_call(name: str, args: dict, call_id: str) -> AIMessage:
    return AIMessage(content="", tool_calls=[
        {"name": name, "args": args, "id": call_id, "type": "tool_call"}])


def _report(**fields) -> AIMessage:
    """모델이 구조화 응답을 내는 방식 — 스키마 이름의 도구를 부른다 (ToolStrategy)."""
    args = {"answer": "연차는 15일입니다.", "usedWikiIds": [], "usedScheduleIds": [],
            "questionType": "wiki", **fields}
    return _tool_call("AnswerReport", args, "report-1")


def _post(monkeypatch, script, *, transport=None, body=None):
    use_fake_backend(monkeypatch, transport or wiki_and_schedule_backend())
    app = create_app(api_key=API_KEY, backend_base_url="http://backend")
    app.state.runtime = DeepAgentsRuntime(
        model="openai:gpt-4o-mini",
        chat_model=ScriptedChatModel(messages=iter(script)))
    client = TestClient(app, raise_server_exceptions=False)
    return client.post(PATH, json=body or REQUEST,
                       headers={"X-Internal-API-Key": API_KEY})


def test_읽고_신고하면_출처가_나온다(monkeypatch):
    """기본 경로 — 본문을 읽고, 쓴 것을 신고하고, 그것이 출처로 나간다."""
    response = _post(monkeypatch, [
        _tool_call("read_wiki", {"scopeKey": "ALL", "wikiId": "101"}, "c1"),
        _report(usedWikiIds=["101"]),
    ])

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["answer"] == "연차는 15일입니다."
    assert body["sources"] == [{"type": "wiki", "wikiId": "101",
                                "title": "휴가 규정"}]
    assert body["questionType"] == "wiki"


def test_안_읽은_것을_신고하면_출처에서_빠진다(monkeypatch):
    """읽은 기록이 화이트리스트다 — 루프를 통과해도 지어낸 출처는 못 나간다."""
    response = _post(monkeypatch, [
        _tool_call("read_wiki", {"scopeKey": "ALL", "wikiId": "101"}, "c1"),
        _report(usedWikiIds=["101", "999"]),
    ])

    assert response.status_code == 200, response.text
    assert [s["wikiId"] for s in response.json()["sources"]] == ["101"]


def test_찾아봤지만_없으면_200_이고_출처가_빈다(monkeypatch):
    """FR-QNA-007. `search_wiki` 만 부르고 끝낸 실행이다 — 본문을 안 읽었으니 출처가 없다."""
    response = _post(monkeypatch, [
        _tool_call("search_wiki", {"scopeKey": "ALL", "query": "연차"}, "c1"),
        _report(answer="위키에서 찾지 못했습니다."),
    ])

    assert response.status_code == 200, response.text
    assert response.json()["sources"] == []
    assert "찾지 못" in response.json()["answer"]


def test_아예_안_찾아보면_500_이다(monkeypatch):
    """**신고용 도구 호출을 조회로 세면 이것이 200 으로 통과한다.** 실제로 통과했다."""
    response = _post(monkeypatch, [_report(answer="아마 15일일 것입니다.")])

    assert response.status_code == 500
    assert response.json()["code"] == "NO_WIKI_OR_SCHEDULE_WAS_READ"


def test_턴_상한은_자기_이름으로_나온다(monkeypatch):
    """끝내지 않고 도구만 계속 부르는 대사. 이름이 `MODEL_CALL_FAILED` 로 뭉개지면
    상한을 조정할 근거를 못 얻는다."""
    script = [_tool_call("read_wiki", {"scopeKey": "ALL", "wikiId": "101"}, f"c{i}")
              for i in range(20)]

    response = _post(monkeypatch, script)

    assert response.status_code == 500
    assert response.json()["code"] == "AGENT_TURN_LIMIT_REACHED"


def test_조회가_실패해도_루프가_계속_돈다(monkeypatch):
    """도구가 예외를 던지면 루프가 죽는다. 문장으로 알려야 다른 범위를 볼 수 있다."""
    response = _post(monkeypatch, [
        _tool_call("read_wiki", {"scopeKey": "ALL", "wikiId": "101"}, "c1"),
        _report(answer="지금은 확인할 수 없습니다.", usedWikiIds=["101"]),
    ], transport=wiki_and_schedule_backend(status=500))

    assert response.status_code == 200, response.text
    assert response.json()["sources"] == []          # 읽은 것이 없다
    assert "확인할 수 없" in response.json()["answer"]
