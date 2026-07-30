"""AI 서버 기동 진입점.

`create_app()` 만으로는 서버가 서지 않는다 — `app.state.runtime` 이 비어 있어서 첫 요청에서
`AttributeError` 가 난다. 이 파일이 그것을 채운다.

얇게 유지한다. 여기서 하는 일은 런타임 선택과 uvicorn 기동뿐이고, 요청 처리 규칙은 전부
`wiki_api/` 안에 있다.

    uv run python -m wiki_api.serve --port 8000              # claude-code (기본)
    uv run python -m wiki_api.serve --runtime deepagents --model anthropic:claude-opus-4-6

`--internal-api-key` 를 안 주면 `INTERNAL_API_KEY` 환경변수를 쓴다. 둘 다 없으면 모든 요청이
401 이다 (`wiki_api/deps.py` — 빈 키는 어떤 값과도 일치하지 않는다).

계약 v1.1.0 은 push 방식이다 — Spring 이 선택·변환 요청 본문에 위키 본문과 목차를 실어
보내므로, 이 서버가 Spring 을 되물어 읽는 경로는 없다.
"""

from __future__ import annotations

import argparse
import os
import sys

from agent_runtime import load_runtime

from .app import create_app


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="AJT AI 서버 (FastAPI 내부 API)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--runtime", default="claude-code",
                        choices=["claude-code", "deepagents"],
                        help="기본은 claude-code — 구독 과금이고 품질이 측정된 판본이다")
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


def resolve_backend_base_url(args: argparse.Namespace) -> str:
    return args.backend_base_url or os.environ.get("BACKEND_BASE_URL", "")


def build_app(*, runtime_name: str, model: str | None, api_key: str,
              backend_base_url: str | None = None):
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
