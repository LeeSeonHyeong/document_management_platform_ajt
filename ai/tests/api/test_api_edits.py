"""wiki-edits — 관리자 채팅 수정.

계약 요청 스키마를 그대로 쓴다. 요청 하나에 수정 대상 페이지와 근거 문서가 다 실려
있으므로 이 엔드포인트는 원래부터 되물을 것이 없었다.

`ai_job` 을 만들지 않는다 — `POST /api/v1/wikis/{id}/chat-messages` 가 동기 처리이고
응답에 `jobId` 가 없다. 그래서 범위 강제가 `jobId` 가 아니라 `requestId` 다.

v1.1.0: 계약은 그대로지만 되묻기가 사라졌다 (설계 §1). 수정 대상 페이지는 요청의
`currentWiki` 가 들고 오고 주소는 `pages/{wikiId}.md` 다 (§3) — 근거 문서는 원래부터
`evidenceDocuments` 로 왔으므로 이쪽은 바뀔 것이 없었다.
"""

from fastapi.testclient import TestClient

from wiki_api.app import create_app
from agent_runtime.base import RunResult, edit_instruction
from .test_api_wiki import API_KEY, PAGE_MD, SCOPE, SOURCE_MD

PAGE_ADDRESS = "pages/101.md"

REQUEST = {
    "wikiId": "101",
    "scopeKey": SCOPE,
    "instruction": "회의 문단을 한 문장으로 줄여줘.",
    "currentWiki": {"title": "커뮤니케이션 가이드", "contentMarkdown": PAGE_MD},
    "evidenceDocuments": [{"documentId": "15", "originalFileName": "회의운영.pdf",
                           "parsedMarkdown": SOURCE_MD}],
    "chatHistory": [{"senderType": "admin", "content": "회의 부분이 너무 길어."},
                    {"senderType": "agent", "content": "어느 문단을 말씀하시나요?"}],
}


class EditingRuntime:
    name = "fake-edit"

    def __init__(self):
        self.instructions: list[str] = []

    async def arun(self, instruction, *, fs, scope_id, **_):
        self.instructions.append(instruction)
        from wiki_mcp.tools.references import sync_references
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


def _client(runtime):
    app = create_app(api_key=API_KEY)
    app.state.runtime = runtime
    return TestClient(app, raise_server_exceptions=False)


def test_edit_response_carries_an_agent_message():
    """관리자에게 보여줄 문장이다. 계약이 agentMessage 를 요구한다."""
    body = _client(EditingRuntime()).post("/internal/v1/wiki-edits", json=REQUEST,
                                          headers={"X-Internal-API-Key": API_KEY}).json()
    assert body["agentMessage"] == "회의 문단을 한 문장으로 줄였습니다."
    assert set(body) == {"agentMessage", "summary", "categoryChanges", "wikiChanges",
                         "relationChanges", "indexEntries"}


def test_edit_is_an_update_on_the_existing_wiki():
    body = _client(EditingRuntime()).post("/internal/v1/wiki-edits", json=REQUEST,
                                          headers={"X-Internal-API-Key": API_KEY}).json()
    change = body["wikiChanges"][0]
    assert change["action"] == "update"
    assert change["wikiId"] == "101"
    assert "회의는 최후의 수단이다" in change["contentMarkdown"]


def test_an_unreadable_wiki_is_a_bad_request():
    """수정 대상 본문이 비어 있으면 편집할 페이지가 없다 — Spring 이 위키를 읽지 못한 채
    (삭제됐거나 범위 밖) 요청을 보낸 경우다.

    **404 가 아니라 400 이다.** 계약(v1.3.0)이 이 엔드포인트에 허용한 상태는 400·401·500
    뿐이고, 400 을 "지시 내용 또는 Wiki 컨텍스트 오류"로 정의한다 — 요청이 실어 온 위키
    컨텍스트가 비어 있는 것이 정확히 그 경우다. 이 서버는 요청에 실린 위키만 알기 때문에
    되물을 곳도 없다.

    본문 모양까지 본다: Spring 이 `code` 로 분기하므로 상태코드만 맞고 `code` 가 다르면
    백엔드가 이 응답을 해석할 수 없다."""
    request = dict(REQUEST, wikiId="999",
                   currentWiki={"title": "커뮤니케이션 가이드", "contentMarkdown": ""})
    response = _client(EditingRuntime()).post("/internal/v1/wiki-edits", json=request,
                                              headers={"X-Internal-API-Key": API_KEY})
    assert response.status_code == 400
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


def test_the_page_comes_from_the_request_not_from_spring():
    """v1.1.0: 되묻지 않는다. `currentWiki` 가 곧 이번 세션의 위키이고 주소는
    `pages/{wikiId}.md` 다 (설계 §3) — `wikiPath` 를 받지 않으므로 다른 주소가 있을 수 없다."""
    runtime = EditingRuntime()
    body = _client(runtime).post("/internal/v1/wiki-edits", json=REQUEST,
                                 headers={"X-Internal-API-Key": API_KEY}).json()
    assert PAGE_ADDRESS in runtime.instructions[0]
    # I1: 하이드레이션 주소는 우리 것이고 Spring 의 `wiki_path` 가 아니다 — 기존 위키를
    # 고친 결과에 `wikiPath` 를 실어 보내면 Spring 이 진짜 파일 경로를 덮어쓴다.
    assert body["wikiChanges"][0]["action"] == "update"
    assert body["wikiChanges"][0]["wikiPath"] is None


class BadQuoteEditingRuntime(EditingRuntime):
    """같은 편집을 하지만 각주 인용문이 원본문서에 없는 문장이다."""

    async def arun(self, instruction, *, fs, scope_id, **_):
        self.instructions.append(instruction)
        from wiki_mcp.tools.references import sync_references
        body = (
            "---\ntitle: 커뮤니케이션 가이드\ndescription: 비동기 우선 소통\n"
            "tags: [커뮤니케이션, 회의]\ncategory: 근무 정책\n---\n\n"
            "회의는 최후의 수단이다[^1].\n\n"
            '[^1]: 회의운영.pdf, 2장 정례 회의 — "주간 회의는 40분을 넘기지 않는다"\n'
        )
        await fs.write(scope_id, PAGE_ADDRESS, body, title="커뮤니케이션 가이드",
                       category="근무 정책", tags=["커뮤니케이션", "회의"])
        await sync_references(fs, scope_id, PAGE_ADDRESS, body)
        return RunResult(text="줄였습니다.", tool_calls={"guide": 1, "edit": 1, "lint": 1})


def test_evidence_documents_are_staged_for_lint():
    """요청이 원본문서를 주므로 되물을 필요가 없다. lint 는 그것으로 원문 대조한다 —
    스테이징이 안 되면 대조할 원문이 없어 인용 검사가 통째로 무력해진다."""
    response = _client(EditingRuntime()).post("/internal/v1/wiki-edits", json=REQUEST,
                                              headers={"X-Internal-API-Key": API_KEY})
    assert response.status_code == 200, response.json()
    # 원문에 실제로 있는 인용문이라 통과했다.
    assert "회의는 최후의 수단이다" in response.json()["wikiChanges"][0]["contentMarkdown"]


def test_a_quote_absent_from_the_evidence_document_fails_lint():
    """대조가 실제로 일어난다는 증거. 통과만 확인하면 스테이징이 빠져도 테스트가 녹색이다."""
    response = _client(BadQuoteEditingRuntime()).post(
        "/internal/v1/wiki-edits", json=REQUEST,
        headers={"X-Internal-API-Key": API_KEY})
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_EDIT_FAILED"
    assert body["failureStage"] == "lint_failed"


def test_agent_error_fails_the_edit():
    class FailingRuntime:
        name = "fake-fail"

        async def arun(self, instruction, **_):
            return RunResult(text="", tool_calls={"guide": 1}, error="모델 오류")

    response = _client(FailingRuntime()).post("/internal/v1/wiki-edits", json=REQUEST,
                                              headers={"X-Internal-API-Key": API_KEY})
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "WIKI_EDIT_FAILED"
    assert body["failureStage"] == "agent_error"


class TimeoutRecordingRuntime:
    """sync 런타임. 세션이 넘긴 시간 상한을 기록한다."""

    name = "fake-sync-edit"

    def __init__(self):
        self.timeout = None

    def run(self, instruction, *, root, scope_key, job_id, timeout=None):
        self.timeout = timeout
        return RunResult(text="변경 없음", tool_calls={"guide": 1, "read": 1})


def test_edit_time_limit_counts_the_wiki_and_its_evidence():
    """T8: 수정 지시의 작업량은 페이지 본문 + 근거 문서 길이다. 페이지 본문만 세면
    큰 근거 문서를 붙인 수정이 부당하게 짧은 상한을 받는다."""
    from agent_runtime.limits import time_limit_seconds

    runtime = TimeoutRecordingRuntime()
    _client(runtime).post("/internal/v1/wiki-edits", json=REQUEST,
                          headers={"X-Internal-API-Key": API_KEY})
    expected = time_limit_seconds(len(PAGE_MD) + len(SOURCE_MD))
    assert runtime.timeout == expected
    assert runtime.timeout > time_limit_seconds(len(PAGE_MD))


def test_instruction_includes_the_admin_words_and_history():
    runtime = EditingRuntime()
    _client(runtime).post("/internal/v1/wiki-edits", json=REQUEST,
                          headers={"X-Internal-API-Key": API_KEY})
    text = runtime.instructions[0]
    assert "회의 문단을 한 문장으로 줄여줘." in text
    assert "회의 부분이 너무 길어." in text
    assert PAGE_ADDRESS in text


def test_instruction_text_limits_scope_to_the_named_page():
    text = edit_instruction(PAGE_ADDRESS, SCOPE, "줄여줘", [])
    assert "요청과 무관한" in text        # FR-AI-006 부분 재작성
