"""기동 수단. `wiki_api/serve.py` 의 인자와 배선 확인.

`create_app` 만으로는 서버가 서지 않는다 — `app.state.runtime` 이 비어 있고 진입점이 없다.
그래서 진입점 자체를 확인한다. 첫 테스트는 pytest 밖에서도 임포트되는지 보는데, pytest 는
자기 설정으로 경로를 만져주기 때문에 그 안에서만 도는 확인은 프로덕션 기동을 보증하지 못한다.

계약 v1.1.0 은 push 방식이라 이 서버가 Spring 을 되물어 읽는 경로는 없다.
"""

import subprocess
import sys

import pytest


def test_serve_imports_outside_pytest():
    """별도 프로세스에서도 진입점이 임포트돼야 한다."""
    proc = subprocess.run(
        [sys.executable, "-c", "from wiki_api import serve; serve.build_parser()"],
        capture_output=True, text=True, timeout=120,
    )
    assert proc.returncode == 0, proc.stderr


def test_the_parser_does_not_read_the_environment(monkeypatch):
    """파서 기본값이 환경을 읽으면 `.env` 병합이 그 뒤라 또 무시된다."""
    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagents")
    args = serve.build_parser().parse_args([])
    assert args.runtime is None
    assert args.internal_api_key is None


def test_settings_take_the_runtime_from_the_environment(monkeypatch):
    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagents")
    settings = serve.settings_from_args(serve.build_parser().parse_args([]))
    assert settings.runtime == "deepagents"


def test_the_command_line_beats_the_environment(monkeypatch):
    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagents")
    monkeypatch.setenv("INTERNAL_API_KEY", "from-env")
    args = serve.build_parser().parse_args(
        ["--runtime", "claude-code", "--internal-api-key", "explicit"])
    settings = serve.settings_from_args(args)

    assert settings.runtime == "claude-code"
    assert settings.internal_api_key == "explicit"


def test_build_app_takes_settings_and_injects_the_runtime():
    from wiki_api import serve
    from wiki_api.settings import ServerSettings

    settings = ServerSettings(runtime="claude-code", model="claude-opus-4-6",
                              internal_api_key="k")
    app = serve.build_app(settings)

    assert app.state.runtime.name == "claude-code"
    assert app.state.api_key == "k"


# ----- claude-code 는 CLI 가 있어야 뜬다 --------------------------------------


def test_the_cli_runtime_refuses_to_start_without_the_cli(monkeypatch):
    """배포 서버에 `claude` 가 없다. 기동은 성공하고 첫 요청에서 실패하면 그 사이 업로드가
    전부 실패로 기록된다 — 기동 시점에 막는다."""
    import pytest

    from wiki_api import serve
    from wiki_api.settings import ServerSettings

    monkeypatch.setattr(serve.shutil, "which", lambda name: None)
    settings = ServerSettings(runtime="claude-code", internal_api_key="k")
    with pytest.raises(SystemExit) as excinfo:
        serve.build_app(settings)
    assert "claude" in str(excinfo.value)


def test_the_deepagents_runtime_does_not_need_the_cli(monkeypatch):
    """`deepagents` 는 CLI 를 쓰지 않는다. 그 검사가 여기까지 오면 배포가 안 뜬다."""
    from wiki_api import serve
    from wiki_api.settings import ServerSettings

    monkeypatch.setattr(serve.shutil, "which", lambda name: None)
    called = {}

    def fake_load(name, model, *, effort=None, fast_model=None, quality_model=None,
                  credentials=None):
        called["name"] = name
        return type("R", (), {"name": name, "model": model})()

    monkeypatch.setattr(serve, "load_runtime", fake_load)
    settings = ServerSettings(runtime="deepagents", internal_api_key="k")
    serve.build_app(settings)
    assert called["name"] == "deepagents"


def test_the_cli_check_passes_when_the_cli_is_present(monkeypatch):
    from wiki_api import serve
    from wiki_api.settings import ServerSettings

    monkeypatch.setattr(serve.shutil, "which", lambda name: "/usr/bin/claude")
    settings = ServerSettings(runtime="claude-code", model="claude-opus-4-6",
                              internal_api_key="k")
    app = serve.build_app(settings)
    assert app.state.runtime.name == "claude-code"


# ----- claude-code 는 자격증명을 받지 않는다 -----------------------------------


def test_claude_code_starts_fine_even_with_an_anthropic_key_and_model_prefix(monkeypatch):
    """이 분기가 브리프의 핵심 제약(claude-code 에 자격증명을 넘기지 않는다)을 지키는
    유일한 코드다. 모델이 `anthropic:` 접두사이고 환경에 Anthropic 키가 있어도
    claude-code 로는 그냥 떠야 한다 — 키가 load_runtime 까지 넘어가면 그 쪽이 거부한다."""
    from wiki_api import serve
    from wiki_api.settings import ServerSettings

    monkeypatch.setattr(serve.shutil, "which", lambda name: "/usr/bin/claude")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-should-not-be-used")
    settings = ServerSettings(runtime="claude-code", model="anthropic:claude-opus-4-6",
                              internal_api_key="k")

    app = serve.build_app(settings)

    assert app.state.runtime.name == "claude-code"


# ----- 잘못된 설정값은 사람이 읽을 한 줄로 실패한다 -----------------------------


def _isolated_settings(monkeypatch, tmp_path, **overrides):
    """실제 `ai/src/.env`(진짜 키가 든 파일)와 프로세스 환경을 완전히 격리한다."""
    for name in ("ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL",
                 "OPENAI_API_KEY", "OPENAI_BASE_URL"):
        monkeypatch.delenv(name, raising=False)
    from wiki_api.settings import ServerSettings

    empty_env = tmp_path / ".env"
    empty_env.write_text("", encoding="utf-8")
    return ServerSettings(_env_file=empty_env, **overrides)


def test_claude_code_warns_on_stderr_when_a_model_key_is_configured_but_unused(
        monkeypatch, capsys, tmp_path):
    """`.env` 에 모델 키를 넣고 claude-code 로 뜨면 그 키가 안 쓰인다는 것을 알아야 한다
    (Minor 6). 기동은 죽이지 않되 stderr 에 한 줄 남긴다."""
    from wiki_api import serve

    monkeypatch.setattr(serve.shutil, "which", lambda name: "/usr/bin/claude")
    settings = _isolated_settings(
        monkeypatch, tmp_path, runtime="claude-code", model="anthropic:claude-opus-4-6",
        internal_api_key="k", anthropic_api_key="sk-configured")

    serve.build_app(settings)

    err = capsys.readouterr().err
    assert "claude-code" in err
    assert "sk-configured" not in err  # 키 값 자체를 찍으면 안 된다


def test_claude_code_stays_quiet_when_no_model_key_is_configured(monkeypatch, capsys,
                                                                 tmp_path):
    from wiki_api import serve

    monkeypatch.setattr(serve.shutil, "which", lambda name: "/usr/bin/claude")
    settings = _isolated_settings(monkeypatch, tmp_path, runtime="claude-code",
                                  internal_api_key="k")

    serve.build_app(settings)

    assert capsys.readouterr().err == ""


def test_a_bad_runtime_value_fails_with_a_readable_one_line_message(monkeypatch):
    """운영자가 `AI_RUNTIME=deepagent` 같은 오타를 pydantic 스택트레이스 안에서
    찾게 하면 기동 시점 가드의 존재 이유가 절반 훼손된다."""
    import pytest

    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagent")   # 오타 — s 가 없다
    args = serve.build_parser().parse_args([])
    with pytest.raises(SystemExit) as excinfo:
        serve.settings_from_args_or_die(args)

    message = str(excinfo.value)
    assert "AI_RUNTIME" in message or "runtime" in message
    assert "deepagent" in message
    assert "Traceback" not in message
    assert "pydantic" not in message


def test_the_readable_error_never_echoes_a_secret_field_value(monkeypatch):
    """pydantic 의 오류 딕셔너리는 항상 `input`(실제로 준 값) 을 담는다 — API 키 필드가
    잘못돼도 그 값이 메시지에 실리면 안 된다. 우리 포매터가 `err["msg"]`·`err["loc"]` 만
    쓰고 `err["input"]`/`err["ctx"]` 를 절대 읽지 않는다는 것을 직접 확인한다."""
    import pytest
    from pydantic import ValidationError

    from wiki_api import serve

    secret = "sk-super-secret-value"

    def fake_settings_from_args(args):
        raise ValidationError.from_exception_data(
            "ServerSettings",
            [{"type": "value_error",
              "loc": ("anthropic_api_key",),
              "input": secret,
              "ctx": {"error": ValueError("형식이 올바르지 않다")}}],
        )

    monkeypatch.setattr(serve, "settings_from_args", fake_settings_from_args)

    with pytest.raises(SystemExit) as excinfo:
        serve.settings_from_args_or_die(serve.build_parser().parse_args([]))

    assert secret not in str(excinfo.value)


def test_startup_rejects_model_without_its_credential():
    """OpenAI 모델을 지정했는데 OpenAI 키가 없으면 기동에서 막는다.

    첫 요청에서 「인증 방법을 못 찾았다」로 죽는 것보다 기동에서 죽는 쪽이 낫다.
    `claude-code` CLI 부재를 기동에서 막는 것과 같은 이유다.
    """
    from wiki_api.serve import check_model_credentials
    from wiki_api.settings import ServerSettings

    settings = ServerSettings(internal_api_key="k", runtime="deepagents",
                              model="openai:gpt-4o-mini", openai_api_key="")

    with pytest.raises(SystemExit) as raised:
        check_model_credentials(settings)

    assert "openai" in str(raised.value).lower()


def test_startup_passes_when_the_credential_is_there():
    from wiki_api.serve import check_model_credentials
    from wiki_api.settings import ServerSettings

    settings = ServerSettings(internal_api_key="k", runtime="deepagents",
                              model="openai:gpt-4o-mini", openai_api_key="sk-test")

    check_model_credentials(settings)   # 예외가 없으면 통과
