"""압축이 터진 뒤 에이전트가 이력을 되찾을 수 있는가 — 전 구간, LLM 호출 0원.

## 재현하는 실패 (2026-08-06 실기동 job 40)

    모델 호출 11회차 프롬프트 35,771 토큰 → 압축 → 12회차 14,120
    12번째 도구 호출: read `/conversation_history/session_….md`
      → "범위 '<br />'를 찾을 수 없다"   (우리 위키 read 로 흘러갔다)
    13번째: guide   ← 문맥을 잃고 처음부터 다시 시작
    결과: 위키를 한 줄도 못 고치고 종료

압축 미들웨어는 옛 대화를 파일로 버리고 "필요하면 그 경로를 읽어라" 고 안내하는데,
`EXCLUDED_BUILTIN_TOOLS` 가 `read_file` 을 막아 그 안내가 막다른 길이었다.

**이 실패는 직접 API 에서는 안 난다** — 트리거가 170K 라 압축이 안 터진다. 배포 기본값인
게이트웨이(34K)에서만 난다. 그래서 실기동 두 번(job 37·38)이 멀쩡히 통과했다.
"""

import tempfile
from pathlib import Path

import pytest

pytest.importorskip("deepagents")

from agent_runtime.deep_agents import DeepAgentsRuntime  # noqa: E402
from wiki_mcp.vaultfs import LocalVaultFS  # noqa: E402
from wiki_mcp.vaultfs.local import bootstrap_scope, register_source  # noqa: E402
from wiki_mcp.vaultfs.spring import SpringVaultFS  # noqa: E402

from .fake_models import ScriptedModel  # noqa: E402

SCOPE, JOB = "ALL", "job-compaction"
# 압축 트리거(게이트웨이 34K)를 넘기려면 이력이 커야 한다.
#
# `read` 도구 결과만으로는 부족했다 — 그쪽은 잘라서 돌려준다. 그래서 **모델 응답 자체**를
# 키워 이력을 부풀린다. 토큰 카운터가 근사치(대략 4자=1토큰)이므로 34K 토큰 ≈ 136K 자다.
BIG_SOURCE = "# 규정\n\n" + ("가나다라마바사아자차카타파하 " * 20 + "\n") * 40
PADDING = "이 문단은 문맥을 채우기 위한 것이다. " * 900   # 약 2.4만 자/턴


async def _vault(tmp_path):
    scope_id = await SpringVaultFS.open(tmp_path, SCOPE, JOB)
    await bootstrap_scope(SCOPE)
    # 두 문서를 교대로 읽는다 — 동일 read 연속은 반복 가드 에스컬레이션(S15P11B106-325)이
    # 6회에서 잡을 끊어 버려서, 압축까지 가는 정당한 장문 작업의 모양이 아니다.
    await register_source(SCOPE, "9", "큰문서A.md", BIG_SOURCE)
    await register_source(SCOPE, "10", "큰문서B.md", BIG_SOURCE.replace("규정", "별첨"))
    return scope_id, SpringVaultFS(SCOPE, JOB)


def _runtime(model):
    # 트리거는 프로바이더 무관 34K 다 (`COMPACTION_TRIGGER`).
    return DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", "https://gms.example/anthropic")},
        chat_model=model,
    )


async def test_압축_뒤_이력을_되읽고_계속한다(tmp_path):
    """고침 전에는 이 지점에서 문맥을 잃고 아무것도 못 했다."""
    read_a = ("tool", "read", {"scope": SCOPE, "path": "sources/9/parsed/content.md"})
    read_b = ("tool", "read", {"scope": SCOPE, "path": "sources/10/parsed/content.md"})
    script = [read_a, read_b] * 4 + [
        # 압축이 터진 뒤 안내받은 경로를 그대로 읽는다. 진짜 모델이 하는 일과 같다.
        ("tool", "read_conversation_history", {"file_path": "$history"}),
        ("text", "이력을 확인하고 이어서 작업했다."),
    ]
    model = ScriptedModel(script=script, padding=PADDING)

    try:
        scope_id, fs = await _vault(tmp_path)
        result = await _runtime(model).arun(
            "원본문서를 반영하라", fs=fs, scope_id=scope_id, root=tmp_path,
            scope_key=SCOPE, job_id=JOB, timeout=120)
    finally:
        await LocalVaultFS.close()

    assert model.seen_history_path is not None, (
        "압축이 안 터졌다 — 이 테스트의 전제가 깨졌다. BIG_SOURCE 를 키우거나 "
        "트리거를 확인한다")
    assert result.error is None, result.error
    assert result.tool_calls.get("read_conversation_history", 0) == 1


async def test_압축이_작업_상태_보존_지시를_요약_모델에_전달한다(tmp_path):
    """job 41 의 두 번째 국면을 막는 배선 — 요약이 「끝났다」를 들고 있어야 한다.

    도구(093c866)만으로는 부족했다는 것이 실측이다: 이력 되읽기는 성공했지만 원문은
    「무엇이 끝났는지」를 말해 주지 않아, 에이전트가 guide 4회·lint 6회를 반복하다 죽었다.
    그래서 요약 프롬프트에 작업 상태 보존을 요구한다 — 이 테스트는 압축이 **실제로
    터졌을 때** 그 지시가 요약 모델에 전달되는지를 잡는다. 상수·배선 중 한쪽만 있으면
    실패한다.

    요약의 **품질**(정말 좋은 상태 요약이 나오는가)은 LLM 판단이라 여기서 못 잰다 —
    실기동의 몫이다.
    """
    read_a = ("tool", "read", {"scope": SCOPE, "path": "sources/9/parsed/content.md"})
    read_b = ("tool", "read", {"scope": SCOPE, "path": "sources/10/parsed/content.md"})
    script = [read_a, read_b] * 4 + [("text", "작업을 마쳤다.")]
    model = ScriptedModel(script=script, padding=PADDING)

    try:
        scope_id, fs = await _vault(tmp_path)
        result = await _runtime(model).arun(
            "원본문서를 반영하라", fs=fs, scope_id=scope_id, root=tmp_path,
            scope_key=SCOPE, job_id=JOB, timeout=120)
    finally:
        await LocalVaultFS.close()

    assert result.error is None, result.error
    assert model.summary_requests >= 1, (
        "압축이 터졌는데 요약 요청에 wiki_work_state 지시가 없다 — "
        "summary_prompt 배선이 빠졌다")
