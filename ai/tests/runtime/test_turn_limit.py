"""위키 경로의 턴 상한 — 상한 도달을 고장과 구분한다 (2026-08-06).

챗봇 경로(`_run_with_tools`)는 `ModelCallLimitMiddleware(exit_behavior="error")` 를 달고
그 이유를 주석에 적어 뒀다: *"`recursion_limit` 만 쓰면 GraphRecursionError 가 나고 그것이
일반 예외로 잡혀 「모델 호출 실패」로 뭉개진다"*. **위키 경로에는 그것이 없었다.**

그 결과 두 가지가 어긋나 있었다.

1. 2026-08-06 01:00 실패가 `except Exception` 으로 떨어져 상한 도달이 아니라 고장처럼
   보고됐다 (`GraphRecursionError (마지막 도달 단계: search)`).
2. 실효 상한이 의도의 2/3 였다. `recursion_limit = MAX_TURNS * 2` 인데 자식 실행의 턴당
   superstep 이 3개다(`model` · `TodoListMiddleware.after_model` · `tools`). 두 실패
   트레이스 모두 `MAX_TURNS`(60)가 아니라 **모델 호출 40회**에서 끊겼다 — 120/3 이다.
"""

import pytest

pytest.importorskip("deepagents")

from agent_runtime.deep_agents import (  # noqa: E402
    MAX_TURNS,
    RECURSION_LIMIT_PER_TURN,
    turn_limit_middleware,
)


def test_recursion_limit_이_실측_superstep_보다_넉넉하다():
    """미들웨어가 먼저 걸려야 상한 도달이 이름을 갖는다.

    실측값은 아래 `test_상한에_닿으면...` 이 만든다. 처음 고친 값(4배)으로 그 테스트를
    돌렸더니 240 superstep / 모델 호출 47회 = **5.1** 이 나와 백스톱이 여전히 먼저 걸렸다.
    추정(3)이 아니라 이 실측에 여유를 얹어야 한다.
    """
    MEASURED_SUPERSTEPS_PER_TURN = 5.1

    assert RECURSION_LIMIT_PER_TURN > MEASURED_SUPERSTEPS_PER_TURN


def test_위키_경로도_턴_상한_미들웨어를_만든다():
    from langchain.agents.middleware import ModelCallLimitMiddleware

    mw = turn_limit_middleware()

    assert isinstance(mw, ModelCallLimitMiddleware)


def test_상한_도달은_정상종료가_아니라_예외다():
    """`exit_behavior="end"` 는 영어 안내문을 마지막 AI 메시지로 끼워 정상 종료처럼 끝낸다.

    위키 경로에서 그러면 그 문장이 관리자에게 나가는 작업 요약이 될 수 있다.
    """
    mw = turn_limit_middleware()

    assert getattr(mw, "exit_behavior", None) == "error"


# ---------------------------------------------------------------------------
# 상한 경로를 실제로 태운다. 위의 넷은 "미들웨어가 만들어진다" 까지만 보므로
# `except ModelCallLimitExceededError` 분기가 한 번도 실행되지 않았다 — 그 분기가 곧
# 「상한 도달과 고장을 구분한다」는 이 변경의 전부다.
#
# 가짜 모델로 태운다. LLM 호출이 없으므로 비용이 0 이고, 실기동 없이 분기를 검증한다.
# ---------------------------------------------------------------------------

import tempfile  # noqa: E402
from pathlib import Path  # noqa: E402

from langchain_core.callbacks import CallbackManagerForLLMRun  # noqa: E402
from langchain_core.language_models import BaseChatModel  # noqa: E402
from langchain_core.messages import AIMessage  # noqa: E402
from langchain_core.outputs import ChatGeneration, ChatResult  # noqa: E402

from agent_runtime.deep_agents import DeepAgentsRuntime  # noqa: E402


class NeverStopsModel(BaseChatModel):
    """항상 `guide` 를 다시 부르는 모델. 스스로는 절대 끝내지 않는다.

    2026-08-06 실패의 모양이다 — 도구만 부르고 텍스트를 한 자도 내지 않았다(AI 메시지
    40개가 전부 순수 도구 호출). 그때 상한이 무엇을 하는지가 이 테스트의 대상이다.
    """

    calls: int = 0

    @property
    def _llm_type(self) -> str:
        return "never-stops"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None,
                  run_manager: CallbackManagerForLLMRun | None = None,
                  **kwargs) -> ChatResult:
        self.calls += 1
        message = AIMessage(
            content="",
            tool_calls=[{"name": "guide", "args": {}, "id": f"call_{self.calls}"}],
        )
        return ChatResult(generations=[ChatGeneration(message=message)])


class ScopelessFS:
    """`guide` 가 부르는 표면만 흉내 낸다."""

    async def list_scopes(self):
        return []


async def test_상한에_닿으면_고장이_아니라_턴_상한으로_보고한다():
    """`GraphRecursionError (마지막 도달 단계: search)` 로 나오던 자리다."""
    runtime = DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", "http://gms.example/anthropic")},
        chat_model=NeverStopsModel(),
    )
    root = Path(tempfile.mkdtemp())

    result = await runtime.arun(
        "위키를 고쳐라", fs=ScopelessFS(), scope_id="scope-1", root=root,
        scope_key="ALL", job_id="job-1", timeout=120,
    )

    assert "작업 한도" in result.error
    assert "GraphRecursionError" not in result.error
    # 상한까지 부른 도구 수가 남는다 — 0 이면 상한이 아니라 모델 호출 실패다.
    assert result.tool_calls.get("guide", 0) > 0


async def test_상한_도달_메시지가_관리자에게_다음_행동을_알려준다():
    """「턴 상한 60회에 도달해 끝내지 못했다」는 관리자에게 아무 행동도 주지 못한다.

    이 문장은 `failure_reason` 으로 관리자 화면에 그대로 뜬다. 관리자는 「턴」이 뭔지
    모르고, 알아도 할 수 있는 게 없다. 오늘 수정(검색 루프·압축·NFC) 이후 상한에 닿는
    현실적 원인은 **주제가 많은 문서**이므로, 실행 가능한 안내(주제별 분리 재업로드)와
    반복 시 문의를 담는다. 진단용 표식(「작업 한도」)은 남긴다 — 로그 grep 과
    `failure_stage_for_error` 이후의 원인 구분이 이 문구에 기대므로 완전히 빼지 않는다.
    """
    runtime = DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", "http://gms.example/anthropic")},
        chat_model=NeverStopsModel(),
    )
    root = Path(tempfile.mkdtemp())

    result = await runtime.arun(
        "위키를 고쳐라", fs=ScopelessFS(), scope_id="scope-1", root=root,
        scope_key="ALL", job_id="job-1", timeout=120,
    )

    assert "작업 한도" in result.error, "진단용 표식"
    assert "나눠" in result.error or "분리" in result.error, "실행 가능한 안내"
    assert "반복되면" in result.error, "결함 가능성의 탈출구"
    assert "턴" not in result.error, "관리자가 모르는 내부 용어"
