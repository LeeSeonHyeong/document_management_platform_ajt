"""챗봇 도구 — 장부, 상한, 조회 클라이언트."""

import httpx

from wiki_api.answer_tools import (
    MAX_SCHEDULE_ROWS,
    MAX_WIKI_SEARCH_ROWS,
    ChatQueryClient,
    ReadLedger,
    build_schedule_tools,
    build_wiki_tools,
)


def test_ledger_records_in_order_without_duplicates():
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")
    ledger.note_wiki("101", "휴가 규정")
    ledger.note_wiki("108", "육아휴직")

    assert ledger.wikis == [("101", "휴가 규정"), ("108", "육아휴직")]


def test_ledger_keeps_titles_for_the_contract():
    """`sources[].title` 은 백엔드가 `answer_source.source_title`(NOT NULL)에 저장한다."""
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")
    ledger.note_schedule("31", "8월 워크샵")

    assert ledger.title_of_wiki("101") == "휴가 규정"
    assert ledger.title_of_schedule("31") == "8월 워크샵"
    assert ledger.title_of_wiki("999") is None      # 안 읽은 것은 제목도 없다


def test_empty_ledger_is_visible_to_the_caller():
    assert ReadLedger().is_empty() is True


def _client(handler) -> ChatQueryClient:
    return ChatQueryClient("http://backend", api_key="k", question_id="500",
                           capabilities={"ALL": "cap-all", "D1": "cap-d1"},
                           transport=httpx.MockTransport(handler))


def test_read_wiki_sends_the_capability_of_that_scope():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["capability"] = request.headers.get("X-Wiki-Capability")
        return httpx.Response(200, json={"wikiId": "101", "title": "휴가 규정",
                                         "contentMarkdown": "# 휴가 규정\n연차 15일"})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_wiki_tools(_client(handler), ledger)}

    text = tools["read_wiki"].call(scopeKey="D1", wikiId="101")

    assert "연차 15일" in text
    assert seen["capability"] == "cap-d1"          # 범위마다 다른 값을 쓴다
    assert "scopeKey=D1" in seen["url"]
    assert ledger.wikis == [("101", "휴가 규정")]    # 읽은 것이 장부에 남는다


def test_search_wiki_caps_rows_and_says_it_truncated():
    rows = [{"wikiId": str(i), "title": f"페이지 {i}", "snippet": "..."}
            for i in range(50)]

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"items": rows})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_wiki_tools(_client(handler), ledger)}

    text = tools["search_wiki"].call(scopeKey="ALL", query="연차")

    assert text.count("wikiId") <= MAX_WIKI_SEARCH_ROWS
    assert "더 있습니다" in text                     # 잘랐다고 알린다
    assert ledger.wikis == []                       # 검색은 읽은 것이 아니다


def test_search_result_is_not_a_source():
    """검색 결과에 제목이 보였다는 것만으로 출처가 되면 안 된다 — 본문을 읽어야 출처다."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"items": [{"wikiId": "101",
                                                    "title": "휴가 규정",
                                                    "snippet": "연차"}]})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_wiki_tools(_client(handler), ledger)}
    tools["search_wiki"].call(scopeKey="ALL", query="연차")

    assert ledger.is_empty() is True


def test_unknown_scope_is_refused_without_calling_the_backend():
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("허가값이 없는 범위는 부르지 않아야 한다")

    tools = {t.name: t for t in build_wiki_tools(_client(handler), ReadLedger())}

    text = tools["read_wiki"].call(scopeKey="SECRET", wikiId="1")

    assert "허가값이 없는 범위" in text
    # 무엇을 넣어야 하는지 알려 준다 — 실측에서 모델이 같은 값으로 다시 불러 턴을 태웠다.
    assert "search_wiki" in text


def test_list_schedules_passes_period_keyword_and_question_id():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"items": [
            {"scheduleId": "31", "title": "8월 워크샵",
             "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T09:00:00Z",
             "targetText": "전사", "location": "본사"}], "truncated": False})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_schedule_tools(_client(handler), ledger)}

    text = tools["list_schedules"].call(**{"from": "2026-08-01", "to": "2026-08-31",
                                          "keyword": "워크샵"})

    assert "8월 워크샵" in text
    assert seen["params"]["questionId"] == "500"     # 권한 판정 근거
    assert seen["params"]["keyword"] == "워크샵"
    assert ledger.is_empty() is True                 # 목록은 읽은 것이 아니다


def test_list_schedules_tells_the_agent_when_the_backend_truncated():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"items": [], "truncated": True})

    tools = {t.name: t for t in build_schedule_tools(_client(handler), ReadLedger())}

    assert "더 있습니다" in tools["list_schedules"].call(**{"from": "2026-01-01",
                                                          "to": "2026-12-31"})


def test_read_schedule_records_the_source():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"scheduleId": "31", "title": "8월 워크샵",
                                         "content": "전사 워크샵 안내",
                                         "startAt": "2026-08-03T01:00:00Z",
                                         "endAt": "2026-08-03T09:00:00Z",
                                         "targetText": "전사", "location": "본사"})

    ledger = ReadLedger()
    tools = {t.name: t for t in build_schedule_tools(_client(handler), ledger)}

    text = tools["read_schedule"].call(scheduleId="31")

    assert "전사 워크샵 안내" in text
    assert ledger.schedules == [("31", "8월 워크샵")]
    assert ledger.title_of_schedule("31") == "8월 워크샵"


def test_schedule_rows_are_capped_locally_too():
    rows = [{"scheduleId": str(i), "title": f"일정 {i}", "startAt": "",
             "endAt": "", "targetText": "", "location": ""} for i in range(120)]

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"items": rows, "truncated": False})

    tools = {t.name: t for t in build_schedule_tools(_client(handler), ReadLedger())}

    text = tools["list_schedules"].call(**{"from": "2026-01-01", "to": "2026-12-31"})

    assert text.count("scheduleId") <= MAX_SCHEDULE_ROWS
