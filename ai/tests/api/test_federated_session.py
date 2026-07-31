"""세션이 요청에 따라 push 경로와 창구 경로를 가르는지 본다.

`wikiCapability`·`scopeVersion`·백엔드 주소 **셋이 다 있을 때만** 창구 경로다. 하나라도
없으면 지금 프로덕션 경로(`SpringVaultFS` + 요청이 실어 온 `selectedWikis`)로 돈다 —
창구는 백엔드가 아직 만들지 않았으므로 이 분기가 잘못되면 없는 주소를 부르다 죽는다.

`ScopeChangedError` 전파도 여기서 고정한다. 툴 층이 `except VaultError` 로 문자열을
돌려주므로(`tools/read.py:169` 등) 예외가 세션까지 올라오지 않을 수 있다. 그래서 세션은
**클라이언트가 기억한 것**을 실행 뒤 확인한다 — 마지막 두 테스트가 그 경로다.
"""

import httpx
import pytest

from agent_runtime.base import RunResult
from wiki_api.errors import FailureStage, InternalError
from wiki_api.schemas import SelectedWiki, TransformRequest
from wiki_mcp.vaultfs import FederatedVaultFS, LocalVaultFS, SpringVaultFS, VaultError

SCOPE = "D1-D2"
PAGE_ADDRESS = "pages/a3f2c1d4.md"
PAGES = [{"wikiId": "101", "title": "휴가 규정", "summary": "연차 기준",
          "wikiCategoryId": "9", "categoryName": "휴가 및 근태",
          "wikiPath": f"wiki/{SCOPE}/pages/a3f2c1d4.md", "contentHash": "h1"}]
BODY = "---\ntitle: 휴가 규정\n---\n\n연차는 다음 해 3월까지 이월할 수 있다.\n"


def make_handler(*, version=47, content=200, list_version=None):
    """창구 6개를 흉내 내는 MockTransport 핸들러.

    `content=404` 는 「목록에 있던 본문이 사라졌다」다 — 어댑터가 `ScopeChangedError` 로
    닫는 경로(`federated.py._ensure_body`)를 태운다.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/index"):
            return httpx.Response(200, json={"scopeVersion": version,
                                             "scopeKey": SCOPE,
                                             "indexMarkdown": "# 목차\n"})
        if path.endswith("/categories"):
            return httpx.Response(200, json={"scopeVersion": version,
                                             "items": [{"wikiCategoryId": "9",
                                                        "name": "휴가 및 근태"}]})
        if path.endswith("/wiki-pages"):
            return httpx.Response(200, json={
                "scopeVersion": version if list_version is None else list_version,
                "nextCursor": None, "items": [dict(page) for page in PAGES]})
        if path.endswith("/content"):
            if content == 404:
                return httpx.Response(404, json={"code": "WIKI_NOT_FOUND",
                                                 "message": "없습니다"})
            return httpx.Response(200, json={"scopeVersion": version,
                                             "wikiId": "101", "title": "휴가 규정",
                                             "contentMarkdown": BODY})
        return httpx.Response(200, json={"scopeVersion": version,
                                         "nextCursor": None, "items": []})

    return handler


class InProcessRuntime:
    """MCP 서버를 띄우지 않는 런타임 자리. **표시를 명시적으로 끈다** —
    `agent_runtime.spawns_mcp_server` 의 기본이 `True`(띄운다) 라서, 끄지 않으면
    창구 요청이 거절된다."""

    name = "in-process"
    spawns_mcp_server = False

    async def arun(self, instruction, *, fs, scope_id, **_):
        return RunResult(text="아무것도 하지 않았다")


def federated_session(runtime=None, **overrides):
    from wiki_api.session import WikiSession

    runtime = runtime or InProcessRuntime()
    kwargs = dict(scope_key=SCOPE, job_id="job-1",
                  error_code="WIKI_TRANSFORMATION_FAILED",
                  wiki_capability="cap-1", scope_version=47,
                  backend_base_url="http://backend.test",
                  query_transport=httpx.MockTransport(make_handler()))
    kwargs.update(overrides)
    return WikiSession(runtime=runtime, **kwargs)


@pytest.fixture(autouse=True)
async def _close_index():
    """세션이 실패해도 임시 색인 연결과 카탈로그를 남기지 않는다."""
    yield
    await FederatedVaultFS.close()


# ---- 계약 1.6.0 필드 --------------------------------------------------------


def test_selected_wiki_accepts_wiki_path():
    """계약 1.6.0 이 기존 Wiki 에 wikiPath 를 필수로 했다."""
    wiki = SelectedWiki(wikiId="101", title="휴가 규정",
                        wikiPath="wiki/D1-D2/pages/a3f2c1d4.md",
                        contentMarkdown="# 휴가 규정\n")

    assert wiki.wikiPath == "wiki/D1-D2/pages/a3f2c1d4.md"


def test_selected_wiki_without_wiki_path_is_still_valid():
    """하위 호환. 없으면 링크 관계가 비는 것이 알려진 결함이다 (설계 4.2)."""
    wiki = SelectedWiki(wikiId="101", title="휴가 규정",
                        contentMarkdown="# 휴가 규정\n")

    assert wiki.wikiPath is None


def test_transform_request_accepts_capability_and_version():
    request = TransformRequest(jobId="42", documentId="15", scopeKey="D1-D2",
                               parsedMarkdown="# 회의\n",
                               wikiCapability="cap-1", scopeVersion=47)

    assert request.wikiCapability == "cap-1"
    assert request.scopeVersion == 47


def test_transform_request_without_capability_is_still_valid():
    """선택 필드다. 백엔드가 창구를 배포하기 전에도 계약이 깨지지 않는다."""
    request = TransformRequest(jobId="42", documentId="15", scopeKey="D1-D2",
                               parsedMarkdown="# 회의\n")

    assert request.wikiCapability is None
    assert request.scopeVersion is None


def test_edit_request_accepts_capability_and_version():
    from wiki_api.schemas import EditRequest

    request = EditRequest(wikiId="101", scopeKey=SCOPE, instruction="줄여줘",
                          currentWiki={"title": "휴가 규정",
                                       "contentMarkdown": BODY},
                          wikiCapability="cap-1", scopeVersion=47)

    assert (request.wikiCapability, request.scopeVersion) == ("cap-1", 47)


def test_edit_request_accepts_the_contract_example_verbatim():
    """계약 1.6.0 「Wiki 관리자 수정」요청 예시를 **그대로** 넣는다.

    `Strict` 가 `extra="forbid"` 라서 `currentWiki.wikiPath` 를 받지 않으면 계약 예시를
    그대로 보낸 첫 실호출이 400 이다. 값을 쓰지는 않는다 — 받아만 둔다.
    """
    from wiki_api.schemas import EditRequest

    request = EditRequest(**{
        "wikiId": "100",
        "scopeKey": "D1-D2",
        "instruction": "중복된 휴가 규정을 하나로 정리해줘.",
        "wikiCapability": "cap-1",
        "scopeVersion": 47,
        "currentWiki": {
            "title": "휴가 규정",
            "wikiPath": "wiki/D1-D2/pages/c4d8e1b2.md",
            "contentMarkdown": "# 휴가 규정\n...",
        },
        "evidenceDocuments": [],
        "chatHistory": [],
    })

    assert request.currentWiki.wikiPath == "wiki/D1-D2/pages/c4d8e1b2.md"


# ---- 분기 ------------------------------------------------------------------


async def test_session_uses_spring_vaultfs_without_capability():
    from wiki_api.session import WikiSession

    async with WikiSession(scope_key=SCOPE, job_id="job-1",
                           error_code="WIKI_TRANSFORMATION_FAILED",
                           pages=[], index_markdown="# 목차\n") as session:
        assert isinstance(session.fs, SpringVaultFS)
        assert not type(session.fs).__name__.startswith("Federated")


async def test_session_uses_federated_vaultfs_with_capability():
    """capability·scopeVersion·주소가 다 있으면 창구 경로를 쓴다."""
    async with federated_session() as session:
        assert isinstance(session.fs, FederatedVaultFS)


@pytest.mark.parametrize("missing", ["wiki_capability", "scope_version",
                                     "backend_base_url"])
async def test_one_missing_piece_falls_back_to_push(missing):
    """셋 중 하나만 없어도 push 다. 창구를 부르지 않는다 —
    MockTransport 가 붙어 있어도 요청이 가면 이 테스트가 그것을 잡는다."""
    def explode(request: httpx.Request) -> httpx.Response:
        raise AssertionError(f"push 경로가 창구를 불렀다: {request.url}")

    async with federated_session(**{missing: None},
                                 query_transport=httpx.MockTransport(explode)) \
            as session:
        assert type(session.fs) is SpringVaultFS


async def test_federated_session_closes_the_query_client():
    session = federated_session()
    async with session:
        client = session._query_client
        assert client is not None
    assert session._query_client is None
    assert client._http.is_closed


# ---- ScopeChangedError 전파 -------------------------------------------------


async def test_scope_change_during_hydration_is_scope_changed():
    """하이드레이션 중 버전이 어긋나면 `context_load` 가 아니라 `scope_changed` 다."""
    session = federated_session(
        query_transport=httpx.MockTransport(make_handler(list_version=99)))

    with pytest.raises(InternalError) as caught:
        await session.__aenter__()

    assert caught.value.failure_stage is FailureStage.SCOPE_CHANGED
    # 계약이 마스킹을 요구한다 — 오류 본문에 허가가 되돌아가지 않는다.
    assert "cap-1" not in caught.value.message


class RaisingRuntime(InProcessRuntime):
    """창구 오류를 그대로 올리는 런타임 (in-process)."""

    name = "raising"

    async def arun(self, instruction, *, fs, scope_id, **_):
        await fs.get(scope_id, PAGE_ADDRESS)
        return RunResult(text="여기까지 오면 안 된다")


class SwallowingRuntime(InProcessRuntime):
    """툴 층과 같이 `VaultError` 를 문자열로 흡수하고 계속 도는 런타임.

    `tools/read.py:169` 이 실제로 하는 일이다. 예외가 세션까지 올라오지 않아도
    중단돼야 한다.
    """

    name = "swallowing"

    async def arun(self, instruction, *, fs, scope_id, **_):
        try:
            await fs.get(scope_id, PAGE_ADDRESS)
        except VaultError as exc:
            self.absorbed = f"오류: {exc}"
        return RunResult(text="계속 작업했다")


async def test_scope_change_raised_by_the_agent_is_scope_changed():
    session = federated_session(
        RaisingRuntime(),
        query_transport=httpx.MockTransport(make_handler(content=404)))

    async with session:
        with pytest.raises(InternalError) as caught:
            await session.run_agent("지시", 60)

    assert caught.value.failure_stage is FailureStage.SCOPE_CHANGED


async def test_scope_change_swallowed_by_a_tool_still_stops_the_run():
    """툴이 문자열로 흡수해도 세션이 클라이언트 상태를 보고 중단한다."""
    runtime = SwallowingRuntime()
    session = federated_session(
        runtime,
        query_transport=httpx.MockTransport(make_handler(content=404)))

    async with session:
        with pytest.raises(InternalError) as caught:
            await session.run_agent("지시", 60)

    assert runtime.absorbed.startswith("오류:")
    assert caught.value.failure_stage is FailureStage.SCOPE_CHANGED


async def test_gateway_is_refused_for_subprocess_runtimes():
    """별도 프로세스 MCP 런타임에서는 창구 요청을 받지 않는다 (fail-closed).

    그 프로세스의 `fs_factory` 가 `LocalVaultFS` 라 창구 본문이 에이전트에 닿지 않는다 —
    받으면 제목만 있는 빈 페이지로 라이브를 덮는다.
    """
    class SpawningRuntime:
        name = "spawning"
        spawns_mcp_server = True

    session = federated_session(SpawningRuntime())

    with pytest.raises(InternalError) as caught:
        await session.__aenter__()

    assert caught.value.failure_stage is FailureStage.CONTEXT_LOAD
    assert "cap-1" not in caught.value.message


async def test_push_path_is_untouched_for_subprocess_runtimes():
    """가드는 창구 요청만 막는다. push 는 그 런타임으로 계속 돈다."""
    from wiki_api.session import WikiSession

    class SpawningRuntime:
        name = "spawning"
        spawns_mcp_server = True

    async with WikiSession(scope_key=SCOPE, job_id="job-1", pages=[],
                           runtime=SpawningRuntime()) as session:
        assert isinstance(session.fs, SpringVaultFS)
    await LocalVaultFS.close()


async def test_a_runtime_without_the_marker_is_refused():
    """**표시를 잊은 런타임도 거절한다.** 기본값이 fail-open 이면 앞으로 추가되는 런타임이
    상수를 안 붙인 채 창구 요청을 통과시키고, 그 결과는 데이터 손실 방향이다."""
    class UnmarkedRuntime:
        name = "unmarked"

        async def arun(self, instruction, **_):
            return RunResult(text="")

    assert not hasattr(UnmarkedRuntime, "spawns_mcp_server")
    session = federated_session(UnmarkedRuntime())

    with pytest.raises(InternalError) as caught:
        await session.__aenter__()

    assert caught.value.failure_stage is FailureStage.CONTEXT_LOAD


def test_the_marker_defaults_to_spawning():
    """판정 함수의 기본값 자체를 고정한다 — 뒤집히면 가드가 조용히 통과한다."""
    from agent_runtime import spawns_mcp_server

    assert spawns_mcp_server(object()) is True
    assert spawns_mcp_server(InProcessRuntime()) is False


def test_shipping_runtimes_declare_that_they_spawn_a_server():
    """가드가 의존하는 표시가 실제 런타임 클래스에 있는지 본다 — 이름을 지우면
    기본값이 받아 주지만(`True`), 성질을 명시해 둔 것이 사라진 것은 알아야 한다."""
    from agent_runtime.claude_code import ClaudeCodeRuntime

    assert ClaudeCodeRuntime.spawns_mcp_server is True
    # deepagents 는 선택 의존성이라 임포트가 실패할 수 있다 — 소스 문자열로 본다.
    import inspect

    from agent_runtime import deep_agents

    assert "spawns_mcp_server = True" in inspect.getsource(deep_agents)


# ---- 라우터 배선 ------------------------------------------------------------


TRANSFORM_BODY = {"jobId": "42", "documentId": "15", "scopeKey": SCOPE,
                  "parsedMarkdown": "# 회의 운영\n\n주간 회의는 30분이다.\n",
                  "currentIndex": "# 목차\n"}


def _spy_client(monkeypatch, *, backend_base_url):
    """`WikiSession` 을 가로채 라우터가 넘긴 인자만 본다.

    창구 경로를 HTTP 로 끝까지 태우려면 진짜 백엔드가 필요하다 — 여기서 고정하는 것은
    「라우터가 계약 필드를 세션까지 옮긴다」뿐이다.
    """
    from fastapi.testclient import TestClient

    from wiki_api.app import create_app
    from wiki_api.routers import wiki as router_module

    recorded: dict = {}

    class SpySession:
        def __init__(self, **kwargs):
            recorded.update(kwargs)

        async def __aenter__(self):
            raise InternalError("WIKI_TRANSFORMATION_FAILED", "여기서 멈춘다",
                                FailureStage.CONTEXT_LOAD)

        async def __aexit__(self, *_exc):
            return False

    monkeypatch.setattr(router_module, "WikiSession", SpySession)
    app = create_app(api_key="secret-key", backend_base_url=backend_base_url)
    app.state.runtime = None
    return TestClient(app, raise_server_exceptions=False), recorded


def test_router_passes_capability_and_backend_url(monkeypatch):
    client, recorded = _spy_client(monkeypatch,
                                   backend_base_url="http://backend.test")

    response = client.post("/internal/v1/wiki-transformations",
                           json={**TRANSFORM_BODY, "wikiCapability": "cap-1",
                                 "scopeVersion": 47},
                           headers={"X-Internal-API-Key": "secret-key"})

    assert recorded["wiki_capability"] == "cap-1"
    assert recorded["scope_version"] == 47
    assert recorded["backend_base_url"] == "http://backend.test"
    assert recorded["internal_api_key"] == "secret-key"
    # 마스킹 — 허가가 응답 본문으로 되돌아가지 않는다.
    assert "cap-1" not in response.text


def test_router_without_backend_url_cannot_reach_the_gateway(monkeypatch):
    """주소가 없으면 capability 가 와도 push 다 — 배포 전 프로덕션의 상태다."""
    client, recorded = _spy_client(monkeypatch, backend_base_url=None)

    client.post("/internal/v1/wiki-transformations",
                json={**TRANSFORM_BODY, "wikiCapability": "cap-1",
                      "scopeVersion": 47},
                headers={"X-Internal-API-Key": "secret-key"})

    assert recorded["backend_base_url"] is None


async def test_push_session_has_no_query_client():
    """push 경로에는 확인할 클라이언트가 없다 — 그 경로가 이 검사로 느려지거나
    깨지지 않는다."""
    from wiki_api.session import WikiSession

    async with WikiSession(scope_key=SCOPE, job_id="job-1", pages=[],
                           runtime=SwallowingRuntime()) as session:
        assert session._query_client is None
        assert isinstance(session.fs, SpringVaultFS)
    await LocalVaultFS.close()
