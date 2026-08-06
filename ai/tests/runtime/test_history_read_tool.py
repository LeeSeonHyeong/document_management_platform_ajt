"""압축이 버린 대화 이력을 되읽는 도구 — 경로를 그 한 곳으로 못 박는다.

## 왜 필요한가 (2026-08-06 실기동 job 40)

압축 미들웨어는 34K 를 넘으면 옛 대화를 `/conversation_history/{thread_id}.md` 로 버리고,
그 자리에 이런 안내를 넣는다 (`deepagents/middleware/summarization.py:786`):

> The full conversation history has been saved to {file_path} should you need to
> refer back to it for details.

그런데 우리는 `EXCLUDED_BUILTIN_TOOLS` 로 `read_file` 을 막아 뒀다 — 안 막으면 에이전트가
MCP 를 우회해 위키 파일을 직접 쓴다. 그래서 **시킨 대로 읽으려다 실패**하고, 문맥도 복구
수단도 없이 `guide` 부터 다시 시작해 아무것도 못 하고 끝났다.

실측 (job 40, 문서 44 삭제):

    모델 호출 11회차 프롬프트 35,771 토큰 → 압축 → 12회차 14,120 토큰
    12번째 도구 호출: read `/conversation_history/session_b2f9b5ac.md`
      → "범위 '<br />'를 찾을 수 없다"  (우리 위키 read 로 흘러갔다)
    13번째: guide  ← 처음부터 다시 시작

## 이 도구의 경계

**압축이 쓴 그 경로만 읽는다.** 작업 공간의 다른 파일도, 저장소 밖도 못 본다 —
`EXCLUDED_BUILTIN_TOOLS` 를 둔 이유(MCP 우회 차단)가 이 도구로 새면 안 된다.
아래 테스트가 그 경계를 고정한다.
"""

import tempfile
from pathlib import Path

import pytest

pytest.importorskip("deepagents")

from deepagents.backends import FilesystemBackend  # noqa: E402

from agent_runtime.history_tool import (  # noqa: E402
    HISTORY_PREFIX,
    history_read_tool,
)


def _backend_with_history(text="옛 대화 전문이다.\n"):
    root = Path(tempfile.mkdtemp())
    (root / "conversation_history").mkdir(parents=True)
    (root / "conversation_history" / "session_abc.md").write_text(text, encoding="utf-8")
    # 저장소 안이지만 이력이 아닌 파일. 이 도구로 보이면 안 된다.
    (root / "secret.md").write_text("위키 작업 공간 파일", encoding="utf-8")
    return root, FilesystemBackend(root_dir=str(root), virtual_mode=True)


async def test_이력_파일을_읽는다():
    _, backend = _backend_with_history()
    tool = history_read_tool(backend)

    out = await tool.call(file_path=f"{HISTORY_PREFIX}/session_abc.md")

    assert "옛 대화 전문이다" in out


async def test_이력_밖의_파일은_거부한다():
    """작업 공간 안이어도 이력이 아니면 안 된다."""
    _, backend = _backend_with_history()
    tool = history_read_tool(backend)

    out = await tool.call(file_path="/secret.md")

    assert "위키 작업 공간 파일" not in out
    assert "읽을 수 없다" in out


@pytest.mark.parametrize("path", [
    "/conversation_history/../secret.md",
    "/conversation_history/./../../etc/passwd",
    "conversation_history/../secret.md",
    "/conversation_history_evil/x.md",     # 접두어만 흉내낸 것
    "//conversation_history/../secret.md",
])
async def test_경로_탈출을_막는다(path):
    """정규화 전 문자열 비교만 하면 `..` 로 빠져나간다."""
    _, backend = _backend_with_history()
    tool = history_read_tool(backend)

    out = await tool.call(file_path=path)

    assert "위키 작업 공간 파일" not in out
    assert "읽을 수 없다" in out


async def test_없는_이력_파일은_그렇다고_말한다():
    _, backend = _backend_with_history()
    tool = history_read_tool(backend)

    out = await tool.call(file_path=f"{HISTORY_PREFIX}/session_없음.md")

    assert "없다" in out


def test_도구_이름과_설명이_용도를_말한다():
    """에이전트는 안내 문장에서 경로를 받는다 — 도구가 그것과 이어져야 쓴다."""
    _, backend = _backend_with_history()
    tool = history_read_tool(backend)

    assert HISTORY_PREFIX in tool.description
    assert "file_path" in tool.input_schema["properties"]


async def test_긴_이력은_한국어_토큰_기준으로_잘라_돌려준다():
    """이 도구가 문맥 폭탄이 되면 안 된다 (2026-08-06 실기동, 문서 48 두 번째 실패).

    처음 상한은 40,000자였다 — 영어(~4자=1토큰) 감각으로 잡은 값인데 **한국어는
    ~1자=1토큰이다** (compaction 설계 문서의 count_tokens 실측 0.85~1.02). 그래서 이력
    읽기 한 번이 ~40K 토큰을 되돌려줬고, 압축 직후(~11K)에 읽으면 한 턴에 +23.5K 가
    뛰어 GMS 42K 벽으로 직행했다. 실측 톱니: 34K→압축→11K→이력읽기→34K→또 압축…
    한 실행에서 압축이 8번 터지며 상태 보존 요약(!273)까지 무력화됐다.

    상한의 근거는 압축 트리거의 여유 설계다: 트리거 34K 는 「1턴 최대 증가 ~4.6K」를
    전제로 잡혔으므로(42K 벽 - 34K - 여유), 도구 결과도 그 계약 안에 있어야 한다.
    4,000자 ≈ 한국어 ~4K 토큰이다.
    """
    root, backend = _backend_with_history("가나다라마바사아자차카타파하 " * 5_000)
    tool = history_read_tool(backend)

    out = await tool.call(file_path=f"{HISTORY_PREFIX}/session_abc.md")

    assert len(out) <= 4_200, f"응답이 {len(out)}자 — 한 턴 증가 예산(~4.6K 토큰)을 넘는다"
    assert out.rstrip().endswith("하") or "하" in out[-40:], "뒤쪽(최근)이 남아야 한다"
