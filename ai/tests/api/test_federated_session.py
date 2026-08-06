"""세션이 Wiki 조회 API 로 라이브 층을 채우는 경로를 본다.

**경로는 하나뿐이다** (S15P11B106-175). 요청이 실어 온 `selectedWikis` 로 채우던 push
분기가 사라져 `wikiCapability`·`scopeVersion`·백엔드 주소는 이제 갈림길의 조건이 아니라
없으면 하이드레이션이 실패하는 필수 재료다.

`ScopeChangedError` 전파도 여기서 고정한다. 툴 층이 `except VaultError` 로 문자열을
돌려주므로(`tools/read.py:169` 등) 예외가 세션까지 올라오지 않을 수 있다. 그래서 세션은
**클라이언트가 기억한 것**을 실행 뒤 확인한다.
"""

import httpx
import pytest

from agent_runtime.base import RunResult
from wiki_api.errors import FailureStage, InternalError
from wiki_api.schemas import EditRequest, TransformRequest
from wiki_mcp.vaultfs import FederatedVaultFS, VaultError

SCOPE = "D1-D2"
PAGE_ADDRESS = "pages/a3f2c1d4.md"
PAGES = [{"wikiId": "101", "title": "휴가 규정", "summary": "연차 기준",
          "wikiCategoryId": "9", "categoryName": "휴가 및 근태",
          "wikiPath": f"wiki/{SCOPE}/pages/a3f2c1d4.md", "contentHash": "h1"}]
BODY = "---\ntitle: 휴가 규정\n---\n\n연차는 다음 해 3월까지 이월할 수 있다.\n"


def make_handler(*, version=47, content=200, list_version=None, pages=None):
    """조회 API 엔드포인트 6개를 흉내 내는 MockTransport 핸들러.

    `content=404` 는 「목록에 있던 본문이 사라졌다」다 — 어댑터가 `ScopeChangedError` 로
    닫는 경로(`federated.py._ensure_body`)를 태운다. `pages` 로 목록 응답을 바꿀 수 있다
    (기본은 고정 픽스처 `PAGES`) — 빈 범위(`live_page_count == 0`)를 흉내 낼 때 쓴다.
    """
    page_rows = PAGES if pages is None else pages

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
        if path.endswith("/relations"):
            return httpx.Response(200, json={"scopeVersion": version, "items": []})
        if path.endswith("/wiki-pages"):
            return httpx.Response(200, json={
                "scopeVersion": version if list_version is None else list_version,
                "nextCursor": None, "items": [dict(page) for page in page_rows]})
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


def _runtime_with_arun():
    """브리프가 이름 붙인 헬퍼 — 이 파일의 기존 `InProcessRuntime` 과 같은 일을 한다."""
    return InProcessRuntime()


def _transport_with_pages(pages: list[dict]):
    """브리프가 이름 붙인 헬퍼 — `make_handler(pages=...)` 를 감싼 MockTransport."""
    return httpx.MockTransport(make_handler(pages=pages))


class InProcessRuntime:
    """도구를 이 프로세스에서 도는 런타임 자리. 조회 API 경로가 성립하는 조건은 `arun` 이
    있는 것이다 (`agent_runtime.runs_tools_in_process`)."""

    name = "in-process"

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


def test_transform_request_requires_capability_and_version():
    request = TransformRequest(jobId="42", documentId="15", scopeKey="D1-D2",
                               parsedMarkdown="# 회의\n",
                               wikiCapability="cap-1", scopeVersion=47)

    assert request.wikiCapability == "cap-1"
    assert request.scopeVersion == 47


def test_edit_request_requires_capability_and_version():
    """요청이 위키를 싣지 않으므로 허가값이 없으면 볼 수 있는 위키가 아예 없다.

    `adminInstructionDocumentId` 도 계약이 필수로 정한 필드다 — 관리자 지시를 저장한
    원본문서 ID 이고, 수정의 근거가 그것을 인용해야 한다.
    """
    request = EditRequest(wikiId="101", scopeKey=SCOPE, instruction="줄여줘",
                          wikiCapability="cap-1", scopeVersion=47,
                          adminInstructionDocumentId="817")

    assert (request.wikiCapability, request.scopeVersion) == ("cap-1", 47)


# ---- 경로 ------------------------------------------------------------------


async def test_the_session_always_uses_the_gateway():
    """갈림길이 없다 — 라이브 층의 출처는 언제나 조회 API 다 (S15P11B106-175)."""
    async with federated_session() as session:
        assert isinstance(session.fs, FederatedVaultFS)


async def test_a_missing_backend_url_fails_at_context_load():
    """주소가 없으면 조회 API 를 부를 수 없고, 대신 쓸 push 경로도 없다.

    조용히 빈 위키로 도는 것보다 `context_load` 로 끊는 쪽이 맞다 — 빈 문맥으로 돈
    에이전트는 라이브를 덮는다."""
    session = federated_session(backend_base_url=None)

    with pytest.raises(InternalError) as caught:
        await session.__aenter__()

    assert caught.value.failure_stage is FailureStage.CONTEXT_LOAD
    assert "cap-1" not in caught.value.message


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
    """조회 API 오류를 그대로 올리는 런타임 (in-process)."""

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
    """별도 프로세스 MCP 런타임에서는 위키 요청을 받지 않는다 (fail-closed).

    그 프로세스의 `fs_factory` 가 `LocalVaultFS` 라 조회 API 본문이 에이전트에 닿지 않는다 —
    받으면 제목만 있는 빈 페이지로 라이브를 덮는다. push 대체 경로가 사라졌으므로
    (S15P11B106-175) 이 거절에는 이제 우회가 없다.
    """
    class SpawningRuntime:
        name = "spawning"          # `arun` 이 없다 — 도구가 하위 프로세스에 있다

    session = federated_session(SpawningRuntime())

    with pytest.raises(InternalError) as caught:
        await session.__aenter__()

    assert caught.value.failure_stage is FailureStage.CONTEXT_LOAD
    assert "cap-1" not in caught.value.message


def test_the_judgement_defaults_to_refusing():
    """판정 함수의 기본을 고정한다 — 뒤집히면 가드가 조용히 통과한다.

    불리언 마커가 아니라 `arun` 유무로 보는 이유: 마커는 붙이는 것을 잊을 수 있는데
    `arun` 은 `fs`(살아 있는 객체)를 받는 시그니처라 하위 프로세스 런타임이 애초에
    구현할 수 없다 (S15P11B106-152).
    """
    from agent_runtime import runs_tools_in_process

    assert runs_tools_in_process(object()) is False
    assert runs_tools_in_process(InProcessRuntime()) is True


def test_only_the_deepagents_runtime_can_use_the_gateway():
    """배송 런타임 둘 중 어느 쪽이 조회 API 를 쓸 수 있는지 고정한다."""
    from agent_runtime import runs_tools_in_process
    from agent_runtime.claude_code import ClaudeCodeRuntime

    assert runs_tools_in_process(ClaudeCodeRuntime(model="claude-opus-4-6")) is False
    # deepagents 는 선택 의존성이라 임포트가 실패할 수 있다 — 소스 문자열로 본다.
    import inspect

    from agent_runtime import deep_agents

    assert "async def arun(" in inspect.getsource(deep_agents)


# ---- 빈 범위 게이트 (설계 4.1) -----------------------------------------------


async def test_removal_on_empty_scope_fails_before_the_agent():
    """삭제·교체인데 위키가 0장이면 모순이다 — 에이전트를 돌리기 전에 끊는다."""
    from wiki_api.session import WikiSession

    session = WikiSession(
        scope_key="D1", job_id="job-1", request_id="r1",
        runtime=_runtime_with_arun(), requires_existing_wiki=True,
        wiki_capability="c", scope_version=47,
        backend_base_url="http://backend", internal_api_key="k",
        query_transport=_transport_with_pages([]))
    with pytest.raises(InternalError) as caught:
        async with session:
            pass
    assert caught.value.failure_stage is FailureStage.CONTEXT_LOAD
    assert "위키가 없습니다" in str(caught.value)


async def test_addition_on_empty_scope_is_allowed():
    """신규 범위에 첫 문서를 넣는 것은 정상이다."""
    from wiki_api.session import WikiSession

    session = WikiSession(
        scope_key="D1", job_id="job-1", request_id="r1",
        runtime=_runtime_with_arun(), requires_existing_wiki=False,
        wiki_capability="c", scope_version=47,
        backend_base_url="http://backend", internal_api_key="k",
        query_transport=_transport_with_pages([]))
    async with session:
        assert session.live_page_count == 0


# ---- 라우터 배선 ------------------------------------------------------------


TRANSFORM_BODY = {"jobId": "42", "documentId": "15", "scopeKey": SCOPE,
                  "parsedMarkdown": "# 회의 운영\n\n주간 회의는 30분이다.\n"}


def _spy_client(monkeypatch, *, backend_base_url):
    """`WikiSession` 을 가로채 라우터가 넘긴 인자만 본다.

    조회 API 경로를 HTTP 로 끝까지 태우려면 진짜 백엔드가 필요하다 — 여기서 고정하는 것은
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


def test_router_without_backend_url_passes_none(monkeypatch):
    """주소가 없으면 `None` 이 그대로 세션까지 간다 — 세션이 `context_load` 로 끊는다."""
    client, recorded = _spy_client(monkeypatch, backend_base_url=None)

    client.post("/internal/v1/wiki-transformations",
                json={**TRANSFORM_BODY, "wikiCapability": "cap-1",
                      "scopeVersion": 47},
                headers={"X-Internal-API-Key": "secret-key"})

    assert recorded["backend_base_url"] is None


async def test_the_query_client_is_released_even_when_hydration_fails():
    """조회 API 클라이언트가 만들어진 뒤 하이드레이션이 실패해도 소켓과 잠금을 놓는다.

    `_teardown` 이 `LocalVaultFS.close()` 가지를 남겨 둔 이유이기도 하다 — 클라이언트가
    만들어지기 **전에** 실패하는 경우가 따로 있다."""
    from wiki_api.session import _SESSION_LOCK, WikiSession

    session = WikiSession(
        scope_key=SCOPE, job_id="job-1", runtime=InProcessRuntime(),
        wiki_capability="cap-1", scope_version=47,
        backend_base_url="http://backend.test",
        query_transport=httpx.MockTransport(make_handler(list_version=99)))

    with pytest.raises(InternalError):
        await session.__aenter__()

    assert session._query_client is None
    assert not _SESSION_LOCK.locked()
