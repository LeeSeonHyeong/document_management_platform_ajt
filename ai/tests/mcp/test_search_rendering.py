"""push 검색 렌더링 형식 회귀 테스트.

`tools/search.py` 의 건별 렌더링이 `_match_lines` 헬퍼로 뽑혀 나갔다 (설계 문서 §9.2.1
근방 리뷰 참고). 원본(`git show c402aba:ai/src/wiki_mcp/tools/search.py`)과 문자 단위로
대조해 동일함을 확인했지만, 그 사실을 잡아 두는 테스트가 없었다. 이 파일이 push 경로
(= 매치에 `origin` 키가 없는 행)의 출력 형식을 고정한다 — 헤더 문구, 건별 형식(쪽수·헤더
경로·`[보기]` 딥링크·코드 펜스), 빈 결과 문구.

형식을 새로 정하지 않는다 — 지금 나오는 문자열을 그대로 굳힌다.
"""

from wiki_mcp.config import settings
from wiki_mcp.tools.helpers import MAX_LIST
from wiki_mcp.tools.search import SearchHandler

SCOPE_ROW = {"id": "scope-1", "scope_key": "D1-D2"}


class FakeFS:
    """`fs.search_chunks` 표면만 흉내 낸다. `origin` 키를 넣지 않아 push 경로를 탄다."""

    def __init__(self, matches):
        self.matches = matches

    async def search_chunks(self, scope_id, query, limit, kind_filter=None):
        return self.matches


class FakeListFS:
    """`fs.list_documents` 표면만 흉내 낸다 — `browse`(mode="list") 렌더링 전용."""

    def __init__(self, docs):
        self.docs = docs

    async def list_documents(self, scope_id):
        return self.docs


async def test_push_search_renders_page_and_breadcrumb_and_deep_link_and_fence():
    match = {
        "address": "pages/a3f2c1d4.md",
        "title": "휴가 규정",
        "kind": "page",
        "page": 3,
        "header_breadcrumb": "휴가 규정 > 3장 연차",
        "content": "연차는 입사일을 기준으로 산정한다. 이월은 다음 해 3월까지 가능하다.",
    }
    handler = SearchHandler(FakeFS([match]), SCOPE_ROW)

    result = await handler.search("연차", "*", None, 10)

    expected = (
        "**1건** — `연차`:\n\n"
        "**pages/a3f2c1d4.md** (3쪽) — 휴가 규정 "
        f"[보기]({settings.APP_URL}/wiki/D1-D2/pages/a3f2c1d4.md)\n"
        "  휴가 규정 > 3장 연차\n"
        "```\n"
        "연차는 입사일을 기준으로 산정한다. 이월은 다음 해 3월까지 가능하다.\n"
        "```\n"
    )
    assert result == expected


async def test_push_search_without_page_or_breadcrumb_omits_them():
    match = {
        "address": "pages/b7e1f2a9.md",
        "title": "보안 규정",
        "kind": "page",
        "content": "장비 반출은 사전 승인이 필요하다.",
    }
    handler = SearchHandler(FakeFS([match]), SCOPE_ROW)

    result = await handler.search("반출", "*", None, 10)

    expected = (
        "**1건** — `반출`:\n\n"
        "**pages/b7e1f2a9.md** — 보안 규정 "
        f"[보기]({settings.APP_URL}/wiki/D1-D2/pages/b7e1f2a9.md)\n"
        "```\n"
        "장비 반출은 사전 승인이 필요하다.\n"
        "```\n"
    )
    assert result == expected


async def test_push_search_with_no_matches_reports_the_empty_message():
    handler = SearchHandler(FakeFS([]), SCOPE_ROW)

    result = await handler.search("없는말", "*", None, 10)

    assert result == "`없는말`에 해당하는 것이 D1-D2 범위에 없다."


# ---- 목록에 한 줄 요약 (2026-08-05) -----------------------------------------
#
# 목록이 주소와 제목만 주면 에이전트가 어느 페이지가 무엇을 다루는지 검색으로 알아내려 한다.
# 실측(job 33): 라이브 10페이지의 제목만 받은 뒤 `search` 35회, 대부분 0건. 조회 API 는 목록
# 응답에 요약을 이미 실어 주므로(`InternalWikiQueryService.WikiPage.summary`) 공짜다.
# spec: 도구 검토 2026-08-05


def _page(address, title, **extra):
    return {"address": address, "title": title, "kind": "page",
            "category": "경비 정산", **extra}


async def test_the_listing_shows_the_one_line_summary():
    handler = SearchHandler(FakeListFS([
        _page("pages/8300a3625b5f.md", "출장비(여비) 정산 안내",
              summary="교통비 실비 기준, 출장 식비 정액, 법인카드 사용 범위"),
    ]), SCOPE_ROW)

    result = await handler.browse("*", None)

    assert "출장비(여비) 정산 안내" in result
    # 제목만으로는 「출장 식비」가 여기 있다는 것을 알 수 없다. 요약이 그것을 말한다.
    assert "출장 식비 정액" in result


async def test_the_listing_falls_back_to_the_frontmatter_description():
    """작업 층에 방금 쓴 페이지는 조회 API 가 모른다 — 본문 frontmatter 가 유일한 출처다."""
    content = ("---\ntitle: 연차유급휴가 규정\ndescription: 연간 20일 부여, 경과규정\n"
               "tags: [연차]\ncategory: 휴가 정책\n---\n\n본문.\n")
    handler = SearchHandler(FakeListFS([
        _page("pages/new.md", "연차유급휴가 규정", content=content),
    ]), SCOPE_ROW)

    result = await handler.browse("*", None)

    assert "연간 20일 부여, 경과규정" in result


async def test_a_page_without_any_summary_renders_as_before():
    """요약이 없으면 줄이 늘지 않는다 — 형식을 함부로 바꾸지 않는다."""
    handler = SearchHandler(FakeListFS([_page("pages/x.md", "제목만 있는 페이지")]), SCOPE_ROW)

    result = await handler.browse("*", None)

    assert result.rstrip().endswith("pages/x.md — 제목만 있는 페이지")


async def test_browse_notes_the_remainder_when_pages_exceed_max_list():
    """위키 페이지가 `MAX_LIST` 를 넘으면 `sources` 처럼 "... N건 더" 를 남긴다.

    index 가 사라진 지금 `search(mode="list")` 가 에이전트가 범위 안 내용을 보는 유일한
    길이다(설계 §과 이 브랜치의 다른 변경 참고). `sources` 는 이미 잘렸다는 사실을 알리는데
    `pages` 는 알리지 않으면, 위키가 50개를 넘는 범위에서는 "다 보여줬다"는 착각을 준다 —
    보이는 목록이 완전해 보이지만 조용히 잘려 있다.
    """
    docs = [
        {"address": f"pages/page-{n}.md", "kind": "page",
         "title": f"문서 {n}", "category": "인사"}
        for n in range(MAX_LIST + 5)
    ]
    handler = SearchHandler(FakeListFS(docs), SCOPE_ROW)

    result = await handler.browse("*", None)

    assert f"... {len(docs) - MAX_LIST}건 더" in result
    assert f"**위키 ({len(docs)}페이지):**" in result
