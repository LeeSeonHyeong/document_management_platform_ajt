"""`stream-json` 파서 — 계획 `2026-07-29-wiki-context-hardening.md` Task 5.

지금까지 모든 측정이 `--output-format json` 이라 **총계만** 남았다. 출력 토큰이 30,224 인
것은 알지만 그것이 어느 턴에서 나왔는지 모른다 (D8). Task 6·7 의 결과를 해석하려면 이것이
먼저다.

아래 샘플은 `claude -p --output-format stream-json --verbose` 의 **실제 출력**에서
가져왔다 (2026-07-29, haiku). 추측으로 쓴 파서는 첫 실행에서 조용히 빈 내역을 낸다.

관측된 함정 둘:

  * **`assistant` 이벤트는 content 블록마다 하나씩 온다.** `thinking` 과 `text` 가 같은
    `message.id` 로 두 번 오고 `usage` 가 동일하다 — 이벤트마다 더하면 토큰이 2배가 된다.
  * **`--verbose` 가 없으면 `stream-json` 이 안 나온다.** CLI 가 그 조합을 요구한다.
"""

import json

from agent_runtime.stream_json import parse_stream

# 실제 출력에서 가져온 모양. 값만 줄였다.
INIT = {"type": "system", "subtype": "init", "session_id": "s1"}
HOOK = {"type": "system", "subtype": "hook_started", "hook_name": "SessionStart:startup"}
THINKING_1 = {"type": "system", "subtype": "thinking_tokens",
              "estimated_tokens": 14, "estimated_tokens_delta": 12}
THINKING_2 = {"type": "system", "subtype": "thinking_tokens",
              "estimated_tokens": 228, "estimated_tokens_delta": 214}
RATE_LIMIT = {"type": "rate_limit_event", "status": "allowed"}

USAGE_1 = {"input_tokens": 10, "cache_creation_input_tokens": 9911,
           "cache_read_input_tokens": 17900, "output_tokens": 6}
USAGE_2 = {"input_tokens": 4, "cache_creation_input_tokens": 0,
           "cache_read_input_tokens": 27811, "output_tokens": 120}


def _assistant(message_id: str, usage: dict, content: list, timestamp: str) -> dict:
    return {"type": "assistant", "timestamp": timestamp, "session_id": "s1",
            "message": {"id": message_id, "model": "claude-haiku-4-5-20251001",
                        "role": "assistant", "content": content, "usage": usage}}


def _tool_result(tool_use_id: str) -> dict:
    return {"type": "user", "session_id": "s1",
            "message": {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": tool_use_id, "content": "ok"}]}}


RESULT = {
    "type": "result", "subtype": "success", "is_error": False,
    "result": "반영했다", "num_turns": 3, "total_cost_usd": 0.042,
    "duration_ms": 91234,
    "usage": {"input_tokens": 14, "cache_creation_input_tokens": 9911,
              "cache_read_input_tokens": 45711, "output_tokens": 126},
}

STREAM = "\n".join(json.dumps(event, ensure_ascii=False) for event in [
    HOOK, INIT, THINKING_1,
    # 턴 1 — 툴 두 번. content 블록마다 이벤트가 오므로 같은 message.id 가 반복된다.
    _assistant("msg_A", USAGE_1, [{"type": "thinking", "thinking": "..."}],
               "2026-07-29T10:30:14.871Z"),
    _assistant("msg_A", USAGE_1, [
        {"type": "tool_use", "id": "t1", "name": "mcp__wiki__guide", "input": {}},
        {"type": "tool_use", "id": "t2", "name": "mcp__wiki__search",
         "input": {"query": "연차"}},
    ], "2026-07-29T10:30:14.875Z"),
    _tool_result("t1"), _tool_result("t2"),
    RATE_LIMIT, THINKING_2,
    # 턴 2 — 툴 하나 + 본문.
    _assistant("msg_B", USAGE_2, [
        {"type": "text", "text": "고쳤다"},
        {"type": "tool_use", "id": "t3", "name": "mcp__wiki__lint", "input": {}},
    ], "2026-07-29T10:31:02.100Z"),
    _tool_result("t3"),
    RESULT,
])


# ---- result 이벤트 ------------------------------------------------------------

def test_the_result_event_is_found():
    """마지막 `result` 이벤트가 기존 `--output-format json` 의 페이로드와 같은 모양이다 —
    그래서 총계 지표(`num_turns`·`total_cost_usd`·`usage`)의 의미가 바뀌지 않는다.
    지난 측정과의 비교 가능성이 여기 달려 있다."""
    summary = parse_stream(STREAM)
    assert summary.result["num_turns"] == 3
    assert summary.result["total_cost_usd"] == 0.042
    assert summary.result["result"] == "반영했다"


# ---- 턴별 내역 ---------------------------------------------------------------

def test_turns_are_counted_by_message_id_not_by_event():
    """`assistant` 이벤트는 content 블록마다 하나씩 온다. 이벤트를 턴으로 세면 2배가 된다."""
    summary = parse_stream(STREAM)
    assert [t.index for t in summary.turns] == [1, 2]


def test_a_turn_carries_its_tokens_once():
    """같은 `message.id` 의 `usage` 는 동일한 값이 반복된다 — 한 번만 센다."""
    summary = parse_stream(STREAM)
    first = summary.turns[0]
    # 입력은 세 필드의 합이다 (`claude_code.py` 의 함정 4).
    assert first.input_tokens == 10 + 9911 + 17900
    assert first.output_tokens == 6


def test_a_turn_carries_the_tools_it_called_in_order():
    summary = parse_stream(STREAM)
    assert summary.turns[0].tools == ["guide", "search"]
    assert summary.turns[1].tools == ["lint"]


def test_a_turn_carries_its_timestamp_and_model():
    summary = parse_stream(STREAM)
    assert summary.turns[0].timestamp == "2026-07-29T10:30:14.871Z"
    assert summary.turns[0].model == "claude-haiku-4-5-20251001"


# ---- 툴 이름 -----------------------------------------------------------------

def test_mcp_tool_names_are_stripped_to_the_bare_tool():
    """CLI 는 `mcp__wiki__search` 로 부른다. `wiki_mcp/telemetry.py` 의 서버 측 집계는
    `search` 로 센다 — 두 수치를 대조하려면 같은 이름이어야 한다."""
    summary = parse_stream(STREAM)
    assert summary.tool_calls == {"guide": 1, "search": 1, "lint": 1}


def test_a_non_mcp_tool_keeps_its_own_name():
    """CLI 내장 툴(`Read`·`Bash` 등)도 셀 수 있어야 한다. 에이전트가 MCP 를 우회해
    파일을 직접 읽으면 그것이 여기 나타난다 — 서버 측 집계에는 안 보인다."""
    stream = json.dumps(_assistant("msg_X", USAGE_1, [
        {"type": "tool_use", "id": "t9", "name": "Read", "input": {}}], "t"))
    assert parse_stream(stream).tool_calls == {"Read": 1}


# ---- 사고 토큰 ---------------------------------------------------------------

def test_thinking_tokens_take_the_last_cumulative_value():
    """`estimated_tokens` 는 누적값이고 `estimated_tokens_delta` 가 증가분이다. 누적값을
    더하면 크게 부풀려진다."""
    assert parse_stream(STREAM).thinking_tokens == 228


# ---- 견고성: 관측이 실행을 깨뜨리지 않는다 -----------------------------------

def test_a_truncated_line_is_skipped_and_counted():
    """스트림이 중간에 잘려도 파싱된 부분은 살린다. 프로세스가 죽었을 때 남은 내역이
    진단의 전부다."""
    summary = parse_stream(STREAM + '\n{"type":"assist')
    assert summary.unparsed_lines == 1
    assert summary.result["num_turns"] == 3


def test_a_non_json_line_is_skipped():
    """CLI 가 경고를 stdout 에 섞어 낼 수 있다."""
    summary = parse_stream("warning: something\n" + STREAM)
    assert summary.unparsed_lines == 1
    assert len(summary.turns) == 2


def test_an_empty_stream_yields_an_empty_summary():
    summary = parse_stream("")
    assert summary.turns == []
    assert summary.result == {}
    assert summary.tool_calls == {}


def test_a_stream_without_a_result_event_still_yields_turns():
    """제한 시간에 걸려 끊긴 실행이 이 모양이다 — `result` 가 없어도 어디까지 갔는지는
    남아야 한다."""
    without_result = "\n".join(
        line for line in STREAM.splitlines()
        if '"type":"result"' not in line.replace(" ", ""))
    summary = parse_stream(without_result)
    assert summary.result == {}
    assert len(summary.turns) == 2


# ---- 보고용 dict --------------------------------------------------------------

def test_as_dict_is_json_serialisable_for_the_report():
    """`report.json` 에 그대로 들어간다. dataclass 가 섞여 있으면 덤프가 터진다."""
    payload = parse_stream(STREAM).as_dict()
    json.dumps(payload, ensure_ascii=False)
    assert payload["turns"][0]["tools"] == ["guide", "search"]
    assert payload["thinkingTokens"] == 228
    assert payload["toolCalls"] == {"guide": 1, "search": 1, "lint": 1}


# ---- 런타임 배선 --------------------------------------------------------------

def test_the_runtime_asks_for_stream_json_with_verbose(monkeypatch, tmp_path):
    """`--verbose` 를 빼면 CLI 가 스트림을 내지 않는다. 그러면 내역이 조용히 빈다."""
    argv_seen = _capture_argv(monkeypatch, stdout=STREAM)
    _run(tmp_path)
    argv = argv_seen[0]
    assert "--output-format" in argv
    assert argv[argv.index("--output-format") + 1] == "stream-json"
    assert "--verbose" in argv


def test_the_runtime_omits_effort_unless_it_is_set(monkeypatch, tmp_path):
    """지금까지 모든 측정이 CLI 기본값으로 돌았다. 여기서 임의로 정하면 과거 측정과의
    비교가 조용히 깨진다 — 정할 근거는 Task 6 의 대조 측정에서 나온다."""
    argv_seen = _capture_argv(monkeypatch, stdout=STREAM)
    _run(tmp_path)
    assert "--effort" not in argv_seen[0]


def test_the_runtime_passes_effort_when_set(monkeypatch, tmp_path):
    argv_seen = _capture_argv(monkeypatch, stdout=STREAM)
    _run(tmp_path, effort="low")
    argv = argv_seen[0]
    assert argv[argv.index("--effort") + 1] == "low"


def test_an_unknown_effort_is_rejected_at_construction():
    """오타가 CLI 오류로 나가면 15분 뒤에 알게 된다."""
    import pytest

    from agent_runtime.claude_code import ClaudeCodeRuntime

    with pytest.raises(ValueError):
        ClaudeCodeRuntime(effort="medum")


def test_the_totals_keep_their_meaning(monkeypatch, tmp_path):
    """`result` 이벤트가 옛 `json` 페이로드와 같은 모양이므로 총계 지표의 의미가 바뀌지
    않는다. 지난 측정과의 비교 가능성이 여기 달려 있다."""
    _capture_argv(monkeypatch, stdout=STREAM)
    result = _run(tmp_path)
    assert result.turns == 3
    assert result.cost_usd == 0.042
    assert result.output_tokens == 126
    assert result.input_tokens == 14 + 9911 + 45711
    assert result.text == "반영했다"


def test_the_run_carries_the_per_turn_detail(monkeypatch, tmp_path):
    _capture_argv(monkeypatch, stdout=STREAM)
    result = _run(tmp_path)
    assert result.detail is not None
    assert [t["tools"] for t in result.detail["turns"]] == [["guide", "search"], ["lint"]]
    assert result.detail["thinkingTokens"] == 228


def test_an_unparsable_stream_still_yields_a_run_result(monkeypatch, tmp_path):
    """관측이 실행을 깨뜨리면 관측을 켤 이유가 없다. 스트림이 전부 쓰레기여도 실패는
    실패로, 성공은 성공으로 나가야 한다 — 여기서는 `result` 가 없으니 실패다."""
    _capture_argv(monkeypatch, stdout="쓰레기\n또 쓰레기\n")
    result = _run(tmp_path)
    assert result.error is not None
    assert result.detail is not None


def _capture_argv(monkeypatch, *, stdout: str, returncode: int = 0) -> list[list[str]]:
    import subprocess

    from agent_runtime import claude_code

    seen: list[list[str]] = []

    class Done:
        def __init__(self):
            self.returncode = returncode
            self.stdout = stdout
            self.stderr = ""

    def fake_run(argv, **_):
        seen.append(argv)
        return Done()

    monkeypatch.setattr(claude_code.subprocess, "run", fake_run)
    assert subprocess  # 임포트가 실제로 쓰였음을 보인다
    return seen


def _run(tmp_path, **kwargs):
    from agent_runtime.claude_code import ClaudeCodeRuntime

    return ClaudeCodeRuntime(**kwargs).run(
        "지시", root=tmp_path, scope_key="ALL", job_id="42")


def test_a_missing_result_event_is_a_failure_not_an_empty_success(monkeypatch, tmp_path):
    """종료 코드가 0 이어도 `result` 이벤트가 없으면 실패다.

    앞 판본은 이것을 **성공으로** 냈다 — `_payload` 가 JSONDecodeError 에 `{}` 를
    돌려주고 `is_error` 가 falsy 라서 아무것도 안 한 실행이 빈 본문의 성공이 됐다.
    세션은 그것을 「변경 없음」 200 으로 Spring 에 보내고 `document_results` 에 성공으로
    남는다 (NFR-AI-003 상실). 이 방향으로 다시 새면 안 된다."""
    # 턴은 정상인데 스트림이 result 앞에서 끊긴 경우 — 제한 시간 밖에서도 생긴다.
    truncated = "\n".join(
        line for line in STREAM.splitlines()
        if '"type":"result"' not in line.replace(" ", ""))
    _capture_argv(monkeypatch, stdout=truncated, returncode=0)
    result = _run(tmp_path)
    assert result.error is not None, "result 없는 실행이 성공으로 나갔다"
    assert "result 이벤트가 없다" in result.error
    # 그래도 어디까지 갔는지는 남는다.
    assert len(result.detail["turns"]) == 2


def test_a_timeout_keeps_the_partial_detail(monkeypatch, tmp_path):
    """제한 시간 초과가 D1·D8 판단의 핵심 사례다. 그때 내역이 없으면 왜 오래 걸렸는지
    알 수 없다."""
    import subprocess

    from agent_runtime import claude_code

    def fake_run(argv, **_):
        raise subprocess.TimeoutExpired(argv, 1, output=STREAM)

    monkeypatch.setattr(claude_code.subprocess, "run", fake_run)
    result = _run(tmp_path)
    assert result.error is not None and "끝나지 않았다" in result.error
    assert [t["tools"] for t in result.detail["turns"]] == [["guide", "search"], ["lint"]]


def test_a_timeout_with_byte_output_still_parses(monkeypatch, tmp_path):
    """`TimeoutExpired.stdout` 은 `text=True` 여도 bytes 로 오는 경우가 있다."""
    import subprocess

    from agent_runtime import claude_code

    def fake_run(argv, **_):
        raise subprocess.TimeoutExpired(argv, 1, output=STREAM.encode("utf-8"))

    monkeypatch.setattr(claude_code.subprocess, "run", fake_run)
    result = _run(tmp_path)
    assert len(result.detail["turns"]) == 2
