"""삭제 재조정 지시가 작업 공간의 실제 상태와 어긋나지 않는다.

`tests/mcp/test_removal_self_contradiction.py` 가 실측한 상태를 지시문이 설명해야 한다:
삭제 재조정은 **지울 문서의 옛 본문을 작업 공간에 얹는다**(그래야 각주가 풀려 backlink 가
잡힌다). 그래서 에이전트가 그 주소를 `read` 하면 문서가 멀쩡히 나오고, `lint` 도 그 각주를
`unresolved-citation` 으로 잡지 않는다.

`document_replaced` 분기는 이미 그 사실을 적고 있고 주석에 이유까지 있다 — *"그 사실을
적지 않으면 에이전트가 옛 내용만 있다고 보고 문단을 지우는 쪽으로 간다"*. **`document_removed`
분기에만 그 문장이 없었다.** 지시는 「삭제됐다」인데 관측은 「있다」라서, 에이전트가 무엇이
잘못됐는지 찾으려 검색을 반복하다 턴 상한에서 죽었다 (2026-08-06 job 35, `search` 32회).
"""

from agent_runtime.base import reconcile_instruction

ADDRESS = "sources/25/parsed/content.md"
AFFECTED = [{"address": "pages/aaa.md", "footnote": "1",
             "location": "제3조 근무시간", "quote": "근무시간은 주 40시간으로 한다"}]


def _removed() -> str:
    return reconcile_instruction(ADDRESS, "ALL", "document_removed", AFFECTED)


def test_삭제_지시가_참고용_사본의_존재를_설명한다():
    """설명이 없으면 「삭제됐다」와 「read 하면 나온다」가 정면으로 어긋난다."""
    text = _removed()

    assert "read" in text
    assert "참고" in text or "사본" in text


def test_삭제_지시가_lint_로_완료를_판단하지_말라고_말한다():
    """각주가 스테이징본으로 풀려 `unresolved-citation` 이 안 뜬다.

    「error 가 0 이 될 때까지」만 있으면 에이전트는 이미 0 인 상태에서 무엇을 더 해야 하는지
    알 수 없다.
    """
    assert "lint" in _removed()
    assert "판단하지" in _removed() or "근거로 삼지" in _removed()


def test_삭제_지시가_목록이_전부임을_말한다():
    """참조 그래프가 각주 단위로 이미 알고 있으므로 전수 탐색할 이유가 없다.

    이 문장이 없어 에이전트가 탐색으로 새는 문이 열려 있었다.
    """
    assert "목록" in _removed()


def test_교체_지시는_그대로다():
    """교체 분기는 이미 옳다 — 회귀 방지."""
    text = reconcile_instruction(ADDRESS, "ALL", "document_replaced", AFFECTED)

    assert "이미 새 내용이 들어 있다" in text
