"""측정 하네스 — Spring 역할.

**`--dry-run` 이 크레딧 방어다.** 프롬프트가 처음부터 맞을 리 없고, 반복 실행이 조용히
예산을 먹는다. 호출 없이 조립 결과만 보는 경로가 있어야 한다.
"""

import sys
from pathlib import Path

AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(AI_ROOT / "experiments"))

import chat_sim  # noqa: E402

PLAN_PATH = AI_ROOT / "experiments" / "chat_questions.json"
WIKI_DIR = (AI_ROOT / "experiments" /
            "2026-07-27-opus46-12docs" / "data" / "wiki" / "ALL")


def test_계획_파일을_읽는다():
    plan = chat_sim.load_plan(PLAN_PATH)
    assert len(plan["questions"]) == 8
    assert plan["schedules"][1]["scheduleId"] == "9002"


def test_1단계_요청을_계약_모양으로_조립한다():
    plan = chat_sim.load_plan(PLAN_PATH)
    body = chat_sim.build_context_body(plan, plan["questions"][0], WIKI_DIR, index=0)
    assert body["question"] == "연차 며칠까지 쓸 수 있어?"
    assert body["wikiIndexes"][0]["scopeKey"] == "ALL"
    assert "pages/eddb3cec8f14.md" in body["wikiIndexes"][0]["indexMarkdown"]
    assert len(body["scheduleSummaries"]) == 5
    # 요약에는 본문이 없다 — 1단계는 목차·요약만 본다.
    assert "content" not in body["scheduleSummaries"][0]


def test_2단계_요청은_고른_ID_의_본문을_싣는다():
    plan = chat_sim.load_plan(PLAN_PATH)
    body = chat_sim.build_answer_body(plan, plan["questions"][0], WIKI_DIR,
                                      question_type="wiki",
                                      wiki_ids=["eddb3cec8f14"], schedule_ids=[])
    assert body["questionType"] == "wiki"
    assert body["selectedWikis"][0]["wikiId"] == "eddb3cec8f14"
    assert body["selectedWikis"][0]["contentMarkdown"].strip()
    # 제목은 파일에서 읽는다 — Spring 은 DB 에서 읽는다.
    assert body["selectedWikis"][0]["title"] != "eddb3cec8f14"
    assert body["selectedSchedules"] == []


def test_2단계_요청이_일정_본문을_싣는다():
    plan = chat_sim.load_plan(PLAN_PATH)
    body = chat_sim.build_answer_body(plan, plan["questions"][7], WIKI_DIR,
                                      question_type="mixed",
                                      wiki_ids=[], schedule_ids=["9002"])
    assert body["selectedSchedules"][0]["title"] == "전사 오프사이트"
    assert body["selectedSchedules"][0]["content"]


def test_채점은_포함_여부와_순위를_본다():
    result = chat_sim.score(expected=["101", "102"], actual=["999", "101", "102"])
    assert result["hit"] is True
    assert result["allHit"] is True
    assert result["rankOfFirstHit"] == 2
    assert result["noise"] == 1


def test_아무것도_못_맞히면_hit_이_거짓이다():
    result = chat_sim.score(expected=["101"], actual=["999"])
    assert result["hit"] is False
    assert result["allHit"] is False
    assert result["rankOfFirstHit"] is None


def test_dry_run_은_호출하지_않는다(capsys, monkeypatch):
    def boom(*_args, **_kwargs):
        raise AssertionError("dry-run 이 HTTP 를 불렀다 — 크레딧이 나간다")

    monkeypatch.setattr(chat_sim, "_post", boom)
    assert chat_sim.main(["--dry-run", "--plan", str(PLAN_PATH),
                          "--wiki", str(WIKI_DIR)]) == 0
    out = capsys.readouterr().out
    assert "크레딧 소모 0" in out
    assert "연차 며칠까지 쓸 수 있어?" in out


def test_limit_이_질문_수를_줄인다(capsys, monkeypatch):
    monkeypatch.setattr(chat_sim, "_post",
                        lambda *a, **k: (_ for _ in ()).throw(AssertionError()))
    chat_sim.main(["--dry-run", "--limit", "2", "--plan", str(PLAN_PATH),
                   "--wiki", str(WIKI_DIR)])
    out = capsys.readouterr().out
    assert "[0]" in out and "[1]" in out
    assert "[2]" not in out
