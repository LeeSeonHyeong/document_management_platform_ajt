"""`claude -p --output-format stream-json` 파서.

`--output-format json` 은 총계만 준다. 출력 토큰이 30,224 라는 것은 알지만 그것이 어느
턴에서 나왔는지 모른다 (설계 문서 D8: 시간 ≈ 출력토큰 ÷ 55 로 거의 일정하므로 **출력
토큰이 곧 지연시간**이고, 그것을 줄이려면 어디서 나오는지부터 알아야 한다).

`stream-json` 은 줄 단위 JSON 이다. 관측된 이벤트 종류 (2026-07-29, haiku):

    system/init              세션 시작
    system/hook_started      SessionStart 훅. 여럿 온다
    system/hook_response
    system/thinking_tokens   `estimated_tokens` 는 **누적값**, `..._delta` 가 증가분
    assistant                content 블록마다 하나. `message.usage` 와 `timestamp`
    user                     tool_result
    rate_limit_event
    result/success|error     마지막. `--output-format json` 페이로드와 같은 모양

## 함정 셋

1. **`assistant` 이벤트는 content 블록마다 온다.** `thinking` 과 `tool_use` 가 같은
   `message.id` 로 따로 오고 `usage` 가 **동일하게 반복**된다. 이벤트마다 더하면 토큰이
   블록 수만큼 부풀려진다. `message.id` 로 접는다.
2. **`--verbose` 가 필요하다.** CLI 가 `-p --output-format stream-json` 에 그것을 요구한다.
3. **`result` 이벤트가 없을 수 있다.** 제한 시간에 걸려 끊긴 실행이 그렇다. 그때도 어디까지
   갔는지는 남아야 하므로 `result` 없이도 턴 내역을 낸다.

## 이 파서는 실행을 깨뜨리지 않는다

파싱 실패는 그 줄을 세고 넘어간다. 관측이 실행을 죽이면 관측을 켤 이유가 없다 —
`claude_code.py` 가 이 요약이 비어도 기존 `RunResult` 를 낸다.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

# CLI 는 MCP 툴을 `mcp__{서버}__{툴}` 로 부른다. 서버 이름은 `claude_code.py` 의
# `_mcp_config` 가 정한다("wiki"). `wiki_mcp/telemetry.py` 의 서버 측 집계는 맨 툴 이름으로
# 세므로, 두 수치를 대조하려면 여기서 접두사를 떼야 한다.
_MCP_PREFIX = "mcp__"


def _bare_tool_name(name: str) -> str:
    if not name.startswith(_MCP_PREFIX):
        return name
    parts = name.split("__")
    return parts[-1] if len(parts) >= 3 else name


def _input_tokens(usage: dict) -> int:
    """읽은 것 전부, 캐시 포함. `claude_code.py` 의 함정 4 참고 — Claude Code 자체
    시스템 프롬프트가 캐시 필드로 들어가므로 세 필드를 합쳐야 한다."""
    return sum(int(usage.get(k, 0) or 0) for k in (
        "input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"))


@dataclass
class TurnRecord:
    """모델 응답 1건. `message.id` 하나 = 턴 하나."""

    index: int
    message_id: str
    model: str
    timestamp: str | None
    input_tokens: int
    output_tokens: int
    tools: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "index": self.index,
            "messageId": self.message_id,
            "model": self.model,
            "timestamp": self.timestamp,
            "inputTokens": self.input_tokens,
            "outputTokens": self.output_tokens,
            "tools": list(self.tools),
        }


@dataclass
class StreamSummary:
    turns: list[TurnRecord] = field(default_factory=list)
    tool_calls: dict[str, int] = field(default_factory=dict)
    thinking_tokens: int = 0
    result: dict = field(default_factory=dict)
    unparsed_lines: int = 0

    def as_dict(self) -> dict:
        """`report.json` 에 그대로 들어가는 모양. dataclass 를 남기면 덤프가 터진다."""
        return {
            "turns": [t.as_dict() for t in self.turns],
            "toolCalls": dict(self.tool_calls),
            "thinkingTokens": self.thinking_tokens,
            "unparsedLines": self.unparsed_lines,
        }


def parse_stream(stdout: str) -> StreamSummary:
    summary = StreamSummary()
    by_message: dict[str, TurnRecord] = {}

    for line in stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            summary.unparsed_lines += 1
            continue
        if not isinstance(event, dict):
            summary.unparsed_lines += 1
            continue

        kind = event.get("type")
        if kind == "result":
            summary.result = event
        elif kind == "assistant":
            _absorb_assistant(event, summary, by_message)
        elif kind == "system" and event.get("subtype") == "thinking_tokens":
            # 누적값이다. 더하지 않고 마지막 값을 쓴다.
            summary.thinking_tokens = int(event.get("estimated_tokens") or 0)

    return summary


def _absorb_assistant(event: dict, summary: StreamSummary,
                      by_message: dict[str, TurnRecord]) -> None:
    message = event.get("message")
    if not isinstance(message, dict):
        summary.unparsed_lines += 1
        return

    message_id = str(message.get("id") or "")
    turn = by_message.get(message_id)
    if turn is None:
        usage = message.get("usage") if isinstance(message.get("usage"), dict) else {}
        turn = TurnRecord(
            index=len(summary.turns) + 1,
            message_id=message_id,
            model=str(message.get("model") or ""),
            timestamp=event.get("timestamp"),
            # 같은 `message.id` 의 다음 이벤트도 같은 usage 를 들고 온다 — 첫 번째만 센다.
            input_tokens=_input_tokens(usage),
            output_tokens=int(usage.get("output_tokens", 0) or 0),
        )
        by_message[message_id] = turn
        summary.turns.append(turn)

    for block in message.get("content") or []:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        name = _bare_tool_name(str(block.get("name") or ""))
        if not name:
            continue
        turn.tools.append(name)
        summary.tool_calls[name] = summary.tool_calls.get(name, 0) + 1
