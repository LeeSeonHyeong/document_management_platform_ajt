"""에이전트 실패가 관리자에게 닿을 때 — 원인별로 다음 행동을 준다.

## 실측 (2026-08-06, ai_job·document 의 failure_reason 전수 조사)

관리자 화면에 지금까지 실제로 뜬 것들:

    ×5  BadRequestError …: Error code: 400 - {'message': '[GMS 에러] Model not found …
    ×8  GraphRecursionError …: Recursion limit of 120 reached without hitting …
    ×1  BadRequestError …: Your credit balance is too low to access the Anthropic API …
    ×1  요청을 처리하지 못했습니다 — 'list' object is not callable

전부 관리자가 읽을 수 없는 말이고, 무엇을 해야 하는지도 없다. 게다가 「Model not
found」는 문구 그대로 읽으면 모델 설정 오류로 보이는데 실제로는 **GMS 요청 크기벽**
오류라(job 7 사고 기록과 대조로 확정) 원인을 감추기까지 한다.

원인별 번역 원칙: 첫 문장은 관리자의 다음 행동, 괄호 안 꼬리는 개발팀용 진단 표식.
"""

import tempfile
from pathlib import Path

import pytest

pytest.importorskip("deepagents")

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models import BaseChatModel
from langchain_core.outputs import ChatResult

from agent_runtime.deep_agents import DeepAgentsRuntime

from .test_turn_limit import ScopelessFS


class RaisingModel(BaseChatModel):
    """첫 호출에서 정해진 예외를 던진다 — 실기동에서 GMS·SDK 가 던진 것을 재현한다."""

    exc: Exception = None

    @property
    def _llm_type(self) -> str:
        return "raising"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None,
                  run_manager: CallbackManagerForLLMRun | None = None,
                  **kwargs) -> ChatResult:
        raise self.exc


async def _run_with(exc: Exception) -> str:
    runtime = DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", "http://gms.example/anthropic")},
        chat_model=RaisingModel(exc=exc),
    )
    result = await runtime.arun(
        "위키를 고쳐라", fs=ScopelessFS(), scope_id="scope-1",
        root=Path(tempfile.mkdtemp()), scope_key="ALL", job_id="job-1", timeout=120)
    assert result.error, "실패인데 error 가 비었다"
    return result.error


async def test_GMS_크기벽_400_은_문서를_나누라고_안내한다():
    """「Model not found」 문구 그대로 내보내면 모델 설정 오류로 오독된다 — 실제는 크기벽."""
    error = await _run_with(Exception(
        "Error code: 400 - {'message': '[GMS 에러] Model not found in request "
        "for domain api.anthropic.com', 'statusCode': 400}"))

    assert "나눠" in error or "나누어" in error
    assert "용량" in error, "진단 표식"
    assert "Model not found" not in error, "오독을 부르는 원문은 감춘다"


async def test_과금_한도_는_개발팀_문의를_안내한다():
    """관리자가 고칠 수 없는 문제 — 재시도 유도가 아니라 문의 안내가 맞다."""
    error = await _run_with(Exception(
        "Error code: 400 - {'type': 'error', 'error': {'message': 'Your credit "
        "balance is too low to access the Anthropic API.'}}"))

    assert "사용량 한도" in error
    assert "개발팀" in error
    assert "나눠" not in error, "문서를 나눠도 소용없는 문제다"


async def test_recursion_백스톱도_작업_한도_안내를_받는다():
    """미들웨어보다 백스톱이 먼저 걸리는 회귀가 다시 생겨도 관리자 메시지는 같아야 한다."""
    from langgraph.errors import GraphRecursionError

    error = await _run_with(GraphRecursionError("Recursion limit of 480 reached"))

    assert "작업 한도" in error
    assert "나눠" in error
    assert "GraphRecursionError" not in error


async def test_모르는_오류는_안내와_진단_꼬리를_함께_준다():
    """번역표에 없는 오류를 뭉개면 안 된다 — 관리자용 첫 문장 + 개발팀용 원문 꼬리."""
    error = await _run_with(TypeError("'list' object is not callable"))

    assert "예상하지 못한 오류" in error
    assert "개발팀" in error
    assert "object is not callable" in error, "진단 원문을 잃으면 개발팀이 못 찾는다"


async def test_시작_전_실패는_여전히_AGENT_START_로_분류된다():
    """`session.py::failure_stage_for_error` 가 「마지막 도달 단계: 시작 전」 문구를
    표식으로 쓴다 — 번역이 그 표식을 지우면 시작 전 실패가 AGENT_ERROR 로 뭉개진다."""
    from wiki_api.session import FailureStage, failure_stage_for_error

    error = await _run_with(Exception(
        "Error code: 400 - {'error': {'message': 'Your credit balance is too low'}}"))

    assert failure_stage_for_error(error) == FailureStage.AGENT_START
