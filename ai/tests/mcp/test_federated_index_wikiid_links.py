"""공간 목차가 wikiId 링크를 쓸 때 하이드레이션 회귀.

실제 백엔드(`WikiIndex.java`)는 목차를 **항상 `pages/{wikiId}.md`** 로 링크한다. 그런데
FederatedVaultFS 는 페이지를 `wikiPath`(pageKey/해시) 주소로 깐다 — 본문 교차링크가 pageKey
기준이라서다. 두 링크 체계가 한 vault 에서 만나면 목차 링크가 페이지 주소와 어긋나
`lint` 가 dangling-link 로 죽는다. 실제로 두 번째 문서부터 잡이 실패했다.

기존 `test_federated_vaultfs.py` 는 가짜 목차가 **해시 링크**를 써서 이 불일치를 숨겼다.
여기서는 실제와 같은 **wikiId 링크** 목차로 재현한다.
"""

import pytest

from wiki_mcp.tools.lint import LintHandler
from wiki_mcp.vaultfs import INDEX_ADDRESS, FederatedVaultFS, LocalVaultFS

SCOPE = "D1-D2"

PAGES = [
    {"wikiId": "101", "title": "휴가 규정", "summary": "연차와 반차 사용 기준",
     "wikiCategoryId": "9", "categoryName": "휴가 및 근태",
     "wikiPath": "wiki/D1-D2/pages/a3f2c1d4.md", "contentHash": "h1",
     "updatedAt": "2026-07-27T09:00:00Z"},
]

# 실제 백엔드처럼 wikiId 로 링크한다.
INDEX_WIKIID_LINKS = "# 목차\n\n- [휴가 규정](pages/101.md) — 연차와 반차 사용 기준\n"


class FakeClient:
    def __init__(self):
        self.scope_key = SCOPE
        self.scope_version = 47
        self.catalog_pages = 0

    def note_catalog_size(self, pages):
        self.catalog_pages = pages

    def note_scope_change(self, error):
        return error

    async def list_pages(self):
        return [dict(page) for page in PAGES]

    async def index_markdown(self):
        return INDEX_WIKIID_LINKS

    async def categories(self):
        return [{"wikiCategoryId": "9", "name": "휴가 및 근태", "wikiCount": 1}]

    async def page_content(self, wiki_id):
        page = next(p for p in PAGES if p["wikiId"] == wiki_id)
        return {"scopeVersion": 47, "wikiId": wiki_id, "title": page["title"],
                "wikiPath": page["wikiPath"],
                "contentMarkdown": "---\ntitle: 휴가 규정\n---\n\n연차 규정.\n",
                "contentHash": page["contentHash"]}

    async def relations(self, wiki_id):
        return {"scopeVersion": 47, "wikiId": wiki_id,
                "wikiRefs": [], "documentRefs": [], "backlinks": []}

    async def scope_relations(self):
        # 하이드레이션이 범위 간선을 1회 당겨 역링크·삭제 재조정·근거 문서 표를
        # 채운다 (S15P11B106-175). 이 회귀는 목차 링크만 보므로 간선은 비운다.
        return []

    async def aclose(self):
        return None


@pytest.fixture
async def federated(tmp_path):
    client = FakeClient()
    scope_id = await FederatedVaultFS.open(tmp_path, SCOPE, "job-1", client=client)
    fs = FederatedVaultFS(SCOPE, "job-1", client)
    yield fs, scope_id
    await LocalVaultFS.close()


async def test_wikiid_index_links_do_not_dangle_when_agent_rewrites_index(federated):
    """에이전트가 하이드레이션된 목차를 읽어 다시 쓰면 그 목차는 작업 층으로 들어가
    전체 dangling 검사를 받는다. 목차 링크가 실제 페이지 주소와 맞아야 통과한다."""
    fs, scope_id = federated
    scope_row = {"id": scope_id, "scope_key": SCOPE,
                 "index_path": f"wiki/{SCOPE}/index.md"}

    # 에이전트는 하이드레이션된 목차를 그대로 읽어 (항목을 더해) 다시 쓴다.
    index_doc = await fs.get(scope_id, INDEX_ADDRESS)
    await fs.write(scope_id, INDEX_ADDRESS, index_doc["content"] or "",
                   title="위키 목차", category="목차")

    report = await LintHandler(fs, scope_row).run("*")

    assert "dangling-link" not in report, report
