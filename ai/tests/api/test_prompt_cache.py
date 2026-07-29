"""프롬프트 캐싱 — 접두사가 고정되는지.

캐시는 **접두사가 완전히 같을 때만** 맞는다. 목차가 맨 앞이 아니면 질문마다 접두사가 달라져
적중이 0 이 되고, 1단계 비용이 예산의 2.4배가 된다. 그것이 조용히 일어나므로 여기서 막는다.

두 겹으로 본다.

  * 조립 단계 — `cache_control` 이 첫 block 에 있고 그 block 이 질문마다 같은가
  * 전송 단계 — 실제로 그 모양이 요청 본문에 실리는가 (mock 서버)
"""

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

pytest.importorskip("langchain_anthropic")

from agent_runtime.base import FAST, answer_context_instruction  # noqa: E402
from agent_runtime.deep_agents import DeepAgentsRuntime  # noqa: E402

INDEX = "# 목차\n- [휴가 규정](pages/101.md)\n- [보상](pages/102.md)\n"
SCHEDULES = [{"scheduleId": "31", "title": "8월 휴가",
              "startAt": "2026-08-03T01:00:00Z", "endAt": "2026-08-03T03:00:00Z"}]


def _messages(question: str, history=None):
    return answer_context_instruction(
        question, history or [], [{"scopeKey": "ALL", "indexMarkdown": INDEX}],
        SCHEDULES)


def test_첫_block_이_목차이고_캐시_표시가_붙는다():
    blocks = _messages("연차 며칠?")[0]["content"]
    assert blocks[0]["cache_control"] == {"type": "ephemeral"}
    assert INDEX in blocks[0]["text"]


def test_질문이_달라도_첫_block_이_같다():
    """접두사 고정. 이게 깨지면 적중이 0 이다."""
    a = _messages("연차 며칠?")[0]["content"][0]
    b = _messages("스톡옵션 베스팅?")[0]["content"][0]
    assert a == b


def test_이전_대화가_달라도_첫_block_이_같다():
    a = _messages("연차?", [])[0]["content"][0]
    b = _messages("연차?", [{"role": "user", "content": "앞선 질문"}])[0]["content"][0]
    assert a == b


def test_질문이_첫_block_에_들어가지_않는다():
    blocks = _messages("연차 며칠?")[0]["content"]
    assert "연차 며칠?" not in blocks[0]["text"]
    assert "연차 며칠?" in blocks[1]["text"]


def test_캐시_표시가_두_번_붙지_않는다():
    """`cache_control` 은 접두사의 끝을 가리킨다. 변동 block 에 붙으면 무의미하다."""
    blocks = _messages("연차 며칠?")[0]["content"]
    marked = [b for b in blocks if "cache_control" in b]
    assert len(marked) == 1
    assert marked[0] is blocks[0]


def test_전송_본문에_캐시_표시가_실린다(monkeypatch):
    """조립이 맞아도 클라이언트가 버리면 적중이 0 이다."""
    seen: list[dict] = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            seen.append(json.loads(self.rfile.read(length) or b"{}"))
            payload = {
                "id": "m", "type": "message", "role": "assistant",
                "model": "mock", "stop_reason": "end_turn",
                "content": [{"type": "text", "text": "{}"}],
                "usage": {"input_tokens": 1, "output_tokens": 1,
                          "cache_read_input_tokens": 0,
                          "cache_creation_input_tokens": 300},
            }
            data = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    host, port = server.server_address
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key-not-real")
    monkeypatch.setenv("ANTHROPIC_BASE_URL", f"http://{host}:{port}")
    try:
        result = DeepAgentsRuntime().complete(_messages("연차?"), tier=FAST, timeout=10)
    finally:
        server.shutdown()
        server.server_close()

    blocks = seen[0]["messages"][0]["content"]
    assert isinstance(blocks, list)
    assert blocks[0].get("cache_control") == {"type": "ephemeral"}
    # 캐시 생성 토큰이 결과형까지 올라와야 한다 — 적중 회귀를 이 값으로 본다.
    assert result.cache_creation_tokens == 300
