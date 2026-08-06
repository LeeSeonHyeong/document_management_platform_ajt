"""마지막 답변 스키마가 지시문의 양식과 어긋나지 않는다.

**실측 (2026-08-06, 실기동 job 37).** 관리자 화면의 「반영 내용」이 이렇게만 나왔다:

    2024년 3월 인사위원회 회의록(...)을 바탕으로 새 페이지 1장을 만들고 기존 페이지
    2장을 고쳤습니다.

무엇을 고쳤는지가 없다. `ingest_instruction` 은 **5열 표(페이지·처리·카테고리·주요
내용·근거) + 보충 4줄**을 요구하고 「표 없이 문장만 길게 쓴 것」을 나쁜 예로까지 박아
뒀는데도 그렇다.

원인은 두 지시가 모순돼서다. 마지막 답변은 `response_format=ToolStrategy(AgentReport)`
로 이 필드 하나에 담기므로, 필드 설명이 프롬프트보다 가깝고 그쪽이 이긴다. 그 설명이
**「2~3문장 보고」**였다.

두 커밋이 같은 티켓(S15P11B106-251)인데 순서가 엇갈렸다 — 스키마가 2026-08-04
(`349f9f9`), 표 양식이 2026-08-05(`f713883`). 나중 것이 앞 것을 안 고쳤다.
"""

import pytest

pytest.importorskip("deepagents")

from agent_runtime.deep_agents import AgentReport  # noqa: E402


def _description() -> str:
    return AgentReport.model_fields["summary"].description or ""


def test_스키마가_길이를_문장수로_제한하지_않는다():
    """표 + 보충 4줄은 2~3문장에 들어가지 않는다."""
    assert "2~3문장" not in _description()
    assert "문장" not in _description() or "양식" in _description()


def test_스키마가_지시문의_양식을_따르라고_말한다():
    """설명이 양식을 다시 정의하면 또 어긋난다 — 정본을 가리키게 한다."""
    assert "양식" in _description()


def test_용어_누출_금지는_유지된다():
    """이 스키마가 존재하는 이유다 (S15P11B106-243). 양식을 고치면서 잃으면 안 된다."""
    d = _description()
    assert "lint" in d and "pages/" in d


# ---- 요약 양식 (2026-08-06) --------------------------------------------------

def _ingest_text() -> str:
    from agent_runtime.base import ingest_instruction

    return ingest_instruction("sources/46/parsed/content.md", "ALL")


def test_양식이_표와_반영하지_않은_내용만_요구한다():
    """태그·시각 자료·상호 링크는 뺐다.

    관리자가 목록에서 훑어볼 때 필요한 것은 「무엇이 어떻게 바뀌었나」와 「무엇이 빠졌나」
    둘이다. 나머지 세 줄은 화면 공간만 쓰고 판단에 안 쓰인다.
    """
    text = _ingest_text()

    assert "반영하지 않은 내용" in text
    assert "| 페이지 | 처리 | 카테고리 | 주요 내용 | 근거 |" in text
    assert "태그:" not in text
    assert "시각 자료" not in text
    assert "상호 링크" not in text


def test_주요_내용을_구체적으로_쓰라고_말한다():
    """실기동 job 37 의 요약이 「2장을 고쳤습니다」로 끝나 무엇이 바뀌었는지 알 수 없었다.

    표를 다시 살려도 칸이 「내용을 보강했습니다」로 채워지면 같은 상태다.
    """
    assert "구체적으로" in _ingest_text()
