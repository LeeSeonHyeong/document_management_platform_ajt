"""에이전트 실행의 토큰 합산(`_usage`).

캐시 적중을 빼면 예산이 몇 배 틀린다 — 같은 입력 토큰이라도 `cache_read` 로 청구되면
정가의 일부다. 에이전트 경로도 `complete` 경로(`CompletionResult.cache_read_tokens`)처럼
캐시 값을 들고 다녀야 "왜 13번 호출인데 이 비용인가" 를 로그로 설명할 수 있다.
"""

import logging
from types import SimpleNamespace

from agent_runtime.deep_agents import _log_usage, _usage


def _msg(input_tokens, output_tokens, cache_read=0, cache_creation=0):
    return SimpleNamespace(
        usage_metadata={
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "input_token_details": {
                "cache_read": cache_read,
                "cache_creation": cache_creation,
            },
        }
    )


def test_입력출력_캐시_토큰을_메시지들에서_합산한다():
    messages = [
        _msg(100, 50, cache_read=80, cache_creation=10),
        _msg(200, 30, cache_read=150, cache_creation=0),
    ]

    total = _usage(messages)

    assert total["input_tokens"] == 300
    assert total["output_tokens"] == 80
    assert total["cache_read"] == 230
    assert total["cache_creation"] == 10


def test_usage_metadata_없는_메시지와_details_없는_메시지를_견딘다():
    messages = [
        SimpleNamespace(content="usage 없음"),
        SimpleNamespace(usage_metadata={"input_tokens": 5, "output_tokens": 2}),
    ]

    total = _usage(messages)

    assert total["input_tokens"] == 5
    assert total["output_tokens"] == 2
    assert total["cache_read"] == 0
    assert total["cache_creation"] == 0


def test_log_usage_는_캐시토큰을_포함해_한_줄로_남긴다(caplog):
    usage = {"input_tokens": 1200, "output_tokens": 300,
             "cache_read": 900, "cache_creation": 100}

    with caplog.at_level(logging.INFO, logger="llmwiki.deepagents"):
        _log_usage("42", usage, turns=13, tool_calls=7, elapsed_seconds=72.4)

    line = caplog.text
    assert "deepagents usage" in line
    assert "job=42" in line
    assert "turns=13" in line
    assert "input=1200" in line
    assert "output=300" in line
    assert "cache_read=900" in line
    assert "cache_creation=100" in line
    assert "tool_calls=7" in line
