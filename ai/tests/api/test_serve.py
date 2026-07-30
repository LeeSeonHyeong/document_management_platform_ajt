"""기동 수단. `wiki_api/serve.py` 의 인자와 배선 확인.

`create_app` 만으로는 서버가 서지 않는다 — `app.state.runtime` 이 비어 있고 진입점이 없다.
그래서 진입점 자체를 확인한다. 첫 테스트는 pytest 밖에서도 임포트되는지 보는데, pytest 는
자기 설정으로 경로를 만져주기 때문에 그 안에서만 도는 확인은 프로덕션 기동을 보증하지 못한다.

계약 v1.1.0 은 push 방식이라 이 서버가 Spring 을 되물어 읽는 경로는 없다.
"""

import subprocess
import sys


def test_serve_imports_outside_pytest():
    """별도 프로세스에서도 진입점이 임포트돼야 한다."""
    proc = subprocess.run(
        [sys.executable, "-c", "from wiki_api import serve; serve.build_parser()"],
        capture_output=True, text=True, timeout=120,
    )
    assert proc.returncode == 0, proc.stderr


def test_serve_injects_the_runtime_into_app_state():
    from wiki_api import serve

    app = serve.build_app(runtime_name="claude-code", model="claude-opus-4-6", api_key="k")
    assert app.state.runtime is not None
    assert app.state.runtime.name == "claude-code"
    assert app.state.api_key == "k"


def test_serve_defaults_to_the_claude_code_runtime():
    from wiki_api import serve

    args = serve.build_parser().parse_args([])
    assert args.runtime == "claude-code"
    assert args.port == 8000


def test_serve_reads_the_api_key_from_the_environment(monkeypatch):
    from wiki_api import serve

    monkeypatch.setenv("INTERNAL_API_KEY", "from-env")
    args = serve.build_parser().parse_args([])
    assert serve.resolve_api_key(args) == "from-env"
    args = serve.build_parser().parse_args(["--internal-api-key", "explicit"])
    assert serve.resolve_api_key(args) == "explicit"


# ----- 배포 런타임을 환경변수로 정한다 ----------------------------------------
#
# 배포는 `claude-code` 가 아니다 — 서버에 `claude` CLI 도 로그인 세션도 없다. 그런데 기본값이
# `claude-code` 라서 `--runtime` 을 잊으면 **기동은 성공하고 첫 변환 요청에서 실패한다.**
# 조용한 실패다.
#
# 기본값을 지금 바꾸지 않는 이유는 API 키다 — 없으면 로컬 개발·측정이 전부 멈춘다. 대신
# 환경변수로 정할 수 있게 해서 배포는 명령줄을 고치지 않고 `AI_RUNTIME` 만 설정하면 되게 한다.


def test_the_runtime_can_be_set_by_environment(monkeypatch):
    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagents")
    assert serve.build_parser().parse_args([]).runtime == "deepagents"


def test_the_command_line_wins_over_the_environment(monkeypatch):
    """측정할 때 환경변수가 남아 있어도 명령줄이 이긴다 — 안 그러면 어느 런타임으로 쟀는지
    모르게 된다."""
    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagents")
    args = serve.build_parser().parse_args(["--runtime", "claude-code"])
    assert args.runtime == "claude-code"


def test_the_environment_default_is_still_claude_code(monkeypatch):
    from wiki_api import serve

    monkeypatch.delenv("AI_RUNTIME", raising=False)
    assert serve.build_parser().parse_args([]).runtime == "claude-code"


def test_an_unknown_runtime_in_the_environment_fails_at_startup(monkeypatch):
    """오타가 조용히 기본값으로 되돌아가면 `deepagents` 로 뜬 줄 알고 배포한다."""
    import pytest

    from wiki_api import serve

    monkeypatch.setenv("AI_RUNTIME", "deepagent")     # 오타 — s 가 없다
    with pytest.raises(SystemExit):
        serve.build_parser().parse_args([])


# ----- claude-code 는 CLI 가 있어야 뜬다 --------------------------------------


def test_the_cli_runtime_refuses_to_start_without_the_cli(monkeypatch):
    """배포 서버에 `claude` 가 없다. 기동은 성공하고 첫 요청에서 실패하면 그 사이 업로드가
    전부 실패로 기록된다 — 기동 시점에 막는다."""
    import pytest

    from wiki_api import serve

    monkeypatch.setattr(serve.shutil, "which", lambda name: None)
    with pytest.raises(SystemExit) as excinfo:
        serve.build_app(runtime_name="claude-code", model=None, api_key="k")
    assert "claude" in str(excinfo.value)


def test_the_deepagents_runtime_does_not_need_the_cli(monkeypatch):
    """`deepagents` 는 CLI 를 쓰지 않는다. 그 검사가 여기까지 오면 배포가 안 뜬다."""
    from wiki_api import serve

    monkeypatch.setattr(serve.shutil, "which", lambda name: None)
    called = {}

    def fake_load(name, model):
        called["name"] = name
        return type("R", (), {"name": name, "model": model})()

    monkeypatch.setattr(serve, "load_runtime", fake_load)
    serve.build_app(runtime_name="deepagents", model=None, api_key="k")
    assert called["name"] == "deepagents"


def test_the_cli_check_passes_when_the_cli_is_present(monkeypatch):
    from wiki_api import serve

    monkeypatch.setattr(serve.shutil, "which", lambda name: "/usr/bin/claude")
    app = serve.build_app(runtime_name="claude-code", model="claude-opus-4-6", api_key="k")
    assert app.state.runtime.name == "claude-code"
