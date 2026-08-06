"""압축 요약이 작업 진행 상태를 보존하는가 (2026-08-06 job 41).

## 재현한 실패

job 41(문서 48 반영)의 트레이스는 두 국면이다.

    1~13턴: 완벽 — read → search 3회 → create → lint → edit → lint. 작업 사실상 완료
    14~40턴: 압축 후 붕괴 — read_conversation_history 5회, guide 4회, lint 6회.
             lint 가 통과했는데도 끝내지 못하고 같은 파일을 돌려 읽다 죽었다

압축 이력 도구(093c866)는 **동작했다** — 안내받은 경로를 읽는 데 성공했다. 그런데
**도움이 안 됐다**: 이력 원문을 되찾아도 「내가 무엇을 만들었고 이미 끝났는지」가
요약에 없어, 에이전트는 매번 guide 부터 다시 시작했다.

기본 요약 프롬프트는 범용 컨텍스트 추출이라 위키 작업의 상태 — 반영 중인 원본문서,
만들거나 고친 페이지 주소, lint 결과, 남은 일 — 를 반드시 남기라는 요구가 없다.
`SummarizationMiddleware` 가 `summary_prompt` 인자를 받으므로 그것을 우리 작업용으로
바꾼다.

## 이 테스트의 한계

요약을 **생성하는 것은 LLM** 이라 「좋은 요약이 실제로 나오는가」는 여기서 못 잰다.
여기서 고정하는 것은 배선이다 — 우리 지시가 요약 모델에게 실제로 전달되는가, 그리고
라이브러리의 `<messages>` 마커 계약(그 자리에 대화가 삽입된다)이 깨지지 않았는가.
요약 품질 자체는 실기동의 몫이다.
"""

import tempfile

import pytest

pytest.importorskip("deepagents")

from deepagents.backends import FilesystemBackend  # noqa: E402

from agent_runtime.deep_agents import (  # noqa: E402
    WIKI_SUMMARY_PROMPT,
    DeepAgentsRuntime,
)


def _middleware():
    runtime = DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", "https://gms.example/anthropic")},
    )
    backend = FilesystemBackend(root_dir=tempfile.mkdtemp(), virtual_mode=True)
    return runtime._summarization_middleware(backend)


def test_요약_프롬프트가_작업_상태_보존을_요구한다():
    """job 41 에서 잃어버린 네 가지가 전부 명시돼야 한다."""
    for required in ("원본문서", "만들었거나 고친", "lint", "남은 일"):
        assert required in WIKI_SUMMARY_PROMPT, f"누락: {required}"


def test_요약_프롬프트가_완료한_일을_반복하지_말라고_말한다():
    """guide 4회·lint 6회가 정확히 「완료한 일의 반복」이었다."""
    assert "반복하지 않는다" in WIKI_SUMMARY_PROMPT


def test_messages_마커_계약이_깨지지_않았다():
    """`<messages>` 자리에 실제 대화가 삽입된다 — 마커가 사라지면 요약 자체가 고장난다.

    deepagents 자신도 같은 방법(마커 앞에 splice)으로 media 안내를 끼운다. 그 계약을
    그대로 따른다.
    """
    assert WIKI_SUMMARY_PROMPT.count("<messages>") == 1
    # 우리 지시가 마커 **앞**에 있어야 한다 — 뒤에 붙이면 대화 본문 취급된다.
    assert WIKI_SUMMARY_PROMPT.index("남은 일") < WIKI_SUMMARY_PROMPT.index("<messages>")


def test_미들웨어가_그_프롬프트를_실제로_쓴다():
    """상수만 있고 배선이 안 되면 아무 일도 안 일어난다 — job 41 의 교훈 그대로다."""
    mw = _middleware()

    assert mw._lc_helper.summary_prompt == WIKI_SUMMARY_PROMPT


def test_media_안내는_유지된다():
    """deepagents 가 끼워 두는 media 참조 안내를 우리 프롬프트가 밀어내면 안 된다."""
    assert "media_reference_information" in WIKI_SUMMARY_PROMPT
