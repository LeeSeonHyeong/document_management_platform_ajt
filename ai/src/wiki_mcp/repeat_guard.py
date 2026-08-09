"""동일 도구 호출 반복 가드 — 3회부터 경고, 6회부터 잡 중단 (S15P11B106-311·322).

2026-08-07 삭제 데드락에서 모델이 같은 페이지 전문 `read` 를 53연속 반복하다 턴 상한에서
죽고 토큰 339만 개를 태웠다. 반복의 구조는 자기강화다: 동일 호출이 동일 본문을 컨텍스트에
또 쌓을수록 다음도 같은 행동을 뽑을 확률이 오른다. 그래서 끊는 지점은 모델이 아니라
**도구 응답**이다 — 같은 결과가 다시 쌓이는 것을 막으면 강화 고리가 깨지고 토큰도 안 쌓인다.

## 판정 규칙

직전 호출과 **(도구, 인자)가 같고 결과도 같으면** 연속 카운터가 오르고, 3회째부터 결과
대신 경고를 돌려준다. 다른 호출이 끼거나 결과가 달라지면 리셋한다 — 수정 후 재읽기,
고치고 다시 lint 같은 정당한 재호출은 결과가 달라지므로 통과한다. 원 호출은 항상 실행하고
비교만 한다(로컬 파일 읽기라 저렴하다) — 판정 기준이 경고 자신에 오염되지 않게 하기 위해서다.

## 래핑 지점

`telemetry.count_tool_calls` 와 같은 `ToolManager.call_tool` 디스패처다. 개별 도구 함수를
감싸면 `from __future__ import annotations` 때문에 인자 모델이 이 모듈의 전역으로 해석돼
등록이 통째로 깨진다 — 서버는 도구 0개로 멀쩡히 뜨는 무서운 실패다(telemetry docstring 의
사고 기록). 상태는 프로세스 전역이다: 프로세스 1개 = 작업 1개(구조적 격리)라 세션 간
오염이 없고, 인프로세스 테스트만 `reset_repeat_memory` 로 비운다.

## 의도된 한계

호출이 매번 미묘하게 다른 퇴행(검색어를 조금씩 바꾸며 도는 것)은 여기서 못 잡는다 — 그건
`search` 의 결과집합 감지(S15P11B106-264)와 턴 상한(S15P11B106-309)의 몫이다.
"""

from __future__ import annotations

import hashlib
import json
from typing import Any

# 몇 번째 연속 동일 호출부터 경고로 바꾸나. 1회째는 정상, 2회째(한 번 더 확인)까지 봐주고,
# 3회째부터는 새 정보가 없다는 사실 자체가 에이전트에게 가장 유용한 정보다.
REPEAT_THRESHOLD = 3

# 몇 번째부터 잡을 끊나 (S15P11B106-325). 잡 61 실측: 경고 21회를 무시하고 40턴을
# 태웠다 — 퇴행 루프(빈 텍스트 + 동일 툴콜 자기복제)는 경고문으로 못 멈춘다. 이력에
# 동일 (호출→결과) 쌍이 쌓일수록 다음 턴도 그 패턴을 복제할 확률이 오르므로, 경고
# 3회를 무시했으면 회복 가능성이 없다고 보고 끊는다. 오탐 여지: 실측 전수(성공 14잡)에서
# 최장 연속 동일 호출이 **1회** — 정상 작업은 이 근처에도 안 온다. 결과가 달라지면
# 스트릭이 리셋되므로 수정 후 재읽기 같은 정당한 재호출도 안전하다.
ABORT_THRESHOLD = 6

_WARNING = (
    "⚠️ **같은 호출을 같은 결과로 {count}번 연속 반복하고 있다.** 이 호출은 몇 번을 "
    "다시 불러도 같은 것을 돌려준다 — 새 정보는 없다. 이미 받은 내용으로 판단해서 "
    "다음 행동(`edit`·`create`·`delete`·`lint`, 또는 작업 종료 보고)으로 넘어간다."
)


class RepeatLoopError(RuntimeError):
    """에이전트가 경고를 무시하고 동일 호출을 계속 반복해 잡을 중단한다.

    도구 래퍼 밖으로 던져져 실행을 끝내는 것이 의도다 — 관리자 문구 번역은
    `agent_runtime.deep_agents._admin_error_message` 가 한다.
    """

    def __init__(self, name: str, count: int) -> None:
        super().__init__(
            f"에이전트가 같은 작업을 반복해 중단했다 (`{name}` 동일 호출 {count}회 연속, "
            f"경고 {count - REPEAT_THRESHOLD + 1}회 무시)")
        self.tool_name = name
        self.count = count

def _fingerprint(name: str, arguments: dict[str, Any], result: Any) -> tuple[str, str, str]:
    args_key = json.dumps(arguments or {}, sort_keys=True, ensure_ascii=False, default=str)
    result_key = hashlib.sha256(repr(result).encode("utf-8", "replace")).hexdigest()
    return (name, args_key, result_key)


class RepeatCallGuard:
    """동일 (도구, 인자)·동일 결과의 연속 반복을 세고, 문턱부터 경고문을 돌려준다.

    상태를 인스턴스에 담는 이유(S15P11B106-316): 운영 위키 경로(`arun`)는 MCP 없이
    도구를 인프로세스로 부른다 — 프로세스 전역이면 여러 작업이 한 서버 프로세스에서
    상태를 공유한다. 실행마다 인스턴스를 새로 만들면 리셋 배관이 필요 없다.
    """

    def __init__(self) -> None:
        self._last_call: tuple[str, str, str] | None = None
        self._streak = 0

    def note(self, name: str, arguments: dict[str, Any], result: Any) -> str | None:
        """호출 결과를 기록하고, 문턱을 넘었으면 결과 대신 쓸 경고문을 돌려준다.

        `ABORT_THRESHOLD` 회째부터는 경고 대신 `RepeatLoopError` 를 던져 잡을 끝낸다.
        """
        fingerprint = _fingerprint(name, arguments, result)
        if fingerprint == self._last_call:
            self._streak += 1
        else:
            self._last_call = fingerprint
            self._streak = 1
        if self._streak >= ABORT_THRESHOLD:
            raise RepeatLoopError(name, self._streak)
        if self._streak < REPEAT_THRESHOLD:
            return None
        return _WARNING.format(count=self._streak)


# MCP 서버 경로(하네스·claude-code)용 모듈 인스턴스. 그쪽은 프로세스 1개 = 작업 1개라
# 전역이어도 세션이 안 섞인다.
_module_guard = RepeatCallGuard()


def reset_repeat_memory() -> None:
    """인프로세스 테스트 사이에 상태를 비운다. 실서버는 프로세스 수명이 곧 작업 수명이라 불필요."""
    global _module_guard
    _module_guard = RepeatCallGuard()


def guard_repeated_calls(mcp) -> None:
    """디스패처를 감싸 동일 호출·동일 결과의 연속 반복을 경고로 바꾼다."""
    manager = mcp._tool_manager
    original = manager.call_tool

    async def guarded(name, arguments, context=None, convert_result=False):
        result = await original(name, arguments, context=context,
                                convert_result=convert_result)
        warning = _module_guard.note(name, arguments, result)
        if warning is None:
            return result
        if convert_result:
            from mcp.types import TextContent
            return [TextContent(type="text", text=warning)]
        return warning

    manager.call_tool = guarded
