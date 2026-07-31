"""FastAPI 앱 조립.

경계: 이 패키지는 계약 모양을 알고 SQLite 를 모른다. `mcp/` 는 반대다. 둘 사이의 유일한
접점은 `pending_changes()` 반환값이며 `api/changes.py` 가 그것을 계약 응답으로 바꾼다.
"""

from __future__ import annotations

import uuid

from fastapi import FastAPI, Request

from .errors import install_error_handlers


def create_app(*, api_key: str | None = None,
               backend_base_url: str | None = None) -> FastAPI:
    """설정은 호출자가 다 채워서 넘긴다. **여기서 `os.environ` 을 읽지 않는다** — 유일한
    조립 지점인 `serve.py` 가 `ServerSettings` 로 이미 병합한 값을 인자로 준다
    (`초기화 인자 > 환경변수 > .env > 기본값`). 여기서 다시 환경을 읽으면 설정 출처가
    갈리는 문제(이 브랜치가 고친 것)가 이 파일에서 되살아난다.
    """
    app = FastAPI(title="AJT FastAPI Internal API", version="1.0.0",
                  docs_url=None, redoc_url=None)
    app.state.api_key = api_key or ""
    # Wiki 조회 창구(계약 1.6.0)의 백엔드 주소. **없으면 창구 경로를 쓰지 않는다** —
    # 요청이 `wikiCapability` 를 실어 와도 주소가 없으면 push 로 돈다
    # (`session.py._federated`). 백엔드가 창구를 배포하기 전의 기본 상태가 이것이다.
    app.state.backend_base_url = backend_base_url or ""

    install_error_handlers(app)

    @app.middleware("http")
    async def _echo_request_id(request: Request, call_next):
        rid = request.headers.get("X-Request-Id") or f"ai-{uuid.uuid4().hex[:16]}"
        request.state.request_id = rid
        response = await call_next(request)
        response.headers["X-Request-Id"] = rid
        return response

    from .routers import answer, source_parse, wiki

    app.include_router(wiki.build_router(app))
    app.include_router(source_parse.build_router(app))
    app.include_router(answer.build_router(app))
    return app
