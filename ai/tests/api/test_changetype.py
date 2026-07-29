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
from .test_api_wiki import (API_KEY, INDEX_MD, PAGE_ADDRESS, PAGE_MD, SCOPE,
                                 SOURCE_MD, SOURCE_NAME)

CITED_PAGE_MD = (
    PAGE_MD.rstrip() + "\n\n주간 회의는 30분을 넘기지 않는다[^1].\n\n"
    f'[^1]: {SOURCE_NAME}, 2장 정례 회의 — "주간 회의는 30분을 넘기지 않는다"\n'
)

REQUEST = {
    "jobId": "43",
    "documentId": "15",
    "scopeKey": SCOPE,
    "changeType": "document_removed",
    "removedParsedMarkdown": SOURCE_MD,
    "currentIndex": INDEX_MD,
    "currentCategories": [{"wikiCategoryId": "9", "name": "근무 정책"}],
    "selectedWikis": [{
        "wikiId": "101", "categoryId": "9", "title": "커뮤니케이션 가이드",
        "summary": "비동기 우선 소통", "contentMarkdown": CITED_PAGE_MD,
    }],
}


class RemovingRuntime:
    """근거가 사라진 문단을 걷어내는 에이전트 자리."""

    name = "fake-remove"

    def __init__(self):
        self.instructions: list[str] = []

    async def arun(self, instruction, *, fs, scope_id, **_):
        self.instructions.append(instruction)
        from wiki_mcp.tools.references import sync_references
        cleaned = PAGE_MD                      # 각주와 그 문단을 뺀 본문
        await fs.write(scope_id, PAGE_ADDRESS, cleaned,
                       title="커뮤니케이션 가이드", category="근무 정책",
                       tags=["커뮤니케이션", "회의"])
        await sync_references(fs, scope_id, PAGE_ADDRESS, cleaned)
        return RunResult(text="문서 15 근거를 걷어냈다",
                         tool_calls={"guide": 1, "references": 1, "edit": 1, "lint": 1})


def _client(runtime):
    app = create_app(api_key=API_KEY)
    app.state.runtime = runtime
    return TestClient(app, raise_server_exceptions=False)


def _post(runtime, body=None):
    return _client(runtime).post("/internal/v1/wiki-transformations", json=body or REQUEST,
                                 headers={"X-Internal-API-Key": API_KEY})


def test_removal_returns_the_transform_shape():
    response = _post(RemovingRuntime())
    assert response.status_code == 200, response.json()
    assert set(response.json()) == {"summary", "categoryChanges", "wikiChanges",
                                    "relationChanges", "indexEntries"}


def test_removing_the_last_citation_unlinks_the_document():
    body = _post(RemovingRuntime()).json()
    unlinks = [r for r in body["relationChanges"]
               if r["action"] == "unlink" and r["documentId"] == "15"]
    assert unlinks, "사라진 문서를 가리키던 관계를 걷어내야 한다 (DR-002)"


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
    """교체는 문서가 사라진 것이 아니다 — 관계를 끊으면 새 내용의 근거까지 잃는다."""
    body = _post(ReplacingRuntime(), REPLACED).json()
    assert [r for r in body["relationChanges"]
            if r["action"] == "unlink" and r["documentId"] == "15"] == []


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


def test_the_removed_document_size_is_what_bounds_the_run():
    """removed 에는 `parsedMarkdown` 이 없다 — 상한을 그것으로 재면 모든 삭제가 최소
    상한을 받는다 (설계 §1 의 「removed 면 removedParsedMarkdown 기준」)."""
    from agent_runtime.limits import time_limit_seconds

    class SyncRuntime:
        name = "fake-sync"
        timeout = None

        def run(self, instruction, *, root, scope_key, job_id, timeout=None):
            type(self).timeout = timeout
            return RunResult(text="변경 없음", tool_calls={"guide": 1})

    big = SOURCE_MD + "가" * 20_000
    _post(SyncRuntime(), dict(REQUEST, removedParsedMarkdown=big))
    assert SyncRuntime.timeout == time_limit_seconds(len(big))


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
