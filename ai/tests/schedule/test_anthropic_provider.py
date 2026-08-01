"""배포 어댑터. 실제 API 를 부르지 않는다 — MockTransport 로 계약만 확인한다."""

import json

import httpx
import pytest

from schedule_extractor import ProviderError
from schedule_extractor.providers.anthropic import AnthropicProvider

SCHEMA = {"type": "object", "properties": {"schedules": {"type": "array"}},
          "required": ["schedules"]}


def _provider(handler) -> AnthropicProvider:
    return AnthropicProvider(model="claude-opus-5", api_key="test-key",
                             base_url="https://api.anthropic.com",
                             timeout_seconds=170.0,
                             transport=httpx.MockTransport(handler))


async def test_forces_structured_output_with_a_tool():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert request.headers["x-api-key"] == "test-key"
        assert request.headers["anthropic-version"]
        assert body["tool_choice"] == {"type": "tool", "name": "emit_schedules"}
        assert body["tools"][0]["input_schema"] == SCHEMA
        return httpx.Response(200, json={
            "content": [{"type": "tool_use", "name": "emit_schedules",
                         "input": {"schedules": []}}]})

    assert await _provider(handler).complete_json("프롬프트", SCHEMA) == {"schedules": []}


async def test_skips_blocks_that_are_not_the_tool_use():
    """생각 블록·텍스트 블록이 앞에 붙어 와도 툴 입력을 찾아낸다."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "content": [{"type": "text", "text": "일정을 정리하겠습니다"},
                        {"type": "tool_use", "name": "emit_schedules",
                         "input": {"schedules": [{"title": "워크샵"}]}}]})

    result = await _provider(handler).complete_json("프롬프트", SCHEMA)
    assert result["schedules"][0]["title"] == "워크샵"


async def test_missing_tool_use_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "content": [{"type": "text", "text": "일정을 찾지 못했습니다"}]})

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


async def test_refusal_becomes_provider_error():
    """안전 분류기가 거절하면 200 이지만 결과가 없다 — 라우터가 500 으로 바꿔야 한다."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "stop_reason": "refusal",
            "stop_details": {"type": "refusal", "category": None},
            "content": []})

    with pytest.raises(ProviderError, match="거절"):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


async def test_truncated_output_becomes_provider_error():
    """max_tokens 에 걸리면 tool_use 의 input 이 잘린 채로 온다.

    dict 이긴 하므로 그대로 통과시키면 일정 일부가 조용히 사라진다 — 관리자는 문서에
    있던 일정 두 건이 왜 없는지 알 방법이 없다. 잘린 결과는 결과가 아니다.
    """
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "stop_reason": "max_tokens",
            "content": [{"type": "tool_use", "name": "emit_schedules",
                         "input": {"schedules": [{"title": "워크샵"}]}}]})

    with pytest.raises(ProviderError, match="상한"):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


async def test_http_error_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": {"message": "rate limited"}})

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


async def test_api_key_never_leaks_into_the_error_message():
    """ProviderError 문자열은 로그로만 간다 — 그래도 키는 담지 않는다 (설계 §3.6)."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"message": "invalid x-api-key"}})

    with pytest.raises(ProviderError) as caught:
        await _provider(handler).complete_json("프롬프트", SCHEMA)
    assert "test-key" not in str(caught.value)
