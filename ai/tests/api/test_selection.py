"""1단계 문맥 선택 — `POST /internal/v1/wiki-context-selections` (설계 §5).

여기서 확인하는 것은 모델의 안목이 아니라 **파싱의 방어력**이다. Spring 은 이 응답의
ID 로 본문을 읽어 2단계 변환에 싣는다 — 지어낸 ID 가 새면 없는 위키를 조회하거나 다른
범위의 위키를 읽는다. 그래서 목차에 실재하는 ID 만, 중복 없이, 5개까지.

선택 품질 자체는 미측정이다 (설계 §7 의 리스크). 여기서는 배관만 본다.
"""

from fastapi.testclient import TestClient

from wiki_api.app import create_app
from wiki_api.selection import MAX_WIKIS, index_wiki_ids, parse_selection
from agent_runtime.base import RunResult, selection_instruction

API_KEY = "secret-key"
SCOPE = "D1-D2"

INDEX_MD = (
    "# 목차\n\n"
    "- [휴가 규정](pages/101.md) — 연차와 반차 사용 기준\n"
    "- [근로시간](pages/102.md) — 소정근로시간과 연장근로\n"
    "- [커뮤니케이션 가이드](pages/103.md) — 비동기 우선 소통\n"
    "- [보안 정책](pages/104.md) — 계정과 접근 권한\n"
    "- [경비 정산](pages/105.md) — 법인카드와 영수증\n"
    "- [온보딩](pages/106.md) — 입사 첫 주\n"
)
SOURCE_MD = "# 취업 규칙\n\n## 3장 휴가\n\n연차는 입사일을 기준으로 산정한다.\n"

REQUEST = {
    "jobId": "42",
    "documentId": "15",
    "scopeKey": SCOPE,
    "parsedMarkdown": SOURCE_MD,
    "currentIndex": INDEX_MD,
}


class FakeRuntime:
    """MCP 없는 단발 호출을 받는 런타임 (`Runtime.complete`, base.py 주석)."""

    name = "fake-select"

    def __init__(self, text: str, error: str | None = None):
        self.text = text
        self.error = error
        self.prompts: list[str] = []

    def complete(self, prompt, *, timeout=None) -> RunResult:
        self.prompts.append(prompt)
        return RunResult(text=self.text, error=self.error)


class ArunOnlyRuntime:
    """`complete` 가 없는 async 런타임 — `arun` 으로 물러서는 경로."""

    name = "fake-select-arun"

    def __init__(self, text: str):
        self.text = text
        self.kwargs: dict = {}
        self.root_contents: list | None = None

    async def arun(self, prompt, **kwargs) -> RunResult:
        self.kwargs = kwargs
        # 임시 루트는 호출이 끝나면 지워진다 — 안에서 봐야 한다.
        self.root_contents = list(kwargs["root"].iterdir())
        return RunResult(text=self.text)


class SyncOnlyRuntime:
    """`run` 만 가진 런타임 (두 프로덕션 런타임이 그렇다)."""

    name = "fake-select-sync"

    def __init__(self, text: str):
        self.text = text
        self.kwargs: dict = {}

    def run(self, prompt, **kwargs) -> RunResult:
        self.kwargs = kwargs
        return RunResult(text=self.text)


class HangingRuntime:
    """`complete` 가 async 이면서 자기에게 넘어온 `timeout` 을 무시하는 런타임."""

    name = "fake-select-hang"

    async def complete(self, prompt, *, timeout=None):
        import asyncio

        await asyncio.sleep(30)
        return RunResult(text='{"wikiIds": ["101"]}')


def _client(runtime):
    app = create_app(api_key=API_KEY)
    app.state.runtime = runtime
    return TestClient(app, raise_server_exceptions=False)


def _post(runtime, body=None):
    return _client(runtime).post("/internal/v1/wiki-context-selections",
                                 json=body or REQUEST,
                                 headers={"X-Internal-API-Key": API_KEY})


# ----- 파싱 -------------------------------------------------------------------


def test_index_ids_come_from_the_page_links():
    assert index_wiki_ids(INDEX_MD) == ["101", "102", "103", "104", "105", "106"]


def test_json_response_is_parsed():
    ids, reason = parse_selection(
        '{"wikiIds": ["101", "103"], "reason": "휴가 항목이 겹친다"}', INDEX_MD)
    assert ids == ["101", "103"]
    assert reason == "휴가 항목이 겹친다"


def test_ids_absent_from_the_index_are_dropped():
    """모델이 ID 를 지어낼 수 있다. 목차에 없는 것은 우리가 먼저 버린다 — Spring 이
    없는 위키를 조회하거나 다른 범위를 읽는 사고를 막는다."""
    ids, _ = parse_selection('{"wikiIds": ["101", "999", "abc"]}', INDEX_MD)
    assert ids == ["101"]


def test_duplicates_are_removed_and_order_is_kept():
    ids, _ = parse_selection('{"wikiIds": ["103", "101", "103"]}', INDEX_MD)
    assert ids == ["103", "101"]


def test_more_than_five_is_truncated():
    ids, _ = parse_selection(
        '{"wikiIds": ["101","102","103","104","105","106"]}', INDEX_MD)
    assert ids == ["101", "102", "103", "104", "105"]
    assert len(ids) == MAX_WIKIS


def test_an_empty_selection_is_valid():
    ids, reason = parse_selection('{"wikiIds": [], "reason": "관련 위키 없음"}', INDEX_MD)
    assert ids == []
    assert reason == "관련 위키 없음"


def test_json_wrapped_in_prose_or_a_code_fence_still_parses():
    text = '고민한 결과입니다.\n```json\n{"wikiIds": ["102"], "reason": "근로시간"}\n```\n'
    ids, reason = parse_selection(text, INDEX_MD)
    assert ids == ["102"]
    assert reason == "근로시간"


def test_free_text_falls_back_to_scanning_for_index_ids():
    """파싱 실패로 선택을 통째로 버리면 2단계가 문맥 없이 돌고 에이전트가 이미 있는
    페이지를 새로 만든다 — 자유 텍스트에서도 줍는다."""
    ids, _ = parse_selection("101번과 104번 위키를 읽으면 됩니다. 999는 없습니다.", INDEX_MD)
    assert ids == ["101", "104"]


def test_an_empty_index_yields_nothing():
    ids, _ = parse_selection('{"wikiIds": ["101"]}', "")
    assert ids == []


# ----- 프롬프트 ---------------------------------------------------------------


def test_the_prompt_carries_the_index_and_the_document():
    text = selection_instruction(SOURCE_MD, INDEX_MD)
    assert "pages/101.md" in text
    assert "연차는 입사일을 기준으로 산정한다" in text
    assert "wikiIds" in text


def test_the_prompt_does_not_ask_for_tools():
    """MCP 서버가 없다. `guide` 를 부르라고 하면 모델이 없는 툴을 찾는다."""
    assert "guide" not in selection_instruction(SOURCE_MD, INDEX_MD)


def test_the_removed_prompt_asks_which_wikis_cited_the_document():
    text = selection_instruction("", INDEX_MD, "document_removed", SOURCE_MD)
    assert "삭제" in text
    assert "근거" in text
    assert "연차는 입사일을 기준으로 산정한다" in text, "삭제된 문서의 본문을 보여준다"


# ----- 엔드포인트 -------------------------------------------------------------


def test_the_endpoint_returns_ids_and_a_reason():
    response = _post(FakeRuntime('{"wikiIds": ["101", "103"], "reason": "겹친다"}'))
    assert response.status_code == 200, response.json()
    assert response.json() == {"wikiIds": ["101", "103"], "reason": "겹친다"}


def test_the_endpoint_never_returns_more_than_five():
    response = _post(FakeRuntime(
        '{"wikiIds": ["101","102","103","104","105","106"], "reason": "전부"}'))
    assert len(response.json()["wikiIds"]) == MAX_WIKIS


def test_selection_does_not_need_a_wiki_session():
    """선택은 임시 색인도 MCP 서버도 쓰지 않는다 — 전역 직렬 잠금을 잡으면 변환 큐가
    선택 때문에 막힌다."""
    from wiki_api import session as session_module

    _post(FakeRuntime('{"wikiIds": []}'))
    assert not session_module._SESSION_LOCK.locked()


async def test_selection_answers_while_a_transformation_holds_the_lock():
    """W1. 위 테스트는 "잠금을 잡지 않는다"까지만 본다 — 잠금이 **이미 잡혀 있을 때** 선택이
    기다리지 않는지가 진짜 성질이다. 변환은 최대 30분이고, 그 뒤에 줄 서면 선택 응답이
    30분 늦는다.

    같은 이벤트 루프에서 잠금을 잡은 채로 부른다. `_SESSION_LOCK` 은 asyncio 잠금이라
    다른 스레드(`TestClient`)에서 잡을 수 없으므로 ASGI 를 직접 태운다."""
    import asyncio

    from httpx import ASGITransport, AsyncClient

    from wiki_api import session as session_module

    app = create_app(api_key=API_KEY)
    app.state.runtime = FakeRuntime('{"wikiIds": ["101"], "reason": "겹친다"}')

    async with session_module._SESSION_LOCK:
        assert session_module._SESSION_LOCK.locked()
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport,
                               base_url="http://internal") as client:
            response = await asyncio.wait_for(
                client.post("/internal/v1/wiki-context-selections", json=REQUEST,
                            headers={"X-Internal-API-Key": API_KEY}),
                timeout=5)
    assert response.status_code == 200, response.text
    assert response.json()["wikiIds"] == ["101"]


def test_a_runtime_error_is_a_500_with_the_contract_code():
    response = _post(FakeRuntime("", error="claude 실패 (코드 1): 모델 오류"))
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_CONTEXT_SELECTION_FAILED"
    assert body["failureStage"] == "agent_error"


def test_an_unknown_field_is_a_400_with_the_selection_code():
    response = _post(FakeRuntime('{"wikiIds": []}'), dict(REQUEST, currentWikis=[]))
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_WIKI_CONTEXT_SELECTION_REQUEST"


def test_the_api_key_is_required():
    app = create_app(api_key=API_KEY)
    app.state.runtime = FakeRuntime('{"wikiIds": []}')
    response = TestClient(app, raise_server_exceptions=False).post(
        "/internal/v1/wiki-context-selections", json=REQUEST)
    assert response.status_code == 401


def test_an_arun_only_runtime_is_given_a_throwaway_root():
    """MCP 루트를 요구하는 런타임에는 **빈** 임시 디렉터리를 준다 — 툴이 뜨더라도 아무것도
    없는 범위를 보므로 선택 호출이 라이브 위키를 만질 수 없다."""
    runtime = ArunOnlyRuntime('{"wikiIds": ["102"]}')
    assert _post(runtime).json()["wikiIds"] == ["102"]
    assert runtime.kwargs["fs"] is None
    assert runtime.root_contents == []


def test_an_async_complete_that_ignores_its_timeout_is_still_bounded(monkeypatch):
    """런타임에 상한을 넘겨도 지킨다는 보장이 없다. async 경로는 취소가 실제로 먹으므로
    우리도 상한을 건다 — 안 걸면 이 호출이 무한정 매달리고 Spring 이 1단계에서 읽기
    타임아웃을 먹는다."""
    from wiki_api import selection

    monkeypatch.setattr(selection, "SELECTION_TIMEOUT_SECONDS", 0.05)
    response = _post(HangingRuntime())
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_CONTEXT_SELECTION_FAILED"
    assert body["failureStage"] == "agent_timeout"


def test_a_sync_only_runtime_is_run_off_the_event_loop_with_a_timeout():
    from wiki_api.selection import SELECTION_TIMEOUT_SECONDS

    runtime = SyncOnlyRuntime('{"wikiIds": ["105"]}')
    assert _post(runtime).json()["wikiIds"] == ["105"]
    assert runtime.kwargs["timeout"] == SELECTION_TIMEOUT_SECONDS
    assert runtime.kwargs["scope_key"] == SCOPE
