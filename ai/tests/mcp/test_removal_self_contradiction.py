"""삭제 재조정이 「지우라는 문서」를 스스로 되살려 놓는다 — 가설 검증.

## 배경

2026-08-06 findings(`duplicate-uploads-and-deletion-deadlock.md`)는 문서 25 삭제가
`GraphRecursionError` 로 교착된 이유를 **「파일명이 같은 문서 23 이 살아 있어 각주가
그쪽으로 해석된다」**고 적었다. 이 파일은 그 설명이 맞는지 본다.

의심한 근거:

* 하이드레이션은 **위키 페이지만** 올린다(`FederatedVaultFS._hydrate_catalog` 가
  `list_pages()` 만 부른다). 문서 23 은 애초에 vault 에 없다.
* 삭제 재조정은 **지울 문서의 옛 본문을 스스로 얹는다**
  (`wiki_api/routers/wiki.py` 의 `load_source(payload.documentId, removed, file_name)`).
  주석이 이유까지 적어 뒀다 — 그래야 각주가 풀려 backlink 가 잡힌다. 필연적 동작이다.

아래 두 테스트는 **중복이 하나도 없는 범위**에서 그 경로를 그대로 태운다.
"""

from ..conftest import SCOPE
from wiki_mcp.tools.lint import LintHandler
from wiki_mcp.tools.references import sync_references
from wiki_mcp.vaultfs import LocalVaultFS
from wiki_mcp.vaultfs.local import bootstrap_scope
from wiki_mcp.vaultfs.spring import SpringVaultFS

JOB = "job-removal"

# 인용 형식은 `tests/mcp/test_lint.py` 의 GOOD_PAGE 와 같다: `파일명, 위치 — "인용문"`.
CITING_PAGE = """\
---
title: 복무 관련 안내
description: 근무시간 기준
date: 2026-08-06
tags: [복무]
category: 인사
---

근무시간은 주 40시간이다[^1].

[^1]: 복무규정.docx, 제3조 근무시간 — "근무시간은 주 40시간으로 한다"
"""

REMOVED_SOURCE = """\
# 복무규정

## 제3조 근무시간

근무시간은 주 40시간으로 한다.
"""


async def _scope_with_a_page_citing(tmp_path):
    """중복이 **하나도 없는** 범위. 원본문서는 아직 없고 페이지만 그것을 인용한다."""
    scope_id = await SpringVaultFS.open(tmp_path, SCOPE, JOB)
    await bootstrap_scope(SCOPE)
    fs = SpringVaultFS(SCOPE, JOB)
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, CITING_PAGE, title="복무 관련 안내",
                   category="인사", tags=["복무"])
    await sync_references(fs, scope_id, address, CITING_PAGE)
    return scope_id, fs


def _scope_row(scope_id):
    return {"id": scope_id, "scope_key": SCOPE, "index_path": f"wiki/{SCOPE}/index.md"}


async def test_삭제_대상_문서가_스테이징되면_find_source_가_그것을_찾는다(tmp_path):
    """중복이 없는데도 「지우라는 문서」가 조회된다.

    이것이 사실이면 findings 의 「문서 23 때문」 설명은 원인을 잘못 짚은 것이다 — 중복이
    아니라 삭제 경로 자신이 모순을 만든다.
    """
    try:
        scope_id, fs = await _scope_with_a_page_citing(tmp_path)
        before = await fs.find_source(scope_id, "복무규정.docx")

        # 삭제 재조정이 하는 일 그대로 — `session.load_source` 가 이것을 부른다.
        await fs.stage_source(scope_id, "25", REMOVED_SOURCE, "복무규정.docx")

        after = await fs.find_source(scope_id, "복무규정.docx")
    finally:
        await LocalVaultFS.close()

    assert before is None, "얹기 전에는 없어야 한다 — 전제 확인"
    assert after is not None, (
        "삭제 대상 문서가 조회된다. 에이전트는 「삭제됐다」는 지시를 받고 그 문서를 "
        "`read` 해 멀쩡한 본문을 본다 — 중복이 하나도 없는 범위인데도 그렇다"
    )


async def test_lint_이_삭제_대상_각주를_미해결로_잡지_못한다(tmp_path):
    """findings ③ 의 「삭제는 안전하다」 전제를 확인한다.

    findings 는 「삭제는 안전하다 — 원본이 통째로 사라지니 `unresolved-citation`(error)이
    잡는다」고 적었다. 그런데 그 원본은 작업 공간에 **올라와 있다.**
    """
    try:
        scope_id, fs = await _scope_with_a_page_citing(tmp_path)
        before = await LintHandler(fs, _scope_row(scope_id)).run("*")

        await fs.stage_source(scope_id, "25", REMOVED_SOURCE, "복무규정.docx")

        after = await LintHandler(fs, _scope_row(scope_id)).run("*")
    finally:
        await LocalVaultFS.close()

    assert "unresolved-citation" in before, "원본문서가 없을 때는 잡아야 한다 — 전제 확인"
    assert "unresolved-citation" not in after, (
        "각주가 스테이징본으로 풀려 미해결로 안 잡힌다. 에이전트에게는 「지울 근거가 "
        "실재하고 lint 도 그 각주를 문제 삼지 않는다」로 보이므로, 「걷어내라」는 지시와 "
        "관측이 어긋난 채 끝낼 조건이 성립하지 않는다"
    )
