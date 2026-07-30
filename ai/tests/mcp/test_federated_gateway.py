"""가짜 창구 게이트웨이. 백엔드가 창구를 만들기 전 붙여보기 위한 것이다."""

import sys
from pathlib import Path

import httpx
import pytest

from wiki_mcp.vaultfs import FederatedVaultFS
from wiki_mcp.vaultfs.query_client import (QUERY_CALL_BUDGET_BASE,
                                           QUERY_CALLS_PER_PAGE, QueryNotFound,
                                           ScopeChangedError, WikiQueryClient)

# `experiments/` 는 패키지가 아니라 sys.path 에 얹어야 임포트된다. 그래서 이 한 줄만
# 임포트 사이에 남는다 — 위로 올리면 E402 를 못 없애고, 아래 임포트를 위로 올리면
# 경로가 없어 실패한다.
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "experiments"))

from query_gateway import build_gateway  # noqa: E402

CAPABILITY = "cap-test"
API_KEY = "key-test"


@pytest.fixture
def corpus(tmp_path):
    pages = tmp_path / "wiki" / "D1-D2" / "pages"
    pages.mkdir(parents=True)
    (pages / "a3f2c1d4.md").write_text(
        "---\ntitle: 휴가 규정\ndescription: 연차와 반차 사용 기준\n---\n\n"
        "연차는 다음 해 3월까지 이월할 수 있다.\n", encoding="utf-8")
    (pages / "b7e1f2a9.md").write_text(
        "---\ntitle: 보안 규정\ndescription: 장비 반출 기준\n---\n\n"
        "장비 반출은 사전 승인이 필요하다. [휴가 규정](pages/a3f2c1d4.md)\n",
        encoding="utf-8")
    (tmp_path / "wiki" / "D1-D2" / "index.md").write_text(
        "# 목차\n\n- [휴가 규정](pages/a3f2c1d4.md) — 연차와 반차 사용 기준\n",
        encoding="utf-8")
    return tmp_path


@pytest.fixture
def gateway(corpus):
    return build_gateway(corpus, scope_key="D1-D2", capability=CAPABILITY,
                         api_key=API_KEY)


def _client(app) -> httpx.AsyncClient:
    return httpx.AsyncClient(base_url="http://gateway.test",
                             transport=httpx.ASGITransport(app=app))


async def test_pages_returns_catalog_without_bodies(gateway):
    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-pages",
                                  params={"scopeKey": "D1-D2"},
                                  headers={"X-Internal-API-Key": API_KEY,
                                           "X-Wiki-Capability": CAPABILITY})
    body = response.json()

    assert response.status_code == 200
    assert body["scopeVersion"] == 47
    assert {item["wikiId"] for item in body["items"]} == {"1", "2"}
    assert all("contentMarkdown" not in item for item in body["items"])
    assert any(item["wikiPath"].endswith("pages/a3f2c1d4.md")
               for item in body["items"])


async def test_missing_capability_is_404(gateway):
    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-pages",
                                  params={"scopeKey": "D1-D2"},
                                  headers={"X-Internal-API-Key": API_KEY})

    assert response.status_code == 404


async def test_other_scope_is_404_not_403(gateway):
    """허가 범위 밖은 존재를 노출하지 않는다 (NFR-SEC-003 · FR-ACL-006)."""
    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-pages",
                                  params={"scopeKey": "D9"},
                                  headers={"X-Internal-API-Key": API_KEY,
                                           "X-Wiki-Capability": CAPABILITY})

    assert response.status_code == 404


async def test_search_finds_korean_term(gateway):
    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-search",
                                  params={"scopeKey": "D1-D2",
                                          "query": "연차", "limit": "5"},
                                  headers={"X-Internal-API-Key": API_KEY,
                                           "X-Wiki-Capability": CAPABILITY})
    items = response.json()["items"]

    assert items
    assert items[0]["title"] == "휴가 규정"


async def test_scope_version_can_be_bumped(gateway):
    gateway.state.gateway.scope_version = 48

    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-spaces/D1-D2/index",
                                  headers={"X-Internal-API-Key": API_KEY,
                                           "X-Wiki-Capability": CAPABILITY})

    assert response.json()["scopeVersion"] == 48


# ---- 설계 9.2 검증 6항목 ------------------------------------------------------


@pytest.fixture
async def wired(gateway, tmp_path):
    """게이트웨이에 어댑터를 붙인다. 실제 HTTP 스택을 ASGI 로 통과한다."""
    client = WikiQueryClient(
        "http://gateway.test", api_key=API_KEY, capability=CAPABILITY,
        scope_key="D1-D2", scope_version=gateway.state.gateway.scope_version,
        request_id="req-42",
        transport=httpx.ASGITransport(app=gateway))
    root = tmp_path / "work"
    root.mkdir()
    scope_id = await FederatedVaultFS.open(root, "D1-D2", "job-1",
                                           client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    yield fs, scope_id, client, gateway
    await client.aclose()
    await FederatedVaultFS.close()


async def test_check1_existing_page_is_addressable(wired):
    """1. 기존 페이지를 고치는가 — 주소가 파일명과 같아야 새로 만들지 않는다.

    `is not None` 만으로는 부족하다. 하이드레이션이 본문 `""` 로 행을 먼저 넣으므로
    주소가 잡힌 것과 본문이 실제로 온 것을 구분하지 못한다. 둘 다 본다.
    """
    fs, scope_id, _, _ = wired

    page = await fs.get(scope_id, "pages/a3f2c1d4.md")

    assert page is not None
    assert page["title"] == "휴가 규정"
    assert "다음 해 3월까지 이월" in page["content"]


async def test_check2_wiki_links_resolve(wired):
    """2. 라이브 본문의 위키 링크가 해석되는가 — 설계 4절 wikiPath 판정."""
    fs, scope_id, _, _ = wired

    # b7e1f2a9 본문이 pages/a3f2c1d4.md 를 링크한다. 본문을 당겨야 간선이 생긴다.
    await fs.get(scope_id, "pages/b7e1f2a9.md")
    backlinks = await fs.get_backlinks(scope_id, "pages/a3f2c1d4.md")

    assert any(row["address"] == "pages/b7e1f2a9.md" for row in backlinks)


async def test_check3_search_has_both_layers(wired):
    """3. 검색에 라이브와 작업층이 모두 나오는가."""
    fs, scope_id, _, _ = wired

    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address,
                   "---\ntitle: 연차 초안\n---\n\n연차 이월 초안이다.\n",
                   title="연차 초안")
    rows = await fs.search_chunks(scope_id, "연차", limit=10)

    assert {row["origin"] for row in rows} == {"live", "work"}


async def test_check4_version_bump_stops_work(wired):
    """4. 버전을 바꿔 두면 멈추는가 — scope_changed 로 이어질 예외."""
    fs, scope_id, _, gateway_app = wired

    gateway_app.state.gateway.scope_version = 48

    with pytest.raises(ScopeChangedError):
        await fs.search_chunks(scope_id, "연차", limit=5)


async def test_check5_other_scope_is_not_found(wired):
    """5. 허가 범위 밖이 404 인가."""
    _, _, client, _ = wired
    client.scope_key = "D9"

    with pytest.raises(QueryNotFound):
        await client.list_pages()


async def test_check6_body_fetched_once(wired):
    """6. 같은 본문을 두 번 받지 않는가."""
    fs, scope_id, client, _ = wired

    await fs.get(scope_id, "pages/a3f2c1d4.md")
    await fs.get(scope_id, "pages/a3f2c1d4.md")
    await fs.search_chunks(scope_id, "연차", limit=5)

    assert client.body_fetches == 1


async def test_hydration_scales_the_call_budget(wired):
    """하이드레이션이 카탈로그 크기를 조회 예산에 반영한다.

    고정 예산은 링크 있는 위키에서 정상 `read` 한 번을 죽였다 — `read` 한 번이 링크
    연결 성분 전체를 당기므로 소비량이 위키 장수에 비례한다. 사슬 링크 100장에서
    read 1회가 창구 100회였다 (INDEX.md 「창구 어댑터 검색 응답」). 배선이 끊기면
    예산이 다시 고정값이 되므로 여기서 붙잡는다.
    """
    _, _, client, gateway = wired
    pages = len(gateway.state.gateway.pages)

    assert pages > 0
    assert client.catalog_pages == pages
    assert client.call_budget == (QUERY_CALL_BUDGET_BASE
                                  + QUERY_CALLS_PER_PAGE * pages)
