"""서버 설정의 우선순위와 검증.

`.env` 에 넣은 값이 조용히 무시되던 것이 이 모듈이 생긴 이유다. LangChain 은
`os.environ` 을 보는데 `.env` 는 pydantic Settings 의 선언된 필드로만 들어갔다.
"""

import pytest

from wiki_api.settings import (RUNTIMES, ServerSettings, credential_table,
                              credentials_for, schedule_settings,
                              vision_ocr_engine)


def _write_env(tmp_path, body: str):
    path = tmp_path / ".env"
    path.write_text(body, encoding="utf-8")
    return path


def test_reads_values_from_the_env_file(tmp_path):
    env = _write_env(tmp_path, "OPENAI_API_KEY=from-file\nAI_RUNTIME=deepagents\n")

    settings = ServerSettings(_env_file=env)

    assert settings.openai_api_key == "from-file"
    assert settings.runtime == "deepagents"


def test_vision_ocr_provider_defaults_to_gemini(tmp_path):
    """GMS 게이트웨이의 요청 크기 상한(실측 약 100KB)에 실제 스캔 페이지 대부분이
    걸려 잘못된 'model not found' 로 나온다 — Google API 는 그 제약이 없다."""
    assert ServerSettings(_env_file=_write_env(tmp_path, "")).vision_ocr_provider == "gemini"


def test_vision_ocr_engine_is_none_without_a_gemini_key(tmp_path):
    """gemini 가 기본이어도 키가 없으면 None → parse_pdf 가 로컬 Tesseract 로 폴백한다."""
    assert vision_ocr_engine(ServerSettings(_env_file=_write_env(tmp_path, ""))) is None


def test_vision_ocr_engine_is_built_from_the_gemini_key(tmp_path):
    env = _write_env(tmp_path, "GEMINI_API_KEY=g-key\n")

    engine = vision_ocr_engine(ServerSettings(_env_file=env))

    assert engine is not None
    assert engine._api_key == "g-key"


def test_vision_ocr_engine_uses_an_explicit_gemini_model_and_base_url(tmp_path):
    env = _write_env(tmp_path,
                     "GEMINI_API_KEY=g-key\n"
                     "GEMINI_MODEL=gemini-1.5-flash\n"
                     "GEMINI_BASE_URL=https://gemini.example\n")

    engine = vision_ocr_engine(ServerSettings(_env_file=env))

    assert engine._model == "gemini-1.5-flash"
    assert engine._base_url == "https://gemini.example"


def test_rejects_an_unknown_vision_ocr_provider_at_construction(tmp_path):
    env = _write_env(tmp_path, "VISION_OCR_PROVIDER=azure\n")

    with pytest.raises(ValueError, match="VISION_OCR_PROVIDER"):
        ServerSettings(_env_file=env)


def test_vision_ocr_engine_is_built_from_the_fast_model_and_gms_creds(tmp_path):
    """`anthropic` 으로 명시하면 저렴한 FAST 티어 모델과 anthropic(GMS) 자격으로 만든다.
    모델 문자열의 `anthropic:` 접두사는 떼고 이름만 API 에 넘긴다 (S15P11B106-180)."""
    env = _write_env(tmp_path,
                     "VISION_OCR_PROVIDER=anthropic\n"
                     "AI_MODEL_FAST=anthropic:claude-haiku-4-5-20251001\n"
                     "ANTHROPIC_API_KEY=k\n"
                     "ANTHROPIC_BASE_URL=https://gms.example/api.anthropic.com\n")

    engine = vision_ocr_engine(ServerSettings(_env_file=env))

    assert engine is not None
    assert engine._model == "claude-haiku-4-5-20251001"
    assert engine._base_url == "https://gms.example/api.anthropic.com"


def test_vision_ocr_engine_is_none_without_a_fast_model(tmp_path):
    """FAST 모델이 없으면 None → parse_pdf 가 로컬 Tesseract 로 폴백한다."""
    env = _write_env(tmp_path, "VISION_OCR_PROVIDER=anthropic\n"
                               "ANTHROPIC_API_KEY=k\n"
                               "ANTHROPIC_BASE_URL=https://gms.example\n")
    assert vision_ocr_engine(ServerSettings(_env_file=env)) is None


def test_vision_ocr_engine_is_none_without_anthropic_creds(tmp_path):
    """모델은 있어도 키·주소가 없으면 None(오류로 기동을 막지 않는다)."""
    env = _write_env(tmp_path,
                     "VISION_OCR_PROVIDER=anthropic\n"
                     "AI_MODEL_FAST=anthropic:claude-haiku-4-5-20251001\n")
    assert vision_ocr_engine(ServerSettings(_env_file=env)) is None


def test_process_environment_beats_the_env_file(tmp_path, monkeypatch):
    env = _write_env(tmp_path, "OPENAI_API_KEY=from-file\n")
    monkeypatch.setenv("OPENAI_API_KEY", "from-environment")

    assert ServerSettings(_env_file=env).openai_api_key == "from-environment"


def test_explicit_argument_beats_everything(tmp_path, monkeypatch):
    env = _write_env(tmp_path, "OPENAI_API_KEY=from-file\n")
    monkeypatch.setenv("OPENAI_API_KEY", "from-environment")

    assert ServerSettings(_env_file=env, openai_api_key="explicit").openai_api_key == "explicit"


def test_defaults_to_the_deepagents_runtime(tmp_path):
    """push 경로가 사라져 `claude-code` 로는 위키 엔드포인트를 못 쓴다 — 기본값은
    `deepagents` 다 (`wiki_api/session.py._assert_runtime_can_use_the_gateway`)."""
    assert ServerSettings(_env_file=_write_env(tmp_path, "")).runtime == "deepagents"


def test_rejects_an_unknown_runtime_at_construction(tmp_path):
    """오타가 조용히 통과하면 배포가 뜨고 첫 요청에서 실패한다."""
    env = _write_env(tmp_path, "AI_RUNTIME=deepagent\n")

    with pytest.raises(ValueError) as excinfo:
        ServerSettings(_env_file=env)
    assert "deepagent" in str(excinfo.value)
    for name in RUNTIMES:
        assert name in str(excinfo.value)


def test_unrelated_env_entries_are_ignored(tmp_path):
    """같은 `.env` 를 `wiki_mcp/config.py` 도 읽는다. 남의 변수로 터지면 안 된다."""
    env = _write_env(tmp_path, "WORKSPACE_PATH=/tmp/ws\nAPP_URL=http://x\n")

    assert ServerSettings(_env_file=env).runtime == "deepagents"


@pytest.mark.parametrize("model,expected", [
    ("anthropic:claude-opus-4-6", ("anthropic-key", "https://anthropic.example")),
    ("openai:some-model", ("openai-key", "https://openai.example")),
    ("mistral:whatever", ("", "")),
    (None, ("", "")),
])
def test_credentials_are_chosen_by_provider_prefix(tmp_path, model, expected):
    settings = ServerSettings(
        _env_file=_write_env(tmp_path, ""),
        anthropic_api_key="anthropic-key", anthropic_base_url="https://anthropic.example",
        openai_api_key="openai-key", openai_base_url="https://openai.example")

    assert credentials_for(model, settings) == expected


def test_credential_table_has_an_entry_per_configured_provider(tmp_path):
    """모델별로 다른 프로바이더를 골라야 하므로 `serve.py` 는 단일 쌍이 아니라 표를
    받는다 (Important 1)."""
    settings = ServerSettings(
        _env_file=_write_env(tmp_path, ""),
        anthropic_api_key="anthropic-key", anthropic_base_url="https://anthropic.example",
        openai_api_key="openai-key", openai_base_url="https://openai.example")

    assert credential_table(settings) == {
        "anthropic": ("anthropic-key", "https://anthropic.example"),
        "openai": ("openai-key", "https://openai.example"),
    }


def test_credential_table_omits_providers_without_any_value(tmp_path):
    settings = ServerSettings(_env_file=_write_env(tmp_path, ""),
                              anthropic_api_key="only-anthropic")

    assert credential_table(settings) == {"anthropic": ("only-anthropic", "")}


def test_credential_table_is_empty_when_nothing_is_configured(tmp_path):
    settings = ServerSettings(_env_file=_write_env(tmp_path, ""))

    assert credential_table(settings) == {}


# ----- 일정 추출 어댑터 설정 ---------------------------------------------------


def test_schedule_settings_defaults_to_anthropic(tmp_path):
    """기본 프로바이더는 anthropic(GMS) 다 — GPU 없는 배포 서버가 ollama 를 못 띄우므로
    (S15P11B106-180). 키가 없으면 build_provider 가 기동에서 죽는다."""
    settings = ServerSettings(_env_file=_write_env(tmp_path, ""))

    extractor = schedule_settings(settings)

    assert extractor.provider == "anthropic"


def test_schedule_settings_reuses_the_anthropic_credentials(tmp_path):
    """GMS 게이트웨이 주소·키를 두 번 적으면 한쪽만 갱신되는 사고가 난다."""
    env = _write_env(tmp_path,
                     "SCHEDULE_EXTRACTOR_PROVIDER=anthropic\n"
                     "ANTHROPIC_API_KEY=gms-key\n"
                     "ANTHROPIC_BASE_URL=https://gms.example/gmsapi/api.anthropic.com\n")

    extractor = schedule_settings(ServerSettings(_env_file=env))

    assert extractor.api_key == "gms-key"
    assert extractor.base_url == "https://gms.example/gmsapi/api.anthropic.com"


def test_an_explicit_schedule_base_url_wins(tmp_path):
    env = _write_env(tmp_path,
                     "SCHEDULE_EXTRACTOR_PROVIDER=anthropic\n"
                     "ANTHROPIC_BASE_URL=https://gms.example\n"
                     "SCHEDULE_EXTRACTOR_BASE_URL=https://direct.example\n")

    assert schedule_settings(ServerSettings(_env_file=env)).base_url \
        == "https://direct.example"


def test_the_ollama_adapter_never_receives_the_anthropic_key(tmp_path):
    """로컬 측정 경로(ollama)에 배포 키가 실려 나가지 않는다.
    기본은 이제 anthropic 이라 ollama 는 명시로 고른다."""
    env = _write_env(tmp_path,
                     "SCHEDULE_EXTRACTOR_PROVIDER=ollama\nANTHROPIC_API_KEY=gms-key\n")

    assert schedule_settings(ServerSettings(_env_file=env)).api_key == ""


def test_rejects_an_unknown_schedule_provider_at_construction(tmp_path):
    env = _write_env(tmp_path, "SCHEDULE_EXTRACTOR_PROVIDER=gpt\n")

    with pytest.raises(Exception, match="SCHEDULE_EXTRACTOR_PROVIDER"):
        ServerSettings(_env_file=env)
