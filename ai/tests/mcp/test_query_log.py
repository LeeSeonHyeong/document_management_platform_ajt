"""실제 에이전트 검색어 수집 — 계획 `2026-07-29-wiki-context-hardening.md` Task 9.

설계 문서 §2.3 이 **"위키 변환 질의는 정확어 성격이다"** 를 전제로 2글자 분해를 골랐다.
정확어 R@5 는 0.95 인데 자연어는 0.25 이므로, 그 전제가 틀리면 결론이 흔들린다.

그런데 그 전제는 **가정이다.** 실제 검색어는 원본문서 문장이 아니라 에이전트가 생성한다.
지금까지 툴 호출 **횟수**만 셌고 질의 문자열은 어디에도 남지 않았다.

## 개인정보

질의에 사내 내용이 실린다. **기본은 꺼져 있고** `--query-log` 를 준 세션에서만 남는다.
`--tool-log`(횟수)와 별개 스위치인 이유가 그것이다 — 횟수는 진단에 늘 필요하고 질의는
측정할 때만 필요하다.

## 파일을 나눈 이유

`read_counts` 가 로그를 공백으로 나눠 센다 (`Counter(text.split())`). 같은 파일에 질의
문자열을 섞으면 어절마다 툴 이름으로 세어져 `guards.bypassed_server` 판정이 망가진다.
"""

import json

import pytest

from wiki_mcp import telemetry
from wiki_mcp.tools.references import sync_references
from wiki_mcp.vaultfs import LocalVaultFS

SCOPE = "ALL"
JOB_ID = "9001"

PAGE = """\
---
title: 연차 규정
description: 연차 발생과 이월
date: 2026-07-29
tags: [휴가, 인사]
category: 휴가 정책
---

연차를 사용하려면 승인이 필요하다. 미사용 연차는 3월까지 이월할 수 있다.
"""


@pytest.fixture(autouse=True)
def _reset_sink():
    telemetry.disable_query_log()
    yield
    telemetry.disable_query_log()


@pytest.fixture
async def vault(tmp_path):
    from wiki_mcp.vaultfs.local import bootstrap_scope

    scope_id = await LocalVaultFS.open(tmp_path, SCOPE, JOB_ID)
    await bootstrap_scope(SCOPE)
    fs = LocalVaultFS(SCOPE, JOB_ID)
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, PAGE, title="연차 규정",
                   category="휴가 정책", tags=["휴가", "인사"])
    await sync_references(fs, scope_id, address, PAGE)
    yield scope_id, fs
    await LocalVaultFS.close()


@pytest.fixture
def scope_row(vault):
    scope_id, _ = vault
    return {"id": scope_id, "scope_key": SCOPE, "index_path": f"wiki/{SCOPE}/index.md"}


async def _search(fs, scope_row, query: str) -> str:
    from wiki_mcp.tools.search import SearchHandler

    return await SearchHandler(fs, scope_row).search(query, "*", None, 10)


# ---- 기본은 꺼져 있다 --------------------------------------------------------

async def test_nothing_is_recorded_by_default(vault, scope_row, tmp_path):
    """개인정보·사내 내용이 실린다. 켜지 않으면 아무 파일도 만들지 않는다."""
    _, fs = vault
    await _search(fs, scope_row, "연차")
    assert list(tmp_path.glob("*.jsonl")) == []


async def test_recording_starts_only_after_it_is_enabled(vault, scope_row, tmp_path):
    _, fs = vault
    await _search(fs, scope_row, "켜기 전")
    log = tmp_path / "q.jsonl"
    telemetry.enable_query_log(log)
    await _search(fs, scope_row, "켠 뒤")

    queries = [row["query"] for row in telemetry.read_queries(log)]
    assert queries == ["켠 뒤"]


# ---- 무엇을 남기나 -----------------------------------------------------------

async def test_a_search_records_the_query_and_the_hit_count(vault, scope_row, tmp_path):
    """분류에 히트 수가 필요하다. 0건 질의가 가장 중요한 신호다 — 그것이 현행 구현이
    무엇을 못 찾았는지 보여 준다."""
    _, fs = vault
    log = tmp_path / "q.jsonl"
    telemetry.enable_query_log(log)
    await _search(fs, scope_row, "연차")

    rows = telemetry.read_queries(log)
    assert len(rows) == 1
    assert rows[0]["query"] == "연차"
    assert rows[0]["hits"] >= 1
    assert rows[0]["scopeKey"] == SCOPE


async def test_a_zero_hit_search_is_recorded_too(vault, scope_row, tmp_path):
    _, fs = vault
    log = tmp_path / "q.jsonl"
    telemetry.enable_query_log(log)
    await _search(fs, scope_row, "싸이버펑크")

    rows = telemetry.read_queries(log)
    assert rows[0]["hits"] == 0


async def test_queries_are_recorded_in_order(vault, scope_row, tmp_path):
    _, fs = vault
    log = tmp_path / "q.jsonl"
    telemetry.enable_query_log(log)
    for query in ("연차", "이월", "승인"):
        await _search(fs, scope_row, query)
    assert [r["query"] for r in telemetry.read_queries(log)] == ["연차", "이월", "승인"]


async def test_the_log_is_json_lines(vault, scope_row, tmp_path):
    """줄 단위 JSON 이라 실행이 죽어도 남은 줄이 읽힌다."""
    _, fs = vault
    log = tmp_path / "q.jsonl"
    telemetry.enable_query_log(log)
    await _search(fs, scope_row, "연차")
    for line in log.read_text(encoding="utf-8").splitlines():
        json.loads(line)


async def test_a_truncated_line_is_skipped(tmp_path):
    """죽은 실행의 로그는 마지막 줄이 잘려 있다. 그것 때문에 전체를 못 읽으면 안 된다."""
    log = tmp_path / "q.jsonl"
    log.write_text('{"query": "연차", "hits": 3}\n{"query": "이', encoding="utf-8")
    assert [r["query"] for r in telemetry.read_queries(log)] == ["연차"]


async def test_reading_a_missing_log_is_empty(tmp_path):
    assert telemetry.read_queries(tmp_path / "없다.jsonl") == []


# ---- 기존 횟수 집계가 깨지지 않는다 ------------------------------------------

def test_the_tool_count_log_is_untouched(tmp_path):
    """`read_counts` 는 공백으로 나눠 센다. 질의 문자열이 같은 파일에 섞이면 어절마다
    툴 이름으로 세어져 `guards.bypassed_server` 판정이 망가진다 — 파일을 나눈 이유다."""
    tool_log = tmp_path / "tools.log"
    tool_log.write_text("guide\nsearch\nsearch\nedit\n", encoding="utf-8")
    assert telemetry.read_counts(tool_log) == {"guide": 1, "search": 2, "edit": 1}


async def test_enabling_the_query_log_does_not_write_to_the_tool_log(
        vault, scope_row, tmp_path):
    _, fs = vault
    tool_log = tmp_path / "tools.log"
    tool_log.write_text("guide\n", encoding="utf-8")
    telemetry.enable_query_log(tmp_path / "q.jsonl")
    await _search(fs, scope_row, "연차 이월")
    assert telemetry.read_counts(tool_log) == {"guide": 1}


# ---- 정확어/자연어 분류 ------------------------------------------------------

def test_a_query_whose_words_are_all_in_the_source_is_exact():
    """§2.3 의 전제를 검증하는 방법이다. 질의어가 원본문서에 글자 그대로 있으면 정확어다."""
    source = "연차를 사용하려면 승인이 필요하다. 미사용 연차는 3월까지 이월할 수 있다."
    assert telemetry.classify_query("연차", source) == "exact"
    assert telemetry.classify_query("이월", source) == "exact"


def test_a_query_whose_words_are_absent_is_paraphrase():
    source = "연차를 사용하려면 승인이 필요하다."
    assert telemetry.classify_query("쉬는 날 며칠 받나", source) == "para"


def test_a_partly_present_query_is_paraphrase():
    """일부만 있으면 정확어가 아니다. 정확어 검색의 강점은 어절이 그대로 맞는 것이다."""
    source = "연차를 사용하려면 승인이 필요하다."
    assert telemetry.classify_query("연차 신청 절차", source) == "para"


def test_an_empty_query_is_not_classified():
    assert telemetry.classify_query("", "본문") is None


def test_classification_ignores_particles_attached_in_the_source():
    """원본문서에는 조사가 붙어 있다 — `"연차를"`. 질의 `"연차"` 는 그 안에 부분문자열로
    있으므로 정확어다. 어절 단위로만 비교하면 이것을 자연어로 잘못 센다."""
    assert telemetry.classify_query("연차", "연차를 사용하려면") == "exact"


def test_summarise_reports_the_exact_ratio(tmp_path):
    """§2.3 재검증의 산출물. 수집한 질의 중 정확어 비율이 이 숫자다."""
    log = tmp_path / "q.jsonl"
    log.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in [
        {"query": "연차", "hits": 3},
        {"query": "이월", "hits": 2},
        {"query": "쉬는 날 며칠 받나", "hits": 0},
        {"query": "승인", "hits": 1},
    ]) + "\n", encoding="utf-8")

    summary = telemetry.summarise_queries(
        log, "연차를 사용하려면 승인이 필요하다. 미사용 연차는 이월할 수 있다.")
    assert summary["total"] == 4
    assert summary["exact"] == 3
    assert summary["para"] == 1
    assert summary["exactRatio"] == 0.75
    assert summary["zeroHit"] == 1


# ---- 배선: 기본은 꺼져 있다 --------------------------------------------------

def test_the_server_defaults_the_query_log_to_off(monkeypatch):
    """`--query-log` 를 안 주면 기록하지 않는다. 개인정보라 기본값이 중요하다."""
    import sys

    from wiki_mcp import local_server

    monkeypatch.setattr(sys, "argv", ["local_server", "--scope", "ALL"])
    assert local_server._parse_args().query_log is None

    monkeypatch.setattr(sys, "argv",
                        ["local_server", "--scope", "ALL", "--query-log", "/tmp/q.jsonl"])
    assert local_server._parse_args().query_log == "/tmp/q.jsonl"


def test_the_runtime_omits_the_query_log_flag_by_default(tmp_path):
    """런타임이 MCP 서버에 그 인자를 넘기지 않아야 한다."""
    import json

    from agent_runtime.claude_code import ClaudeCodeRuntime

    config = ClaudeCodeRuntime()._mcp_config(
        tmp_path, "ALL", "42", tmp_path / "tools.log")
    args = json.loads(config)["mcpServers"]["wiki"]["args"]
    assert "--query-log" not in args
    assert "--tool-log" in args, "횟수 집계는 늘 켜져 있다 — 진단에 필요하다"


def test_the_runtime_passes_the_query_log_when_set(tmp_path):
    import json

    from agent_runtime.claude_code import ClaudeCodeRuntime

    target = tmp_path / "queries.jsonl"
    config = ClaudeCodeRuntime(query_log=target)._mcp_config(
        tmp_path, "ALL", "42", tmp_path / "tools.log")
    args = json.loads(config)["mcpServers"]["wiki"]["args"]
    assert args[args.index("--query-log") + 1] == str(target)
