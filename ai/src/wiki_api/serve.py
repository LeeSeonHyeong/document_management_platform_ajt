"""AI 서버 기동 진입점.

`create_app()` 만으로는 서버가 서지 않는다 — `app.state.runtime` 이 비어 있어서 첫 요청에서
`AttributeError` 가 난다. 이 파일이 그것을 채운다.

얇게 유지한다. 여기서 하는 일은 런타임 선택과 uvicorn 기동뿐이고, 요청 처리 규칙은 전부
`wiki_api/` 안에 있다.

    uv run python -m wiki_api.serve --port 8000              # claude-code (기본)
    uv run python -m wiki_api.serve --runtime deepagents --model anthropic:claude-opus-4-6

`--internal-api-key` 를 안 주면 `INTERNAL_API_KEY` 환경변수를 쓴다. 둘 다 없으면 모든 요청이
401 이다 (`wiki_api/deps.py` — 빈 키는 어떤 값과도 일치하지 않는다).

## 기본 런타임이 `claude-code` 인 이유와 그 위험

**배포는 `claude-code` 가 아니다.** 서버에 `claude` CLI 도 로그인 세션도 없다. 그런데 기본값이
그것이라 `--runtime` 을 잊으면 기동은 성공하고 **첫 변환 요청에서 실패한다** — 그 사이 올라온
문서가 전부 실패로 기록된다.

기본값을 지금 뒤집지 않는 이유는 API 키다. `deepagents` 는 키가 있어야 돌고, 없으면 로컬
개발과 측정이 전부 멈춘다. 키가 확보되면 기본값을 바꾼다.

그때까지 두 가지로 조용한 실패를 막는다.

  * **`AI_RUNTIME` 환경변수**로 기본값을 정할 수 있다. 배포는 명령줄을 고치지 않고 그것만
    설정하면 된다. 명령줄이 환경변수를 이긴다 — 측정할 때 남은 환경변수가 결과를 바꾸면
    어느 런타임으로 쟀는지 모르게 된다.
  * **`claude-code` 로 뜰 때 CLI 가 PATH 에 있는지 기동 시점에 확인한다.** 없으면 기동을
    실패시킨다. 첫 요청까지 기다리지 않는다.

계약 v1.1.0 은 push 방식이다 — Spring 이 선택·변환 요청 본문에 위키 본문과 목차를 실어
보내므로, 이 서버가 Spring 을 되물어 읽는 경로는 없다.
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys

from agent_runtime import load_runtime

from .app import create_app


RUNTIMES = ("claude-code", "deepagents")


def _runtime_from_environment() -> str:
    """`AI_RUNTIME` 을 읽고 값을 검증한다.

    **`argparse` 의 `choices` 는 기본값을 검사하지 않는다.** 그래서 `AI_RUNTIME=deepagent`
    같은 오타가 조용히 통과하고, `deepagents` 로 뜬 줄 알고 배포한 채 첫 변환 요청에서
    실패한다. 여기서 직접 막는다.
    """
    name = os.environ.get("AI_RUNTIME")
    if not name:
        return "claude-code"
    if name not in RUNTIMES:
        raise SystemExit(
            f"AI_RUNTIME 값이 올바르지 않다: {name!r} — {', '.join(RUNTIMES)} 중 하나여야 한다")
    return name


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="AJT AI 서버 (FastAPI 내부 API)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    # 기본값을 환경변수로 정할 수 있다. 배포는 `AI_RUNTIME=deepagents` 만 설정하면 된다.
    parser.add_argument("--runtime", default=_runtime_from_environment(),
                        choices=RUNTIMES,
                        help="기본은 claude-code — 구독 과금이고 품질이 측정된 판본이다. "
                             "배포는 deepagents 다 (AI_RUNTIME 로도 정할 수 있다)")
    parser.add_argument("--model", default=None,
                        help="정확한 이름을 쓴다 (claude-opus-4-6). 별칭은 비교를 깬다")
    parser.add_argument("--internal-api-key", default=None,
                        help="기본은 환경변수 INTERNAL_API_KEY")
    parser.add_argument("--backend-base-url", default=None,
                        help=("Spring 의 Wiki 조회 창구 주소 (기본은 환경변수 "
                              "BACKEND_BASE_URL). 없으면 요청이 wikiCapability 를 "
                              "실어 와도 push 경로로 돈다"))
    parser.add_argument("--log-level", default="info")
    return parser


def resolve_api_key(args: argparse.Namespace) -> str:
    return args.internal_api_key or os.environ.get("INTERNAL_API_KEY", "")


def assert_runtime_is_usable(runtime_name: str) -> None:
    """이 런타임이 이 환경에서 실제로 돌 수 있나. 못 돌면 기동을 실패시킨다.

    `claude-code` 는 `claude` CLI 를 subprocess 로 띄우고 그 CLI 는 로그인 세션으로
    과금한다 (`agent_runtime/claude_code.py` 함정 1). 배포 서버에는 둘 다 없다.

    첫 요청까지 기다리면 그 사이 올라온 문서가 전부 실패로 기록된다 — Spring 은 그것을
    `document_results` 에 남기고 사람은 왜인지 모른다. 기동 시점이 알 수 있는 가장 이른
    지점이다.
    """
    if runtime_name == "claude-code" and shutil.which("claude") is None:
        raise SystemExit(
            "런타임 claude-code 를 골랐는데 `claude` 실행 파일이 PATH 에 없다. "
            "배포에서는 --runtime deepagents 또는 AI_RUNTIME=deepagents 를 쓴다.")


def resolve_backend_base_url(args: argparse.Namespace) -> str:
    return args.backend_base_url or os.environ.get("BACKEND_BASE_URL", "")


def build_app(*, runtime_name: str, model: str | None, api_key: str,
              backend_base_url: str | None = None):
    assert_runtime_is_usable(runtime_name)
    app = create_app(api_key=api_key, backend_base_url=backend_base_url)
    # 런타임은 프로세스 하나에 하나다. 요청마다 만들지 않는다 — 모델·설정이 요청 사이에
    # 흔들리면 두 측정의 비교가 조용히 깨진다.
    app.state.runtime = load_runtime(runtime_name, model)
    return app


def main() -> None:
    import uvicorn

    args = build_parser().parse_args()
    api_key = resolve_api_key(args)
    if not api_key:
        print("경고: 내부 API 키가 없다 — 모든 요청이 401 이다 "
              "(--internal-api-key 또는 INTERNAL_API_KEY)", file=sys.stderr)

    app = build_app(runtime_name=args.runtime, model=args.model, api_key=api_key,
                    backend_base_url=resolve_backend_base_url(args))
    print(f"AI 서버 — 런타임 {app.state.runtime.name} "
          f"({getattr(app.state.runtime, 'model', '?')}), "
          f"http://{args.host}:{args.port}")
    uvicorn.run(app, host=args.host, port=args.port, log_level=args.log_level)


if __name__ == "__main__":
    main()
