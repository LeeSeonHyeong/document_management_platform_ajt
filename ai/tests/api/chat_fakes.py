"""챗봇 엔드포인트 테스트용 가짜 — 도구를 실제로 부르는 런타임과 가짜 백엔드.

**모델만 가짜다.** 도구 실행·HTTP·장부·응답 조립은 진짜로 돈다 — 출처가 「읽은 기록」에서
나오므로 도구를 부르지 않는 가짜로는 200 응답조차 재현할 수 없다.
"""

from __future__ import annotations

import httpx

from agent_runtime.base import RunResult


class ScriptedAgentRuntime:
    """대사대로 도구를 부르고 신고를 돌려주는 런타임.

    `calls` 는 `(도구 이름, 인자 dict)` 목록이다. 순서대로 부른다.
    `report` 는 모델이 낼 구조화 신고(`AnswerReport` 모양)이거나 `None` 이다.
    """

    name = "scripted-agent"

    def __init__(self, calls: list[tuple[str, dict]], *, report: dict | None = None,
                 text: str = "", error: str | None = None,
                 raises: Exception | None = None):
        self.calls = calls
        self.report = report
        self.text = text
        self.error = error
        self.raises = raises
        self.guides: list[str] = []
        self.questions: list[str] = []
        self.tool_names: list[str] = []

    def run_with_tools(self, guide, question, *, tools, max_turns, timeout,
                       response_format=None) -> RunResult:
        self.guides.append(guide)
        self.questions.append(question)
        self.tool_names = [tool.name for tool in tools]
        if self.raises:
            raise self.raises
        by_name = {tool.name: tool for tool in tools}
        counted: dict[str, int] = {}
        for name, kwargs in self.calls:
            by_name[name].call(**kwargs)
            counted[name] = counted.get(name, 0) + 1
        return RunResult(text=self.text, tool_calls=counted,
                         structured=self.report, error=self.error)


def wiki_and_schedule_backend(*, wikis: dict[str, str] | None = None,
                              schedules: dict[str, str] | None = None,
                              status: int = 200):
    """`httpx.MockTransport` 핸들러. 제목 표는 `{id: 제목}` 이다."""
    wikis = wikis or {"101": "휴가 규정"}
    schedules = schedules or {"31": "8월 휴가 일정"}

    def handler(request: httpx.Request) -> httpx.Response:
        if status != 200:
            return httpx.Response(status, json={"message": "거절"})
        path = request.url.path
        if "/wiki-search" in path:
            return httpx.Response(200, json={"items": [
                {"wikiId": wiki_id, "title": title, "snippet": "..."}
                for wiki_id, title in wikis.items()]})
        if path.endswith("/content"):
            wiki_id = path.split("/wikis/")[1].split("/")[0]
            return httpx.Response(200, json={
                "wikiId": wiki_id, "title": wikis.get(wiki_id, "제목 없음"),
                "contentMarkdown": "연차는 15일이다."})
        if "/index" in path:
            return httpx.Response(200, json={"indexMarkdown": "- 목차"})
        if "/schedules/" in path:
            schedule_id = path.rsplit("/", 1)[1]
            return httpx.Response(200, json={
                "scheduleId": schedule_id,
                "title": schedules.get(schedule_id, "제목 없음"),
                "content": "개발부 휴가", "startAt": "2026-08-03T01:00:00Z",
                "endAt": "2026-08-03T03:00:00Z", "targetText": "개발부",
                "location": "본사"})
        if path.endswith("/schedules"):
            return httpx.Response(200, json={"items": [
                {"scheduleId": schedule_id, "title": title,
                 "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z",
                 "targetText": "개발부", "location": "본사"}
                for schedule_id, title in schedules.items()], "truncated": False})
        raise AssertionError(f"가짜 백엔드가 모르는 경로: {path}")

    return httpx.MockTransport(handler)


def use_fake_backend(monkeypatch, transport) -> None:
    """`generate_answer` 가 만드는 조회 클라이언트에 가짜 전송을 끼운다.

    클라이언트를 요청마다 새로 만들기 때문에 인스턴스를 바꿔치기할 수 없다 — 생성 자리를
    감싼다.
    """
    from wiki_api import answer as answer_module
    from wiki_api.answer_tools import ChatQueryClient

    def make(*args, **kwargs):
        return ChatQueryClient(*args, **{**kwargs, "transport": transport})

    monkeypatch.setattr(answer_module, "ChatQueryClient", make)
