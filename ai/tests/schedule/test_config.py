"""어댑터 선택. **환경을 읽지 않는다** — 설정은 `ServerSettings` 가 읽어 인자로 내린다.

환경변수 → 이 설정 dataclass 의 변환은 `tests/api/test_settings.py` 가 본다.
여기서는 "설정 모양이 주어졌을 때 어떤 어댑터가 나오나" 만 본다.
"""

import pytest

from schedule_extractor.config import (DEFAULT_ANTHROPIC_MODEL, DEFAULT_OLLAMA_MODEL,
                                       ScheduleExtractorSettings, build_provider)
from schedule_extractor.providers.anthropic import AnthropicProvider
from schedule_extractor.providers.ollama import OllamaProvider


def test_defaults_to_ollama():
    settings = ScheduleExtractorSettings().resolved()
    assert settings.provider == "ollama"
    assert settings.model == DEFAULT_OLLAMA_MODEL
    assert settings.base_url == "http://localhost:11434"
    assert settings.timeout_seconds == 170.0


def test_timeout_stays_under_the_backend_read_limit():
    """백엔드가 180초에 끊는다. 우리가 먼저 끊어야 계약 오류로 나간다 (설계 §3.6)."""
    assert ScheduleExtractorSettings().timeout_seconds < 180.0


def test_anthropic_defaults_are_filled_in_by_the_package():
    """프로바이더를 아는 곳이 여기다 — 위층이 anthropic 기본 모델을 몰라도 된다.

    기본값이 `claude-opus-4-6` 인 이유는 GMS 게이트웨이가 프록시하는 목록이 거기까지라서다
    (`src/.env.example` 의 확인 목록).
    """
    settings = ScheduleExtractorSettings(provider="anthropic").resolved()
    assert settings.model == DEFAULT_ANTHROPIC_MODEL == "claude-opus-4-6"
    assert settings.base_url == "https://api.anthropic.com"


def test_given_values_win_over_the_defaults():
    settings = ScheduleExtractorSettings(
        provider="anthropic", model="claude-sonnet-4-6",
        base_url="https://example.invalid", timeout_seconds=90.0,
        api_key="test-key").resolved()
    assert settings.model == "claude-sonnet-4-6"
    assert settings.base_url == "https://example.invalid"
    assert settings.timeout_seconds == 90.0


def test_build_provider_returns_ollama_by_default():
    assert isinstance(build_provider(ScheduleExtractorSettings()), OllamaProvider)


def test_build_provider_returns_anthropic_when_asked():
    provider = build_provider(
        ScheduleExtractorSettings(provider="anthropic", api_key="test-key"))
    assert isinstance(provider, AnthropicProvider)


def test_anthropic_without_a_key_fails_at_startup():
    """기동 때 알아야 한다 — 첫 요청에서 500 을 내는 것보다 낫다."""
    with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
        build_provider(ScheduleExtractorSettings(provider="anthropic"))


def test_unknown_provider_name_fails():
    with pytest.raises(ValueError, match="gpt"):
        build_provider(ScheduleExtractorSettings(provider="gpt"))
