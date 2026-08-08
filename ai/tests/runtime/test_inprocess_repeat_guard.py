"""운영(인프로세스) 도구 경로에 반복 가드가 걸려 있는가 (S15P11B106-316).

S15P11B106-311 의 가드는 MCP 서버 디스패처에 배선됐는데, **운영 위키 경로(`arun`)는
MCP 없이 도구를 인프로세스로 부른다** (S15P11B106-152 창구 모드) — 그래서 job 60 에서
같은 read 39연속에 가드 개입이 0회였다 (2026-08-07 트레이스). 이 테스트는 그 경로
그대로(가짜 모델 + `wiki_agent_tools`)를 태워, **동일 read 반복의 3회째 응답에 경고가
오는가**를 고정한다 — 배선이 빠지면 경고가 영영 안 나온다.
"""

import pytest

pytest.importorskip("deepagents")

from wiki_mcp.vaultfs import LocalVaultFS  # noqa: E402
from wiki_mcp.vaultfs.local import bootstrap_scope, register_source  # noqa: E402
from wiki_mcp.vaultfs.spring import SpringVaultFS  # noqa: E402

from agent_runtime.deep_agents import DeepAgentsRuntime  # noqa: E402

from .fake_models import ScriptedModel  # noqa: E402

SCOPE, JOB = "ALL", "job-session"
GUARD_MARKER = "같은 호출을 같은 결과로"


class ProbeModel(ScriptedModel):
    """메시지에 반복 가드 경고가 나타났는지 기록한다."""

    saw_guard_warning: bool = False

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        if any(GUARD_MARKER in str(getattr(m, "content", "")) for m in messages):
            self.saw_guard_warning = True
        return super()._generate(messages, stop=stop, run_manager=run_manager, **kwargs)


async def test_동일_read_반복이_한_세션에서_가드_경고를_받는다(tmp_path):
    scope_id = await SpringVaultFS.open(tmp_path, SCOPE, JOB)
    await bootstrap_scope(SCOPE)
    await register_source(SCOPE, "9", "작은문서.md", "# 규정\n\n연차는 15일이다.")
    read_same = ("tool", "read", {"scope": SCOPE, "path": "sources/9/parsed/content.md"})
    model = ProbeModel(script=[read_same] * 4 + [("text", "끝")], padding="")
    runtime = DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", "https://gms.example/anthropic")},
        chat_model=model,
    )

    try:
        result = await runtime.arun(
            "원본문서를 반영하라", fs=SpringVaultFS(SCOPE, JOB), scope_id=scope_id,
            root=tmp_path, scope_key=SCOPE, job_id=JOB, timeout=120)
    finally:
        await LocalVaultFS.close()

    assert result.error is None, result.error
    assert model.saw_guard_warning, (
        "동일 read 4연속에도 가드 경고가 안 왔다 — 인프로세스 도구 래핑(langchain_tools)에 "
        "반복 가드가 배선되지 않았다 (job 60 의 39연속 read 무개입과 같은 구멍)")


class CountingProbe(ScriptedModel):
    """모델이 실제로 몇 번 불렸는지 센다 — 에스컬레이션이 잡을 끊으면 대본을 다 못 쓴다."""

    calls_made: int = 0

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        self.calls_made += 1
        return super()._generate(messages, stop=stop, run_manager=run_manager, **kwargs)


async def test_경고를_무시하고_반복하면_잡이_중단된다(tmp_path):
    """S15P11B106-325. 잡 61 실측: 경고 21회를 무시하고 40턴을 태웠다 — 경고로는 퇴행을
    못 멈춘다(빈 텍스트 자기복제 루프). 임계(6회)부터는 잡 자체를 끊는다. 성공 잡의 최장
    연속 동일 호출은 실측 전수에서 1회라 오탐 여지가 없다."""
    scope_id = await SpringVaultFS.open(tmp_path, SCOPE, JOB + "-esc")
    await bootstrap_scope(SCOPE)
    await register_source(SCOPE, "9", "작은문서.md", "# 규정\n\n연차는 15일이다.")
    read_same = ("tool", "read", {"scope": SCOPE, "path": "sources/9/parsed/content.md"})
    # 대본은 12연속 — 에스컬레이션이 없으면 12턴을 다 쓰고 정상 종료해 버린다.
    model = CountingProbe(script=[read_same] * 12 + [("text", "끝")], padding="")
    runtime = DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", "https://gms.example/anthropic")},
        chat_model=model,
    )

    try:
        result = await runtime.arun(
            "원본문서를 반영하라", fs=SpringVaultFS(SCOPE, JOB + "-esc"), scope_id=scope_id,
            root=tmp_path, scope_key=SCOPE, job_id=JOB + "-esc", timeout=120)
    finally:
        await LocalVaultFS.close()

    assert result.error is not None, (
        "동일 호출 12연속이 끝까지 돌았다 — 에스컬레이션이 잡을 끊지 않았다")
    assert "같은 작업을 반복" in result.error, result.error
    assert "다시 시도해" in result.error, ("관리자 번역이 안 탔다: " + result.error)
    # 6회째 호출에서 끊어야 한다. 여유를 줘도 8턴 안이어야 한다 — 12턴을 다 썼다면 실패.
    assert model.calls_made <= 8, f"{model.calls_made}턴이나 돌았다"
