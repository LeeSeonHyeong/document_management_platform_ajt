"""챗봇 에이전트 — 이전 대화 상한과 응답 조립."""

from datetime import date

import pytest

from agent_runtime.base import RunResult
from wiki_api.answer import (
    NO_SOURCE_CODE,
    TURN_LIMIT_CODE,
    build_response,
    trim_history,
)
from wiki_api.answer_guide import chat_guide
from wiki_api.answer_tools import ReadLedger
from wiki_api.errors import InternalError


def test_history_keeps_the_most_recent_items():
    messages = [{"role": "user", "content": f"질문 {i}"} for i in range(20)]

    kept = trim_history(messages, max_items=12, max_chars=4000)

    assert len(kept) == 12
    assert kept[0]["content"] == "질문 8"      # 오래된 8개가 버려진다
    assert kept[-1]["content"] == "질문 19"    # 순서는 원래대로


def test_history_also_respects_the_character_limit():
    messages = [{"role": "user", "content": "가" * 1500} for _ in range(6)]

    kept = trim_history(messages, max_items=12, max_chars=4000)

    assert len(kept) == 2                       # 1500 * 2 = 3000, 3개면 4500 으로 넘는다
    assert sum(len(m["content"]) for m in kept) <= 4000


def test_history_shorter_than_the_limit_is_untouched():
    messages = [{"role": "user", "content": "짧다"}]

    assert trim_history(messages) == messages


def test_a_single_oversized_message_is_kept():
    """하나만 있고 그것이 상한을 넘으면 버리지 않는다 — 버리면 대화가 통째로 사라진다."""
    messages = [{"role": "user", "content": "가" * 9000}]

    assert trim_history(messages, max_chars=4000) == messages



def test_guide_carries_history_and_indexes_but_not_the_question():
    text = chat_guide(
        [{"role": "user", "content": "안녕"}],
        [{"scopeKey": "ALL", "indexMarkdown": "- [휴가 규정](pages/101.md)"}],
        today=date(2026, 7, 31),
    )

    assert "안녕" in text
    assert "휴가 규정" in text
    assert "ALL" in text


def test_guide_tells_the_model_what_day_it_is():
    """모델은 오늘을 모른다. 없으면 「다음 워크샵」이 훈련 시점 기준으로 조회된다."""
    text = chat_guide([], [], today=date(2026, 7, 31))

    assert "2026-07-31" in text


def test_guide_states_the_hard_rules():
    """지침에서 빠지면 안 되는 규칙 — 읽지 않고 답하지 않기, 모르면 모른다고 하기."""
    text = chat_guide([], [], today=date(2026, 7, 31))

    assert "read_wiki" in text          # 본문을 읽어야 한다는 것을 도구 이름으로 못박는다
    assert "read_schedule" in text
    assert "모른다" in text


def _report(answer="연차는 15일입니다.", wikis=(), schedules=(), kind="wiki"):
    return {"answer": answer, "usedWikiIds": list(wikis),
            "usedScheduleIds": list(schedules), "questionType": kind}


def test_sources_are_what_the_model_used_and_actually_read():
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")
    ledger.note_schedule("31", "8월 워크샵")
    run = RunResult(text="", tool_calls={"read_wiki": 1, "read_schedule": 1},
                    structured=_report(wikis=["101"], schedules=["31"], kind="mixed"))

    response = build_response(run, ledger)

    assert response.answer == "연차는 15일입니다."
    assert response.questionType == "mixed"
    assert {(s.type, s.wikiId or s.scheduleId, s.title) for s in response.sources} == {
        ("wiki", "101", "휴가 규정"), ("schedule", "31", "8월 워크샵")}


def test_a_source_the_model_named_but_never_read_is_dropped():
    """읽은 기록이 화이트리스트다. 「취업 규칙을 썼다」고 신고해도 안 읽었으면 빠진다."""
    ledger = ReadLedger()
    ledger.note_wiki("108", "육아휴직")
    run = RunResult(text="", tool_calls={"read_wiki": 1},
                    structured=_report(wikis=["108", "999"]))

    response = build_response(run, ledger)

    assert [s.wikiId for s in response.sources] == ["108"]


def test_a_page_read_but_not_used_is_also_dropped():
    """3장 읽고 1장으로 답했으면 출처는 1장이다 — 「읽은 것 전부」와 다른 점이다."""
    ledger = ReadLedger()
    for wiki_id, title in [("101", "휴가 규정"), ("108", "육아휴직"), ("120", "출장")]:
        ledger.note_wiki(wiki_id, title)
    run = RunResult(text="", tool_calls={"read_wiki": 3},
                    structured=_report(wikis=["101"]))

    assert [s.wikiId for s in build_response(run, ledger).sources] == ["101"]


def test_no_report_falls_back_to_everything_read():
    """신고를 빼먹었을 때 출처를 0개로 만드는 것보다 과다 포함이 낫다."""
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")
    run = RunResult(text="연차는 15일입니다.", tool_calls={"read_wiki": 1})

    assert [s.wikiId for s in build_response(run, ledger).sources] == ["101"]


def test_looked_but_found_nothing_is_a_normal_answer():
    """FR-QNA-007 — 근거를 못 찾으면 정보 부족을 안내한다. 오류가 아니다."""
    run = RunResult(text="", tool_calls={"search_wiki": 1},
                    structured=_report(answer="위키에서 찾지 못했습니다."))

    response = build_response(run, ReadLedger())

    assert response.sources == []
    assert "찾지 못" in response.answer
    assert response.questionType == "wiki"


def test_never_even_looking_is_a_failure():
    """찾아보지도 않은 것은 고장이다 — 도구 호출이 0건이다."""
    run = RunResult(text="아마 15일일 것입니다.", tool_calls={})

    with pytest.raises(InternalError) as raised:
        build_response(run, ReadLedger())

    assert raised.value.code == NO_SOURCE_CODE
    assert raised.value.status == 500


def test_empty_answer_with_sources_is_also_a_failure():
    ledger = ReadLedger()
    ledger.note_wiki("101", "휴가 규정")

    with pytest.raises(InternalError):
        build_response(RunResult(text="   ", tool_calls={"read_wiki": 1}), ledger)


def test_turn_limit_has_its_own_name():
    """이름만 보고 무슨 일이 있었는지 알 수 있어야 한다."""
    assert TURN_LIMIT_CODE == "AGENT_TURN_LIMIT_REACHED"
    assert NO_SOURCE_CODE == "NO_WIKI_OR_SCHEDULE_WAS_READ"


def test_the_first_stage_endpoint_is_gone():
    """바로 지운다 — 남겨두면 백엔드가 계속 부른다."""
    from fastapi.testclient import TestClient

    from wiki_api.app import create_app

    app = create_app(api_key="k", backend_base_url="http://backend")
    client = TestClient(app)

    response = client.post("/internal/v1/answer-context-selections", json={},
                           headers={"X-Internal-API-Key": "k"})

    assert response.status_code == 404


def test_request_no_longer_accepts_pushed_context():
    """`selectedWikis` 같은 옛 필드를 보내면 400 이다 — 조용히 무시하면 백엔드가
    보내고 있다고 믿는다."""
    from pydantic import ValidationError

    from wiki_api.schemas import AnswerRequest

    with pytest.raises(ValidationError):
        AnswerRequest(questionId="1", conversationId="c", question="q",
                      selectedWikis=[{"wikiId": "101", "title": "t",
                                      "contentMarkdown": "b"}])


def test_index_entry_carries_the_capability():
    from wiki_api.schemas import WikiIndexEntry

    entry = WikiIndexEntry(scopeKey="ALL", indexMarkdown="- 목차",
                           wikiCapability="cap-all")

    assert entry.wikiCapability == "cap-all"
