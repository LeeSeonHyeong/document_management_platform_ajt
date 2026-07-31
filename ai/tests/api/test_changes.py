"""작업 층을 계약 응답으로 바꾼다.

`pageKey` 와 `tempWikiId` 는 같은 발상이다 — DB 행이 생기기 전에 페이지를 쓰고 서로
링크하려면 쓰는 쪽이 이름을 발급해야 한다. 계약은 그것을 파일명이 아니라 응답 안 참조로
표현하므로 매핑이 1:1 이다.

`evidence` 가 붙는 이유는 FR-AI-009 다 — 「각 변경의 근거 문서 위치」를 `summary` 문자열
하나로는 담을 수 없다.
"""

import pytest

from wiki_api.changes import TempRefs, build_response, parse_index_entries

PAGE = """\
---
title: 회의 운영
description: 정례 회의 운영 기준
tags: [회의, 운영]
category: 근무 정책
---

주간 회의는 30분을 넘기지 않는다[^1].

[^1]: 인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"
"""


def test_temp_refs_are_stable_and_one_to_one():
    refs = TempRefs()
    first = refs.for_new_page("a3f2c1d4")
    assert first == "wiki-temp-1"
    assert refs.for_new_page("a3f2c1d4") == "wiki-temp-1"   # 같은 키는 같은 참조
    assert refs.for_new_page("7b91e0c2") == "wiki-temp-2"


def test_existing_page_resolves_to_its_wiki_id():
    refs = TempRefs()
    refs.bind_existing("a3f2c1d4", "101")
    assert refs.ref_for("a3f2c1d4") == "101"


def test_index_entries_come_from_the_markdown_the_agent_wrote():
    refs = TempRefs()
    refs.bind_existing("a3f2c1d4", "101")
    refs.for_new_page("7b91e0c2")

    entries = parse_index_entries(
        "# 목차\n\n"
        "- [회의 운영](pages/a3f2c1d4.md) — 정례 회의 운영 기준\n"
        "- [휴가 규정](pages/7b91e0c2.md) — 연차와 반차\n",
        refs,
    )

    assert [(e.wikiRef, e.order, e.title, e.summary) for e in entries] == [
        ("101", 1, "회의 운영", "정례 회의 운영 기준"),
        ("wiki-temp-1", 2, "휴가 규정", "연차와 반차"),
    ]


def test_index_entry_without_a_summary():
    refs = TempRefs()
    refs.bind_existing("a3f2c1d4", "101")
    entries = parse_index_entries("- [회의 운영](pages/a3f2c1d4.md)\n", refs)
    assert entries[0].summary is None


def test_index_entries_come_from_a_table_the_agent_wrote():
    """에이전트는 목차를 표로 쓴다 — 저장된 측정 8건이 전부 표였다.

    표 행은 `| 주제 | [제목](주소) | 요약 |` 모양이고 링크가 첫 칸이 아니다. 제목은
    링크 글자를 쓴다 (목록 형식과 같은 규칙). 요약은 링크 없는 뒤쪽 칸이다.
    """
    refs = TempRefs()
    refs.bind_existing("a3f2c1d4", "101")
    refs.for_new_page("7b91e0c2")

    entries = parse_index_entries(
        "## 핵심 내용\n\n"
        "| 주제 | 페이지 | 요약 |\n"
        "|------|--------|------|\n"
        "| 회의 | [회의 운영](pages/a3f2c1d4.md) | 정례 회의 운영 기준 |\n"
        "| 휴가 | [휴가 규정](pages/7b91e0c2.md) | 연차와 반차 |\n",
        refs,
    )

    assert [(e.wikiRef, e.order, e.title, e.summary) for e in entries] == [
        ("101", 1, "회의 운영", "정례 회의 운영 기준"),
        ("wiki-temp-1", 2, "휴가 규정", "연차와 반차"),
    ]


def test_table_row_without_a_summary_cell():
    refs = TempRefs()
    refs.bind_existing("a3f2c1d4", "101")
    entries = parse_index_entries("| [회의 운영](pages/a3f2c1d4.md) |\n", refs)
    assert [(e.title, e.summary) for e in entries] == [("회의 운영", None)]


def test_bullet_link_wrapped_in_bold():
    """`- **[제목](주소)** — 요약` 도 목차 줄이다. 실제 측정에서 나온 세 번째 형식이다."""
    refs = TempRefs()
    refs.bind_existing("a3f2c1d4", "101")
    entries = parse_index_entries(
        "- **[회의 운영](pages/a3f2c1d4.md)** — 정례 회의 운영 기준\n", refs)
    assert [(e.title, e.summary) for e in entries] == [
        ("회의 운영", "정례 회의 운영 기준"),
    ]


def test_change_log_bullet_has_no_summary():
    """「최근 변경」 줄은 요약이 아니다 — 구분선(—) 없이 서술만 이어진다."""
    refs = TempRefs()
    refs.bind_existing("a3f2c1d4", "101")
    entries = parse_index_entries(
        "- 2026-07-27: [회의 운영](pages/a3f2c1d4.md) 신규 생성 (원본: `01.md`)\n", refs)
    assert [(e.title, e.summary) for e in entries] == [("회의 운영", None)]


def test_same_page_listed_twice_becomes_one_entry():
    """「핵심 내용」 표와 「최근 변경」 목록에 같은 페이지가 함께 나온다.

    Spring 이 같은 페이지를 목차에 두 번 받으면 안 된다. 먼저 나온 것을 남긴다.
    """
    refs = TempRefs()
    refs.bind_existing("a3f2c1d4", "101")

    entries = parse_index_entries(
        "| 회의 | [회의 운영](pages/a3f2c1d4.md) | 정례 회의 운영 기준 |\n"
        "- [회의 운영](pages/a3f2c1d4.md) — 나중에 나온 설명\n",
        refs,
    )

    assert [(e.wikiRef, e.order, e.summary) for e in entries] == [
        ("101", 1, "정례 회의 운영 기준"),
    ]


def test_index_entries_skip_links_to_unknown_pages():
    """목차가 없는 페이지를 가리키면 Spring 이 매달린 참조를 받는다."""
    entries = parse_index_entries("- [없음](pages/deadbeef.md) — 설명\n", TempRefs())
    assert entries == []


async def test_create_becomes_a_wiki_change_with_a_temp_ref(vault, scope_row):
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, PAGE, title="회의 운영", category="근무 정책",
                   tags=["회의", "운영"])
    from wiki_mcp.tools.references import sync_references
    await sync_references(fs, scope_id, address, PAGE)

    response = await build_response(fs, scope_id, summary="회의 운영 Wiki를 생성했습니다.")

    assert response.summary == "회의 운영 Wiki를 생성했습니다."
    change = response.wikiChanges[0]
    assert change.action == "create"
    assert change.tempWikiId == "wiki-temp-1"
    assert change.wikiId is None
    assert change.title == "회의 운영"
    assert "주간 회의는 30분" in change.contentMarkdown


async def test_evidence_carries_location_and_quote(vault, scope_row):
    """lint 가 원문 대조한 그 값이 그대로 실린다 (FR-AI-009, NFR-AI-002)."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, PAGE, title="회의 운영")
    from wiki_mcp.tools.references import sync_references
    await sync_references(fs, scope_id, address, PAGE)

    response = await build_response(fs, scope_id, summary="x")
    evidence = response.wikiChanges[0].evidence

    assert evidence, "각주가 있는데 evidence 가 비었다"
    assert evidence[0].documentId == "101"
    assert evidence[0].location == "3장 휴가"
    assert evidence[0].quote == "연차는 입사일을 기준으로 산정한다"


async def test_relation_changes_never_carry_a_wiki_document_item(vault, scope_row):
    """S15P11B106-157: Wiki-원본문서 관계는 더 이상 `relationChanges` 로 나가지 않는다.

    `wikiChanges[].evidence` 가 같은 정보를 나르고 Spring 은 그것으로 `wiki.document_refs`
    를 채운다(`evidenceDocumentIds` 는 `originDocumentId` 를 항상 포함한다) — 그래서
    `relationChanges` 는 위키↔위키(`wiki_wiki`) 전용이다. 이 페이지는 각주로 원본문서
    101 을 인용하지만 그 관계는 `evidence` 로만 나가야 한다.
    """
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, PAGE, title="회의 운영")
    from wiki_mcp.tools.references import sync_references
    await sync_references(fs, scope_id, address, PAGE)

    response = await build_response(fs, scope_id, summary="x")

    assert response.wikiChanges[0].evidence[0].documentId == "101"
    assert all(r.type == "wiki_wiki" for r in response.relationChanges)
    assert not any(getattr(r, "type", None) == "wiki_document"
                   for r in response.relationChanges)


async def test_an_inline_wiki_link_becomes_an_undirected_wiki_wiki_relation(vault, scope_row):
    """DR-002·003 의 Wiki-Wiki 쪽. **이 단정이 없어서 조립이 KeyError 로 500 이었다.**

    `get_forward_references()` 는 대상 문서의 주소를 `address` 컬럼으로 준다
    (`mcp/vaultfs/local.py` 의 SELECT — `get_backlinks`·`search.py`·`references.py` 도 전부
    그 이름을 읽는다). `changes.py` 만 `target_address` 를 읽어서, **본문에 다른 위키로 가는
    인라인 링크가 하나라도 있으면** 변환 응답 조립이 통째로 터졌다
    (`failureStage: assemble`). 12건 측정 코퍼스에 그런 링크가 69개였다.
    """
    _, scope_id, fs = vault
    from wiki_mcp.tools.references import sync_references

    target = await fs.allocate_page(scope_id)
    await fs.write(scope_id, target, PAGE, title="회의 운영", category="근무 정책")
    await sync_references(fs, scope_id, target, PAGE)
    from wiki_mcp.vaultfs.local import commit_job
    await commit_job(fs.scope_key, fs.job_id, scope_id, lambda: "501")

    linking = await fs.allocate_page(scope_id)
    body = (PAGE.replace("title: 회의 운영", "title: 회의 준비")
            .replace("주간 회의는 30분을 넘기지 않는다[^1].",
                     f"자세한 것은 [회의 운영]({target})을 본다[^1]."))
    await fs.write(scope_id, linking, body, title="회의 준비", category="근무 정책")
    await sync_references(fs, scope_id, linking, body)

    response = await build_response(fs, scope_id, summary="x")

    wiki_links = [(r.action, r.sourceWikiRef, r.targetWikiRef)
                  for r in response.relationChanges if r.type == "wiki_wiki"]
    assert wiki_links == [("add", "wiki-temp-1", "501")]


async def test_remove_becomes_a_remove_with_no_wiki_document_unlink(vault, scope_row):
    """S15P11B106-157: 사라진 위키가 원본문서만 인용하고(위키↔위키 링크가 없으면) 이제
    `relationChanges` 는 비어 있어야 한다 — Wiki-원본문서 unlink 를 더 이상 여기서 안 낸다.
    문서 자체가 사라지며 Spring 쪽 `document_refs` 는 그 문서 삭제로 정리된다."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, PAGE, title="회의 운영")
    from wiki_mcp.tools.references import sync_references
    await sync_references(fs, scope_id, address, PAGE)

    # A tombstone (vs. an outright drop) only happens for a page already live —
    # commit it first so `remove` has something to hand over.
    from wiki_mcp.vaultfs.local import commit_job
    await commit_job(fs.scope_key, fs.job_id, scope_id, lambda: "501")

    await fs.remove(scope_id, address)

    response = await build_response(fs, scope_id, summary="x")

    assert response.wikiChanges[0].action == "remove"
    assert response.relationChanges == []


async def test_dropping_a_footnote_yields_no_relation_change(vault, scope_row):
    """S15P11B106-157: 페이지를 고치면서 각주(Wiki-원본문서 인용)를 떨어뜨려도 더 이상
    `relationChanges` 에는 안 실린다 — `wikiChanges[].evidence` 가 갱신된 인용 목록을
    그대로 실어 Spring 이 그것을 `wiki.document_refs` 에 추가한다. (인용이 끊어진
    항목을 걷어내는 경로는 Spring 에 아직 없다 — 별건.)"""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    from wiki_mcp.tools.references import sync_references
    await fs.write(scope_id, address, PAGE, title="회의 운영")
    await sync_references(fs, scope_id, address, PAGE)

    from wiki_mcp.vaultfs.local import commit_job
    await commit_job(fs.scope_key, fs.job_id, scope_id, lambda: "501")

    stripped = PAGE.split("[^1]")[0].rstrip() + "\n"
    await fs.write(scope_id, address, stripped, title="회의 운영")
    await sync_references(fs, scope_id, address, stripped)

    response = await build_response(fs, scope_id, summary="x")

    assert response.relationChanges == []
    assert response.wikiChanges[0].evidence == []


async def test_removing_a_page_unlinks_its_wiki_wiki_relation(vault, scope_row):
    """위키↔위키 관계의 `remove` 는 각주가 아니라 **페이지 자체가 사라질 때** 난다 —
    `action` 은 그 페이지의 `WikiChange.action` 을 그대로 물려받는다 (`changes.py`).
    이 페이지가 지워지면 그 페이지가 걸었던 인라인 링크도 `relationChanges` 에
    `action="remove"` 로 실려야 한다."""
    _, scope_id, fs = vault
    from wiki_mcp.tools.references import sync_references
    from wiki_mcp.vaultfs.local import commit_job

    target = await fs.allocate_page(scope_id)
    await fs.write(scope_id, target, PAGE, title="회의 운영", category="근무 정책")
    await sync_references(fs, scope_id, target, PAGE)
    await commit_job(fs.scope_key, fs.job_id, scope_id, lambda: "501")

    linking = await fs.allocate_page(scope_id)
    body = (PAGE.replace("title: 회의 운영", "title: 회의 준비")
            .replace("주간 회의는 30분을 넘기지 않는다[^1].",
                     f"자세한 것은 [회의 운영]({target})을 본다[^1]."))
    await fs.write(scope_id, linking, body, title="회의 준비", category="근무 정책")
    await sync_references(fs, scope_id, linking, body)
    await commit_job(fs.scope_key, fs.job_id, scope_id, lambda: "502")

    await fs.remove(scope_id, linking)

    response = await build_response(fs, scope_id, summary="x")

    wiki_links = [(r.action, r.sourceWikiRef, r.targetWikiRef)
                  for r in response.relationChanges if r.type == "wiki_wiki"]
    assert wiki_links == [("remove", "502", "501")]


async def test_wiki_change_carries_the_wiki_path(vault, scope_row):
    """C2. Spring 이 DR-016 `wiki_path` 컬럼에 그대로 저장하고, 신규 페이지 사이의 본문
    링크를 실제 `wikiId` 로 치환할 때 이 값이 열쇠다."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, PAGE, title="회의 운영")

    response = await build_response(fs, scope_id, summary="x")

    assert response.wikiChanges[0].wikiPath == f"wiki/{fs.scope_key}/{address}"


async def test_known_category_is_referenced_by_its_id_not_recreated(vault, scope_row):
    """카테고리는 에이전트가 관리하고 관리자는 조회만 한다 (FR-WIKI-014). 그 관리가 성립하려면
    이미 있는 이름을 다시 만들지 않고 그 `wikiCategoryId` 를 가리켜야 한다 (DR-019)."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, PAGE, title="회의 운영", category="근무 정책")

    response = await build_response(fs, scope_id, summary="x",
                                   current_categories={"근무 정책": "9"})

    assert response.categoryChanges == []
    assert response.wikiChanges[0].wikiCategoryRef == "9"


async def test_unknown_category_is_created_and_referenced_by_temp_id(vault, scope_row):
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address, PAGE, title="회의 운영", category="근무 정책")

    response = await build_response(fs, scope_id, summary="x", current_categories={})

    created = response.categoryChanges[0]
    assert created.action == "create"
    assert created.name == "근무 정책"
    assert created.tempCategoryId == "category-temp-1"
    assert response.wikiChanges[0].wikiCategoryRef == "category-temp-1"


async def test_no_changes_gives_empty_lists(vault, scope_row):
    """재투입에서 변경 0 은 정상이다 (FR-DOC-012). 오류가 아니다."""
    _, scope_id, fs = vault
    response = await build_response(fs, scope_id, summary="변경이 없습니다.")
    assert response.wikiChanges == []
    assert response.relationChanges == []
