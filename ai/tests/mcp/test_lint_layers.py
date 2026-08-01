"""`lint` 검사 범위 분리 (D5) — 계획 `2026-07-29-wiki-context-hardening.md` Task 3.

`lint` 은 라이브 층 전체를 검사한다. 상류(lucas-llmwiki)에서는 에이전트가 저장소 전체의
주인이었으니 그것이 맞았다. **여기서는 라이브가 에이전트에게 읽기 전용이다.**

에이전트 지시는 "마지막에 `lint` 를 부르고 `error` 를 전부 고친다" 다 (`vaultfs/base.py`).
5개 상한이 없어져 위키가 전량 실려 오면 남이 만든 각주·frontmatter 가 error 로 쏟아지고
**에이전트가 그것을 고치려 든다.** 고칠 수도 없다 — 그 페이지들이 인용하는 원본문서는
이번 요청에 실려 오지 않았다.

그래서 둘로 나눈다.

  * 에이전트용 — 이번 작업이 만든 것만. 자기가 고칠 수 있는 것만 보여 준다
  * 반영 게이트(`api/session.py`) — 지금과 **동일하게** 막는다. 범위를 좁히는 것이지
    게이트를 느슨하게 하는 것이 아니다

한 가지 예외가 있다. 이번 작업이 페이지를 지우거나 병합해서 **남의 링크를 깼으면** 그것은
이번 작업이 만든 것이므로 에이전트에게 보여 준다. 반대로 나가는 링크를 지운 경우는 대상이
아니다 — 그것은 남의 페이지를 고치라는 요구가 된다.
"""

import pytest

from wiki_mcp.tools.lint import LintHandler
from wiki_mcp.tools.references import sync_references
from wiki_mcp.vaultfs import LocalVaultFS
from wiki_mcp.vaultfs.spring import SpringVaultFS

from .hydration import open_with_pages

SCOPE = "D1-D2"
JOB_ID = "42"

# 이번 요청과 관계있는 라이브 페이지. frontmatter 도 각주도 온전하다.
CLEAN_LIVE = {
    "wikiId": "101",
    "title": "커뮤니케이션 가이드",
    "wikiPath": f"wiki/{SCOPE}/pages/a3f2c1d4.md",
    "summary": "비동기 우선 소통",
    "contentMarkdown": (
        "---\ntitle: 커뮤니케이션 가이드\ndescription: 비동기 우선 소통\n"
        "tags: [커뮤니케이션, 회의]\ncategory: 근무 정책\n---\n\n"
        "회의는 최후의 수단으로 쓴다.\n"
    ),
}
CLEAN_ADDRESS = "pages/a3f2c1d4.md"

# frontmatter 가 아예 없는 라이브 페이지. 운영 중 위키에 실제로 있을 수 있고 이번 요청이
# 고칠 수 있는 것이 아니다.
NO_FRONTMATTER_LIVE = {
    "wikiId": "102",
    "title": "옛 페이지",
    "wikiPath": f"wiki/{SCOPE}/pages/b0b0b0b0.md",
    "contentMarkdown": "# 옛 페이지\n\n프론트매터 없이 저장돼 있다.\n",
}
NO_FRONTMATTER_ADDRESS = "pages/b0b0b0b0.md"

# 이번 요청에 실려 오지 않은 원본문서를 인용하는 라이브 페이지. 하이드레이션은 위키를
# 전부 올리지만 원본문서는 이번 것만 올리므로 이 각주는 **구조적으로** 풀리지 않는다.
FOREIGN_CITATION_LIVE = {
    "wikiId": "103",
    "title": "취업규칙 요약",
    "wikiPath": f"wiki/{SCOPE}/pages/c1c1c1c1.md",
    "contentMarkdown": (
        "---\ntitle: 취업규칙 요약\ndescription: 근로시간과 휴게\n"
        "tags: [취업규칙, 근로시간]\ncategory: 근무 정책\n---\n\n"
        "소정근로시간은 주 40시간이다[^1].\n\n"
        '[^1]: 취업규칙.pdf, 2장 근로시간 — "소정근로시간은 주 40시간이다"\n'
    ),
}
FOREIGN_CITATION_ADDRESS = "pages/c1c1c1c1.md"

# `CLEAN_LIVE` 를 가리키는 라이브 페이지. 에이전트가 그 페이지를 지우면 이 링크가 깨진다.
LINKS_TO_CLEAN_LIVE = {
    "wikiId": "104",
    "title": "온보딩",
    "wikiPath": f"wiki/{SCOPE}/pages/d2d2d2d2.md",
    "contentMarkdown": (
        "---\ntitle: 온보딩\ndescription: 첫 주에 읽을 것\n"
        "tags: [온보딩, 안내]\ncategory: 근무 정책\n---\n\n"
        f"[커뮤니케이션 가이드]({CLEAN_ADDRESS})를 먼저 읽는다.\n"
    ),
}
LINKS_TO_CLEAN_ADDRESS = "pages/d2d2d2d2.md"

# 처음부터 없는 곳을 가리키는 라이브 페이지. 이번 작업이 만든 문제가 아니다.
ALREADY_BROKEN_LIVE = {
    "wikiId": "105",
    "title": "복리후생",
    "wikiPath": f"wiki/{SCOPE}/pages/e3e3e3e3.md",
    "contentMarkdown": (
        "---\ntitle: 복리후생\ndescription: 지원 제도\n"
        "tags: [복리후생, 지원]\ncategory: 근무 정책\n---\n\n"
        "[사라진 페이지](pages/deadbeef.md)를 참고한다.\n"
    ),
}
ALREADY_BROKEN_ADDRESS = "pages/e3e3e3e3.md"

INDEX_MD = (
    "# 목차\n\n"
    f"- [커뮤니케이션 가이드]({CLEAN_ADDRESS}) — 비동기 우선 소통\n"
    f"- [옛 페이지]({NO_FRONTMATTER_ADDRESS})\n"
    f"- [취업규칙 요약]({FOREIGN_CITATION_ADDRESS})\n"
    f"- [온보딩]({LINKS_TO_CLEAN_ADDRESS})\n"
    f"- [복리후생]({ALREADY_BROKEN_ADDRESS})\n"
)

SOURCE_MD = "# 회의 운영\n\n## 2장 정례 회의\n\n주간 회의는 30분을 넘기지 않는다.\n"

AGENT_PAGE = (
    "---\ntitle: 정례 회의\ndescription: 주간 회의 운영 기준\n"
    "tags: [회의, 운영]\ncategory: 근무 정책\n---\n\n"
    "주간 회의는 30분을 넘기지 않는다[^1].\n\n"
    '[^1]: document-15, 2장 정례 회의 — "주간 회의는 30분을 넘기지 않는다"\n'
)


@pytest.fixture
async def vault(tmp_path):
    """라이브 페이지 5장 + 이번 요청의 원본문서 1건. 작업 층은 비어 있다."""
    pages = [CLEAN_LIVE, NO_FRONTMATTER_LIVE, FOREIGN_CITATION_LIVE,
             LINKS_TO_CLEAN_LIVE, ALREADY_BROKEN_LIVE]
    scope_id = await open_with_pages(tmp_path, SCOPE, JOB_ID, pages, INDEX_MD)
    fs = SpringVaultFS(SCOPE, JOB_ID)
    await fs.stage_source(scope_id, "15", SOURCE_MD)
    yield scope_id, fs
    await LocalVaultFS.close()


@pytest.fixture
def scope_row(vault):
    scope_id, _ = vault
    return {"id": scope_id, "scope_key": SCOPE, "index_path": f"wiki/{SCOPE}/index.md"}


async def _for_agent(fs, scope_row, pattern="*") -> str:
    return await LintHandler(fs, scope_row).run(pattern)


async def _write_page(fs, scope_id, content=AGENT_PAGE):
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, content, title="정례 회의",
                   category="근무 정책", tags=["회의", "운영"])
    await sync_references(fs, scope_id, address, content)
    return address


# ---- 에이전트가 보는 것: 라이브 전용 페이지의 내용 문제는 안 보인다 ----------

async def test_the_agent_does_not_see_frontmatter_errors_on_untouched_pages(
        vault, scope_row):
    """이번 작업이 건드리지 않은 페이지의 frontmatter 는 에이전트가 고칠 일이 아니다.

    보여 주면 에이전트가 남의 페이지를 열어 고치려 들고, 그 수정이 작업 층에 쌓여
    Spring 이 반영한다 — 이번 문서와 무관한 변경이 조용히 섞인다."""
    scope_id, fs = vault
    await _write_page(fs, scope_id)
    report = await _for_agent(fs, scope_row)
    assert "missing-frontmatter" not in report, report
    assert NO_FRONTMATTER_ADDRESS not in report, report


async def test_the_agent_does_not_see_citations_it_cannot_resolve(vault, scope_row):
    """하이드레이션은 위키를 전부 올리지만 원본문서는 이번 것만 올린다. 남의 각주가
    풀리지 않는 것은 그 사실의 부산물이고 에이전트가 고칠 방법이 없다 — 유일한 «수정»은
    남의 각주를 지우는 것이다."""
    scope_id, fs = vault
    await _write_page(fs, scope_id)
    report = await _for_agent(fs, scope_row)
    assert "unresolved-citation" not in report, report


async def test_the_agent_still_sees_errors_on_its_own_pages(vault, scope_row):
    """범위를 좁히는 것이 검사를 없애는 것은 아니다. 작업 층은 전부 검사한다."""
    scope_id, fs = vault
    await _write_page(fs, scope_id, "# 프론트매터 없는 새 페이지\n\n본문.\n")
    report = await _for_agent(fs, scope_row)
    assert "missing-frontmatter" in report, report


async def test_the_agent_still_sees_a_fabricated_quote_on_its_own_page(
        vault, scope_row):
    """FR-WIKI-001·NFR-AI-002 의 핵심 검사다. 이것이 죽으면 지어낸 인용이 통과한다."""
    scope_id, fs = vault
    await _write_page(fs, scope_id, AGENT_PAGE.replace(
        '"주간 회의는 30분을 넘기지 않는다"', '"주간 회의는 2시간을 넘기지 않는다"'))
    report = await _for_agent(fs, scope_row)
    assert "citation-quote-not-found" in report, report


# ---- 남의 링크를 깬 경우는 보여 준다 -----------------------------------------

async def test_the_agent_sees_the_inbound_link_it_broke_by_removing_a_page(
        vault, scope_row):
    """이번 작업이 페이지를 지우면 그것을 가리키던 라이브 페이지의 링크가 깨진다.
    **이것은 이번 작업이 만든 문제**이므로 보여 준다 — 지우지 않거나 링크를 옮기거나
    목차를 고치는 판단은 에이전트가 한다."""
    scope_id, fs = vault
    await _write_page(fs, scope_id)
    await fs.remove(scope_id, CLEAN_ADDRESS)
    report = await _for_agent(fs, scope_row)
    assert "dangling-link" in report, report
    assert LINKS_TO_CLEAN_ADDRESS in report, report


async def test_the_agent_does_not_see_links_that_were_already_broken(
        vault, scope_row):
    """처음부터 없는 곳을 가리키던 링크는 이번 작업의 책임이 아니다."""
    scope_id, fs = vault
    await _write_page(fs, scope_id)
    report = await _for_agent(fs, scope_row)
    assert ALREADY_BROKEN_ADDRESS not in report, report


# ---- uncited-source 는 유지한다 ---------------------------------------------

async def test_an_uncited_source_stays_an_error_for_the_agent(vault, scope_row):
    """이번 원본문서를 아무 페이지도 인용하지 않으면 그 지식은 어디에도 안 남았다.
    범위를 좁혀도 이 검사는 남아야 한다 — 이번 입력이 반영됐는지 보는 유일한 검사다.

    반영 게이트(`api/session.py.assert_lint_clean`)는 이 코드를 버린다. 정당한 무변경 재투입이
    있기 때문이다 (FR-DOC-012). 그 판단은 서버가 하고 **에이전트에게는 알린다.**"""
    _, fs = vault                       # 페이지를 쓰지 않는다 — 문서가 반영되지 않았다
    report = await _for_agent(fs, scope_row)
    assert "uncited-source" in report, report


# ---- 읽기 전용 세션(개발 도구)은 전부 검사한다 ------------------------------

async def test_a_read_only_session_checks_everything(tmp_path):
    """`--job-id` 없이 띄운 개발 도구는 작업 층이 없다. 그때 좁히면 아무것도 검사하지
    않는 도구가 된다 — 사람이 기존 위키를 점검하는 용도이므로 전부 본다.

    `local_server.py` 의 `--job-id` 가 그 구분이다: 있으면 에이전트가 편집하는 세션,
    없으면 읽기 전용이다."""
    from wiki_mcp.vaultfs.local import bootstrap_scope, register_source

    scope_id = await LocalVaultFS.open(tmp_path, SCOPE, None)
    await bootstrap_scope(SCOPE)
    await register_source(SCOPE, "101", "인사규정.pdf", "# 인사규정\n\n내용.\n")
    fs = LocalVaultFS(SCOPE, None)
    try:
        row = {"id": scope_id, "scope_key": SCOPE,
               "index_path": f"wiki/{SCOPE}/index.md"}
        report = await LintHandler(fs, row).run("*")
        # 인용하는 페이지가 없으니 uncited-source 가 떠야 한다 — 검사가 돌았다는 증거다.
        assert "uncited-source" in report, report
    finally:
        await LocalVaultFS.close()


# ---- 규모 — 전량 push 조건 (계획 Task 3 Verify) -----------------------------

def _noisy_pages(count: int) -> list[dict]:
    """frontmatter 도 없고 없는 문서를 인용하는 라이브 페이지 여러 장.

    좁히지 않으면 페이지마다 error 가 여러 건씩 나온다 — `FR-WIKI-002` v2.9 로 5개 상한이
    없어진 뒤 Spring 이 범위를 전량 실어 보내면 이것이 실제 조건이다."""
    return [{
        "wikiId": f"9{i:03d}",
        "title": f"옛 페이지 {i}",
        "wikiPath": f"wiki/{SCOPE}/pages/old{i:04d}.md",
        "contentMarkdown": (
            f"옛 본문 {i}. frontmatter 가 없다.\n\n"
            f"근거는 여기 있다[^1].\n\n"
            f'[^1]: 없는문서{i}.pdf, 1장 — "옛 본문 {i}"\n'
        ),
    } for i in range(count)]


async def test_a_hundred_live_pages_produce_no_noise_for_the_agent(tmp_path):
    """위키 100장 + 원본문서 1건. 에이전트가 받는 보고서에 라이브 페이지의 각주·frontmatter
    오류가 하나도 없어야 한다.

    좁히기 전에는 여기서 error 300건쯤이 나왔고 에이전트는 "끝내기 전에 모두 고친다" 지시에
    따라 남의 페이지를 고치려 들었다. 고칠 수도 없다 — 인용된 문서들이 실려 오지 않았다."""
    pages = _noisy_pages(100)
    scope_id = await open_with_pages(tmp_path, SCOPE, JOB_ID, pages, "# 목차\n")
    fs = SpringVaultFS(SCOPE, JOB_ID)
    try:
        await fs.stage_source(scope_id, "15", SOURCE_MD)
        await _write_page(fs, scope_id)
        row = {"id": scope_id, "scope_key": SCOPE,
               "index_path": f"wiki/{SCOPE}/index.md"}
        report = await LintHandler(fs, row).run("*")
        assert "missing-frontmatter" not in report, report
        assert "unresolved-citation" not in report, report
        assert "없는문서" not in report, report
    finally:
        await LocalVaultFS.close()


async def test_removing_a_page_at_scale_still_names_every_broken_inbound_link(tmp_path):
    """반대쪽. 100장 중 한 장을 지우면 그것을 가리키던 페이지들이 잡혀야 한다 — 범위를
    좁힌 것이 «우리가 깬 것»까지 덮으면 남의 위키를 조용히 망가뜨린다."""
    target = "pages/old0007.md"
    pages = _noisy_pages(100)
    linkers = [{
        "wikiId": f"8{i:03d}",
        "title": f"안내 {i}",
        "wikiPath": f"wiki/{SCOPE}/pages/ref{i:04d}.md",
        "contentMarkdown": (
            f"---\ntitle: 안내 {i}\ndescription: 안내\n"
            f"tags: [안내, 참고]\ncategory: 근무 정책\n---\n\n"
            f"[옛 페이지 7]({target})를 본다.\n"
        ),
    } for i in range(3)]

    scope_id = await open_with_pages(tmp_path, SCOPE, JOB_ID,
                                     [*pages, *linkers], "# 목차\n")
    fs = SpringVaultFS(SCOPE, JOB_ID)
    try:
        await fs.stage_source(scope_id, "15", SOURCE_MD)
        await _write_page(fs, scope_id)
        await fs.remove(scope_id, target)
        row = {"id": scope_id, "scope_key": SCOPE,
               "index_path": f"wiki/{SCOPE}/index.md"}
        report = await LintHandler(fs, row).run("*")
        for i in range(3):
            assert f"pages/ref{i:04d}.md" in report, report
    finally:
        await LocalVaultFS.close()
