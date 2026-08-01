"""AI 서버 기동 진입점.

`create_app()` 만으로는 서버가 서지 않는다 — `app.state.runtime` 이 비어 있어서 첫 요청에서
`AttributeError` 가 난다. 이 파일이 그것을 채운다.

얇게 유지한다. 여기서 하는 일은 런타임 선택과 uvicorn 기동뿐이고, 요청 처리 규칙은 전부
`wiki_api/` 안에 있다.

    uv run python -m wiki_api.serve --port 8000              # deepagents (기본)
    uv run python -m wiki_api.serve --runtime claude-code --model claude-opus-4-6

`--internal-api-key` 를 안 주면 `INTERNAL_API_KEY` 환경변수를 쓴다. 둘 다 없으면 모든 요청이
401 이다 (`wiki_api/deps.py` — 빈 키는 어떤 값과도 일치하지 않는다).

## 설정은 `ServerSettings` 가 조립한다

우선순위는 `초기화 인자(CLI) > 환경변수 > .env > 기본값` 이고, 이 파일은 그 병합을 직접 하지
않는다 — `wiki_api/settings.py` 의 `ServerSettings` 가 pydantic-settings 로 이미 그 규칙을
구현하고 있다. 그래서 **`build_parser()` 의 기본값은 전부 `None` 이다.**

이전 판본은 `--runtime` 의 기본값에서 `_runtime_from_environment()` 를 불렀는데, 그 함수가
`os.environ` 을 읽는 시점이 **파서를 만드는 시점**이었다. 파서 생성은 설정 병합보다 먼저
일어나므로 `.env` 파일의 `AI_RUNTIME` 값은 거기 도달하기 전에 이미 무시됐다 — 이 티켓이
고치려는 것과 같은 함정이다. 그래서 파서 기본값은 절대 환경을 읽지 않는다.

## 기본 런타임이 `deepagents` 인 이유

S15P11B106-175 가 요청 본문으로 위키를 받는 push 경로를 지웠다. 그 뒤로 위키 엔드포인트
(`wiki-transformations`·`wiki-edits`)는 도구가 이 프로세스 안에서 도는 런타임에서만
성립한다 — `claude-code` 는 `claude` CLI 를 하위 프로세스로 띄우고 그 MCP 서버가 다른
프로세스에 있어 조회 API 본문이 에이전트에 닿지 않는다
(`wiki_api/session.py._assert_runtime_can_use_the_gateway`). 기본값이 `claude-code` 였을 때는
`--runtime` 을 잊으면 기동은 성공하고 **첫 위키 요청에서 거절됐다** — 그래서 기본값을
`deepagents` 로 뒤집었다 (`wiki_api/settings.py`).

`claude-code` 는 로컬에서 명시할 때만 뜬다 (`--runtime claude-code` 또는
`AI_RUNTIME=claude-code`). 로그인 세션으로 과금하고 API 키가 없어도 되지만, 그 상태에서는
챗봇(`/answers`)·파싱(`/source-parses`)만 되고 위키 엔드포인트는 위 이유로 거절된다.

  * **`AI_RUNTIME` 환경변수**로 기본값을 바꿀 수 있다. 명령줄이 환경변수를 이긴다 — 측정할
    때 남은 환경변수가 결과를 바꾸면 어느 런타임으로 쟀는지 모르게 된다.
  * **`claude-code` 로 뜰 때 CLI 가 PATH 에 있는지 기동 시점에 확인한다.** 없으면 기동을
    실패시킨다. 첫 요청까지 기다리지 않는다 (`assert_runtime_is_usable`).

계약 v1.1.0 부터는 pull 방식이다 — 이 서버가 Spring 의 Wiki 조회 API 를 불러 라이브 층을
채운다. Spring 이 선택·변환 요청 본문에 위키 본문과 목차를 실어 보내던 push 경로는 없다.
"""

from __future__ import annotations

import argparse
import shutil
import sys

from pydantic import ValidationError

from agent_runtime import load_runtime
from schedule_extractor.config import build_provider

from .app import create_app
from .settings import (RUNTIMES, ServerSettings, credential_table,
                       schedule_settings)


def build_parser() -> argparse.ArgumentParser:
    """**파서는 환경을 읽지 않는다.** 기본값이 전부 `None` 이고 병합은 `ServerSettings`
    가 한다 — 그래야 `초기화 인자 > 환경변수 > .env > 기본값` 이 한 규칙으로 성립한다.

    이전 판본은 `--runtime` 의 기본값에서 `os.environ` 을 읽었다. 그 시점이 설정 로딩보다
    빨라서 `.env` 의 값이 무시됐다 — 이 티켓이 고치려는 것과 똑같은 함정이다.
    """
    parser = argparse.ArgumentParser(description="AJT AI 서버 (FastAPI 내부 API)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--runtime", default=None, choices=RUNTIMES,
                        help="기본은 deepagents (배포). claude-code 는 로컬 전용이고 "
                             "위키 엔드포인트를 못 쓴다 "
                             "(AI_RUNTIME 환경변수나 .env 로도 정할 수 있다)")
    parser.add_argument("--model", default=None,
                        help="정확한 이름을 쓴다 (anthropic:claude-opus-4-6). "
                             "별칭은 비교를 깬다")
    parser.add_argument("--internal-api-key", default=None,
                        help="기본은 INTERNAL_API_KEY (환경변수 또는 .env)")
    parser.add_argument("--backend-base-url", default=None,
                        help="Spring 의 Wiki 조회 API 주소 (기본은 BACKEND_BASE_URL)")
    parser.add_argument("--log-level", default="info")
    return parser


# CLI 인자 이름 → 설정 필드 이름. 여기 없는 인자는 설정으로 가지 않는다
# (`--host`·`--port`·`--log-level` 은 uvicorn 것이다).
_CLI_TO_SETTING = {
    "runtime": "runtime", "model": "model",
    "internal_api_key": "internal_api_key", "backend_base_url": "backend_base_url",
}


def settings_from_args(args: argparse.Namespace) -> ServerSettings:
    """CLI 로 준 값만 초기화 인자로 얹는다.

    pydantic-settings 가 초기화 인자를 최우선으로 치므로 `CLI > 환경변수 > .env >
    기본값` 이 별도 병합 코드 없이 성립한다.
    """
    overrides = {field: getattr(args, name)
                 for name, field in _CLI_TO_SETTING.items()
                 if getattr(args, name) is not None}
    return ServerSettings(**overrides)


def settings_from_args_or_die(args: argparse.Namespace) -> ServerSettings:
    """`settings_from_args` 를 감싸 `pydantic.ValidationError` 를 사람이 읽을 한두 줄로
    바꾼다.

    안 그러면 운영자가 `AI_RUNTIME=deepagent` 같은 오타를 pydantic 스택트레이스 안에서
    찾아야 한다 — 이 가드의 존재 이유(기동 시점에 즉시 알아보게 하는 것)가 절반
    훼손된다.

    **필드 이름과 메시지만 쓰고 값은 쓰지 않는다.** `err["msg"]` 는 pydantic 이 만든
    일반 오류 문구이거나 우리가 직접 쓴 검증기 메시지뿐이다 — 어느 쪽도 `err["input"]`
    (실제로 준 값, API 키 같은 민감값이 들어올 수 있다) 을 담지 않는다. 그래서 `err`
    딕셔너리에서 `"input"`·`"ctx"` 는 절대 읽지 않는다.
    """
    try:
        return settings_from_args(args)
    except ValidationError as exc:
        lines = [f"{'.'.join(str(part) for part in err['loc'])}: {err['msg']}"
                 for err in exc.errors()]
        raise SystemExit("설정 값이 올바르지 않다 (환경변수 또는 .env 확인):\n"
                         + "\n".join(f"  - {line}" for line in lines)) from None


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


def assert_backend_base_url_is_set(settings: ServerSettings) -> None:
    """Wiki 조회 API 주소가 없으면 기동하지 않는다 (설계 4.3).

    S15P11B106-175 부터 요청이 위키 본문을 싣지 않는다. 주소가 없으면 하이드레이션이
    아무것도 받지 못하고, 에이전트가 빈 위키를 보고 "내용이 없다"고 판단해 라이브를
    덮는다. 설정 하나로 나는 사고이므로 기동 시점에 막는다.

    `assert_runtime_is_usable` 이 같은 이유로 있다 — 첫 요청에서 알게 되는 구성 오류는
    기동에서 알려 준다.
    """
    if (settings.backend_base_url or "").strip():
        return
    raise SystemExit(
        "BACKEND_BASE_URL 이 비어 있다 — Wiki 조회 API 주소가 없으면 위키 변환이 "
        "빈 문맥으로 돌아 라이브 Wiki 를 덮는다. 환경변수 BACKEND_BASE_URL 또는 "
        "src/.env 에 Spring 주소를 넣고 다시 띄운다 (예: http://backend:8080).")


def check_model_credentials(settings: ServerSettings) -> None:
    """모델의 제공자와 자격증명이 맞는지 기동 시점에 본다.

    `claude-code` 는 로그인 세션으로 과금하므로 검사 대상이 아니다.

    `assert_runtime_is_usable` 과 같은 이유로 기동에서 막는다 — 첫 요청에서 「인증 방법을
    못 찾았다」로 죽으면 그 사이 들어온 요청이 전부 실패로 기록되고 원인이 남지 않는다.
    """
    if settings.runtime != "deepagents":
        return
    needed = {
        (settings.model or "").split(":", 1)[0],
        (settings.model_fast or "").split(":", 1)[0],
        (settings.model_quality or "").split(":", 1)[0],
    } - {""}
    keys = {"anthropic": settings.anthropic_api_key,
            "openai": settings.openai_api_key}
    for provider in sorted(needed):
        if provider in keys and not keys[provider]:
            raise SystemExit(
                f"{provider} 모델을 쓰도록 설정했는데 {provider} API 키가 없습니다. "
                f"src/.env 의 {provider.upper()}_API_KEY 를 채우거나 모델을 바꾸십시오.")


def build_app(settings: ServerSettings):
    assert_runtime_is_usable(settings.runtime)
    assert_backend_base_url_is_set(settings)
    check_model_credentials(settings)
    app = create_app(api_key=settings.internal_api_key,
                     backend_base_url=settings.backend_base_url)
    # `claude-code` 는 로그인 세션으로 과금한다 — 모델 API 키를 넘기면 `load_runtime` 이
    # 거부한다 (agent_runtime/__init__.py). 모델 문자열이 `anthropic:` 로 시작하고 `.env`
    # 에 Anthropic 키가 있으면 그냥 넘겼을 때 기동이 죽는다.
    if settings.runtime == "claude-code":
        credentials: dict[str, tuple[str, str]] = {}
        if credential_table(settings):
            # 기동을 죽이지 않는다 — `.env` 에 모델 키를 두고 실험 삼아 claude-code 로
            # 뜨는 것은 흔한 실수지 사고가 아니다. 다만 조용히 버려지면 "키를 넣었는데
            # 왜 안 쓰이나" 를 알 방법이 없다. **키 값은 찍지 않는다.**
            print("경고: .env 에 모델 API 키가 있지만 claude-code 런타임은 로그인 "
                  "세션으로 과금하므로 그 키를 쓰지 않는다.", file=sys.stderr)
    else:
        # 표는 프로바이더별이다 — 에이전트 모델(settings.model)과 티어 모델
        # (model_fast·model_quality)의 프로바이더가 다를 수 있어서 모델 하나 기준
        # 쌍 하나로는 부족하다 (Important 1). `DeepAgentsRuntime` 이 호출할 모델마다
        # 이 표에서 자기 프로바이더 몫을 조회한다.
        credentials = credential_table(settings)
    # 런타임은 프로세스 하나에 하나다. 요청마다 만들지 않는다 — 모델·설정이 요청 사이에
    # 흔들리면 두 측정의 비교가 조용히 깨진다.
    app.state.runtime = load_runtime(
        settings.runtime, settings.model,
        fast_model=settings.model_fast, quality_model=settings.model_quality,
        credentials=credentials)
    # 일정 추출 어댑터도 프로세스 하나에 하나다. 여기서 만들면 설정 오류(예:
    # provider=anthropic 인데 키 없음)가 첫 요청 500 이 아니라 기동 실패로 나온다.
    app.state.schedule_provider = build_provider(schedule_settings(settings))
    return app


def startup_banner(app, settings: ServerSettings, *, host: str, port: int) -> str:
    """기동 한 줄. **키 값은 넣지 않는다** — 무엇으로 떴는지만 적는다.

    일정 추출 어댑터를 함께 적는 이유: 기본값이 로컬 ollama 다. 배포에서
    `SCHEDULE_EXTRACTOR_PROVIDER` 를 빼먹으면 서버는 정상으로 뜨고 첫 일정 문서
    업로드만 500 이 된다 — 가장 늦게 발견되는 실패 모양이다. `anthropic` 은 키가
    없으면 기동에서 죽지만(`config.py`) `ollama` 는 죽을 이유가 없어 그 가드가 없다.
    무엇으로 떴는지 기동 로그에 남기는 것이 그 자리를 메운다.

    `resolved()` 를 부르는 이유: 비운 `model`·`base_url` 을 프로바이더별 기본값으로
    채운 뒤의 값이 실제로 쓰이는 값이다. 설정 파일에 적힌 빈 문자열을 적으면 로그가
    거짓말을 한다.
    """
    schedule = schedule_settings(settings).resolved()
    return (f"AI 서버 — 런타임 {app.state.runtime.name} "
            f"({getattr(app.state.runtime, 'model', '?')}), "
            f"일정 추출 {schedule.provider}/{schedule.model} @ {schedule.base_url}, "
            f"http://{host}:{port}")


def main() -> None:
    import uvicorn

    args = build_parser().parse_args()
    settings = settings_from_args_or_die(args)
    if not settings.internal_api_key:
        print("경고: 내부 API 키가 없다 — 모든 요청이 401 이다 "
              "(--internal-api-key 또는 INTERNAL_API_KEY)", file=sys.stderr)

    app = build_app(settings)
    print(startup_banner(app, settings, host=args.host, port=args.port))
    uvicorn.run(app, host=args.host, port=args.port, log_level=args.log_level)


if __name__ == "__main__":
    main()
