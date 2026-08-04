"""위키 변환의 조회 API 호출 횟수를 고정한다 (S15P11B106-175, 설계 §7).

실모델 측정은 한 번 찍는 숫자다. **회귀를 계속 지키는 것은 여기다.** 세 가지를 박는다 —
하이드레이션이 조회 API 를 몇 번 부르는지, 삭제 재조정이 인용 위키만 당기는지, 역링크 조회가
페이지별 조회 API 호출을 만들지 않는지.

**횟수는 계약이고 순서는 아니다.** 계약이 정하는 것은 「위키 장수와 무관하게 상수 회」이므로
단언도 그것에 걸어 둔다. 하이드레이션 구현 순서가 바뀌면 순서 단언이 아니라 구현을 따라간다.

픽스처는 `test_api_wiki.py` 것을 그대로 가져온다 — 가짜 조회 API 를 두 번 만들면 둘이 갈리고,
갈린 쪽이 조용히 낡는다.

**모델을 대본화하지 않는다.** 여기서 재는 것은 조회 API 호출 수이고 그것을 만드는 것은 라우터의
재조정과 `FederatedVaultFS` 의 지연 적재다 — 둘 다 모델 판단 밖이다. `ScriptedChatModel` 로
루프를 돌리면 도구 선택이 대본에 따라 흔들려 호출 수가 같이 흔들리고, 그러면 이 파일이
재려던 상수가 측정 불가능해진다. 대신 **재려는 경로만 정확히 부르는 런타임**을 세운다.
루프·도구 실행 자체는 `test_api_wiki.py` 의 `FakeRuntime` 계열이 덮는다.
"""

from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from agent_runtime.base import RunResult
from wiki_api.app import create_app

from .test_api_wiki import (API_KEY, BACKEND_URL, LEGACY_PAGE, LIVE_PAGE, REQUEST,
                            SCOPE, SOURCE_MD, make_gateway, request_with)

# 이번 요청과 무관한 세 번째 라이브 페이지. 「장수가 늘어도 하이드레이션은 상수 회」를
# 보이려면 픽스처의 두 장으로는 부족하다 — 2 회와 상수를 구분할 수 없다.
THIRD_PAGE = {
    "wikiId": "103", "title": "복지 안내", "summary": "사내 복지",
    "categoryName": "근무 정책", "contentHash": "h103",
    "contentMarkdown": "---\ntitle: 복지 안내\ncategory: 근무 정책\n---\n\n복지는 이렇다.\n",
}

THREE_PAGES = [LIVE_PAGE, LEGACY_PAGE, THIRD_PAGE]

# 조회 API 가 주는 범위 전체 간선. 라우터·카탈로그가 이것을 뒤집어 두 질문에 답한다 —
# 「문서 15 를 인용한 위키는?」(101 하나) 과 「101 을 가리키는 위키는?」(103 하나).
SCOPE_RELATIONS = [
    {"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]},
    {"wikiId": "102", "wikiRefs": [], "documentRefs": ["99"]},
    {"wikiId": "103", "wikiRefs": ["101"], "documentRefs": []},
]

HYDRATION_PATHS = {
    f"/internal/v1/wiki-spaces/{SCOPE}/categories",
    "/internal/v1/wiki-pages",
    f"/internal/v1/wiki-spaces/{SCOPE}/relations",
    f"/internal/v1/wiki-spaces/{SCOPE}/index",
}

# 하이드레이션이 아니라 **이번 문서 1건**의 파일명 조회다 (S15P11B106-245). 각주가 파일명으로
# 문서를 가리키는데 요청이 그것을 싣지 않아 여기서 받아 온다. 위키 장수와 무관한 상수 1회다.
SOURCE_NAME_PATH = f"/internal/v1/documents/{REQUEST['documentId']}/parsed"


def recording_gateway(paths: list[str], **kwargs) -> httpx.MockTransport:
    """`make_gateway` 를 감싸 호출 경로를 순서대로 `paths` 에 적는다."""
    inner = make_gateway(**kwargs)

    def handler(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        return inner.handler(request)

    return httpx.MockTransport(handler)


class QuietRuntime:
    """툴을 하나도 부르지 않고 읽은 것으로 신고만 하는 런타임.

    조회 API 를 부르지 않으므로 남는 호출은 **라우터와 하이드레이션이 만든 것뿐**이다. 그것이
    하이드레이션 상수와 삭제 재조정을 재는 데 필요한 조건이다. `tool_calls` 에 `read` 를
    적는 것은 4.2 의 「읽지 않고 썼다」 검사 때문이고, 쓰기가 0 이라 그 검사 대상도 아니다.
    """

    name = "quiet"

    def __init__(self):
        self.instructions: list[str] = []

    async def arun(self, instruction, *, fs, scope_id, **_) -> RunResult:  # noqa: ARG002
        self.instructions.append(instruction)
        return RunResult(text="변경 없음", tool_calls={"guide": 1, "search": 1, "read": 1})


class BacklinkReadingRuntime(QuietRuntime):
    """역링크를 실제로 조회하는 런타임 — `merge`·`delete`·`lint` 가 타는 경로다.

    `write.py` 의 `merge` 와 `lint.py` 의 삭제 검사가 페이지마다 `fs.get_backlinks` 를
    부른다. 예전에는 그 한 번이 캐시 없는 `/wikis/{id}/relations` 호출 하나였다 — 그
    항이 사라진 것을 재려면 이 함수를 실제로 불러야 한다. 부르지 않고 「0회다」를
    단언하면 아무것도 지키지 않는 빈 테스트가 된다.
    """

    def __init__(self):
        super().__init__()
        self.backlinks: dict[str, list[str]] = {}

    async def arun(self, instruction, *, fs, scope_id, **_) -> RunResult:
        self.instructions.append(instruction)
        for doc in await fs.list_documents(scope_id):
            if doc["kind"] != "page":
                continue
            rows = await fs.get_backlinks(scope_id, doc["address"])
            self.backlinks[doc["address"]] = [row["address"] for row in rows]
        return RunResult(text="변경 없음", tool_calls={"guide": 1, "search": 1, "read": 1})


@pytest.fixture
def call_paths():
    return []


@pytest.fixture
def post(call_paths):
    def send(body=None, *, runtime=None, **gateway_kwargs):
        app = create_app(api_key=API_KEY, backend_base_url=BACKEND_URL)
        app.state.runtime = runtime or QuietRuntime()
        app.state.query_transport = recording_gateway(call_paths, **gateway_kwargs)
        client = TestClient(app, raise_server_exceptions=False)
        return client.post("/internal/v1/wiki-transformations", json=body or REQUEST,
                           headers={"X-Internal-API-Key": API_KEY})

    return send


def test_hydration_calls_the_gateway_once_per_kind(post, call_paths):
    """카탈로그·목차·카테고리·범위 관계에 각주용 파일명 하나. **위키 장수와 무관하게 각 1회다.**

    이 상수가 조회 API 전환의 핵심이다. 장수에 비례하면 100장짜리 범위에서 하이드레이션만으로
    수백 회가 나가고, 그때는 지연 적재(S15P11B106-151)가 무의미해진다.

    5회째는 하이드레이션이 아니라 이번 문서의 파일명 조회다(`SOURCE_NAME_PATH`) — 문서 1건당
    1회이므로 이 불변식을 깨지 않는다. 늘어난 것이 이 하나임을 여기서 못박는다.
    """
    assert post(pages=THREE_PAGES).status_code == 200, call_paths

    assert set(call_paths) == HYDRATION_PATHS | {SOURCE_NAME_PATH}, call_paths
    assert len(call_paths) == 5, call_paths


def test_hydration_does_not_pull_bodies(post, call_paths):
    """하이드레이션은 본문을 당기지 않는다 — 지연 적재가 살아 있다는 뜻이다."""
    assert post(pages=THREE_PAGES).status_code == 200

    assert not [path for path in call_paths if path.endswith("/content")], call_paths


def test_removal_pulls_only_citing_bodies(post, call_paths):
    """위키 3장 중 1장만 인용했으면 본문 조회는 1회다 (설계 §3.1).

    범위 전체 간선을 뒤집어 인용한 위키를 고르므로, 전 본문을 당기는 안(3회)과 갈린다.
    """
    response = post(
        request_with(changeType="document_removed", parsedMarkdown="",
                     removedParsedMarkdown=SOURCE_MD),
        pages=THREE_PAGES, relations=SCOPE_RELATIONS)

    assert response.status_code == 200, response.json()
    content_calls = [path for path in call_paths if path.endswith("/content")]
    assert content_calls == ["/internal/v1/wikis/101/content"], call_paths


def test_backlinks_come_from_the_catalog_not_a_per_page_call(post, call_paths):
    """역링크는 카탈로그에서 읽는다 — 페이지별 `relations` 호출은 0회다 (Task 3).

    예전에는 `get_backlinks` 의 원격 보강이 캐시 없이 **부를 때마다**
    `/wikis/{id}/relations` 를 불렀다. 그 항이 통째로 없어진 것을 지킨다.

    **답이 맞는 것까지 본다.** 호출이 0회인 것만 보면 「역링크가 아예 안 나온다」와
    구분되지 않는다 — 101 을 가리키는 것이 둘 있어야 한다. 목차는 링크가 본문에 있어
    `sync_references` 가 넣고(S15P11B106-151 이 목차만 남긴 그 경로), 103 은 본문을 당기지
    않았는데도 카탈로그 뒤집기가 넣는다. **후자가 이 태스크가 만든 것이다.**
    """
    runtime = BacklinkReadingRuntime()
    assert post(pages=THREE_PAGES, relations=SCOPE_RELATIONS,
                runtime=runtime).status_code == 200, call_paths

    assert sorted(runtime.backlinks["pages/101.md"]) == ["index.md", "pages/103.md"], \
        runtime.backlinks
    # 나머지를 가리키는 것은 없다 — 뒤집기가 간선을 넓게 흘리지 않는다.
    assert runtime.backlinks["pages/102.md"] == [], runtime.backlinks
    assert runtime.backlinks["pages/103.md"] == [], runtime.backlinks

    per_page = [path for path in call_paths
                if path.startswith("/internal/v1/wikis/") and path.endswith("/relations")]
    assert per_page == [], call_paths
