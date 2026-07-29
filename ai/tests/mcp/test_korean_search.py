"""한국어 검색 (D8) — 계획 `2026-07-29-wiki-context-hardening.md` Task 2.

FTS5 `unicode61` 은 공백으로만 자른다. 한국어는 조사·어미가 붙어 오므로
`"연차를 사용하려면"` 이 `[연차를] [사용하려면]` 두 토큰이 되고 **질의 `"연차"` 가 아무것도
찾지 못한다.**

측정 (`experiments/corpus-ko/`, 질의 40개):

    unicode61 AND (현행)   R@5 0.33  정확어 0.65  0건 26/40
    2글자 분해 OR          R@5 0.60  정확어 0.95  0건  7/40

`trigram` 은 3글자 이상만 토큰이 되어 한국어 핵심어(연차·이월·승인·부여)를 전부 놓친다 —
현행보다 나쁘다 (0.30).

**색인과 질의 양쪽에 같은 전처리를 걸어야 한다.** 한쪽만 하면 아무것도 맞지 않는다. 그
성질을 이 파일이 지킨다.
"""

import json
from pathlib import Path

import pytest

from wiki_mcp.services.chunker import search_tokens
from wiki_mcp.tools.references import sync_references
from wiki_mcp.vaultfs import LocalVaultFS

SCOPE = "ALL"
JOB_ID = "9001"

LEAVE_PAGE = """\
---
title: 연차 규정
description: 연차 발생과 이월 기준
date: 2026-07-29
tags: [휴가, 인사]
category: 휴가 정책
---

## 발생

연차를 사용하려면 승인이 필요하다. 입사 1년 미만은 월 1일씩 부여한다.

## 이월

미사용 연차는 다음 해 3월까지 이월할 수 있다.
"""

ENGLISH_PAGE = """\
---
title: Expense Policy
description: How to file expenses
date: 2026-07-29
tags: [expense, finance]
category: 근무 정책
---

## Reimbursement

Submit receipts within thirty days. Approval is required for amounts over $500.

## Corporate Card

The corporate card covers travel and client meals.
"""


@pytest.fixture
async def vault(tmp_path):
    from wiki_mcp.vaultfs.local import bootstrap_scope

    scope_id = await LocalVaultFS.open(tmp_path, SCOPE, JOB_ID)
    await bootstrap_scope(SCOPE)
    fs = LocalVaultFS(SCOPE, JOB_ID)
    yield scope_id, fs
    await LocalVaultFS.close()


async def _write(fs, scope_id, content: str, title: str) -> str:
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, content, title=title,
                   category="휴가 정책", tags=["휴가", "인사"])
    await sync_references(fs, scope_id, address, content)
    return address


async def _hits(fs, scope_id, query: str) -> list[str]:
    rows = await fs.search_chunks(scope_id, query, limit=20)
    seen: list[str] = []
    for row in rows:
        if row["address"] not in seen:
            seen.append(row["address"])
    return seen


# ---- 전처리 함수 자체 --------------------------------------------------------

def test_search_tokens_splits_korean_into_overlapping_bigrams():
    assert search_tokens("연차를") == "연차 차를"


def test_search_tokens_keeps_a_one_character_word_whole():
    """1글자 어절은 나눌 수 없다. 버리면 그 글자로는 아무것도 못 찾는다."""
    assert search_tokens("이 달") == "이 달"


def test_search_tokens_does_not_split_latin_or_digits():
    """영문·숫자를 나누면 영어 재현율이 떨어진다 — 공백 분리가 이미 맞는 언어다."""
    assert search_tokens("Expense Policy 2026") == "expense policy 2026"


def test_search_tokens_normalises_to_nfc():
    """한글은 조합형·완성형 두 표현이 있다. 정규화하지 않으면 같은 글자가 다른 토큰이 된다."""
    decomposed = "연차"      # 조합형 '연차'
    assert search_tokens(decomposed) == search_tokens("연차")


def test_search_tokens_drops_punctuation():
    assert search_tokens("연차, 이월!") == "연차 이월"


def test_search_tokens_is_stable_on_empty_input():
    assert search_tokens("") == ""
    assert search_tokens("   ") == ""


# ---- 색인·질의 경로 ---------------------------------------------------------

@pytest.mark.parametrize("query", ["연차", "이월", "승인", "부여"])
async def test_a_two_character_korean_query_finds_the_page(vault, query):
    """현행 구현이 실패하는 지점이다. 본문에는 `연차를`·`이월할`·`승인이`·`부여한다` 로
    조사·어미가 붙어 있고, 공백 분리 색인에서는 2글자 질의가 어디에도 맞지 않는다."""
    scope_id, fs = vault
    address = await _write(fs, scope_id, LEAVE_PAGE, "연차 규정")
    assert address in await _hits(fs, scope_id, query), query


async def test_a_query_with_its_own_particle_still_finds_the_page(vault):
    """질의에도 조사가 붙어 올 수 있다. `연차를` 의 bigram 은 `연차 차를` 이고 본문
    `연차를` 의 색인도 같은 두 토큰이라 맞는다."""
    scope_id, fs = vault
    address = await _write(fs, scope_id, LEAVE_PAGE, "연차 규정")
    assert address in await _hits(fs, scope_id, "연차를")


async def test_a_multi_word_korean_query_finds_the_page(vault):
    scope_id, fs = vault
    address = await _write(fs, scope_id, LEAVE_PAGE, "연차 규정")
    assert address in await _hits(fs, scope_id, "연차 이월")


async def test_a_word_that_is_not_in_the_corpus_finds_nothing(vault):
    """오탐 회귀 방지. 2글자로 쪼개면 우연한 겹침이 늘어나므로 이 방향을 못 박는다."""
    scope_id, fs = vault
    await _write(fs, scope_id, LEAVE_PAGE, "연차 규정")
    assert await _hits(fs, scope_id, "싸이버펑크") == []


async def test_a_partial_bigram_overlap_does_not_pull_in_the_page(vault):
    """`"확인"` 은 본문에 없다. 본문 `"승인이"` 의 색인은 `[승인, 인이]` 이고 `"확인"` 의
    bigram 은 `[확인]` 이라 겹치지 않는다.

    bigram 을 평평한 OR 로 잇기로 한 근거가 이것이다 — bigram 이 연속 부분문자열이라
    어절 단위 phrase 로 묶지 않아도 순서가 뒤집힌 오탐이 생기지 않는다
    (`chunker.search_query`)."""
    scope_id, fs = vault
    await _write(fs, scope_id, LEAVE_PAGE, "연차 규정")
    assert await _hits(fs, scope_id, "확인") == []


async def test_a_scrambled_korean_query_finds_nothing(vault):
    """`"차연"` 은 `"연차"` 의 글자를 뒤집은 것이다. bigram 이 연속 부분문자열이므로
    본문 `[연차, 차를]` 색인에 `[차연]` 은 없다."""
    scope_id, fs = vault
    await _write(fs, scope_id, LEAVE_PAGE, "연차 규정")
    assert await _hits(fs, scope_id, "차연") == []


async def test_an_english_query_still_works(vault):
    """기존 영어 코퍼스 회귀 방지. 영문을 분해하면 여기가 깨진다."""
    scope_id, fs = vault
    address = await _write(fs, scope_id, ENGLISH_PAGE, "Expense Policy")
    assert address in await _hits(fs, scope_id, "reimbursement")
    assert address in await _hits(fs, scope_id, "corporate card")


async def test_an_english_query_does_not_match_an_unrelated_page(vault):
    scope_id, fs = vault
    await _write(fs, scope_id, ENGLISH_PAGE, "Expense Policy")
    assert await _hits(fs, scope_id, "kubernetes") == []


# ---- 원문과 메타데이터가 그대로 나온다 --------------------------------------

async def test_the_result_carries_the_original_text_not_the_preprocessed_form(vault):
    """전처리본은 색인 전용이다. 스니펫·각주 원문 대조가 `document_chunks.content` 를
    읽으므로 그것이 bigram 으로 바뀌면 인용 검증이 전부 깨진다."""
    scope_id, fs = vault
    await _write(fs, scope_id, LEAVE_PAGE, "연차 규정")
    rows = await fs.search_chunks(scope_id, "연차", limit=20)
    assert rows
    assert "연차를 사용하려면 승인이 필요하다" in rows[0]["content"]
    assert "연차 차를" not in rows[0]["content"]


async def test_the_result_carries_the_header_breadcrumb(vault):
    """검색 결과가 문서 안 어디인지 말해야 한다 — `search` 가 이 값을 찍는다."""
    scope_id, fs = vault
    await _write(fs, scope_id, LEAVE_PAGE, "연차 규정")
    rows = await fs.search_chunks(scope_id, "이월", limit=20)
    assert rows
    assert any((r.get("header_breadcrumb") or "") for r in rows)


# ---- 색인 재생성 -------------------------------------------------------------

async def test_rebuilding_the_index_reproduces_the_same_hits(tmp_path):
    """색인은 파생 데이터다 (`shared/schema.sql`). `rebuild_index` 로 다시 만들어도 같은
    결과가 나와야 한다 — 전처리가 쓰기 경로에만 있고 재생성 경로에 빠지면 재생성한
    저장소에서 한국어 검색이 조용히 죽는다.

    `rebuild_index` 는 작업 층을 다시 만들지 않는다 (DR-009). 그래서 라이브 층에 페이지를
    두고 확인한다 — 하이드레이션이 만드는 것이 라이브 층이고, 재생성 대상도 그쪽이다."""
    from wiki_mcp.vaultfs.rebuild import rebuild_index
    from wiki_mcp.vaultfs.spring import SpringVaultFS

    page = {
        "wikiId": "101",
        "title": "연차 규정",
        "wikiPath": f"wiki/{SCOPE}/pages/a1b2c3d4.md",
        "contentMarkdown": LEAVE_PAGE,
    }
    scope_id = await SpringVaultFS.open(tmp_path, SCOPE, JOB_ID,
                                       pages=[page], index_markdown="# 목차\n")
    fs = SpringVaultFS(SCOPE, JOB_ID)
    try:
        before = await _hits(fs, scope_id, "연차")
        assert "pages/a1b2c3d4.md" in before
        await rebuild_index(tmp_path, SCOPE)
        assert await _hits(fs, scope_id, "연차") == before
    finally:
        await LocalVaultFS.close()


# ---- 코퍼스 목표치 (계획 Task 2 Verify) --------------------------------------

CORPUS = Path(__file__).resolve().parents[2] / "experiments" / "corpus-ko"


async def test_the_shipped_search_path_meets_the_corpus_targets(tmp_path):
    """`experiments/corpus-ko/` 100장을 실제 하이드레이션 경로로 올리고 실제
    `search_chunks` 로 잰다.

    `evaluate_sqlite.py` 는 메모리 SQLite 에 전략을 격리해 재는 하네스다 — 아이디어가
    맞는지 본다. 이 테스트는 **배포되는 코드**가 그 목표에 닿는지 본다. 둘이 갈라지면
    (전처리가 한쪽 경로에만 걸리는 등) 하네스만 통과하고 제품은 못 찾는다.

    목표는 계획 Task 2 Verify 다 — R@5 ≥ 0.55, 정확어 R@5 ≥ 0.90.
    `dev` 분할로 잰다. `test` 분할은 판정에만 한 번 쓴다 (`corpus-ko/README.md`).
    """
    from wiki_mcp.vaultfs.spring import SpringVaultFS

    stems = sorted(p.stem for p in (CORPUS / "pages").glob("*.md"))
    # wikiId 는 1부터. 주소는 하이드레이션이 `pages/{wikiId}.md` 로 짓는다.
    address_of = {stem: f"pages/{i}.md" for i, stem in enumerate(stems, 1)}
    pages = [{
        "wikiId": str(i),
        "title": stem,
        "contentMarkdown": (CORPUS / "pages" / f"{stem}.md").read_text(encoding="utf-8"),
    } for i, stem in enumerate(stems, 1)]

    queries = json.loads((CORPUS / "queries.json").read_text(encoding="utf-8"))["dev"]

    scope_id = await SpringVaultFS.open(tmp_path, SCOPE, JOB_ID,
                                        pages=pages, index_markdown="# 목차\n")
    fs = SpringVaultFS(SCOPE, JOB_ID)
    try:
        hit5 = {"exact": 0.0, "para": 0.0}
        counts = {"exact": 0, "para": 0}
        zero = 0
        for item in queries:
            gold = {address_of[g] for g in item["gold"]}
            top = (await _hits(fs, scope_id, item["query"]))[:5]
            if not top:
                zero += 1
            hit5[item["kind"]] += len(set(top) & gold) / len(gold)
            counts[item["kind"]] += 1
    finally:
        await LocalVaultFS.close()

    exact = hit5["exact"] / counts["exact"]
    para = hit5["para"] / counts["para"]
    overall = (hit5["exact"] + hit5["para"]) / (counts["exact"] + counts["para"])
    report = (f"R@5 {overall:.2f} · 정확어 {exact:.2f} · 자연어 {para:.2f} · "
              f"0건 {zero}/{len(queries)}")

    assert exact >= 0.90, report
    assert overall >= 0.55, report
