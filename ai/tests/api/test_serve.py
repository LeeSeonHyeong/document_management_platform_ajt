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
