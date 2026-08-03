"""wiki-edits — 관리자 채팅 수정.

`ai_job` 을 만들지 않는다 — `POST /api/v1/wikis/{id}/chat-messages` 가 동기 처리이고
응답에 `jobId` 가 없다. 그래서 범위 강제가 `jobId` 가 아니라 `requestId` 다.

S15P11B106-175: 이 엔드포인트도 위키를 실어 받지 않는다. 요청에 남는 것은 `wikiId`·
지시문·대화 이력과 허가값 둘뿐이고, 수정 대상 본문과 **근거 문서**는 Wiki 조회 API 에서
읽는다 — 근거 문서 목록은 하이드레이션이 받아둔 범위 관계(`documentRefs`)에 있고
본문은 `GET /documents/{documentId}/parsed` 가 준다
(`session.stage_evidence_documents`).
"""

from fastapi.testclient import TestClient

from wiki_api.app import create_app
from agent_runtime.base import RunResult, edit_instruction
from .test_api_wiki import (API_KEY, BACKEND_URL, CAPABILITY, LIVE_PAGE, PAGE_MD,
                            SCOPE, SCOPE_VERSION, SOURCE_MD, make_gateway)

PAGE_ADDRESS = "pages/101.md"

REQUEST = {
    "wikiId": "101",
    "scopeKey": SCOPE,
    "instruction": "회의 문단을 한 문장으로 줄여줘.",
    "wikiCapability": CAPABILITY,
    "scopeVersion": SCOPE_VERSION,
    "chatHistory": [{"senderType": "admin", "content": "회의 부분이 너무 길어."},
                    {"senderType": "agent", "content": "어느 문단을 말씀하시나요?"}],
}

# 이 위키가 근거로 쓴 문서. 조회 API 의 범위 관계가 그 사실을 알려주고
# (`documentRefs`), 파싱본 조회 API 가 본문을 준다.
RELATIONS = [{"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]}]
DOCUMENTS = {"15": {"documentId": "15", "originalFileName": "회의운영.pdf",
                    "parsedMarkdown": SOURCE_MD}}


def edit_gateway(**overrides):
    kwargs = {"relations": RELATIONS, "documents": DOCUMENTS}
    kwargs.update(overrides)
    return make_gateway([LIVE_PAGE], **kwargs)


class EditingRuntime:
    name = "fake-edit"

    def __init__(self):
        self.instructions: list[str] = []

    async def arun(self, instruction, *, fs, scope_id, **_):
        self.instructions.append(instruction)
        from wiki_mcp.tools.references import sync_references
        # 조회 API 는 본문을 지연 적재한다 — 고칠 페이지를 먼저 읽는 것이 실제 순서다.
        await fs.get(scope_id, PAGE_ADDRESS)
        body = (
            "---\ntitle: 커뮤니케이션 가이드\ndescription: 비동기 우선 소통\n"
            "tags: [커뮤니케이션, 회의]\ncategory: 근무 정책\n---\n\n"
            "회의는 최후의 수단이다[^1].\n\n"
            '[^1]: 회의운영.pdf, 2장 정례 회의 — "주간 회의는 30분을 넘기지 않는다"\n'
        )
        await fs.write(scope_id, PAGE_ADDRESS, body, title="커뮤니케이션 가이드",
                       category="근무 정책", tags=["커뮤니케이션", "회의"])
        await sync_references(fs, scope_id, PAGE_ADDRESS, body)
        return RunResult(text="회의 문단을 한 문장으로 줄였습니다.",
                         tool_calls={"guide": 1, "read": 1, "edit": 1, "lint": 1})


def _client(runtime, gateway=None):
    app = create_app(api_key=API_KEY, backend_base_url=BACKEND_URL)
    app.state.runtime = runtime
    app.state.query_transport = gateway or edit_gateway()
    return TestClient(app, raise_server_exceptions=False)


def _post(runtime, body=None, gateway=None):
    return _client(runtime, gateway).post(
        "/internal/v1/wiki-edits", json=body or REQUEST,
        headers={"X-Internal-API-Key": API_KEY})


def test_edit_response_carries_an_agent_message():
    """관리자에게 보여줄 문장이다. 계약이 agentMessage 를 요구한다."""
    body = _post(EditingRuntime()).json()
    assert body["agentMessage"] == "회의 문단을 한 문장으로 줄였습니다."
    assert set(body) == {"agentMessage", "summary", "categoryChanges", "wikiChanges",
                         "relationChanges", "indexEntries"}


def test_edit_is_an_update_on_the_existing_wiki():
    body = _post(EditingRuntime()).json()
    change = body["wikiChanges"][0]
    assert change["action"] == "update"
    assert change["wikiId"] == "101"
    assert "회의는 최후의 수단이다" in change["contentMarkdown"]


def test_an_unknown_wiki_id_is_a_bad_request():
    """조회 API 가 준 카탈로그에 없는 `wikiId` 는 요청값의 문제다 — 그 위키가 지워졌거나 이
    허가의 범위 밖이다.

    **404 가 아니라 400 이다.** 계약(v1.3.0)이 이 엔드포인트에 허용한 상태는 400·401·500
    뿐이고, 400 을 "지시 내용 또는 Wiki 컨텍스트 오류"로 정의한다.

    본문 모양까지 본다: Spring 이 `code` 로 분기하므로 상태코드만 맞고 `code` 가 다르면
    백엔드가 이 응답을 해석할 수 없다."""
    response = _post(EditingRuntime(), dict(REQUEST, wikiId="999"))
    assert response.status_code == 400, response.json()
    body = response.json()
    assert body["code"] == "INVALID_WIKI_EDIT_REQUEST"
    assert set(body) == {"timestamp", "status", "error", "code", "message", "path",
                         "fieldErrors"}
    assert body["status"] == 400
    assert body["error"] == "Bad Request"
    assert body["path"] == "/internal/v1/wiki-edits"
    assert body["fieldErrors"] == []
    # 제목도 존재 여부도 노출하지 않는다.
    assert "999" not in body["message"]
    # `requestId` 는 본문에 넣지 않는다 — 헤더로만 나간다 (컨벤션 6.4).
    assert "requestId" not in body
    assert response.headers["X-Request-Id"]


def test_an_empty_scope_fails_before_the_agent():
    """고칠 위키가 한 장도 없는 범위는 모순이다 — `requires_existing_wiki=True` 다.

    앞 판본은 요청의 `currentWiki.contentMarkdown` 이 비었는지로 같은 것을 봤다. 요청이
    본문을 싣지 않게 되면서 그 판단이 하이드레이션 뒤로 옮겨갔다 (설계 4.1)."""
    runtime = EditingRuntime()
    response = _post(runtime, gateway=make_gateway([], index_markdown="# 목차\n"))
    assert response.status_code == 500, response.json()
    body = response.json()
    assert body["code"] == "WIKI_EDIT_FAILED"
    assert body["failureStage"] == "context_load"
    assert runtime.instructions == []


def test_the_page_comes_from_the_gateway_not_from_the_request():
    """수정 대상 주소는 조회 API 카탈로그가 정한다 — 목록에 `wikiPath` 가 없으므로
    `pages/{wikiId}.md` 다 (설계 §3)."""
    runtime = EditingRuntime()
    body = _post(runtime).json()
    assert PAGE_ADDRESS in runtime.instructions[0]
    # I1: 하이드레이션 주소는 우리 것이고 Spring 의 `wiki_path` 가 아니다 — 기존 위키를
    # 고친 결과에 `wikiPath` 를 실어 보내면 Spring 이 진짜 파일 경로를 덮어쓴다.
    assert body["wikiChanges"][0]["action"] == "update"
    assert body["wikiChanges"][0]["wikiPath"] is None


class BadQuoteEditingRuntime(EditingRuntime):
    """같은 편집을 하지만 각주 인용문이 원본문서에 없는 문장이다 (위치는 맞다).

    2026-08-02: `citation-quote-not-found`는 `error`에서 `warn`으로 내렸다 (위치가 맞으면
    표현이 원문과 완전히 같지 않아도 근거는 있는 것으로 본다 — 실기동에서 에이전트가 리터럴
    일치를 못 찾아 턴 상한까지 헤매다 실패하는 사례가 나왔다). 그래서 이 클래스는 이제
    500이 아니라 200 + warn을 낸다."""

    async def arun(self, instruction, *, fs, scope_id, **_):
        self.instructions.append(instruction)
        from wiki_mcp.tools.references import sync_references
        await fs.get(scope_id, PAGE_ADDRESS)
        body = (
            "---\ntitle: 커뮤니케이션 가이드\ndescription: 비동기 우선 소통\n"
            "tags: [커뮤니케이션, 회의]\ncategory: 근무 정책\n---\n\n"
            "회의는 최후의 수단이다[^1].\n\n"
            '[^1]: 회의운영.pdf, 2장 정례 회의 — "주간 회의는 40분을 넘기지 않는다"\n'
        )
        await fs.write(scope_id, PAGE_ADDRESS, body, title="커뮤니케이션 가이드",
                       category="근무 정책", tags=["커뮤니케이션", "회의"])
        await sync_references(fs, scope_id, PAGE_ADDRESS, body)
        return RunResult(text="줄였습니다.",
                         tool_calls={"guide": 1, "read": 1, "edit": 1, "lint": 1})


class BadLocationEditingRuntime(EditingRuntime):
    """같은 편집을 하지만 각주 위치가 원본문서에 없는 절이다 — 위치는 여전히 `error`다."""

    async def arun(self, instruction, *, fs, scope_id, **_):
        self.instructions.append(instruction)
        from wiki_mcp.tools.references import sync_references
        await fs.get(scope_id, PAGE_ADDRESS)
        body = (
            "---\ntitle: 커뮤니케이션 가이드\ndescription: 비동기 우선 소통\n"
            "tags: [커뮤니케이션, 회의]\ncategory: 근무 정책\n---\n\n"
            "회의는 최후의 수단이다[^1].\n\n"
            '[^1]: 회의운영.pdf, 9장 없는 장 — "주간 회의는 30분을 넘기지 않는다"\n'
        )
        await fs.write(scope_id, PAGE_ADDRESS, body, title="커뮤니케이션 가이드",
                       category="근무 정책", tags=["커뮤니케이션", "회의"])
        await sync_references(fs, scope_id, PAGE_ADDRESS, body)
        return RunResult(text="줄였습니다.",
                         tool_calls={"guide": 1, "read": 1, "edit": 1, "lint": 1})


def test_evidence_documents_are_staged_from_the_gateway():
    """근거 문서를 조회 API 에서 읽어 라이브 층에 올린다. lint 는 그것으로 원문 대조한다 —
    스테이징이 안 되면 대조할 원문이 없어 정상 각주가 `unresolved-citation` 이 된다."""
    response = _post(EditingRuntime())
    assert response.status_code == 200, response.json()
    # 원문에 실제로 있는 인용문이라 통과했다.
    assert "회의는 최후의 수단이다" in response.json()["wikiChanges"][0]["contentMarkdown"]


def test_a_quote_absent_from_the_evidence_document_only_warns():
    """인용문이 원문과 리터럴로 다르지만 위치는 맞다 — `warn`이라 요청은 통과한다."""
    response = _post(BadQuoteEditingRuntime())
    assert response.status_code == 200, response.json()


def test_a_location_absent_from_the_evidence_document_fails_lint():
    """대조가 실제로 일어난다는 증거. 통과만 확인하면 스테이징이 빠져도 테스트가 녹색이다.
    위치 불일치는 여전히 `error`라 요청이 실패한다."""
    response = _post(BadLocationEditingRuntime())
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_EDIT_FAILED"
    assert body["failureStage"] == "lint_failed"


def test_a_wiki_with_no_evidence_relation_stages_nothing():
    """관계 표에 문서가 없으면 파싱본 조회 API 를 부를 일이 없다 — 그 경우에도 죽지 않는다.

    각주가 없는 위키(요약만 고치는 수정)가 그 경우다. 없는 문서를 지어내 조회하면
    조회 API 예산만 태우고 404 로 실패한다."""
    class SummaryOnlyRuntime(EditingRuntime):
        async def arun(self, instruction, *, fs, scope_id, **_):
            self.instructions.append(instruction)
            row = await fs.get(scope_id, PAGE_ADDRESS)
            self.seen = (row or {}).get("content")
            return RunResult(text="고칠 것이 없습니다.",
                             tool_calls={"guide": 1, "read": 1})

    runtime = SummaryOnlyRuntime()
    response = _post(runtime, gateway=edit_gateway(relations=[]))
    assert response.status_code == 200, response.json()
    assert runtime.seen == PAGE_MD


def test_a_scope_change_while_staging_evidence_documents_is_scope_changed():
    """`stage_evidence_documents`(근거 문서를 조회 API 에서 읽어 올리는 호출)는
    `WikiSession.__aenter__`·`run_agent` 의 `try` 밖, 라우터 핸들러 본문에서 불린다.
    승격하지 않으면 범위 변경이 `errors.py` 의 마지막 그물로 떨어져 `failureStage`
    없는 500 만 나가고, 재시도로 풀리는 실패가 Spring 에 「원인 미상」으로 남는다."""
    gateway = edit_gateway(documents={
        "15": {"documentId": "15", "originalFileName": "회의운영.pdf",
              "parsedMarkdown": SOURCE_MD, "scopeVersion": SCOPE_VERSION + 1}})
    runtime = EditingRuntime()
    response = _post(runtime, gateway=gateway)
    assert response.status_code == 500, response.json()
    body = response.json()
    assert body["code"] == "WIKI_EDIT_FAILED"
    assert body["failureStage"] == "scope_changed"
    assert runtime.instructions == [], "근거 문서를 올리는 단계에서 끊겨 에이전트를 부르지 않는다"


def test_a_scope_change_while_fetching_the_page_for_the_time_limit_is_scope_changed():
    """시간 상한을 재려고 페이지 본문을 당기는 `session.content_length_for` 호출도
    `WikiSession.__aenter__`·`run_agent` 의 `try` 밖, 라우터 핸들러 본문에서 불린다
    (`stage_evidence_documents` 뒤·`run_agent` 전). 승격하지 않으면 이 범위 변경도
    `errors.py` 의 마지막 그물로 떨어져 `failureStage` 없는 500 만 나간다."""
    gateway = edit_gateway(content_scope_version=SCOPE_VERSION + 1)
    runtime = EditingRuntime()
    response = _post(runtime, gateway=gateway)
    assert response.status_code == 500, response.json()
    body = response.json()
    assert body["code"] == "WIKI_EDIT_FAILED"
    assert body["failureStage"] == "scope_changed"
    assert runtime.instructions == [], "본문을 당기는 단계에서 끊겨 에이전트를 부르지 않는다"


def test_agent_error_fails_the_edit():
    class FailingRuntime:
        name = "fake-fail"

        async def arun(self, instruction, **_):
            return RunResult(text="", tool_calls={"guide": 1}, error="모델 오류")

    response = _post(FailingRuntime())
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_EDIT_FAILED"
    assert body["failureStage"] == "agent_error"


def test_a_blind_edit_run_is_rejected():
    """읽기 툴을 한 번도 안 부르고 덮어쓴 수정은 실패다 (설계 4.2)."""
    class BlindRuntime:
        name = "fake-blind"

        async def arun(self, instruction, *, fs, scope_id, **_):
            await fs.write(scope_id, PAGE_ADDRESS, "---\ntitle: 덮어씀\n---\n\n본문\n")
            return RunResult(text="덮었다", tool_calls={"edit": 1})

    response = _post(BlindRuntime())
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_EDIT_FAILED"
    assert "현재 Wiki 를 읽지 않았습니다" in body["message"]


def test_edit_time_limit_counts_the_wiki_and_its_evidence(monkeypatch):
    """T8: 수정 지시의 작업량은 페이지 본문 + 근거 문서 길이다. 페이지 본문만 세면
    큰 근거 문서를 붙인 수정이 부당하게 짧은 상한을 받는다.

    상한을 재는 재료가 요청에서 조회 API 로 옮겨갔다 — 페이지 본문은 지연 적재한 라이브
    본문이고, 근거 문서 길이는 `stage_evidence_documents` 가 돌려주는 합계다.

    근거 문서가 시간 floor(작은 입력이 다 받는 최소치)를 넘길 만큼 커야 그 차이가
    드러난다 — 작은 근거는 페이지 본문과 똑같이 floor 로 뭉개져 이 규칙을 검증할 수
    없다. 그래서 조회 API 가 돌려주는 근거를 floor 경계(~9.6천자) 위로 키운다. 각주가
    인용하는 문장은 `SOURCE_MD` 안에 있으므로 그것을 그대로 품어 lint 를 통과시킨다.
    """
    from agent_runtime.limits import FLOOR_SECONDS, time_limit_seconds
    from wiki_api.routers import wiki as wiki_router

    big_evidence = SOURCE_MD + "\n\n" + "회의 운영 세칙 조항. " * 1400
    gateway = edit_gateway(documents={
        "15": {"documentId": "15", "originalFileName": "회의운영.pdf",
               "parsedMarkdown": big_evidence}})

    sizes: list[int] = []
    real = wiki_router.time_limit_seconds
    monkeypatch.setattr(wiki_router, "time_limit_seconds",
                        lambda size: sizes.append(size) or real(size))

    assert _post(EditingRuntime(), gateway=gateway).status_code == 200
    assert sizes == [len(PAGE_MD) + len(big_evidence)]
    # 페이지 본문만 세면 floor 에 뭉개지지만, 큰 근거를 더하면 그보다 길어진다.
    assert time_limit_seconds(len(PAGE_MD)) == FLOOR_SECONDS
    assert time_limit_seconds(sizes[0]) > time_limit_seconds(len(PAGE_MD))


def test_instruction_includes_the_admin_words_and_history():
    runtime = EditingRuntime()
    _post(runtime)
    text = runtime.instructions[0]
    assert "회의 문단을 한 문장으로 줄여줘." in text
    assert "회의 부분이 너무 길어." in text
    assert PAGE_ADDRESS in text


def test_instruction_text_limits_scope_to_the_named_page():
    text = edit_instruction(PAGE_ADDRESS, SCOPE, "줄여줘", [])
    assert "요청과 무관한" in text        # FR-AI-006 부분 재작성
