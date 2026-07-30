"""wiki-transformations 엔드포인트 (v1.1.0 — push).

에이전트 런타임은 가짜로 바꾼다. 여기서 확인할 것은 배관이다 — 요청이 실어 온
`selectedWikis` 로 라이브 층이 채워지는지, 작업 층이 계약 응답으로 나오는지, lint error 가
남으면 반영을 막는지. 모델 품질은 검증 계층 3(claude-code, 구독이라 비용 0)에서 본다.

**MockTransport 가 사라졌다.** AI 서버는 Spring 에 되묻지 않는다 (설계 §1) — 픽스처는
HTTP 핸들러가 아니라 요청 본문의 `selectedWikis` dict 다. 기존 페이지의 주소는
`pages/{wikiId}.md` 이고(§3), 원본문서는 요청의 `parsedMarkdown` 을 그대로 얹으므로
파일명이 없다 — `stage_source` 가 `document-{documentId}` 를 쓰고 각주도 그 이름을
가리킨다.
"""

import pytest
from fastapi.testclient import TestClient

from wiki_api.app import create_app
from agent_runtime.base import RunResult

API_KEY = "secret-key"
SCOPE = "D1-D2"

PAGE_MD = (
    "---\ntitle: 커뮤니케이션 가이드\ndescription: 비동기 우선 소통\n"
    "tags: [커뮤니케이션, 회의]\ncategory: 근무 정책\n---\n\n"
    "회의는 최후의 수단으로 쓴다.\n"
)
SOURCE_MD = "# 회의 운영\n\n## 2장 정례 회의\n\n주간 회의는 30분을 넘기지 않는다.\n"
INDEX_MD = "# 목차\n\n- [커뮤니케이션 가이드](pages/101.md) — 비동기 우선 소통\n"

# 요청이 이번 변환에 필요하다고 실어 보낸 위키 1장. 1단계 선택이 고르고 Spring 이
# 재검증해 본문까지 채운 것 (설계 §2).
SELECTED_PAGE = {
    "wikiId": "101", "categoryId": "9", "title": "커뮤니케이션 가이드",
    "summary": "비동기 우선 소통", "contentMarkdown": PAGE_MD,
}
PAGE_ADDRESS = "pages/101.md"

# 이번 요청과 무관하지만 함께 실려 온 라이브 페이지. 각주가 다른 원본문서(`취업규칙.pdf`)를
# 가리키는데 그 문서는 이번 요청이 스테이징하지 않는다 — 원본문서는 이번 것만 올라간다.
LEGACY_PAGE_MD = (
    "---\ntitle: 취업규칙 요약\ndescription: 근로시간과 휴게\n"
    "tags: [취업규칙, 근로시간]\ncategory: 근무 정책\n---\n\n"
    "소정근로시간은 주 40시간이다[^1].\n\n"
    '[^1]: 취업규칙.pdf, 2장 근로시간 — "소정근로시간은 주 40시간이다"\n'
)
LEGACY_PAGE = {
    "wikiId": "102", "categoryId": "9", "title": "취업규칙 요약",
    "summary": "근로시간과 휴게", "contentMarkdown": LEGACY_PAGE_MD,
}
LEGACY_ADDRESS = "pages/102.md"

REQUEST = {
    "jobId": "42",
    "documentId": "15",
    "scopeKey": SCOPE,
    "parsedMarkdown": SOURCE_MD,
    "currentIndex": INDEX_MD,
    "currentCategories": [{"wikiCategoryId": "9", "name": "근무 정책"}],
    "selectedWikis": [SELECTED_PAGE],
}

# 요청이 준 원본문서에는 파일명이 없다 — `stage_source` 가 이 이름을 붙이고, 각주는
# 파일명으로 문서를 가리키므로 에이전트도 이 이름으로 인용한다.
SOURCE_NAME = "document-15"


def request_with(**overrides) -> dict:
    """`selectedWikis` 를 갈아끼운 요청 본문."""
    body = dict(REQUEST)
    body.update(overrides)
    return body


class FakeRuntime:
    """에이전트 자리. 툴 대신 VaultFS 를 직접 써서 결정적으로 만든다."""

    name = "fake"

    def __init__(self, behaviour="edit_existing", category="근무 정책"):
        self.behaviour = behaviour
        self.category = category
        self.instructions: list[str] = []

    async def arun(self, instruction, *, fs, scope_id, **_) -> RunResult:
        self.instructions.append(instruction)
        from wiki_mcp.tools.references import sync_references

        if self.behaviour == "noop":
            return RunResult(text="변경 없음", tool_calls={"guide": 1, "search": 2, "read": 1})

        body = (PAGE_MD.rstrip() + "\n\n주간 회의는 30분을 넘기지 않는다[^1].\n\n"
                f'[^1]: {SOURCE_NAME}, 2장 정례 회의 — "주간 회의는 30분을 넘기지 않는다"\n')
        if self.behaviour == "bad_quote":
            body = body.replace("30분을 넘기지 않는다\"", "40분을 넘기지 않는다\"")
        body = body.replace("category: 근무 정책", f"category: {self.category}")

        await fs.write(scope_id, PAGE_ADDRESS, body, title="커뮤니케이션 가이드",
                       category=self.category, tags=["커뮤니케이션", "회의"])
        await sync_references(fs, scope_id, PAGE_ADDRESS, body)
        return RunResult(text="반영했다",
                         tool_calls={"guide": 1, "search": 2, "read": 2, "edit": 1, "lint": 1})


@pytest.fixture
def make_client():
    def build(runtime=None):
        app = create_app(api_key=API_KEY)
        app.state.runtime = runtime or FakeRuntime()
        return TestClient(app, raise_server_exceptions=False)

    return build


def _post(client, body=None):
    return client.post("/internal/v1/wiki-transformations", json=body or REQUEST,
                       headers={"X-Internal-API-Key": API_KEY})


def test_transform_returns_the_contract_shape(make_client):
    response = _post(make_client())
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"summary", "categoryChanges", "wikiChanges",
                         "relationChanges", "indexEntries"}


def test_editing_an_existing_page_is_an_update_with_its_wiki_id(make_client):
    body = _post(make_client()).json()
    change = body["wikiChanges"][0]
    assert change["action"] == "update"
    assert change["wikiId"] == "101"
    assert change["tempWikiId"] is None


def test_evidence_reaches_the_response(make_client):
    body = _post(make_client()).json()
    evidence = body["wikiChanges"][0]["evidence"][0]
    assert evidence["documentId"] == "15"
    assert evidence["location"] == "2장 정례 회의"
    assert evidence["quote"] == "주간 회의는 30분을 넘기지 않는다"


def test_only_the_selected_wikis_are_visible_to_the_agent(make_client):
    """v1.1.0 의 핵심 성질. 라이브 층은 요청이 실어 온 것이 전부다 — 되묻지 않으므로
    선택되지 않은 위키는 이번 변환에서 아예 보이지 않고, 따라서 변하지 않는다 (설계 §1).

    `selectedWikis` 를 비우면 같은 문서가 기존 페이지를 못 찾아 **새로** 만들어야 한다."""
    class CreatingRuntime(FakeRuntime):
        async def arun(self, instruction, *, fs, scope_id, **_):
            self.seen = [d["address"] for d in await fs.list_documents(scope_id)]
            return RunResult(text="본 것만 적는다", tool_calls={"guide": 1})

    runtime = CreatingRuntime()
    response = _post(make_client(runtime), request_with(selectedWikis=[]))
    assert response.status_code == 200, response.json()
    assert PAGE_ADDRESS not in runtime.seen
    assert "sources/15/parsed/content.md" in runtime.seen, "원본문서는 요청이 준다"


def test_the_selected_wiki_is_hydrated_at_its_wiki_id_address(make_client):
    """selectedWikis 에는 `wikiPath` 가 없다 — 주소는 `pages/{wikiId}.md` 다 (설계 §3)."""
    class ListingRuntime(FakeRuntime):
        async def arun(self, instruction, *, fs, scope_id, **_):
            self.seen = [d["address"] for d in await fs.list_documents(scope_id)]
            return RunResult(text="변경 없음", tool_calls={"guide": 1})

    runtime = ListingRuntime()
    assert _post(make_client(runtime)).status_code == 200
    assert PAGE_ADDRESS in runtime.seen


def test_no_changes_is_a_200_with_empty_lists(make_client):
    """재투입에서 변경 0 은 정상 결과다 (FR-DOC-012). 실패가 아니다."""
    response = _post(make_client(FakeRuntime("noop")))
    assert response.status_code == 200
    assert response.json()["wikiChanges"] == []


def test_lint_error_fails_the_request_and_reports_the_stage(make_client):
    """각주 인용문이 원문과 다르면 반영하지 않는다. 부분 반영은 없다."""
    response = _post(make_client(FakeRuntime("bad_quote")))
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert body["failureStage"] == "lint_failed"


def test_the_instruction_names_the_source_document(make_client):
    runtime = FakeRuntime()
    _post(make_client(runtime))
    assert "sources/15/parsed/content.md" in runtime.instructions[0]
    assert SCOPE in runtime.instructions[0]


class BrokenIndexLinkRuntime(FakeRuntime):
    """정상적으로 페이지를 고치지만, 목차에 존재하지 않는 페이지로 가는 링크를 남긴다."""

    async def arun(self, instruction, *, fs, scope_id, **_):
        result = await super().arun(instruction, fs=fs, scope_id=scope_id)
        await fs.write(scope_id, "index.md",
                       INDEX_MD + "\n- [없음](pages/deadbeef.md)\n")
        return result


class FailingRuntime:
    """런타임이 실패를 `RunResult.error` 로 돌려주는 자리.

    두 런타임 모두 예외를 던지지 않고 `error` 를 채워서 돌려준다 — CLI 는 오류에도 종료
    코드가 0 이고, DeepAgents 어댑터는 예외를 잡아 문장으로 바꾼다. 그것을 안 보면 실패한
    작업이 200 「변경 없음」으로 나가 Spring 이 성공으로 기록한다.
    """

    name = "fake-fail"

    def __init__(self, error: str):
        self.error = error

    async def arun(self, instruction, *, fs, scope_id, **_) -> RunResult:
        return RunResult(text="", tool_calls={"guide": 1}, error=self.error)


def test_agent_error_is_a_500_not_a_silent_no_change(make_client):
    response = _post(make_client(FailingRuntime("claude 실패 (코드 1): 모델 오류")))
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert body["failureStage"] == "agent_error"


def test_mcp_startup_failure_is_agent_start(make_client):
    """MCP 서버가 안 뜨거나 툴이 0개인 것은 모델 오류와 다른 단계다 (설계 4.5)."""
    response = _post(make_client(FailingRuntime(
        "RuntimeError (마지막 도달 단계: 시작 전): MCP 툴이 빠졌다: ['merge']")))
    assert response.json()["failureStage"] == "agent_start"


def test_runtime_reported_timeout_is_agent_timeout(make_client):
    """런타임 자체 timeout 은 예외가 아니라 `error` 로 온다. 단계가 agent_timeout 이어야
    Spring 이 사람에게 「시간 초과」로 번역할 수 있다."""
    response = _post(make_client(FailingRuntime("1347초 안에 끝나지 않았다")))
    assert response.json()["failureStage"] == "agent_timeout"


# ----- 카테고리 수렴 (C4) ----------------------------------------------------


def test_existing_category_is_not_recreated(make_client):
    """12건 측정에서 카테고리가 5개로 수렴했다. 기존 이름을 매번 create 로 내면 Spring 이
    같은 이름을 반복 생성하고 그 수렴이 무너진다 (FR-WIKI-014, DR-019)."""
    body = _post(make_client()).json()
    assert body["categoryChanges"] == []
    assert body["wikiChanges"][0]["wikiCategoryRef"] == "9"


def test_the_contract_spelling_of_the_category_id_is_accepted(make_client):
    """계약 예시(`docs/FastAPI명세서.json`)는 `currentCategories[].categoryId` 로 쓰고
    우리 응답·ERD 는 `wikiCategoryId` 로 쓴다. `extra="forbid"` 라서 한쪽만 받으면 계약
    예시를 그대로 보낸 **첫 실호출이 400** 이다 — 둘 다 받고 수렴도 그대로 동작해야 한다."""
    body = _post(make_client(),
                 request_with(currentCategories=[{"categoryId": "9",
                                                  "name": "근무 정책"}])).json()
    assert body["categoryChanges"] == [], "이미 있는 이름을 다시 만들지 않는다"
    assert body["wikiChanges"][0]["wikiCategoryRef"] == "9"


def test_both_category_id_spellings_reach_the_same_field():
    from wiki_api.schemas import CategoryRef

    assert CategoryRef(categoryId="9", name="근무 정책").wikiCategoryId == "9"
    assert CategoryRef(wikiCategoryId="9", name="근무 정책").wikiCategoryId == "9"


PAGE_MD_WITHOUT_FRONTMATTER = "회의는 최후의 수단으로 쓴다.\n"


class CategoryKeepingRuntime(FakeRuntime):
    """읽은 페이지의 분류를 그대로 다시 적는 에이전트 자리.

    `FakeRuntime` 은 frontmatter 를 자기가 지어내므로 하이드레이션이 카테고리를 잃어도
    티가 나지 않는다 — 그래서 `categoryId` 해석 경로에 단정이 하나도 없었다 (I4).
    """

    async def arun(self, instruction, *, fs, scope_id, **_):
        from wiki_mcp.tools.references import sync_references

        row = await fs.get(scope_id, PAGE_ADDRESS)
        self.seen_category = (row or {}).get("category")
        # 읽은 분류를 그대로 frontmatter 에 적는다 — `lint` 가 frontmatter 를 요구한다.
        body = (f"---\ntitle: {(row or {}).get('title')}\n"
                f"tags: [커뮤니케이션, 회의]\ncategory: {self.seen_category}\n---\n\n"
                + (row or {}).get("content", "").strip()
                + "\n\n주간 회의는 30분을 넘기지 않는다[^1].\n\n"
                f'[^1]: {SOURCE_NAME}, 2장 정례 회의 — "주간 회의는 30분을 넘기지 않는다"\n')
        await fs.write(scope_id, PAGE_ADDRESS, body, title=(row or {}).get("title"),
                       category=self.seen_category)
        await sync_references(fs, scope_id, PAGE_ADDRESS, body)
        return RunResult(text="분류를 유지하며 고쳤다",
                         tool_calls={"guide": 1, "read": 1, "edit": 1, "lint": 1})


def test_a_selected_wiki_without_frontmatter_keeps_its_category_id(make_client):
    """I4. frontmatter 에 `category` 가 없는 페이지는 `selectedWikis[].categoryId` 가
    유일한 분류 정보다 — `currentCategories` 로 이름까지 풀어 얹지 않으면 에이전트가
    분류 없는 페이지를 보고 이미 있는 분류를 새로 만들자고 낸다 (C4 수렴 상실).

    지금까지는 픽스처의 frontmatter 가 늘 이겨서 이 경로가 아예 돌지 않았다."""
    runtime = CategoryKeepingRuntime()
    body = _post(make_client(runtime), request_with(selectedWikis=[
        dict(SELECTED_PAGE, contentMarkdown=PAGE_MD_WITHOUT_FRONTMATTER)])).json()
    assert runtime.seen_category == "근무 정책", "categoryId 9 가 이름으로 풀려 있어야 한다"
    assert body["categoryChanges"] == [], "이미 있는 분류를 새로 만들지 않는다"
    assert body["wikiChanges"][0]["wikiCategoryRef"] == "9"


def test_new_category_is_created_and_referenced_by_temp_id(make_client):
    body = _post(make_client(FakeRuntime(category="신설 분류"))).json()
    change = body["categoryChanges"][0]
    assert change["action"] == "create"
    assert change["name"] == "신설 분류"
    assert change["tempCategoryId"]
    assert body["wikiChanges"][0]["wikiCategoryRef"] == change["tempCategoryId"]


# ----- 시간 상한이 런타임까지 간다 (C3) --------------------------------------


class SyncRuntime:
    """`run()` 만 가진 런타임 (실제 두 런타임이 그렇다). 받은 timeout 을 기록한다."""

    name = "fake-sync"

    def __init__(self):
        self.timeout = None

    def run(self, instruction, *, root, scope_key, job_id, timeout=None) -> RunResult:
        self.timeout = timeout
        return RunResult(text="변경 없음", tool_calls={"guide": 1, "read": 1})


def test_sync_runtime_receives_the_computed_time_limit(make_client):
    """26KB 문서가 1347초를 받는다. `asyncio.to_thread` 는 취소할 수 없으므로 런타임 자체
    timeout 이 유일한 강제 수단이다."""
    from agent_runtime.limits import time_limit_seconds

    runtime = SyncRuntime()
    big = "가" * 26139
    response = _post(make_client(runtime), request_with(parsedMarkdown=big))
    assert response.status_code == 200, response.json()
    assert runtime.timeout == time_limit_seconds(len(big)) == 1347


class SlowRuntime:
    """async 런타임. 여기서는 세션의 `wait_for` 가 강제 수단이다."""

    name = "fake-slow"

    async def arun(self, instruction, *, fs, scope_id, **_) -> RunResult:
        import asyncio

        await asyncio.sleep(5)
        return RunResult(text="늦었다")


def test_async_runtime_over_the_limit_is_agent_timeout(make_client, monkeypatch):
    from wiki_api.routers import wiki as wiki_router

    monkeypatch.setattr(wiki_router, "time_limit_seconds", lambda *_a, **_k: 0)
    response = _post(make_client(SlowRuntime()))
    assert response.status_code == 500
    assert response.json()["failureStage"] == "agent_timeout"


# ----- 접수 시점의 크기 상한 (v1.1 §1) ---------------------------------------


def test_a_document_over_the_ceiling_fails_at_context_load(make_client):
    """36k자를 넘으면 천장(30분) 안에 끝날 수 없다 (`runtime/limits.py`). 30분 뒤에
    `agent_timeout` 으로 알리는 것은 같은 답을 30분 늦게 주는 것이고, 그동안 전역 직렬
    큐가 막힌다 — 접수 시점에 거절한다."""
    runtime = FakeRuntime()
    response = _post(make_client(runtime), request_with(parsedMarkdown="가" * 40_000))
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert body["failureStage"] == "context_load"
    assert runtime.instructions == [], "에이전트를 부르지 않는다"


def test_a_document_just_under_the_ceiling_is_accepted(make_client):
    """상한 **직하**가 통과해야 한다 (W2). 천장에 닿는 지점은 36,200자쯤이다
    (`exceeds_ceiling`: 1.9 + 5.0 × 글자수/1만 을 1.5배 한 값이 30분을 넘는 곳) — 30,000자로
    재면 경계에서 6,000자 떨어진 곳을 재는 것이고, 상한식이 조금 조여져도 초록으로 남는다."""
    from agent_runtime.limits import exceeds_ceiling

    # 각주 인용문이 원문에 있어야 lint 를 통과한다 — 본문 뒤에 채우기만 한다.
    body = SOURCE_MD + "가" * (36_100 - len(SOURCE_MD))
    assert not exceeds_ceiling(len(body)) and exceeds_ceiling(len(body) + 200), \
        "이 길이가 정말 경계 직하인가"
    response = _post(make_client(), request_with(parsedMarkdown=body))
    assert response.status_code == 200, response.json()


# ----- 요청이 성립하지 않으면 400 이다 (I2·I3) --------------------------------


def test_added_without_a_body_is_a_400(make_client):
    """I2. `document_added` 인데 `parsedMarkdown` 이 비면 변환할 원본문서가 없다. 조용한
    200 은 Spring 의 `document_results` 에 "성공, 변경 0"으로 남아 사라진 본문을 감춘다."""
    runtime = FakeRuntime()
    response = _post(make_client(runtime), request_with(parsedMarkdown=""))
    assert response.status_code == 400, response.json()
    body = response.json()
    assert body["code"] == "INVALID_WIKI_TRANSFORMATION_REQUEST"
    assert [e["field"] for e in body["fieldErrors"]] == ["parsedMarkdown"]
    assert runtime.instructions == [], "에이전트를 부르지 않는다"


def test_removed_without_selected_wikis_is_a_400_that_names_the_alternative(make_client):
    """I3. removed 는 걷어낼 인용 위키를 요청이 실어 와야 한다 (회신 #3). 빈 목록이면
    unlink 0건의 200 이 나가는데, 그것은 "지웠다"와 구별되지 않는다.

    다만 인용 위키가 진짜 0인 삭제는 합법이다 — 그 경우 변환을 부르지 않으면 된다는 것을
    메시지가 알려줘야 Spring 이 무엇을 고칠지 안다."""
    runtime = FakeRuntime()
    response = _post(make_client(runtime), request_with(
        changeType="document_removed", removedParsedMarkdown=SOURCE_MD,
        parsedMarkdown="", selectedWikis=[]))
    assert response.status_code == 400, response.json()
    body = response.json()
    assert body["code"] == "INVALID_WIKI_TRANSFORMATION_REQUEST"
    assert [e["field"] for e in body["fieldErrors"]] == ["selectedWikis"]
    reason = body["fieldErrors"][0]["reason"]
    assert "변환" in reason and "삭제" in reason, reason
    assert runtime.instructions == []


# ----- 동시 요청 (I1) ---------------------------------------------------------


class ConcurrencyProbe(FakeRuntime):
    """동시에 몇 건이 세션 안에 들어와 있었는지 센다."""

    active = 0
    peak = 0

    async def arun(self, instruction, *, fs, scope_id, **kwargs):
        import asyncio

        type(self).active += 1
        type(self).peak = max(type(self).peak, type(self).active)
        await asyncio.sleep(0.05)
        try:
            return await super().arun(instruction, fs=fs, scope_id=scope_id, **kwargs)
        finally:
            type(self).active -= 1


async def test_two_requests_do_not_share_the_temporary_index():
    """`vaultfs/local.py` 의 연결이 프로세스 전역이라, 두 요청이 겹치면 뒤 요청의 `open` 이
    앞 요청의 연결을 덮고 `close` 가 남의 연결을 닫는다 — 임시 색인이 섞인다."""
    import asyncio

    import httpx as _httpx

    ConcurrencyProbe.active = ConcurrencyProbe.peak = 0
    app = create_app(api_key=API_KEY)
    app.state.runtime = ConcurrencyProbe()

    transport = _httpx.ASGITransport(app=app)
    async with _httpx.AsyncClient(transport=transport, base_url="http://ai") as client:
        responses = await asyncio.gather(*[
            client.post("/internal/v1/wiki-transformations", json=REQUEST,
                        headers={"X-Internal-API-Key": API_KEY})
            for _ in range(2)
        ])

    assert [r.status_code for r in responses] == [200, 200]
    assert ConcurrencyProbe.peak == 1, "세션이 겹쳤다 — 임시 색인이 섞인다"


async def test_a_hydration_failure_releases_the_session_lock(monkeypatch):
    """`__aenter__` 가 예외로 빠져나가면 `async with` 본문이 시작되지 않아 `__aexit__` 도
    안 불린다. 잠금을 놓지 않으면 `acquire()` 에 timeout 이 없으므로 그 뒤 모든 요청이
    영구히 매달린다 — 서버가 죽지 않고 조용히 멈춘다.

    실제 후보: 클라이언트가 하이드레이션 중 끊어져 uvicorn 이 태스크를 취소
    (`CancelledError`), 요청이 준 위키 dict 가 계약과 다른 모양이라 `KeyError`,
    임시 디렉터리 `OSError`.
    """
    import asyncio

    import httpx as _httpx

    from wiki_api import session as session_module

    async def boom(*_a, **_k):
        raise RuntimeError("하이드레이션 도중 예상 못한 실패")

    monkeypatch.setattr(session_module.SpringVaultFS, "open", staticmethod(boom))

    app = create_app(api_key=API_KEY)
    app.state.runtime = FakeRuntime()

    # `raise_app_exceptions=False`: Starlette 의 ServerErrorMiddleware 는 500 을 보낸 뒤에도
    # 예외를 다시 던진다(서버가 로그를 남길 수 있게). 여기서 보려는 것은 그 예외가 아니라
    # 다음 요청이 서비스되는지다.
    transport = _httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with _httpx.AsyncClient(transport=transport, base_url="http://ai") as client:
        first = await client.post("/internal/v1/wiki-transformations", json=REQUEST,
                                  headers={"X-Internal-API-Key": API_KEY})
        assert first.status_code == 500
        assert first.json()["failureStage"] == "context_load"
        # 두 번째 요청이 매달리면 잠금이 샜다. 5초면 충분하다 — 가짜 런타임은 즉시 끝난다.
        second = await asyncio.wait_for(
            client.post("/internal/v1/wiki-transformations", json=REQUEST,
                        headers={"X-Internal-API-Key": API_KEY}),
            timeout=5)
        assert second.status_code == 500

    assert not session_module._SESSION_LOCK.locked(), "세션 잠금이 남았다"


# ----- 조립 실패와 catch-all (I5) --------------------------------------------


def test_assemble_failure_reports_the_assemble_stage(make_client, monkeypatch):
    from wiki_api.routers import wiki as wiki_router

    async def boom(*_a, **_k):
        raise RuntimeError("응답 조립 실패")

    monkeypatch.setattr(wiki_router, "build_response", boom)
    response = _post(make_client())
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert body["failureStage"] == "assemble"


# ----- 게이트가 live/work 를 구분한다 (C1) -----------------------------------


def test_a_live_page_citing_an_unstaged_source_does_not_block(make_client):
    """C1. 원본문서는 이번 요청의 것만 올라간다 — 다른 문서를 인용하는 기존 페이지는
    각주가 풀리지 않아 `unresolved-citation` error 가 된다. 그 페이지를 이번 요청이 건드리지
    않았다면 이 요청으로 고칠 방법이 없다. 게이트가 그것까지 막으면 문서 2건째부터 전 요청이
    500 이 되고 그 범위는 영구히 반영 불능이 된다."""
    response = _post(make_client(),
                     request_with(selectedWikis=[SELECTED_PAGE, LEGACY_PAGE]))
    assert response.status_code == 200, response.json()
    assert [c["wikiId"] for c in response.json()["wikiChanges"]] == ["101"]


def test_work_layer_errors_still_block_when_a_live_page_is_unverifiable(make_client):
    """완화가 work 층까지 새면 게이트가 사라진다 — 라이브에 미검증 페이지가 있어도
    이번 요청이 쓴 페이지의 인용 오류는 그대로 500 이다."""
    response = _post(make_client(FakeRuntime("bad_quote")),
                     request_with(selectedWikis=[SELECTED_PAGE, LEGACY_PAGE]))
    assert response.status_code == 500
    assert response.json()["failureStage"] == "lint_failed"


class LegacyFootnoteEditRuntime(FakeRuntime):
    """라이브 페이지의 산문만 고치고, 이번 요청이 올리지 않은 문서를 가리키는 **기존** 각주
    정의 줄은 글자 하나 안 바꾼 채 유지한다. 실제 편집에서 늘 일어난다."""

    async def arun(self, instruction, *, fs, scope_id, **_):
        from wiki_mcp.tools.references import sync_references

        body = LEGACY_PAGE_MD.replace("소정근로시간은 주 40시간이다[^1].",
                                      "소정근로시간은 주 40시간이다(개정)[^1].")
        assert '[^1]: 취업규칙.pdf, 2장 근로시간 — "소정근로시간은 주 40시간이다"' in body
        await fs.write(scope_id, LEGACY_ADDRESS, body, title="취업규칙 요약",
                       category="근무 정책", tags=["취업규칙", "근로시간"])
        await sync_references(fs, scope_id, LEGACY_ADDRESS, body)
        return RunResult(text="반영했다", tool_calls={"guide": 1, "edit": 1, "lint": 1})


def test_an_edited_page_keeping_an_old_footnote_verbatim_passes(make_client):
    """F1. 각주 정의 줄이 에이전트 실행 전 라이브 본문에 그대로 있으면 그 각주는 처음
    쓰일 때 검증된 것이다 — 지금 원문이 없을 뿐이다. 막으면 각주 있는 페이지를 두 번
    다시 고칠 수 없다."""
    response = _post(make_client(LegacyFootnoteEditRuntime()),
                     request_with(selectedWikis=[SELECTED_PAGE, LEGACY_PAGE]))
    assert response.status_code == 200, response.json()


class FabricatedCitationRuntime(FakeRuntime):
    """페이지를 만들면서 존재하지 않는 원본문서를 인용한다 — 모델이 근거를 지어낸 경우."""

    async def arun(self, instruction, *, fs, scope_id, **_):
        from wiki_mcp.tools.references import sync_references

        body = (PAGE_MD.rstrip() + "\n\n연차는 20일을 부여한다[^1].\n\n"
                '[^1]: 없는문서.pdf, 3장 휴가 — "연차는 20일을 부여한다"\n')
        await fs.write(scope_id, PAGE_ADDRESS, body, title="커뮤니케이션 가이드",
                       category="근무 정책", tags=["커뮤니케이션", "회의"])
        await sync_references(fs, scope_id, PAGE_ADDRESS, body)
        return RunResult(text="반영했다", tool_calls={"guide": 1, "edit": 1, "lint": 1})


def test_a_fabricated_citation_on_an_agent_written_page_fails_lint(make_client):
    """F1 필수 테스트. 에이전트가 없는 원본문서를 인용하면 반영하지 않는다 (NFR-AI-002).
    이 각주 정의는 라이브 본문에 없으므로 「원래 있던 각주」 예외에 걸리지 않는다."""
    response = _post(make_client(FabricatedCitationRuntime()))
    assert response.status_code == 500, response.json()
    body = response.json()
    assert body["failureStage"] == "lint_failed"
    assert "unresolved-citation" in body["message"]


# ----- 절단으로 게이트가 새지 않는다 (F2) ------------------------------------


def _noisy_live_pages(count: int) -> list[dict]:
    """frontmatter 없는 라이브 페이지 여러 장. 각각 `missing-frontmatter` error 1건이다.
    `wikiId` 를 `0…` 으로 시작시켜(= 주소가 `pages/0…`) 보고서 앞줄을 채우게 한다."""
    return [{
        "wikiId": f"0legacy{i:03d}", "categoryId": "9", "title": f"옛 페이지 {i}",
        "summary": None, "contentMarkdown": f"frontmatter 없는 옛 본문 {i}\n",
    } for i in range(count)]


def test_forty_live_only_errors_cannot_hide_a_work_layer_error(make_client):
    """F2. 라이브 전용 error 가 많아도 작업 층 error 는 반드시 막는다.

    이 테스트가 생긴 이유는 절단이었다 — `lint` 보고서가 group 당 40건(`_MAX_PER_GROUP`)만
    찍고 나머지를 「... N건 더」로 접는데 게이트가 그 문자열을 되파싱하고 있었다. 라이브
    전용 error 가 앞을 채우면 작업 층 error 가 보고서에서 사라져 fail-open 이 됐다.

    Task 3 이 그 원인을 둘 다 없앴다. 게이트는 `LintHandler.collect()` 로 구조화된 결과를
    받고(절단 없음), 에이전트용 검사가 라이브 전용 페이지의 내용을 아예 보지 않는다. 그래도
    이 테스트는 남긴다 — 어느 쪽 변경으로도 다시 새면 안 되는 성질이다."""
    response = _post(
        make_client(FakeRuntime("bad_quote")),
        request_with(selectedWikis=[SELECTED_PAGE, *_noisy_live_pages(45)]))
    assert response.status_code == 500, response.json()
    assert response.json()["failureStage"] == "lint_failed"


def test_forty_live_only_errors_alone_do_not_block(make_client):
    """반대쪽도 본다 — 라이브 전용 error 가 45건이어도 이번 요청이 깨끗하면 200 이다."""
    response = _post(make_client(),
                     request_with(selectedWikis=[SELECTED_PAGE, *_noisy_live_pages(45)]))
    assert response.status_code == 200, response.json()


# ----- 라이브 전용 dangling-link (F3) ----------------------------------------

LINK_TARGET_PAGE = {
    "wikiId": "103", "categoryId": "9", "title": "근로시간 상세",
    "summary": "근로시간",
    "contentMarkdown": ("---\ntitle: 근로시간 상세\ndescription: 근로시간\n"
                        "tags: [근로시간, 근무]\ncategory: 근무 정책\n---\n\n본문.\n"),
}
LINKING_PAGE = {
    "wikiId": "104", "categoryId": "9", "title": "근무 안내", "summary": "근무 안내",
    "contentMarkdown": ("---\ntitle: 근무 안내\ndescription: 근무 안내\n"
                        "tags: [근무, 안내]\ncategory: 근무 정책\n---\n\n"
                        "자세한 것은 [근로시간 상세](pages/103.md)를 본다.\n"),
}
PAGE_WITH_A_BROKEN_LINK = {
    "wikiId": "105", "categoryId": "9", "title": "옛 안내", "summary": "옛 안내",
    "contentMarkdown": ("---\ntitle: 옛 안내\ndescription: 옛 안내\n"
                        "tags: [옛, 안내]\ncategory: 근무 정책\n---\n\n"
                        "자세한 것은 [사라진 페이지](pages/deadbeef.md)를 본다.\n"),
}


def test_a_live_only_broken_link_does_not_block(make_client):
    """F3. 라이브 페이지의 깨진 링크는 하이드레이션이 부분적이라는 사실의 부산물일 수
    있고(선택된 5개만 온다), 이번 요청이 고칠 수도 없다 — 막으면 그 범위가 영구 반영
    불능이다."""
    response = _post(make_client(),
                     request_with(selectedWikis=[SELECTED_PAGE, PAGE_WITH_A_BROKEN_LINK]))
    assert response.status_code == 200, response.json()


class PageRemovingRuntime(FakeRuntime):
    """정상적으로 편집하면서, 다른 라이브 페이지가 링크하고 있는 페이지를 지운다."""

    async def arun(self, instruction, *, fs, scope_id, **_):
        result = await super().arun(instruction, fs=fs, scope_id=scope_id)
        await fs.remove(scope_id, "pages/103.md")
        return result


def test_removing_a_linked_page_blocks_even_though_the_linker_is_live_only(make_client):
    """F3 의 반대쪽. 깨진 링크의 **원인이 이번 요청**이면(대상이 작업 층 주소다) 막는다 —
    반영하면 라이브에 죽은 링크가 남는다."""
    response = _post(
        make_client(PageRemovingRuntime()),
        request_with(selectedWikis=[SELECTED_PAGE, LINK_TARGET_PAGE, LINKING_PAGE]))
    assert response.status_code == 500, response.json()
    body = response.json()
    assert body["failureStage"] == "lint_failed"
    assert "dangling-link" in body["message"]


# ----- wikiPath 는 create 에만 실린다 (C2, I1) --------------------------------


def test_updating_an_existing_page_does_not_invent_a_wiki_path(make_client):
    """I1. 기존 페이지의 실제 파일은 Spring 이 정한 `wiki_path` 이고 우리는 그것을 모른다 —
    이번 세션의 하이드레이션 주소(`pages/{wikiId}.md`)를 `wikiPath` 로 되돌려주면 Spring 이
    그 값을 저장해 진짜 파일과 어긋난다(그 위키가 404 가 된다). 변경에는 아예 안 보낸다."""
    body = _post(make_client()).json()
    change = body["wikiChanges"][0]
    assert change["action"] == "update"
    assert change["wikiPath"] is None


def test_a_created_page_carries_its_wiki_path(make_client):
    """신규는 반대다 — `pageKey` 는 에이전트가 발급한 값이라 Spring 이 유도할 수 없고,
    이것이 없으면 새 페이지 사이의 본문 링크를 실제 `wikiId` 로 치환할 수 없다 (DR-016)."""
    class CreatingRuntime(FakeRuntime):
        async def arun(self, instruction, *, fs, scope_id, **_):
            from wiki_mcp.tools.references import sync_references

            address = await fs.allocate_page(scope_id)
            body = (
                "---\ntitle: 회의 운영\ntags: [회의]\ncategory: 근무 정책\n---\n\n"
                "주간 회의는 30분을 넘기지 않는다[^1].\n\n"
                f'[^1]: {SOURCE_NAME}, 2장 정례 회의 — "주간 회의는 30분을 넘기지 않는다"\n')
            await fs.write(scope_id, address, body, title="회의 운영",
                           category="근무 정책", tags=["회의"])
            await sync_references(fs, scope_id, address, body)
            self.address = address
            return RunResult(text="새로 만들었다", tool_calls={"guide": 1, "edit": 1})

    runtime = CreatingRuntime()
    # 목차도 비운다 — 선택이 비었으니 `pages/101.md` 가 없고, 그것을 가리키는 목차를 얹으면
    # 이 테스트와 무관한 dangling-link 로 lint 가 먼저 막는다.
    response = _post(make_client(runtime),
                     request_with(selectedWikis=[], currentIndex="# 목차\n"))
    assert response.status_code == 200, response.json()
    body = response.json()
    change = next(c for c in body["wikiChanges"] if c["action"] == "create")
    assert change["wikiPath"] == f"wiki/{SCOPE}/{runtime.address}"


# ----- requestId 를 한 번만 발급한다 (M-a) -----------------------------------


def test_the_request_id_is_echoed_once(make_client):
    """app.py 미들웨어와 deps.py 가 각자 발급하면 응답 헤더의 값과 처리 중 쓴 값이
    달라진다 (설계 4.1). Spring 이 준 값이 있으면 그것을 그대로 되돌려준다."""
    response = make_client().post(
        "/internal/v1/wiki-transformations", json=REQUEST,
        headers={"X-Internal-API-Key": API_KEY, "X-Request-Id": "01KABCDEF123456789"})
    assert response.status_code == 200, response.json()
    assert response.headers["X-Request-Id"] == "01KABCDEF123456789"


def test_a_request_without_an_id_still_gets_one(make_client):
    response = _post(make_client())
    assert response.headers["X-Request-Id"].startswith("ai-")


def test_dangling_link_in_index_fails_lint(make_client):
    """index.md 를 이번 요청에서 고쳤고 그 안에 존재하지 않는 페이지로 가는 링크를
    남겼다면 반영을 막는다 — index.md 를 lint 대상에서 통째로 뺄 수는 없다."""
    response = _post(make_client(BrokenIndexLinkRuntime()))
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert body["failureStage"] == "lint_failed"


# ----- 게이트가 구조화된 결과를 쓴다 (Task 3) ---------------------------------


def test_the_gate_no_longer_reparses_the_lint_report():
    """게이트는 `LintHandler.collect()` 의 `LintIssue` 를 읽는다.

    앞 판본은 `run()` 의 마크다운 보고서를 정규식 3개로 되파싱했다. 그래서 `tools/lint.py`
    의 한국어 문장을 다듬으면 게이트가 조용히 새거나 조용히 과하게 막았고, 테스트가
    그것을 잡지 못했다. 이름이 남아 있으면 새 코드가 그것을 다시 붙잡는다."""
    import wiki_api.session as session

    for gone in ("_ISSUE_LINE_RE", "_FOOTNOTE_LABEL_RE", "_LINK_TARGET_RE"):
        assert not hasattr(session, gone), gone
    assert not hasattr(session.WikiSession, "_errors")


def test_only_dangling_link_blocks_on_a_live_only_page():
    """라이브 전용 페이지에서 막는 코드는 하나뿐이다.

    하이드레이션은 위키를 전부 올리지만 원본문서는 이번 요청의 것만 올린다. 그래서 다른
    문서를 인용하는 기존 각주와 Spring 이 준 그대로의 frontmatter 는 **이 요청으로 고칠 수
    없다** — 막으면 그 범위가 영구히 반영 불능이 된다. 이 집합이 늘어나면 그 사고가
    재발한다."""
    from wiki_api.session import _LIVE_ONLY_BLOCKING_CODES

    assert _LIVE_ONLY_BLOCKING_CODES == frozenset({"dangling-link"})
