"""로컬 모델 어댑터.

전송 계층은 모델 없이 검사한다 — httpx MockTransport 로 ollama 응답을 흉내낸다.
실제 모델 호출은 @pytest.mark.llm 이고 기본 실행에서 빠진다 (설계 §6.4).
"""

import json

import httpx
import pytest

from schedule_extractor import ProviderError
from schedule_extractor.providers.ollama import OllamaProvider

SCHEMA = {"type": "object", "properties": {"schedules": {"type": "array"}}}


def _provider(handler) -> OllamaProvider:
    transport = httpx.MockTransport(handler)
    return OllamaProvider(model="test-model", base_url="http://localhost:11434",
                          timeout_seconds=5.0, transport=transport)


async def test_parses_the_model_response():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["model"] == "test-model"
        assert body["stream"] is False
        assert body["format"] == SCHEMA
        assert body["options"]["temperature"] == 0
        return httpx.Response(200, json={"response": '{"schedules": []}'})

    result = await _provider(handler).complete_json("프롬프트", SCHEMA)
    assert result == {"schedules": []}


async def test_http_error_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="model not found")

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


async def test_non_json_response_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"response": "일정을 찾았습니다!"})

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


async def test_timeout_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("too slow", request=request)

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


# ---- 실호출 (@pytest.mark.llm) ---------------------------------------------
#
# 기본 실행에서 빠진다. 돌리려면:
#   uv run pytest -m llm -v
# ollama 가 없거나 모델이 없으면 skip 한다.

from datetime import datetime, timezone
from pathlib import Path

CORPUS = Path(__file__).resolve().parents[2] / "experiments" / "corpus-schedule"
LLM_MODEL = "qwen2.5:7b-instruct"
NOW = datetime(2026, 7, 29, 3, 0, tzinfo=timezone.utc)


def _live_provider():
    return OllamaProvider(model=LLM_MODEL, base_url="http://localhost:11434",
                          timeout_seconds=170.0)


def _require_ollama():
    try:
        response = httpx.get("http://localhost:11434/api/tags", timeout=2.0)
        response.raise_for_status()
    except httpx.HTTPError:
        pytest.skip("ollama 가 없다")
    names = [model["name"] for model in response.json().get("models", [])]
    if LLM_MODEL not in names:
        pytest.skip(f"{LLM_MODEL} 가 없다 — ollama pull {LLM_MODEL}")


@pytest.mark.llm
async def test_extracts_every_schedule_from_the_notice():
    """작은 모델은 여기서 0건을 낸다 (설계 §2.1). 7B 급이 하한이다."""
    _require_ollama()
    from schedule_extractor import extract_schedules

    markdown = (CORPUS / "documents" / "01-august-notice.md").read_text(encoding="utf-8")
    result = await extract_schedules(markdown, now=NOW, provider=_live_provider())

    titles = " ".join(item.title for item in result.schedules)
    assert "워크샵" in titles
    assert "안전교육" in titles
    assert "급여" in titles

    workshop = next(item for item in result.schedules if "워크샵" in item.title)
    assert workshop.start_at == datetime(2026, 8, 12, 0, 0, tzinfo=timezone.utc)


@pytest.mark.llm
async def test_document_without_dates_yields_nothing():
    _require_ollama()
    from schedule_extractor import extract_schedules

    markdown = (CORPUS / "documents" / "02-no-schedule.md").read_text(encoding="utf-8")
    result = await extract_schedules(markdown, now=NOW, provider=_live_provider())
    assert result.schedules == ()
