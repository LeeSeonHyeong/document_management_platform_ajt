"""macOS 한글 파일명(NFD)과 모델이 쓴 각주(NFC)가 만나야 한다.

## 실측 (2026-08-06 job 43, 문서 48)

macOS 브라우저는 파일명을 **NFD**(자모 분해)로 올린다 — MySQL 실측:
`HEX(LEFT(name,2)) = E18480E185A9` (ᄀ+ᅩ). 모델이 각주에 다시 타이핑한 이름은
**NFC**(완성형 「공」= EAB3B5)다. 겉보기에 같은 「공통 프로젝트 산출물 제출 안내.pdf」
인데 바이트가 달라 `find_source` 의 `lower(x)=lower(?)` 가 영원히 실패했다.

그 결과 lint 가 고칠 수 없는 `unresolved-citation`(error) 을 냈고, 에이전트는
「error 0 이 될 때까지」 지시대로 60턴 내내 고치려다 상한에서 죽었다 — 작업 자체는
12턴에 끝나 있었다. **영문 파일명만 테스트해 와서 여기까지 안 드러났다.**

경계에서 NFC 로 정규화한다: 저장(`register_source`·`stage_source`)과
조회(`find_source`) 양쪽이다. 한쪽만 하면 반대 방향 조합에서 다시 터진다.
"""

import unicodedata

from ..conftest import SCOPE
from wiki_mcp.tools.lint import LintHandler
from wiki_mcp.tools.references import sync_references
from wiki_mcp.vaultfs import LocalVaultFS
from wiki_mcp.vaultfs.local import bootstrap_scope, register_source
from wiki_mcp.vaultfs.spring import SpringVaultFS

JOB = "job-nfd"
NFC_NAME = "공통 산출물 안내.pdf"
NFD_NAME = unicodedata.normalize("NFD", NFC_NAME)

CITING_PAGE = f"""\
---
title: 산출물 제출 안내
description: 산출물 제출 절차
date: 2026-08-06
tags: [제출]
category: 공지
---

산출물은 기한 내 제출한다[^1].

[^1]: {NFC_NAME}, 제출 안내 — "산출물은 기한 내 제출한다"
"""

SOURCE = """\
# 산출물 제출 안내

## 제출 안내

산출물은 기한 내 제출한다.
"""


def test_전제_확인_NFD_와_NFC_는_다른_바이트다():
    assert NFC_NAME != NFD_NAME
    assert NFC_NAME == unicodedata.normalize("NFC", NFD_NAME)


async def test_NFD_로_저장된_문서를_NFC_각주가_찾는다(tmp_path):
    """macOS 업로드(NFD) × 모델 각주(NFC) — job 43 의 조합 그대로."""
    try:
        scope_id = await LocalVaultFS.open(tmp_path, SCOPE, JOB)
        await bootstrap_scope(SCOPE)
        fs = LocalVaultFS(SCOPE, JOB)
        await register_source(SCOPE, "48", NFD_NAME, SOURCE)

        found = await fs.find_source(scope_id, NFC_NAME)
    finally:
        await LocalVaultFS.close()

    assert found is not None, "NFD 저장 × NFC 조회가 실패한다 — job 43 재현"


async def test_NFC_로_저장된_문서를_NFD_각주가_찾는다(tmp_path):
    """반대 방향. 조회 쪽만 정규화하면 이 조합이 남는다."""
    try:
        scope_id = await LocalVaultFS.open(tmp_path, SCOPE, JOB)
        await bootstrap_scope(SCOPE)
        fs = LocalVaultFS(SCOPE, JOB)
        await register_source(SCOPE, "48", NFC_NAME, SOURCE)

        found = await fs.find_source(scope_id, NFD_NAME)
    finally:
        await LocalVaultFS.close()

    assert found is not None


async def test_확장자_없는_각주도_정규화를_거친다(tmp_path):
    """`.pdf` 를 뺀 인용은 파이썬 폴백 경로를 탄다 — 그쪽도 정규화해야 한다."""
    try:
        scope_id = await LocalVaultFS.open(tmp_path, SCOPE, JOB)
        await bootstrap_scope(SCOPE)
        fs = LocalVaultFS(SCOPE, JOB)
        await register_source(SCOPE, "48", NFD_NAME, SOURCE)

        found = await fs.find_source(scope_id, NFC_NAME.removesuffix(".pdf"))
    finally:
        await LocalVaultFS.close()

    assert found is not None


async def test_lint_이_NFD_원본의_NFC_각주를_해결한다(tmp_path):
    """job 43 이 60턴 내내 마주친 그 error 가 나면 안 된다.

    프로덕션 경로(`SpringVaultFS.stage_source`)로 얹는다 — 실제 삭제·반영 재조정이
    타는 길이다.
    """
    try:
        scope_id = await SpringVaultFS.open(tmp_path, SCOPE, JOB)
        await bootstrap_scope(SCOPE)
        fs = SpringVaultFS(SCOPE, JOB)
        await fs.stage_source(scope_id, "48", SOURCE, NFD_NAME)
        address = await fs.allocate_page(scope_id)
        await fs.write(scope_id, address, CITING_PAGE, title="산출물 제출 안내",
                       category="공지", tags=["제출"])
        await sync_references(fs, scope_id, address, CITING_PAGE)

        report = await LintHandler(fs, {"id": scope_id, "scope_key": SCOPE,
                                        "index_path": f"wiki/{SCOPE}/index.md"}).run("*")
    finally:
        await LocalVaultFS.close()

    assert "unresolved-citation" not in report, report[:400]
