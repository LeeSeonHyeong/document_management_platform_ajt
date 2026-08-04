"""문서 삭제·교체 — `wiki-transformations` 의 `changeType` 분기.

v1.1.0 이전에는 `POST /wiki-reconciliations` 라는 별도 엔드포인트였다. 계약이 그것을
없애고 변환 요청의 `changeType` 으로 흡수했다 (설계 §1) — 시나리오는 그대로다.
프론트 API 4개(`DELETE`·`PUT /file`·`PATCH` 범위 변경·`retry`)가 위키 재처리를 유발한다.

걷어내기는 추가보다 어렵다 — 여러 문서가 같은 페이지에 각주를 걸면 어느 문단이 누구
근거인지 갈라야 한다. 참조 그래프가 각주 단위로 그 자료를 들고 있다.
"""

from fastapi.testclient import TestClient

from wiki_api.app import create_app
from agent_runtime.base import RunResult, reconcile_instruction
from .test_api_wiki import (API_KEY, BACKEND_URL, CAPABILITY, LIVE_PAGE,
                            PAGE_ADDRESS, PAGE_MD, SCOPE, SCOPE_VERSION,
                            SOURCE_MD, SOURCE_NAME, make_gateway)

CITED_PAGE_MD = (
    PAGE_MD.rstrip() + "\n\n주간 회의는 30분을 넘기지 않는다[^1].\n\n"
    f'[^1]: {SOURCE_NAME}, 2장 정례 회의 — "주간 회의는 30분을 넘기지 않는다"\n'
)

# 조회 API 가 들고 있는 위키 1장 — 사라진 문서 15 를 각주로 인용하고 있다.
CITED_PAGE = dict(LIVE_PAGE, contentMarkdown=CITED_PAGE_MD)

REQUEST = {
    "jobId": "43",
    "documentId": "15",
    "scopeKey": SCOPE,
    "changeType": "document_removed",
    "removedParsedMarkdown": SOURCE_MD,
    "wikiCapability": CAPABILITY,
    "scopeVersion": SCOPE_VERSION,
}


class RemovingRuntime:
    """근거가 사라진 문단을 걷어내는 에이전트 자리."""

    name = "fake-remove"

    def __init__(self):
        self.instructions: list[str] = []

    async def arun(self, instruction, *, fs, scope_id, **_):
        self.instructions.append(instruction)
        from wiki_mcp.tools.references import sync_references
        # 조회 API 는 본문을 지연 적재한다 — 고칠 페이지를 먼저 읽는 것이 실제 순서다.
        await fs.get(scope_id, PAGE_ADDRESS)
        cleaned = PAGE_MD                      # 각주와 그 문단을 뺀 본문
        await fs.write(scope_id, PAGE_ADDRESS, cleaned,
                       title="커뮤니케이션 가이드", category="근무 정책",
                       tags=["커뮤니케이션", "회의"])
        await sync_references(fs, scope_id, PAGE_ADDRESS, cleaned)
        return RunResult(text="문서 15 근거를 걷어냈다",
                         tool_calls={"guide": 1, "read": 1, "references": 1,
                                     "edit": 1, "lint": 1})


def _client(runtime, gateway=None):
    app = create_app(api_key=API_KEY, backend_base_url=BACKEND_URL)
    app.state.runtime = runtime
    # 사라진 문서를 인용한 위키를 조회 API 가 알려준다 — 걷어내기의 backlink 는 이
    # `documentRefs` 를 뒤집어 찾는다 (`FederatedVaultFS.get_citation_backlinks`).
    app.state.query_transport = gateway or make_gateway(
        [CITED_PAGE],
        relations=[{"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]}])
    return TestClient(app, raise_server_exceptions=False)


def _post(runtime, body=None, gateway=None):
    return _client(runtime, gateway).post(
        "/internal/v1/wiki-transformations", json=body or REQUEST,
        headers={"X-Internal-API-Key": API_KEY})


def test_removal_returns_the_transform_shape():
    response = _post(RemovingRuntime())
    assert response.status_code == 200, response.json()
    assert set(response.json()) == {"summary", "categoryChanges", "wikiChanges",
                                    "relationChanges", "indexEntries"}


def test_removing_the_last_citation_drops_it_from_evidence_not_relation_changes():
    """S15P11B106-157: Wiki-원본문서 관계는 `relationChanges` 로 안 나간다 — 걷어낸 근거는
    `wikiChanges[].evidence` 에서 그냥 빠진다. Spring 은 evidence 를 `wiki.document_refs`
    에 추가한다 — 인용이 끊어진 항목을 걷어내는 경로는 Spring 에 아직 없다(별건)지만,
    이번 요청이 evidence 에서 문서 15 를 뺀 채로 다시 실어 보내므로 문서 15 에 대한
    별도 `remove` 신호가 필요 없다."""
    body = _post(RemovingRuntime()).json()
    assert not any(r["type"] == "wiki_document" for r in body["relationChanges"])
    evidence_doc_ids = {e["documentId"] for change in body["wikiChanges"]
                        for e in change.get("evidence") or []}
    assert "15" not in evidence_doc_ids


def test_the_instruction_lists_the_pages_that_cite_the_document():
    """참조 그래프가 고칠 곳을 각주 단위로 알려준다. 에이전트가 전수 탐색하지 않는다."""
    runtime = RemovingRuntime()
    _post(runtime)
    instruction = runtime.instructions[0]
    assert PAGE_ADDRESS in instruction
    assert "2장 정례 회의" in instruction


NEW_SOURCE = "# 회의 운영\n\n## 2장 정례 회의\n\n주간 회의는 45분을 넘기지 않는다.\n"
REPLACED = dict(REQUEST, changeType="document_replaced", parsedMarkdown=NEW_SOURCE)


class ReplacingRuntime:
    """교체된 새 내용을 읽고 그것을 근거로 문단을 고치는 에이전트 자리.

    `source_seen` 에 자기가 읽은 원본문서 본문을 담는다 — 스테이징이 옛 내용을 남겨두면
    에이전트가 새 내용을 볼 방법이 아예 없다.
    """

    name = "fake-replace"

    def __init__(self):
        self.instructions: list[str] = []
        self.source_seen: str | None = None

    async def arun(self, instruction, *, fs, scope_id, **_):
        self.instructions.append(instruction)
        from wiki_mcp.tools.references import sync_references

        row = await fs.get(scope_id, "sources/15/parsed/content.md")
        self.source_seen = (row or {}).get("content")

        await fs.get(scope_id, PAGE_ADDRESS)
        body = (PAGE_MD.rstrip() + "\n\n주간 회의는 45분을 넘기지 않는다[^1].\n\n"
                f'[^1]: {SOURCE_NAME}, 2장 정례 회의 — "주간 회의는 45분을 넘기지 않는다"\n')
        await fs.write(scope_id, PAGE_ADDRESS, body, title="커뮤니케이션 가이드",
                       category="근무 정책", tags=["커뮤니케이션", "회의"])
        await sync_references(fs, scope_id, PAGE_ADDRESS, body)
        return RunResult(text="새 내용에 맞게 고쳤다",
                         tool_calls={"guide": 1, "read": 1, "edit": 1, "lint": 1})


def test_replaced_stages_the_new_content_for_the_agent():
    """교체는 삭제와 다르다 — 새 내용이 있고 에이전트가 그것을 읽어야 한다. 옛 내용만
    스테이징하면 새 내용을 근거로 쓴 인용이 lint 에서 전부 error 가 된다."""
    runtime = ReplacingRuntime()
    response = _post(runtime, REPLACED)
    assert response.status_code == 200, response.json()
    assert runtime.source_seen and "45분" in runtime.source_seen
    assert "45분" in response.json()["wikiChanges"][0]["contentMarkdown"]


def test_replaced_instruction_says_the_new_content_is_readable():
    runtime = ReplacingRuntime()
    _post(runtime, REPLACED)
    instruction = runtime.instructions[0]
    assert "교체" in instruction
    assert "sources/15/parsed/content.md" in instruction


def test_replaced_backlinks_are_still_found_from_the_old_content():
    """backlink 는 옛 내용으로 계산해야 한다 — 고칠 곳은 옛 근거를 인용한 문단이다."""
    runtime = ReplacingRuntime()
    _post(runtime, REPLACED)
    assert PAGE_ADDRESS in runtime.instructions[0]


def test_replaced_does_not_emit_a_removal_unlink():
    """교체는 문서가 사라진 것이 아니다 — 관계를 끊으면 새 내용의 근거까지 잃는다.

    `relationChanges` 는 위키↔위키 전용이라(S15P11B106-157) `documentId` 필드 자체가
    없다 — 이 단정은 항상 공허하게 통과한다. 삭제되지 않는다는 것 자체는
    `test_dropping_a_footnote_yields_no_relation_change`(test_changes.py) 가 이미
    검증한다."""
    body = _post(ReplacingRuntime(), REPLACED).json()
    assert [r for r in body["relationChanges"]
            if r["action"] == "remove" and r.get("documentId") == "15"] == []


def test_agent_error_fails_the_removal():
    class FailingRuntime:
        name = "fake-fail"

        async def arun(self, instruction, **_):
            return RunResult(text="", tool_calls={"guide": 1}, error="모델 오류")

    response = _post(FailingRuntime())
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert body["failureStage"] == "agent_error"


def test_a_citing_page_gone_missing_while_fetching_backlinks_is_scope_changed():
    """`session.citation_backlinks`(옛 backlink 계산, `get_citation_backlinks` 를 감싼
    것)는 `WikiSession.__aenter__`·`run_agent` 의 `try` 밖, 라우터 핸들러 본문에서
    불린다. 승격하지 않으면 목록에 있던 위키의 본문이 사라진 경합(`_ensure_body` 의
    `ScopeChangedError`)이 `errors.py` 마지막 그물로 떨어져 `failureStage` 없는 500 만
    나간다."""
    import httpx

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/index"):
            return httpx.Response(200, json={"scopeVersion": SCOPE_VERSION,
                                             "scopeKey": SCOPE,
                                             "indexMarkdown": "# 목차\n"})
        if path.endswith("/categories"):
            return httpx.Response(200, json={"scopeVersion": SCOPE_VERSION, "items": []})
        if path.endswith("/relations"):
            return httpx.Response(200, json={
                "scopeVersion": SCOPE_VERSION,
                "items": [{"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]}]})
        if path.endswith("/wiki-pages"):
            return httpx.Response(200, json={
                "scopeVersion": SCOPE_VERSION, "nextCursor": None,
                "items": [{k: v for k, v in CITED_PAGE.items() if k != "contentMarkdown"}]})
        if path.endswith("/content"):
            # 목록에 있던 위키 101 의 본문이 그 사이 사라졌다 — `_ensure_body` 가
            # `ScopeChangedError` 로 닫는 경로(설계 2.6)를 태운다.
            return httpx.Response(404, json={"code": "WIKI_NOT_FOUND", "message": "없습니다"})
        return httpx.Response(200, json={"scopeVersion": SCOPE_VERSION,
                                         "nextCursor": None, "items": []})

    runtime = RemovingRuntime()
    response = _post(runtime, gateway=httpx.MockTransport(handler))
    assert response.status_code == 500, response.json()
    body = response.json()
    assert body["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert body["failureStage"] == "scope_changed"
    assert runtime.instructions == [], "backlink 를 모으는 단계에서 끊겨 에이전트를 부르지 않는다"


def test_unknown_change_type_is_400():
    response = _post(RemovingRuntime(), dict(REQUEST, changeType="document_exploded"))
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_WIKI_TRANSFORMATION_REQUEST"


def test_removal_without_the_removed_markdown_is_400():
    """걷어낼 근거가 무엇이었는지 모르면 backlink 도 못 찾고 인용 대조도 못 한다.

    어느 필드가 문제인지 `fieldErrors` 에 적는다 (API_컨벤션 6.2) — Spring 이 그것을
    사람이 읽을 문장으로 바꾼다."""
    body = dict(REQUEST)
    body.pop("removedParsedMarkdown")
    response = _post(RemovingRuntime(), body)
    assert response.status_code == 400
    payload = response.json()
    assert payload["code"] == "INVALID_WIKI_TRANSFORMATION_REQUEST"
    assert [e["field"] for e in payload["fieldErrors"]] == ["removedParsedMarkdown"]


def test_a_replacement_is_bounded_by_the_larger_of_the_two_bodies():
    """교체는 두 본문이 모두 색인된다 — 옛 본문으로 backlink 를 잡고 새 본문으로 덮는다.
    새 본문만 재면 40k짜리 옛 본문이 「새 문서가 짧다」는 이유로 통과한다."""
    response = _post(ReplacingRuntime(),
                     dict(REPLACED, removedParsedMarkdown="가" * 40_000,
                          parsedMarkdown=NEW_SOURCE))
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert body["failureStage"] == "context_load"


def test_the_removed_document_size_is_what_bounds_the_run(monkeypatch):
    """removed 에는 `parsedMarkdown` 이 없다 — 상한을 그것으로 재면 모든 삭제가 최소
    상한을 받는다 (설계 §1 의 「removed 면 removedParsedMarkdown 기준」).

    앞 판본은 `run()` 만 가진 sync 런타임으로 상한을 받아 봤다. 하위 프로세스 런타임은
    위키 요청을 아예 받지 못하게 됐으므로(S15P11B106-175, 조회 API 본문이 그쪽에 닿지
    않는다) 라우터가 무엇으로 재는지를 직접 본다."""
    from wiki_api.routers import wiki as wiki_router

    sizes: list[int] = []
    real = wiki_router.time_limit_seconds
    monkeypatch.setattr(wiki_router, "time_limit_seconds",
                        lambda size: sizes.append(size) or real(size))

    big = SOURCE_MD + "가" * 20_000
    _post(RemovingRuntime(), dict(REQUEST, removedParsedMarkdown=big))
    assert sizes == [len(big)]


def test_an_oversized_removed_document_fails_at_context_load():
    response = _post(RemovingRuntime(),
                     dict(REQUEST, removedParsedMarkdown="가" * 40_000))
    assert response.status_code == 500
    assert response.json()["failureStage"] == "context_load"


def test_instruction_text_for_removal_names_the_change_type():
    text = reconcile_instruction("sources/15/parsed/content.md", SCOPE, "document_removed",
                                 [{"address": PAGE_ADDRESS, "footnote": "1",
                                   "location": "2장 정례 회의", "quote": "주간 회의는"}])
    assert "삭제" in text
    assert PAGE_ADDRESS in text
    assert "2장 정례 회의" in text


def test_instruction_text_for_replacement_mentions_the_new_content():
    text = reconcile_instruction("sources/15/parsed/content.md", SCOPE,
                                 "document_replaced", [])
    assert "교체" in text


class DoingNothingRuntime:
    """backlink 가 있다는 지시문을 받고도 아무 것도 안 고치는 에이전트 자리.

    `affected` 는 서버가 조회 API 관계를 뒤집어 직접 계산한 값이라 에이전트 판단이
    끼어들 자리가 없다 — 이 실행처럼 읽고 lint 만 부르고 끝내면, 고칠 곳이 있었는데도
    `pending_changes()` 가 비어 `wikiChanges` 없는 성공 응답이 나갈 뻔했던 경우다.
    """

    name = "fake-do-nothing"

    async def arun(self, instruction, **_):
        return RunResult(text="확인했지만 고칠 것이 없다고 판단했다",
                         tool_calls={"guide": 1, "read": 1, "lint": 1})


def test_removal_fails_when_a_real_backlink_is_never_addressed():
    """S15P11B106-214 후속: 인용하는 페이지가 실재하는데 에이전트가 아무 위키도 고치지
    않으면, 조용한 빈 배열 성공이 아니라 에이전트 오류로 떨어져야 한다."""
    response = _post(DoingNothingRuntime())
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert body["failureStage"] == "agent_error"


def test_addition_never_triggers_the_backlink_check():
    """`document_added` 에는 `affected` 자체가 없다 — 새 검증이 추가 경로를 막으면 안 된다."""

    class AddingRuntime:
        name = "fake-add"

        async def arun(self, instruction, *, fs, scope_id, **_):
            await fs.write(scope_id, await fs.allocate_page(scope_id), PAGE_MD,
                           title="새 페이지", category="근무 정책", tags=["신규"])
            return RunResult(text="새 페이지를 만들었다",
                             tool_calls={"guide": 1, "read": 1, "create": 1, "lint": 1})

    response = _client(AddingRuntime()).post(
        "/internal/v1/wiki-transformations",
        json={"jobId": "44", "documentId": "16", "scopeKey": SCOPE,
              "changeType": "document_added", "parsedMarkdown": SOURCE_MD,
              "wikiCapability": CAPABILITY, "scopeVersion": SCOPE_VERSION},
        headers={"X-Internal-API-Key": API_KEY})
    assert response.status_code == 200, response.json()
