"""MCP 없는 단발 호출의 유일한 입구.

**모든 호출자가 이 함수만 쓴다.** 런타임의 `complete` 를 직접 부르면 두 가지가 깨진다.

1. **이벤트 루프.** `complete` 는 sync 다. async 함수에서 그냥 부르면 그 줄에서 루프가
   멈추고 서버의 모든 요청이 대기한다. `selection.py` 가 실제로 그 모양이었다 —
   `complete` 가 아직 없어서 죽은 경로였을 뿐이다.
2. **오류 모양.** 계약은 엔드포인트마다 500 코드 이름이 다르다. 변환을 호출자마다 쓰면
   한 곳이 빠지고 그러면 `INTERNAL_SERVER_ERROR` 가 나가 Spring 분기가 깨진다.

두 겹의 상한을 둔다. `asyncio.wait_for` 는 **호출자**를 풀어주고, 런타임에 넘긴 `timeout`
이 **연결**을 끊는다. `to_thread` 로 띄운 스레드는 취소할 수 없으므로 앞의 것만으로는
호출이 계속 살아 크레딧을 쓴다 (`base.py` 의 `run` 주석과 같은 이유).
"""

from __future__ import annotations

import asyncio
import inspect
import tempfile
from pathlib import Path

from agent_runtime.base import QUALITY, CompletionResult, render_messages

from .errors import FailureStage, InternalError
from .telemetry import record

# 스레드를 취소할 수 없으므로 바깥 상한을 런타임 상한보다 조금 넉넉하게 준다. 같게 주면
# 런타임이 자기 오류를 조립할 시간에 바깥이 먼저 터져 "타임아웃"이 실제 원인을 덮는다.
_OUTER_GRACE_SECONDS = 5


def input_bytes(messages: list[dict]) -> int:
    """조립된 메시지 전체의 UTF-8 바이트.

    필드별로 세지 않는 이유는 합계가 상한을 넘는 조합이 통과하기 때문이다. 바이트를 쓰는
    이유는 토큰/문자 비율이 문서마다 1.58배 흔들리는데 토큰/바이트는 1.34배라 덜 흔들려서다
    (실측: 목차 0.488, 산문 0.364 토큰/바이트).

    상한 자체는 각 엔드포인트가 정한다 — 1단계와 2단계가 싣는 것이 다르다.
    """
    total = 0
    for message in messages:
        content = message.get("content")
        if isinstance(content, str):
            total += len(content.encode("utf-8"))
        else:
            for block in content or []:
                total += len(str(block.get("text", "")).encode("utf-8"))
    return total


async def complete(runtime, messages: list[dict], *, tier: str = QUALITY,
                   timeout: float, error_code: str, path: str,
                   request_id: str,
                   fallback_kwargs: dict | None = None) -> CompletionResult:
    """런타임에 한 번 묻고 `CompletionResult` 를 돌려준다.

    `path` 와 `request_id` 를 필수 인자로 받는 이유는 관측 로그가 그 둘을 쓰기 때문이다.
    `ContextVar` + 미들웨어로도 되지만, 하네스가 함수를 직접 부를 때(`--dry-run`) 값이
    비어 로그가 요청과 상관되지 않는다.

    `fallback_kwargs` 는 `complete` 가 없는 런타임을 위한 `run`/`arun` 인자다
    (`scope_key`·`job_id`). 두 프로덕션 런타임이 `complete` 를 갖게 된 뒤에도 남겨 두는
    이유는 테스트 대역과 이식 중인 런타임이 있기 때문이다. 이 경로는 **빈 임시 디렉터리**를
    준다 — 툴이 뜨더라도 아무것도 없는 범위를 보므로 단발 호출이 라이브 위키를 만질 수 없다.
    """
    method = getattr(runtime, "complete", None)
    outer = timeout + _OUTER_GRACE_SECONDS
    try:
        if method is not None:
            if inspect.iscoroutinefunction(method):
                result = await asyncio.wait_for(
                    method(messages, tier=tier, timeout=timeout), timeout=outer)
            else:
                result = await asyncio.wait_for(
                    asyncio.to_thread(method, messages, tier=tier, timeout=timeout),
                    timeout=outer)
        else:
            result = await _fallback(runtime, messages, timeout=timeout,
                                     error_code=error_code,
                                     kwargs=fallback_kwargs or {})
    except asyncio.TimeoutError as exc:
        record(path, request_id, tier, error=f"timeout {timeout}s")
        raise InternalError(
            error_code, f"단발 호출이 제한 시간({timeout}초)을 초과했습니다.",
            FailureStage.AGENT_TIMEOUT) from exc
    except InternalError as exc:
        record(path, request_id, tier, error=exc.message)
        raise
    except Exception as exc:
        record(path, request_id, tier, error=f"{type(exc).__name__}: {exc}")
        raise InternalError(error_code, f"단발 호출에 실패했습니다 — {exc}",
                            FailureStage.AGENT_ERROR) from exc

    # 런타임이 실패를 예외가 아니라 결과 필드로 돌려주는 경우가 있다 (CLI 는 오류에도 종료
    # 코드 0 이다). 그것을 안 보면 실패가 「관련 자료 없음」으로 둔갑한다.
    error = getattr(result, "error", None)
    if error:
        record(path, request_id, tier, error=str(error))
        raise InternalError(error_code, f"단발 호출에 실패했습니다 — {error}",
                            FailureStage.AGENT_ERROR)

    if not isinstance(result, CompletionResult):
        result = CompletionResult(text=str(getattr(result, "text", result) or ""))

    record(path, request_id, tier, result)
    return result


async def _fallback(runtime, messages: list[dict], *, timeout: float,
                    error_code: str, kwargs: dict):
    """`complete` 가 없는 런타임을 빈 임시 루트로 띄운다."""
    prompt = render_messages(messages)
    if hasattr(runtime, "arun"):
        with tempfile.TemporaryDirectory(prefix="ajt-complete-") as tmp:
            return await asyncio.wait_for(
                runtime.arun(prompt, fs=None, scope_id=None, root=Path(tmp),
                             **kwargs),
                timeout=timeout)
    if hasattr(runtime, "run"):
        # sync 런타임의 스레드는 취소할 수 없다. 계산된 상한을 런타임에 넘기는 것이
        # 유일한 강제 수단이므로 `wait_for` 를 걸지 않는다 (`base.py` 의 run 주석).
        with tempfile.TemporaryDirectory(prefix="ajt-complete-") as tmp:
            return await asyncio.to_thread(
                runtime.run, prompt, root=Path(tmp), timeout=timeout, **kwargs)
    raise InternalError(
        error_code,
        f"런타임 {getattr(runtime, 'name', '?')} 에 complete 도 run 도 없습니다.",
        FailureStage.AGENT_START)
