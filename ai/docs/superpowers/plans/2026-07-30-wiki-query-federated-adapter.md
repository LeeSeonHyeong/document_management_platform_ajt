# Wiki 조회 창구 federated 어댑터 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 에이전트가 위키 본문을 요청 본문으로 받는 대신 Spring 창구에서 조회하도록 하는 어댑터를 만들고, 창구가 없는 상태에서 가짜 게이트웨이로 검증한다.

**Architecture:** `vaultfs/base.py` 포트는 손대지 않는다. `spring.py`(push)와 나란히 `federated.py`(pull)를 세 번째 구현체로 추가하고, HTTP 호출은 `query_client.py`로 분리한다. 라이브 층은 카탈로그(메타데이터)만 미리 채우고 본문은 첫 접근에 당긴다. 검색은 창구(라이브)와 내부 색인(작업층) 두 곳에서 모아 합친다. 세션은 요청에 `wikiCapability`가 왔는지로 두 경로를 가르므로 **과도기 push 가 그대로 돈다.** `experiments/query_gateway.py`가 창구 7개를 흉내 내어, 백엔드 구현을 기다리지 않고 붙여볼 수 있게 한다.

**Tech Stack:** Python 3.12+, httpx(이미 의존성), aiosqlite, pytest + pytest-asyncio(`asyncio_mode=auto`), uv.

## Global Constraints

- 티켓 `S15P11B106-150`, 브랜치 `feature/S15P11B106-150-wiki-query-federation-contract` (이미 생성됨).
- 계약은 **v1.6.0으로 반영 완료**. 창구 7개·`X-Wiki-Capability`·`scope_changed`(요구사항 v2.11)·`wikiCapability`·`scopeVersion`·`selectedWikis[].wikiPath`·`404` `code` 4종이 모두 계약에 있다. 계약과 다르게 구현하지 않는다.
- **`wikiCapability` 는 로그·예외 메시지·telemetry 에 남기지 않는다.** 계약이 마스킹을 요구한다.
- **`404` 는 `code` 로 갈라 처리한다.** `WIKI_CAPABILITY_EXPIRED` 는 중단(`scope_changed`), 나머지는 대상 없음이다. 상태만 보고 뭉개면 안전하게 멈출 수 없다.
- **수정 범위는 `ai/` 뿐이다.** `../docs/`·`backend/`·루트 파일은 이 계획에서 건드리지 않는다.
- stage는 `ai/` 하위 경로만 명시한다. `git add -A`·`git add .` 금지. 커밋 전 `git status`로 확인한다.
- **push 경로를 깨지 않는다.** `SpringVaultFS`와 `wiki-transformations`의 `selectedWikis` 경로는 그대로 동작해야 한다. 창구 모드는 선택 사항이다.
- 기존 테스트가 전부 통과해야 한다: `uv run pytest -m "not ocr"` → 현재 **506 passed, 2 skipped**.
- `mcp`라는 이름의 새 디렉터리를 만들지 않는다. `tests/mcp/`는 이미 있으므로 그 안에 추가한다. `tests/__init__.py`를 지우지 않는다.
- 직접 임포트하는 패키지는 전이 의존성이어도 `pyproject.toml`에 명시한다.
- 허가 범위 밖은 `404`다. `403`을 쓰지 않는다 (NFR-SEC-003 · FR-ACL-006).
- 저장소 루트는 환경변수가 아니라 CLI/인자로 준다.
- 명령은 `ai/`에서 실행한다. `node`가 필요하면 `/home/ssafy/.vscode-server/bin/*/node`를 쓴다 (시스템에 없다).

## File Structure

| 파일 | 책임 |
| --- | --- |
| `src/wiki_api/errors.py` (수정) | `FailureStage.SCOPE_CHANGED` 추가 |
| `src/wiki_mcp/vaultfs/query_client.py` (신규) | 창구 7개 HTTP 호출. 허가 헤더·`scopeVersion` 감시·`404` `code` 분기·본문 캐시·호출 수 상한. **SQL을 모른다** |
| `src/wiki_mcp/vaultfs/federated.py` (신규) | `SpringVaultFS` 상속. 카탈로그 하이드레이션·본문 지연 적재·검색 두 섹션 병합·원격 관계. **HTTP를 모른다** |
| `src/wiki_mcp/vaultfs/__init__.py` (수정) | 새 클래스 export |
| `src/wiki_mcp/tools/search.py` (수정) | 라이브/작업층 두 섹션 렌더링 |
| `src/wiki_api/schemas.py` (수정) | `wikiCapability`·`scopeVersion`·`selectedWikis[].wikiPath` 수용 |
| `src/wiki_api/session.py` (수정) | `wikiCapability` 가 오면 `FederatedVaultFS`, 없으면 지금처럼 `SpringVaultFS` |
| `experiments/query_gateway.py` (신규) | 창구 7개를 흉내 내는 HTTP 서버. 폐기 가능 |
| `tests/mcp/test_query_client.py` (신규) | 클라이언트 단위 — `MockTransport`, 네트워크 없음 |
| `tests/mcp/test_federated_vaultfs.py` (신규) | 어댑터 단위 |
| `tests/mcp/test_federated_gateway.py` (신규) | 9.2 검증 6항목 |
| `tests/api/test_federated_session.py` (신규) | 세션 분기 — 두 경로가 모두 도는지 |

경계 이유: HTTP와 SQL을 한 파일에 두면 둘 중 하나만 테스트할 수 없다. `query_client.py`는 `MockTransport`로 서버 없이, `federated.py`는 가짜 클라이언트로 네트워크 없이 검증한다.

---

### Task 1: `scope_changed` 실패 단계

계약 v1.5.0과 요구사항 v2.11이 이미 이 값을 정의했다. 코드가 아직 못 낸다.

**Files:**
- Modify: `src/wiki_api/errors.py:28-34`
- Test: `tests/api/test_errors.py`

**Interfaces:**
- Consumes: 없음
- Produces: `FailureStage.SCOPE_CHANGED` (값 `"scope_changed"`) — Task 2·3이 이 값으로 실패를 표시한다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/api/test_errors.py` 끝에 추가한다.

```python
def test_scope_changed_failure_stage_exists():
    """계약 v1.5.0·요구사항 v2.11 이 정의한 값이다.

    변환 중 같은 scope_key 의 Wiki 가 바뀌어(DR-030) 반영하지 않고 종료한 경우.
    """
    from wiki_api.errors import FailureStage

    assert FailureStage.SCOPE_CHANGED.value == "scope_changed"


def test_failure_stages_cover_requirement_list():
    """NFR-AI-003 이 열거한 여섯 단계가 모두 있는지 확인한다.

    agent_start 는 요구사항에 없는 코드 쪽 추가값이다 — 이 테스트는 요구사항이
    요구한 것이 빠지지 않았는지만 본다.
    """
    from wiki_api.errors import FailureStage

    required = {
        "context_load", "agent_timeout", "agent_error",
        "lint_failed", "assemble", "scope_changed",
    }
    assert required <= {stage.value for stage in FailureStage}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/api/test_errors.py -k scope_changed -v
```

Expected: FAIL — `AttributeError: SCOPE_CHANGED`

- [ ] **Step 3: 값을 추가한다**

`src/wiki_api/errors.py`의 `FailureStage`를 이렇게 바꾼다.

```python
class FailureStage(str, Enum):
    CONTEXT_LOAD = "context_load"
    AGENT_START = "agent_start"
    AGENT_TIMEOUT = "agent_timeout"
    AGENT_ERROR = "agent_error"
    LINT_FAILED = "lint_failed"
    ASSEMBLE = "assemble"
    # 변환 중 같은 scope_key 의 Wiki 가 바뀌어 반영할 수 없게 됐다 (DR-030).
    # 요구사항 v2.11 · 계약 v1.5.0 에서 신설했다. AI 가 제안해 정한 이름이므로
    # 백엔드가 다른 이름을 원하면 바꾼다 (설계 문서 7절).
    SCOPE_CHANGED = "scope_changed"
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/api/test_errors.py -v
```

Expected: PASS

- [ ] **Step 5: 커밋한다**

```bash
cd /home/ssafy/workspace/S15P11B106
git add ai/src/wiki_api/errors.py ai/tests/api/test_errors.py
git status --short   # ai/ 밖 변경이 섞이지 않았는지 확인
git commit -m "feat(ai): scope_changed 실패 단계 추가"
```

- [ ] **Step 6: `agent_start` 드리프트를 기록한다**

코드에 `agent_start`가 있는데 NFR-AI-003의 목록에 없다. **요구사항을 임의로 고치지 않는다.** 설계 문서 11절 미결에 한 줄 추가하고 같은 커밋 뒤에 따로 커밋한다.

`ai/docs/superpowers/specs/2026-07-30-wiki-query-federation-contract-design.md` 11절에 추가:

```markdown
- `FailureStage.AGENT_START` 가 코드에만 있다 (`errors.py:30`). NFR-AI-003 의 목록에 없어
  Spring 이 받으면 정의되지 않은 값이다. 요구사항에 넣을지 코드에서 뺄지 협의 필요 —
  이 티켓에서 임의로 정하지 않는다
```

```bash
git add ai/docs/superpowers/specs/2026-07-30-wiki-query-federation-contract-design.md
git commit -m "docs(ai): agent_start 가 요구사항 목록에 없다는 것을 미결에 기록"
```

---

### Task 2: 창구 HTTP 클라이언트

**Files:**
- Create: `src/wiki_mcp/vaultfs/query_client.py`
- Test: `tests/mcp/test_query_client.py`

**Interfaces:**
- Consumes: `VaultError` (`src/wiki_mcp/vaultfs/base.py`)
- Produces:
  - `class ScopeChangedError(VaultError)` — `.expected: int`, `.actual: int`
  - `class QueryNotFound(VaultError)`
  - `class QueryBudgetExceeded(VaultError)`
  - `MAX_QUERY_CALLS: int` (Task 8 에서 실측값으로 고친다. 초기값 `200`)
  - `class WikiQueryClient` with
    `__init__(base_url: str, *, api_key: str, capability: str, scope_key: str, scope_version: int, request_id: str | None = None, transport=None)`,
    `async search(query: str, limit: int = 10) -> list[dict]`,
    `async list_pages() -> list[dict]` (커서를 끝까지 따라가 전체를 모은다),
    `async page_content(wiki_id: str) -> dict`,
    `async relations(wiki_id: str) -> dict`,
    `async index_markdown() -> str`,
    `async categories() -> list[dict]`,
    `async parsed_document(document_id: str) -> dict`,
    `async aclose() -> None`,
    `body_fetches: int`·`calls: int` (테스트가 캐시 적중과 호출 수를 세는 계수기)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/mcp/test_query_client.py`를 새로 만든다.

```python
"""창구 HTTP 클라이언트 단위 테스트. 네트워크를 쓰지 않는다 — MockTransport 다."""

import httpx
import pytest

from wiki_mcp.vaultfs.query_client import (QueryBudgetExceeded, QueryNotFound,
                                           ScopeChangedError, WikiQueryClient)

BASE = "http://backend.test"


def _client(handler, *, scope_version: int = 47) -> WikiQueryClient:
    return WikiQueryClient(
        BASE, api_key="k", capability="cap-1", scope_key="D1-D2",
        scope_version=scope_version, request_id="req-42",
        transport=httpx.MockTransport(handler))


async def test_search_sends_capability_and_returns_items():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["capability"] = request.headers.get("X-Wiki-Capability")
        seen["api_key"] = request.headers.get("X-Internal-API-Key")
        seen["request_id"] = request.headers.get("X-Request-Id")
        return httpx.Response(200, json={
            "scopeVersion": 47,
            "items": [{"wikiId": "101", "title": "휴가 규정",
                       "breadcrumb": "휴가 규정 > 연차", "snippet": "연차는...",
                       "chunkIndex": 3, "contentHash": "h1"}],
        })

    client = _client(handler)
    items = await client.search("연차 이월", limit=5)
    await client.aclose()

    assert [item["wikiId"] for item in items] == ["101"]
    assert seen["capability"] == "cap-1"
    assert seen["api_key"] == "k"
    assert seen["request_id"] == "req-42"
    assert "scopeKey=D1-D2" in seen["url"]
    assert "limit=5" in seen["url"]


async def test_scope_version_mismatch_raises():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"scopeVersion": 48, "items": []})

    client = _client(handler, scope_version=47)
    with pytest.raises(ScopeChangedError) as caught:
        await client.search("연차")
    await client.aclose()

    assert caught.value.expected == 47
    assert caught.value.actual == 48


async def test_404_not_found_raises_query_not_found():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"code": "WIKI_NOT_FOUND",
                                         "message": "요청한 자료를 찾을 수 없습니다."})

    client = _client(handler)
    with pytest.raises(QueryNotFound):
        await client.page_content("999")
    await client.aclose()


async def test_404_capability_expired_raises_scope_changed():
    """같은 404 인데 code 가 다르면 처리가 다르다.

    허가가 죽은 것은 "그 페이지가 없다" 와 전혀 다른 상황이다. 상태만 보고
    뭉개면 안전하게 멈출 수 없다 (계약 1.6.0).
    """
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"code": "WIKI_CAPABILITY_EXPIRED",
                                         "message": "요청한 자료를 찾을 수 없습니다."})

    client = _client(handler)
    with pytest.raises(ScopeChangedError):
        await client.list_pages()
    await client.aclose()


async def test_list_pages_follows_cursor_to_the_end():
    """목록은 커서 페이지네이션이다. nextCursor 가 null 이 될 때까지 모은다."""
    seen_cursors = []

    def handler(request: httpx.Request) -> httpx.Response:
        cursor = request.url.params.get("cursor")
        seen_cursors.append(cursor)
        if not cursor:
            return httpx.Response(200, json={
                "scopeVersion": 47, "nextCursor": "c2",
                "items": [{"wikiId": "101"}]})
        return httpx.Response(200, json={
            "scopeVersion": 47, "nextCursor": None,
            "items": [{"wikiId": "108"}]})

    client = _client(handler)
    pages = await client.list_pages()
    await client.aclose()

    assert [page["wikiId"] for page in pages] == ["101", "108"]
    assert seen_cursors == [None, "c2"]


async def test_call_budget_is_enforced(monkeypatch):
    """상한을 선언만 하고 세지 않으면 아무 효과가 없다 (설계 9.5)."""
    from wiki_mcp.vaultfs import query_client as module

    monkeypatch.setattr(module, "MAX_QUERY_CALLS", 2)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"scopeVersion": 47, "items": []})

    client = _client(handler)
    await client.search("연차")
    await client.search("이월")
    with pytest.raises(QueryBudgetExceeded):
        await client.search("승인")
    await client.aclose()

    assert client.calls == 2


async def test_page_content_is_cached_by_hash():
    """같은 본문을 두 번 받지 않는다. 설계 9.2 검증 항목."""
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return httpx.Response(200, json={
            "scopeVersion": 47, "wikiId": "101", "title": "휴가 규정",
            "wikiPath": "wiki/D1-D2/pages/a3f2c1d4.md",
            "contentMarkdown": "본문", "contentHash": "h1"})

    client = _client(handler)
    first = await client.page_content("101")
    second = await client.page_content("101")
    await client.aclose()

    assert first["contentMarkdown"] == second["contentMarkdown"] == "본문"
    assert len(calls) == 1
    assert client.body_fetches == 1


async def test_index_and_categories_and_relations_and_parsed():
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/index"):
            return httpx.Response(200, json={"scopeVersion": 47,
                                             "scopeKey": "D1-D2",
                                             "indexMarkdown": "# 목차"})
        if path.endswith("/categories"):
            return httpx.Response(200, json={"scopeVersion": 47, "items": [
                {"wikiCategoryId": "9", "name": "휴가", "wikiCount": 12}]})
        if path.endswith("/relations"):
            return httpx.Response(200, json={"scopeVersion": 47, "wikiId": "101",
                                             "wikiRefs": ["102"],
                                             "documentRefs": ["15"],
                                             "backlinks": ["108"]})
        if path.endswith("/parsed"):
            return httpx.Response(200, json={"documentId": "15",
                                             "originalFileName": "취업규칙.pdf",
                                             "parsedMarkdown": "# 취업 규칙"})
        return httpx.Response(200, json={"scopeVersion": 47, "items": []})

    client = _client(handler)
    assert await client.index_markdown() == "# 목차"
    assert (await client.categories())[0]["wikiCount"] == 12
    assert (await client.relations("101"))["backlinks"] == ["108"]
    assert (await client.parsed_document("15"))["originalFileName"] == "취업규칙.pdf"
    assert await client.list_pages() == []
    await client.aclose()
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_query_client.py -v
```

Expected: FAIL — `ModuleNotFoundError: No module named 'wiki_mcp.vaultfs.query_client'`

- [ ] **Step 3: 클라이언트를 구현한다**

`src/wiki_mcp/vaultfs/query_client.py`를 새로 만든다.

```python
"""Wiki 조회 창구 클라이언트 — 계약 v1.5.0 「Wiki 조회 창구」 7개.

방향이 나머지와 반대다: AI 서버가 호출자이고 Spring 이 응답한다. 그래서 이 파일만
`httpx` 를 안다 — `federated.py` 는 HTTP 를 모르고 이 객체만 받는다.

**허가와 버전이 호출마다 붙는다.**

  * `X-Wiki-Capability` — 요청 1개·scopeKey 1개·scopeVersion 1개에 묶인 열람 허가.
    `requestId` 는 로그에 남는 correlation ID 라 인가에 쓰지 않는다 (설계 §7.2).
  * 응답의 `scopeVersion` 이 발급값과 다르면 그 자리에서 `ScopeChangedError` 다.
    반영 직전 백엔드 확인(DR-030)이 안전장치고 이것은 헛일을 줄이는 장치다.

**본문은 `wikiId` 로 캐시한다.** 요청 하나 안에서 `scopeVersion` 이 고정이므로 같은
`wikiId` 는 같은 본문이다. 버전이 바뀌면 그 자리에서 중단하니 캐시를 무효화할 일이
없다. 목록의 `contentHash` 는 백엔드가 준 값을 그대로 실어 보낼 뿐 캐시 키로 쓰지
않는다. 에이전트는 같은 페이지를 검색·읽기·수정에서 여러 번 보므로 캐시가 없으면
같은 본문을 반복해 받는다 (설계 9.4).

**`404` 는 `code` 로 갈라 처리한다.** HTTP 상태를 나누면 존재가 노출되므로 계약이
전부 `404` 로 통일했지만, 오류 본문의 `code` 는 허가를 통과한 호출자만 본다
(계약 1.6.0 · 설계 2.3).

```
WIKI_CAPABILITY_EXPIRED   허가가 죽었다      → ScopeChangedError (중단)
그 밖 (…_NOT_FOUND)       대상이 없다        → QueryNotFound
```

**호출 수를 센다.** 상한을 선언만 하고 세지 않으면 아무 효과가 없다. 목적은 시간이
아니라 D9(`10-onboarding`: 724초에 툴 77회, 출력 589토큰)처럼 같은 것을 반복 조회하며
맴도는 것을 끊는 것이다 (설계 9.5).
"""

from __future__ import annotations

import httpx

from .base import VaultError

# Task 8 에서 실측값으로 고친다. 그때까지의 안전판이다.
MAX_QUERY_CALLS = 200


class ScopeChangedError(VaultError):
    """작업 중 같은 범위의 위키가 바뀌었다. `failureStage = scope_changed` 로 나간다."""

    def __init__(self, expected: int, actual: int | None = None,
                 reason: str | None = None):
        detail = reason or f"시작 버전 {expected}, 현재 {actual}"
        super().__init__(f"작업 중 이 범위의 Wiki가 바뀌었습니다 — {detail}")
        self.expected = expected
        self.actual = actual


class QueryNotFound(VaultError):
    """허가 범위 밖이거나 없는 대상. 이 둘은 계약이 일부러 구분하지 않는다."""


class QueryBudgetExceeded(VaultError):
    """조회 횟수 상한 초과. `failureStage = agent_error` 로 나간다."""


class WikiQueryClient:
    """창구 7개. 한 요청의 생애만큼 살고 그때 버린다."""

    def __init__(self, base_url: str, *, api_key: str, capability: str,
                 scope_key: str, scope_version: int,
                 request_id: str | None = None, transport=None):
        self.scope_key = scope_key
        self.scope_version = scope_version
        # 테스트와 telemetry 가 보는 계수기.
        self.body_fetches = 0
        self.calls = 0
        self._bodies: dict[str, dict] = {}
        headers = {
            "X-Internal-API-Key": api_key,
            "X-Wiki-Capability": capability,
        }
        if request_id:
            headers["X-Request-Id"] = request_id
        self._http = httpx.AsyncClient(base_url=base_url.rstrip("/"),
                                       headers=headers, timeout=30.0,
                                       transport=transport)

    async def aclose(self) -> None:
        await self._http.aclose()

    # ----- 공통 -------------------------------------------------------------

    async def _get(self, path: str, params: dict | None = None) -> dict:
        if self.calls >= MAX_QUERY_CALLS:
            raise QueryBudgetExceeded(
                f"조회 횟수 상한 {MAX_QUERY_CALLS}회를 넘었습니다")
        self.calls += 1
        response = await self._http.get(path, params=dict(params or {}))
        if response.status_code == 404:
            code = self._error_code(response)
            if code == "WIKI_CAPABILITY_EXPIRED":
                raise ScopeChangedError(self.scope_version,
                                        reason="열람 허가가 만료되었습니다")
            raise QueryNotFound(f"요청한 자료를 찾을 수 없습니다 — {path}")
        if response.status_code >= 400:
            # 본문을 그대로 싣지 않는다 — capability 가 되돌아올 여지를 남기지 않는다.
            raise VaultError(f"창구 오류 {response.status_code} — {path}")
        body = response.json()
        # 파싱본 창구만 scopeVersion 이 없다 (설계 2.4·3.7). 문서 파싱 결과는 위키
        # 스냅샷과 무관하므로 위키 버전으로 판정하면 무관한 이유로 중단된다.
        actual = body.get("scopeVersion")
        if actual is not None and actual != self.scope_version:
            raise ScopeChangedError(self.scope_version, actual)
        return body

    @staticmethod
    def _error_code(response: httpx.Response) -> str | None:
        try:
            return response.json().get("code")
        except ValueError:
            return None

    def _scoped(self, extra: dict | None = None) -> dict:
        params = {"scopeKey": self.scope_key}
        params.update(extra or {})
        return params

    # ----- 창구 -------------------------------------------------------------

    async def search(self, query: str, limit: int = 10) -> list[dict]:
        body = await self._get("/internal/v1/wiki-search",
                               self._scoped({"query": query, "limit": limit}))
        return body.get("items", [])

    async def list_pages(self) -> list[dict]:
        """커서를 끝까지 따라가 카탈로그 전체를 모은다 (계약 1.6.0).

        `scopeVersion` 이 페이지 사이에 바뀌면 `_get` 이 중단시킨다 — 반쯤 낡은
        카탈로그로 작업하면 안 된다 (설계 3.2).
        """
        items: list[dict] = []
        cursor: str | None = None
        while True:
            params = self._scoped()
            if cursor:
                params["cursor"] = cursor
            body = await self._get("/internal/v1/wiki-pages", params)
            items.extend(body.get("items", []))
            cursor = body.get("nextCursor")
            if not cursor:
                return items

    async def page_content(self, wiki_id: str) -> dict:
        cached = self._bodies.get(wiki_id)
        if cached is not None:
            return cached
        body = await self._get(f"/internal/v1/wikis/{wiki_id}/content",
                               self._scoped())
        self._bodies[wiki_id] = body
        self.body_fetches += 1
        return body

    async def relations(self, wiki_id: str) -> dict:
        return await self._get(f"/internal/v1/wikis/{wiki_id}/relations",
                               self._scoped())

    async def index_markdown(self) -> str:
        body = await self._get(
            f"/internal/v1/wiki-spaces/{self.scope_key}/index")
        return body.get("indexMarkdown", "")

    async def categories(self) -> list[dict]:
        body = await self._get(
            f"/internal/v1/wiki-spaces/{self.scope_key}/categories")
        return body.get("items", [])

    async def parsed_document(self, document_id: str) -> dict:
        return await self._get(
            f"/internal/v1/documents/{document_id}/parsed", self._scoped())
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_query_client.py -v
```

Expected: 5 passed

- [ ] **Step 5: 전체 테스트가 여전히 통과하는 것을 확인한다**

```bash
cd ai && uv run pytest -m "not ocr" -q
```

Expected: 511 passed, 2 skipped (기존 506 + 신규 5)

- [ ] **Step 6: 커밋한다**

```bash
cd /home/ssafy/workspace/S15P11B106
git add ai/src/wiki_mcp/vaultfs/query_client.py ai/tests/mcp/test_query_client.py
git status --short
git commit -m "feat(ai): Wiki 조회 창구 HTTP 클라이언트 추가"
```

---

### Task 3: FederatedVaultFS — 카탈로그 하이드레이션과 본문 지연 적재

**Files:**
- Create: `src/wiki_mcp/vaultfs/federated.py`
- Modify: `src/wiki_mcp/vaultfs/__init__.py`
- Test: `tests/mcp/test_federated_vaultfs.py`

**Interfaces:**
- Consumes: `WikiQueryClient`·`QueryNotFound` (Task 2), `SpringVaultFS`(`_insert_live`·`_sync_page_references` 상속)·`address_from_wiki_path`·`PAGES_PREFIX` (기존)
- Produces:
  - `class FederatedVaultFS(SpringVaultFS)` with
    `@classmethod async open(root, scope_key: str, job_id: str | None, *, client: WikiQueryClient) -> str` (scope_id 반환),
    `__init__(scope_key: str, job_id: str | None, client: WikiQueryClient)`,
    `async get(scope_id, address) -> dict | None` (본문 지연 적재 후 super),
    `async search_chunks(scope_id, query, limit, kind_filter=None) -> list[dict]` (각 행에 `"origin": "live" | "work"`)
  - Task 4·5가 `FederatedVaultFS.open(...)`으로 어댑터를 세운다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/mcp/test_federated_vaultfs.py`를 새로 만든다. 네트워크 대신 가짜 클라이언트를 넣는다.

```python
"""FederatedVaultFS 단위 테스트. HTTP 를 쓰지 않는다 — 가짜 클라이언트다."""

import pytest

from wiki_mcp.vaultfs import INDEX_ADDRESS, FederatedVaultFS, LocalVaultFS
from wiki_mcp.vaultfs.query_client import QueryNotFound

PAGES = [
    {"wikiId": "101", "title": "휴가 규정", "summary": "연차와 반차 사용 기준",
     "wikiCategoryId": "9", "categoryName": "휴가 및 근태",
     "wikiPath": "wiki/D1-D2/pages/a3f2c1d4.md", "contentHash": "h1",
     "updatedAt": "2026-07-27T09:00:00Z"},
    {"wikiId": "108", "title": "보안 규정", "summary": "장비 반출 기준",
     "wikiCategoryId": "10", "categoryName": "보안",
     "wikiPath": "wiki/D1-D2/pages/b7e1f2a9.md", "contentHash": "h2",
     "updatedAt": "2026-07-27T09:00:00Z"},
]

BODIES = {
    "101": "---\ntitle: 휴가 규정\n---\n\n연차는 다음 해 3월까지 이월할 수 있다.\n",
    "108": "---\ntitle: 보안 규정\n---\n\n장비 반출은 사전 승인이 필요하다.\n",
}


class FakeClient:
    """WikiQueryClient 와 같은 표면만 흉내 낸다."""

    def __init__(self, *, scope_key="D1-D2"):
        self.scope_key = scope_key
        self.scope_version = 47
        self.body_fetches = 0
        self.searched: list[str] = []

    async def list_pages(self):
        return [dict(page) for page in PAGES]

    async def index_markdown(self):
        return "# 목차\n\n- [휴가 규정](pages/a3f2c1d4.md) — 연차와 반차 사용 기준\n"

    async def categories(self):
        return [{"wikiCategoryId": "9", "name": "휴가 및 근태", "wikiCount": 1}]

    async def page_content(self, wiki_id):
        if wiki_id not in BODIES:
            raise QueryNotFound(wiki_id)
        self.body_fetches += 1
        page = next(p for p in PAGES if p["wikiId"] == wiki_id)
        return {"scopeVersion": 47, "wikiId": wiki_id, "title": page["title"],
                "wikiPath": page["wikiPath"], "contentMarkdown": BODIES[wiki_id],
                "contentHash": page["contentHash"]}

    async def search(self, query, limit=10):
        self.searched.append(query)
        return [{"wikiId": "101", "title": "휴가 규정",
                 "breadcrumb": "휴가 규정", "snippet": "연차는 다음 해...",
                 "chunkIndex": 0, "contentHash": "h1"}]

    async def relations(self, wiki_id):
        return {"scopeVersion": 47, "wikiId": wiki_id,
                "wikiRefs": [], "documentRefs": [], "backlinks": []}

    async def aclose(self):
        return None


@pytest.fixture
async def federated(tmp_path):
    client = FakeClient()
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1",
                                           client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    yield fs, scope_id, client
    await LocalVaultFS.close()


async def test_hydration_fills_catalog_without_bodies(federated):
    """목록 창구는 본문을 주지 않는다. 하이드레이션 시점에 본문을 당기지 않는다."""
    fs, scope_id, client = federated

    docs = await fs.list_documents(scope_id)
    addresses = {doc["address"] for doc in docs}

    assert "pages/a3f2c1d4.md" in addresses
    assert "pages/b7e1f2a9.md" in addresses
    assert INDEX_ADDRESS in addresses
    assert client.body_fetches == 0


async def test_wiki_path_becomes_the_address(federated):
    """wikiPath 접두사를 떼어 주소로 쓴다. pages/{wikiId}.md 가 아니다.

    본문의 위키 링크가 pageKey 기준이므로 그 이름을 써야 링크가 해석된다
    (설계 4절).
    """
    fs, scope_id, _ = federated

    assert await fs.get(scope_id, "pages/a3f2c1d4.md") is not None
    assert await fs.get(scope_id, "pages/101.md") is None


async def test_get_fetches_body_once(federated):
    """첫 접근에 본문을 당기고 두 번째는 당기지 않는다."""
    fs, scope_id, client = federated

    first = await fs.get(scope_id, "pages/a3f2c1d4.md")
    second = await fs.get(scope_id, "pages/a3f2c1d4.md")

    assert "이월할 수 있다" in first["content"]
    assert first["content"] == second["content"]
    assert client.body_fetches == 1


async def test_search_merges_live_and_work_with_origin(federated):
    """창구(라이브)와 내부 색인(작업층)을 모아 origin 으로 구분한다 (설계 §7.1)."""
    fs, scope_id, client = federated

    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address,
                   "---\ntitle: 재택근무 규정\n---\n\n재택근무는 주 2일까지 가능하다.\n",
                   title="재택근무 규정")

    rows = await fs.search_chunks(scope_id, "재택근무", limit=10)
    origins = {row["origin"] for row in rows}

    assert "work" in origins
    assert client.searched == ["재택근무"]


async def test_search_live_rows_carry_origin_live(federated):
    fs, scope_id, _ = federated

    rows = await fs.search_chunks(scope_id, "연차", limit=10)

    assert rows
    assert rows[0]["origin"] == "live"
    assert rows[0]["address"] == "pages/a3f2c1d4.md"
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_federated_vaultfs.py -v
```

Expected: FAIL — `ImportError: cannot import name 'FederatedVaultFS'`

- [ ] **Step 3: 어댑터를 구현한다**

`src/wiki_mcp/vaultfs/federated.py`를 새로 만든다.

```python
"""라이브 층을 Spring 창구에서 당겨오는 VaultFS — FR-WIKI-002 의 목표 형태.

`spring.py`(push)와 나란한 세 번째 구현체다. 포트(`base.py`)와 툴 10개는 이 파일을
모른다. 바뀌는 것은 라이브 층의 출처뿐이다.

**카탈로그와 본문을 가른다** (설계 §3.3). `open()` 은 목록·목차만 채운다. 본문은
`get()` 이 처음 요구할 때 당긴다. push 경로가 본문을 한 번에 받아야 했던 이유는
검색이 AI 안의 청크 FTS 로 돌기 때문이었는데, 여기서는 검색이 창구로 나가므로 그
제약이 없다.

**검색은 두 곳에서 온다.** 창구는 반영이 완료된 라이브만 색인한다(DR-029). 작업 중에
에이전트가 쓴 페이지는 백엔드가 모르므로 내부 색인에서 찾아 합친다. 섞지 않고
`origin` 으로 구분해 돌려준다 — 에이전트가 "내가 방금 쓴 것"과 "원래 있던 것"을
구분하지 못하면 남의 페이지를 자기 초안으로 착각한다 (설계 §7.1).
"""

from __future__ import annotations

from pathlib import Path

from .local import PAGES_PREFIX, LocalVaultFS
from .query_client import QueryNotFound, ScopeChangedError, WikiQueryClient
from .spring import SpringVaultFS, address_from_wiki_path

INDEX_ADDRESS = "index.md"


# `SpringVaultFS` 를 상속하는 이유: 라이브 층을 밖에서 채워 넣는 두 단계 SQLite 와
# `_insert_live`·`_sync_page_references` 가 거기 있다 (`spring.py:196`·`:106`).
# push 와 pull 은 라이브 층의 출처만 다르고 저장 방식이 같다. 다시 쓰면 두 벌이 된다.
class FederatedVaultFS(SpringVaultFS):
    """한 요청·한 스코프. 임시 루트를 쓰고 요청이 끝나면 버린다."""

    def __init__(self, scope_key: str, job_id: str | None,
                 client: WikiQueryClient):
        super().__init__(scope_key, job_id)
        self._client = client
        # wikiId ↔ address. 주소로 본문을 당기려면 역방향이 필요하다.
        self._wiki_id_by_address: dict[str, str] = {}
        self._hydrated_bodies: set[str] = set()
        # 하이드레이션이 채운다. 카테고리 툴이 읽는다.
        self.categories: list[dict] = []

    @classmethod
    async def open(cls, root: Path | str, scope_key: str, job_id: str | None, *,
                   client: WikiQueryClient) -> str:
        """임시 색인을 열고 카탈로그만 채운다. 본문은 당기지 않는다."""
        scope_id = await LocalVaultFS.open(root, scope_key, job_id)
        fs = cls(scope_key, job_id, client)
        await fs._hydrate_catalog(scope_id)
        return scope_id

    async def _hydrate_catalog(self, scope_id: str) -> None:
        # 카테고리 맵. FR-WIKI-014 의 생성·병합·삭제 판단 근거다 (설계 9.6). 목록의
        # categoryName 만으로는 "빈 카테고리" 와 "사용량" 을 알 수 없다.
        self.categories = await self._client.categories()
        for page in await self._client.list_pages():
            wiki_path = page.get("wikiPath")
            wiki_id = page.get("wikiId")
            # wikiPath 가 있으면 그 이름을 쓴다 — 본문의 위키 링크가 pageKey 기준이라
            # 이름이 달라지면 링크가 아무것도 가리키지 못한다 (설계 4절).
            address = (address_from_wiki_path(wiki_path, self.scope_key)
                       if wiki_path else f"{PAGES_PREFIX}{wiki_id}.md")
            self._wiki_id_by_address[address] = wiki_id
            # 본문은 빈 문자열이다. 메타데이터만 있어도 browse·목록·주소 해석이 된다.
            await self._insert_live(
                scope_id, address, "", wiki_id=wiki_id,
                title=page.get("title"), category=page.get("categoryName"))
        await self._insert_live(scope_id, INDEX_ADDRESS,
                               await self._client.index_markdown(),
                               title="위키 목차", category="목차")

    async def _ensure_body(self, scope_id: str, address: str) -> None:
        """이 주소의 본문을 아직 안 당겼으면 당겨 채운다."""
        if address in self._hydrated_bodies:
            return
        wiki_id = self._wiki_id_by_address.get(address)
        if wiki_id is None:
            return
        try:
            body = await self._client.page_content(wiki_id)
        except QueryNotFound as exc:
            # 목록에 있던 페이지의 본문이 없다 — 그 사이 지워졌다. **빈 본문으로
            # 계속 진행하지 않는다** (설계 2.6). 에이전트가 빈 페이지를 보고 "내용이
            # 없다" 고 판단해 덮어쓸 수 있다. 버전 비교만으로는 목록과 본문 조회
            # 사이의 경합을 못 잡으므로 여기서 닫는다.
            raise ScopeChangedError(
                self._client.scope_version,
                reason=f"목록에 있던 Wiki {wiki_id} 의 본문이 사라졌습니다") from exc
        await self._insert_live(
            scope_id, address, body.get("contentMarkdown") or "",
            wiki_id=wiki_id, title=body.get("title"))
        self._hydrated_bodies.add(address)
        # 인용 그래프는 본문이 있어야 만들어진다. 페이지 하나씩 당기므로
        # 그때그때 다시 훑는다.
        await self._sync_page_references(scope_id)

    async def get(self, scope_id: str, address: str) -> dict | None:
        await self._ensure_body(scope_id, address)
        return await super().get(scope_id, address)

    async def get_backlinks(self, scope_id: str, address: str) -> list[dict]:
        """내부 그래프 + 원격 관계 창구를 합친다.

        **지연 적재의 사각지대를 메우는 곳이다** (설계 9.6). 내부 그래프는 본문을
        당긴 페이지만 안다. 제거·병합은 범위 전체의 역링크를 알아야 하는데, 안 읽은
        페이지의 링크를 놓치면 남의 링크를 조용히 끊는다.

        원격 응답은 `wikiId` 로 오므로 카탈로그의 역방향 표로 주소를 되돌린다.
        카탈로그에 없는 `wikiId` 는 건너뛴다 — 다른 범위이거나 이번 요청이 모르는
        페이지다.
        """
        rows = await super().get_backlinks(scope_id, address)
        wiki_id = self._wiki_id_by_address.get(address)
        if wiki_id is None:
            return rows

        known = {row["address"] for row in rows}
        address_by_wiki_id = {wid: addr
                              for addr, wid in self._wiki_id_by_address.items()}
        try:
            remote = await self._client.relations(wiki_id)
        except QueryNotFound:
            # 그 사이 지워졌다. 내부 그래프만으로 답한다 — 제거 대상 자체가
            # 사라진 것이므로 여기서 중단할 이유는 없다.
            return rows

        for backlink_id in remote.get("backlinks", []):
            remote_address = address_by_wiki_id.get(backlink_id)
            if remote_address is None or remote_address in known:
                continue
            rows.append({"address": remote_address, "origin": "live"})
        return rows

    async def search_chunks(self, scope_id: str, query: str, limit: int,
                            kind_filter: str | None = None) -> list[dict]:
        """창구(라이브) + 내부 색인(작업층). 섞지 않고 origin 을 붙인다."""
        work_rows = await super().search_chunks(scope_id, query, limit,
                                                kind_filter)
        work_addresses = set()
        merged: list[dict] = []
        for row in work_rows:
            # 라이브 행은 창구가 준다. 내부 색인의 라이브 행은 본문이 비어 있어
            # 애초에 맞지 않지만, 당겨온 본문이 있으면 중복이 되므로 걸러낸다.
            if row.get("layer") == "work":
                work_addresses.add(row["address"])
                merged.append({**row, "origin": "work"})

        for item in await self._client.search(query, limit=limit):
            wiki_id = item.get("wikiId")
            address = next(
                (addr for addr, wid in self._wiki_id_by_address.items()
                 if wid == wiki_id), f"{PAGES_PREFIX}{wiki_id}.md")
            if address in work_addresses:
                # 작업층이 같은 페이지를 이미 고쳤다. 작업층 쪽이 최신이다.
                continue
            merged.append({
                "origin": "live",
                "address": address,
                "content": item.get("snippet") or "",
                "header_breadcrumb": item.get("breadcrumb"),
                "chunk_index": item.get("chunkIndex", 0),
                "kind": "wiki",
                "title": item.get("title"),
                "category": None,
                "page": None,
                "original_file_name": None,
                "tags": None,
            })
        return merged[:limit]
```

- [ ] **Step 4: export 를 추가한다**

`src/wiki_mcp/vaultfs/__init__.py`에 추가한다. `spring` 임포트 줄 바로 아래에 둔다.

```python
from .federated import FederatedVaultFS  # noqa: E402,F401
```

그리고 `__all__`에 `"FederatedVaultFS",`를 추가한다.

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_federated_vaultfs.py -v
```

Expected: 5 passed

`_insert_live`·`_sync_page_references`의 실제 시그니처가 다르면 `src/wiki_mcp/vaultfs/spring.py:85-99`를 보고 맞춘다. 그 파일이 같은 두 헬퍼를 쓴다.

- [ ] **Step 6: 전체 테스트가 통과하는 것을 확인한다**

```bash
cd ai && uv run pytest -m "not ocr" -q
```

Expected: 516 passed, 2 skipped

- [ ] **Step 7: 커밋한다**

```bash
cd /home/ssafy/workspace/S15P11B106
git add ai/src/wiki_mcp/vaultfs/federated.py ai/src/wiki_mcp/vaultfs/__init__.py \
        ai/tests/mcp/test_federated_vaultfs.py
git status --short
git commit -m "feat(ai): 창구에서 라이브 층을 당기는 FederatedVaultFS 추가"
```

---

### Task 4: 검색 툴이 두 섹션으로 보여준다

`origin` 을 붙였지만 에이전트에게는 아직 한 덩어리로 보인다.

**Files:**
- Modify: `src/wiki_mcp/tools/search.py:83-110`
- Test: `tests/mcp/test_federated_vaultfs.py` (같은 파일에 추가)

**Interfaces:**
- Consumes: `search_chunks` 결과의 `origin` 키 (Task 3)
- Produces: 없음 — 툴 응답 문자열만 바뀐다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/mcp/test_federated_vaultfs.py` 끝에 추가한다.

```python
async def test_search_tool_renders_two_sections(federated):
    """origin 이 있으면 「작업 중」과 「반영된 위키」로 나눠 보여준다."""
    from wiki_mcp.tools.search import SearchHandler

    fs, scope_id, _ = federated
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address,
                   "---\ntitle: 연차 초안\n---\n\n연차 이월 초안 문서다.\n",
                   title="연차 초안")

    # SearchHandler 는 scope dict 에서 "id" 를 읽는다 (`tools/search.py:47`).
    handler = SearchHandler(fs, {"id": scope_id, "scope_key": "D1-D2"})
    rendered = await handler.search("연차", pattern="*", tags=None, limit=10)

    assert "작업 중" in rendered
    assert "반영된 위키" in rendered
    assert rendered.index("작업 중") < rendered.index("반영된 위키")
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_federated_vaultfs.py -k two_sections -v
```

Expected: FAIL — `AssertionError: '작업 중' not in ...`

`SearchHandler.__init__`의 두 번째 인자가 `scope` dict 가 아니면 `src/wiki_mcp/tools/search.py:45`를 보고 맞춘다.

- [ ] **Step 3: 렌더링을 나눈다**

`src/wiki_mcp/tools/search.py`의 `search` 메서드에서 결과를 줄로 만드는 부분을 이렇게 감싼다. 기존 한 덩어리 렌더링을 헬퍼로 빼고 두 번 부른다.

```python
    async def search(self, query: str, pattern: str, tags: list[str] | None,
                     limit: int) -> str:
        matches = await self.fs.search_chunks(
            self.scope_id, query, limit, self._kind_filter(pattern))
        if not matches:
            return f"`{query}` 에 맞는 것이 없습니다."

        # origin 이 없으면 push 경로다 — 한 덩어리로 그대로 보여준다. 창구 경로에서만
        # 나눈다 (설계 §7.1). 섞으면 에이전트가 자기 초안과 라이브를 구분하지 못한다.
        if not any("origin" in match for match in matches):
            return "\n".join(self._match_lines(matches, query))

        work = [match for match in matches if match.get("origin") == "work"]
        live = [match for match in matches if match.get("origin") != "work"]
        lines: list[str] = []
        if work:
            lines.append(f"**작업 중 ({len(work)}):** 이번 작업에서 쓴 것입니다.")
            lines.extend(self._match_lines(work, query))
        if live:
            if lines:
                lines.append("")
            lines.append(f"**반영된 위키 ({len(live)}):** 이미 서비스 중입니다.")
            lines.extend(self._match_lines(live, query))
        return "\n".join(lines)

    def _match_lines(self, matches: list[dict], query: str) -> list[str]:
        """한 건을 한 줄로. 기존 렌더링을 그대로 옮긴 것이다."""
        lines = []
        for match in matches:
            label = match.get("title") or match["address"]
            breadcrumb = match.get("header_breadcrumb")
            where = f" — {breadcrumb}" if breadcrumb else ""
            lines.append(f"- `{match['address']}` {label}{where}")
            lines.append(f"    {_snippet(match.get('content') or '', query)}")
        return lines
```

기존 `search` 본문의 렌더링 코드가 위 `_match_lines`와 다르면, **기존 코드를 그대로** `_match_lines`로 옮기고 위 `search`만 새로 쓴다. 렌더링 형식을 바꾸면 기존 테스트가 깨진다.

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_federated_vaultfs.py -v
```

Expected: 6 passed

- [ ] **Step 5: 기존 검색 테스트가 깨지지 않았는지 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_korean_search.py tests/mcp/test_server.py -q
```

Expected: 전부 통과. 깨지면 `_match_lines`가 기존 형식과 다른 것이므로 기존 형식으로 되돌린다.

- [ ] **Step 6: 커밋한다**

```bash
cd /home/ssafy/workspace/S15P11B106
git add ai/src/wiki_mcp/tools/search.py ai/tests/mcp/test_federated_vaultfs.py
git status --short
git commit -m "feat(ai): 검색 결과를 작업층과 라이브 두 섹션으로 나눠 보여준다"
```

---

### Task 5: 가짜 창구 게이트웨이

**Files:**
- Create: `experiments/query_gateway.py`
- Test: `tests/mcp/test_federated_gateway.py` (Task 6에서 씀 — 여기서는 게이트웨이 자체만)

**Interfaces:**
- Consumes: 없음 (독립 실행)
- Produces:
  - `def build_gateway(corpus: Path, *, scope_key: str, capability: str, api_key: str, scope_version: int = 47) -> fastapi.FastAPI`
  - `class GatewayState` with `scope_version: int` (테스트가 버전을 올려 `scope_changed`를 유발한다)
  - `build_gateway` 가 돌려주는 앱의 `state.gateway` 가 그 `GatewayState`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/mcp/test_federated_gateway.py`를 새로 만든다.

```python
"""가짜 창구 게이트웨이. 백엔드가 창구를 만들기 전 붙여보기 위한 것이다."""

import sys
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "experiments"))

from query_gateway import build_gateway  # noqa: E402

CAPABILITY = "cap-test"
API_KEY = "key-test"


@pytest.fixture
def corpus(tmp_path):
    pages = tmp_path / "wiki" / "D1-D2" / "pages"
    pages.mkdir(parents=True)
    (pages / "a3f2c1d4.md").write_text(
        "---\ntitle: 휴가 규정\ndescription: 연차와 반차 사용 기준\n---\n\n"
        "연차는 다음 해 3월까지 이월할 수 있다.\n", encoding="utf-8")
    (pages / "b7e1f2a9.md").write_text(
        "---\ntitle: 보안 규정\ndescription: 장비 반출 기준\n---\n\n"
        "장비 반출은 사전 승인이 필요하다. [휴가 규정](pages/a3f2c1d4.md)\n",
        encoding="utf-8")
    (tmp_path / "wiki" / "D1-D2" / "index.md").write_text(
        "# 목차\n\n- [휴가 규정](pages/a3f2c1d4.md) — 연차와 반차 사용 기준\n",
        encoding="utf-8")
    return tmp_path


@pytest.fixture
def gateway(corpus):
    return build_gateway(corpus, scope_key="D1-D2", capability=CAPABILITY,
                         api_key=API_KEY)


def _client(app) -> httpx.AsyncClient:
    return httpx.AsyncClient(base_url="http://gateway.test",
                             transport=httpx.ASGITransport(app=app))


async def test_pages_returns_catalog_without_bodies(gateway):
    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-pages",
                                  params={"scopeKey": "D1-D2"},
                                  headers={"X-Internal-API-Key": API_KEY,
                                           "X-Wiki-Capability": CAPABILITY})
    body = response.json()

    assert response.status_code == 200
    assert body["scopeVersion"] == 47
    assert {item["wikiId"] for item in body["items"]} == {"1", "2"}
    assert all("contentMarkdown" not in item for item in body["items"])
    assert any(item["wikiPath"].endswith("pages/a3f2c1d4.md")
               for item in body["items"])


async def test_missing_capability_is_404(gateway):
    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-pages",
                                  params={"scopeKey": "D1-D2"},
                                  headers={"X-Internal-API-Key": API_KEY})

    assert response.status_code == 404


async def test_other_scope_is_404_not_403(gateway):
    """허가 범위 밖은 존재를 노출하지 않는다 (NFR-SEC-003 · FR-ACL-006)."""
    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-pages",
                                  params={"scopeKey": "D9"},
                                  headers={"X-Internal-API-Key": API_KEY,
                                           "X-Wiki-Capability": CAPABILITY})

    assert response.status_code == 404


async def test_search_finds_korean_term(gateway):
    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-search",
                                  params={"scopeKey": "D1-D2",
                                          "query": "연차", "limit": "5"},
                                  headers={"X-Internal-API-Key": API_KEY,
                                           "X-Wiki-Capability": CAPABILITY})
    items = response.json()["items"]

    assert items
    assert items[0]["title"] == "휴가 규정"


async def test_scope_version_can_be_bumped(gateway):
    gateway.state.gateway.scope_version = 48

    async with _client(gateway) as http:
        response = await http.get("/internal/v1/wiki-spaces/D1-D2/index",
                                  headers={"X-Internal-API-Key": API_KEY,
                                           "X-Wiki-Capability": CAPABILITY})

    assert response.json()["scopeVersion"] == 48
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_federated_gateway.py -v
```

Expected: FAIL — `ModuleNotFoundError: No module named 'query_gateway'`

- [ ] **Step 3: 게이트웨이를 구현한다**

`experiments/query_gateway.py`를 새로 만든다.

```python
"""가짜 Wiki 조회 창구 — 계약 v1.5.0 「Wiki 조회 창구」 7개를 파일 트리로 흉내 낸다.

**이 파일은 폐기 대상이다.** 백엔드가 창구를 만들면 사라진다. `src/` 아래 어느
파일도 이것을 임포트하지 않는다.

증명하는 것과 못 하는 것을 구분해 둔다. 이 게이트웨이는 **우리 주문서에 앞뒤가
안 맞는 게 없다**를 보인다. 우리가 상상한 대로 답하기 때문이다. **백엔드가 실제로
이렇게 만들 수 있다**는 증명하지 못한다 — 그것은 백엔드 검토에서만 확인된다.

`wikiId` 는 파일명 순서로 1부터 붙인다. 진짜 백엔드는 DB 시퀀스를 쓴다. 이 차이가
`wikiPath` 없이는 주소를 복원할 수 없다는 것을 그대로 재현한다 (설계 4절).

검색은 2글자 부분문자열 포함으로 한다. MySQL `ngram_token_size=2` 와 같은 낟알이라
한국어 조사를 뚫는다 — 실측 근거는 `experiments/INDEX.md`.
"""

from __future__ import annotations

import argparse
import hashlib
import re
from dataclasses import dataclass, field
from pathlib import Path

from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse

FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n", re.DOTALL)


def _frontmatter_field(text: str, key: str) -> str | None:
    match = FRONTMATTER_RE.match(text)
    if not match:
        return None
    for line in match.group(1).splitlines():
        if line.startswith(f"{key}:"):
            return line.split(":", 1)[1].strip() or None
    return None


def _bigrams(text: str) -> set[str]:
    squeezed = re.sub(r"\s+", "", text)
    return {squeezed[i:i + 2] for i in range(len(squeezed) - 1)}


@dataclass
class GatewayState:
    corpus: Path
    scope_key: str
    capability: str
    api_key: str
    # 테스트가 이 값을 올려 scope_changed 를 유발한다.
    scope_version: int = 47
    pages: dict[str, dict] = field(default_factory=dict)

    def load(self) -> None:
        base = self.corpus / "wiki" / self.scope_key
        self.pages = {}
        for index, path in enumerate(sorted((base / "pages").glob("*.md")), 1):
            text = path.read_text(encoding="utf-8")
            self.pages[str(index)] = {
                "wikiId": str(index),
                "title": _frontmatter_field(text, "title") or path.stem,
                "summary": _frontmatter_field(text, "description"),
                "wikiCategoryId": "9",
                "categoryName": "기본",
                "wikiPath": f"wiki/{self.scope_key}/pages/{path.name}",
                "contentHash": hashlib.sha256(text.encode()).hexdigest(),
                "updatedAt": "2026-07-27T09:00:00Z",
                "_body": text,
            }

    @property
    def index_markdown(self) -> str:
        path = self.corpus / "wiki" / self.scope_key / "index.md"
        return path.read_text(encoding="utf-8") if path.exists() else ""


def _not_found() -> JSONResponse:
    """허가 범위 밖과 없는 것을 한 값으로 덮는다. 403 을 쓰지 않는다."""
    return JSONResponse(
        status_code=404,
        content={"timestamp": "2026-07-30T00:00:00Z", "status": 404,
                 "error": "Not Found", "code": "WIKI_NOT_FOUND",
                 "message": "요청한 자료를 찾을 수 없습니다.", "path": "",
                 "fieldErrors": []})


def build_gateway(corpus: Path, *, scope_key: str, capability: str,
                  api_key: str, scope_version: int = 47) -> FastAPI:
    state = GatewayState(Path(corpus), scope_key, capability, api_key,
                         scope_version)
    state.load()

    app = FastAPI(title="AJT Wiki Query Gateway (fake)")
    app.state.gateway = state

    def guard(request: Request, scope_key_param: str | None) -> bool:
        if request.headers.get("X-Internal-API-Key") != state.api_key:
            return False
        if request.headers.get("X-Wiki-Capability") != state.capability:
            return False
        if scope_key_param is not None and scope_key_param != state.scope_key:
            return False
        return True

    def envelope(payload: dict) -> dict:
        return {"scopeVersion": state.scope_version, **payload}

    @app.get("/internal/v1/wiki-pages")
    async def wiki_pages(request: Request, scopeKey: str):
        if not guard(request, scopeKey):
            return _not_found()
        items = [{k: v for k, v in page.items() if k != "_body"}
                 for page in state.pages.values()]
        return envelope({"items": items})

    @app.get("/internal/v1/wiki-search")
    async def wiki_search(request: Request, scopeKey: str, query: str,
                          limit: int = 10):
        if not guard(request, scopeKey):
            return _not_found()
        wanted = _bigrams(query)
        items = []
        for page in state.pages.values():
            if wanted & _bigrams(page["_body"]):
                items.append({
                    "wikiId": page["wikiId"], "title": page["title"],
                    "breadcrumb": page["title"],
                    "snippet": page["_body"][:120],
                    "chunkIndex": 0, "contentHash": page["contentHash"]})
        return envelope({"items": items[:limit]})

    @app.get("/internal/v1/wikis/{wiki_id}/content")
    async def wiki_content(request: Request, wiki_id: str, scopeKey: str):
        if not guard(request, scopeKey) or wiki_id not in state.pages:
            return _not_found()
        page = state.pages[wiki_id]
        return envelope({"wikiId": wiki_id, "title": page["title"],
                         "wikiPath": page["wikiPath"],
                         "contentMarkdown": page["_body"],
                         "contentHash": page["contentHash"]})

    @app.get("/internal/v1/wikis/{wiki_id}/relations")
    async def wiki_relations(request: Request, wiki_id: str, scopeKey: str):
        if not guard(request, scopeKey) or wiki_id not in state.pages:
            return _not_found()
        body = state.pages[wiki_id]["_body"]
        refs = [other["wikiId"] for other in state.pages.values()
                if other["wikiId"] != wiki_id
                and Path(other["wikiPath"]).name in body]
        backlinks = [other["wikiId"] for other in state.pages.values()
                     if Path(state.pages[wiki_id]["wikiPath"]).name
                     in other["_body"] and other["wikiId"] != wiki_id]
        return envelope({"wikiId": wiki_id, "wikiRefs": refs,
                         "documentRefs": [], "backlinks": backlinks})

    @app.get("/internal/v1/wiki-spaces/{scope_key}/index")
    async def wiki_index(request: Request, scope_key: str):
        if not guard(request, scope_key):
            return _not_found()
        return envelope({"scopeKey": scope_key,
                         "indexMarkdown": state.index_markdown})

    @app.get("/internal/v1/wiki-spaces/{scope_key}/categories")
    async def wiki_categories(request: Request, scope_key: str):
        if not guard(request, scope_key):
            return _not_found()
        return envelope({"items": [{"wikiCategoryId": "9", "name": "기본",
                                    "wikiCount": len(state.pages)}]})

    @app.get("/internal/v1/documents/{document_id}/parsed")
    async def document_parsed(request: Request, document_id: str,
                              scopeKey: str):
        if not guard(request, scopeKey):
            return _not_found()
        path = (state.corpus / "sources" / document_id / "parsed" /
                "content.md")
        if not path.exists():
            return _not_found()
        # 파싱본에는 scopeVersion 을 싣지 않는다 — 위키 스냅샷과 무관하다.
        return {"documentId": document_id, "originalFileName": f"{document_id}.pdf",
                "parsedMarkdown": path.read_text(encoding="utf-8")}

    return app


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True, help="위키 트리 루트")
    parser.add_argument("--scope", default="D1-D2")
    parser.add_argument("--capability", default="cap-local")
    parser.add_argument("--api-key", default="key-local")
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()

    import uvicorn
    uvicorn.run(build_gateway(Path(args.corpus), scope_key=args.scope,
                             capability=args.capability, api_key=args.api_key),
                host="127.0.0.1", port=args.port)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_federated_gateway.py -v
```

Expected: 5 passed

`httpx.ASGITransport`가 없다는 오류가 나면 httpx 버전을 확인한다 (`pyproject.toml`은 `httpx>=0.27`이고 `ASGITransport`는 그보다 오래됐다).

- [ ] **Step 5: 커밋한다**

```bash
cd /home/ssafy/workspace/S15P11B106
git add ai/experiments/query_gateway.py ai/tests/mcp/test_federated_gateway.py
git status --short
git commit -m "feat(ai): 창구 7개를 흉내 내는 가짜 게이트웨이 추가"
```

---

### Task 6: 9.2 검증 6항목

게이트웨이와 어댑터를 실제로 붙여 설계 문서 9.2가 요구한 여섯 가지를 확인한다.

**Files:**
- Test: `tests/mcp/test_federated_gateway.py` (같은 파일에 추가)
- Modify: `ai/docs/superpowers/specs/2026-07-30-wiki-query-federation-contract-design.md` (4절 판정 기록)

**Interfaces:**
- Consumes: `build_gateway` (Task 5), `WikiQueryClient` (Task 2), `FederatedVaultFS` (Task 3)
- Produces: 없음 — 검증만

- [ ] **Step 1: 여섯 항목 테스트를 쓴다**

`tests/mcp/test_federated_gateway.py` 끝에 추가한다.

```python
# ---- 설계 9.2 검증 6항목 ------------------------------------------------------

import pytest_asyncio

from wiki_mcp.vaultfs import FederatedVaultFS, LocalVaultFS
from wiki_mcp.vaultfs.query_client import ScopeChangedError, WikiQueryClient


@pytest_asyncio.fixture
async def wired(gateway, tmp_path):
    """게이트웨이에 어댑터를 붙인다. 실제 HTTP 스택을 ASGI 로 통과한다."""
    client = WikiQueryClient(
        "http://gateway.test", api_key=API_KEY, capability=CAPABILITY,
        scope_key="D1-D2", scope_version=gateway.state.gateway.scope_version,
        request_id="req-42",
        transport=httpx.ASGITransport(app=gateway))
    root = tmp_path / "work"
    root.mkdir()
    scope_id = await FederatedVaultFS.open(root, "D1-D2", "job-1",
                                            client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    yield fs, scope_id, client, gateway
    await client.aclose()
    await LocalVaultFS.close()


async def test_check1_existing_page_is_addressable(wired):
    """1. 기존 페이지를 고치는가 — 주소가 파일명과 같아야 새로 만들지 않는다."""
    fs, scope_id, _, _ = wired

    assert await fs.get(scope_id, "pages/a3f2c1d4.md") is not None


async def test_check2_wiki_links_resolve(wired):
    """2. 라이브 본문의 위키 링크가 해석되는가 — 설계 4절 wikiPath 판정."""
    fs, scope_id, _, _ = wired

    # b7e1f2a9 본문이 pages/a3f2c1d4.md 를 링크한다. 본문을 당겨야 간선이 생긴다.
    await fs.get(scope_id, "pages/b7e1f2a9.md")
    backlinks = await fs.get_backlinks(scope_id, "pages/a3f2c1d4.md")

    assert any(row["address"] == "pages/b7e1f2a9.md" for row in backlinks)


async def test_check3_search_has_both_layers(wired):
    """3. 검색에 라이브와 작업층이 모두 나오는가."""
    fs, scope_id, _, _ = wired

    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address,
                   "---\ntitle: 연차 초안\n---\n\n연차 이월 초안이다.\n",
                   title="연차 초안")
    rows = await fs.search_chunks(scope_id, "연차", limit=10)

    assert {row["origin"] for row in rows} == {"live", "work"}


async def test_check4_version_bump_stops_work(wired):
    """4. 버전을 바꿔 두면 멈추는가 — scope_changed 로 이어질 예외."""
    fs, scope_id, _, gateway_app = wired

    gateway_app.state.gateway.scope_version = 48

    with pytest.raises(ScopeChangedError):
        await fs.search_chunks(scope_id, "연차", limit=5)


async def test_check5_other_scope_is_not_found(wired):
    """5. 허가 범위 밖이 404 인가."""
    from wiki_mcp.vaultfs.query_client import QueryNotFound

    _, _, client, _ = wired
    client.scope_key = "D9"

    with pytest.raises(QueryNotFound):
        await client.list_pages()


async def test_check6_body_fetched_once(wired):
    """6. 같은 본문을 두 번 받지 않는가."""
    fs, scope_id, client, _ = wired

    await fs.get(scope_id, "pages/a3f2c1d4.md")
    await fs.get(scope_id, "pages/a3f2c1d4.md")
    await fs.search_chunks(scope_id, "연차", limit=5)

    assert client.body_fetches == 1
```

- [ ] **Step 2: 테스트를 돌려 어느 항목이 실패하는지 본다**

```bash
cd ai && uv run pytest tests/mcp/test_federated_gateway.py -k check -v
```

Expected: 여섯 개 중 일부 실패. **여기서 실패하는 것이 이 계획의 산출물이다** — 주문서가 틀린 곳을 찾는 것이 목적이다.

- [ ] **Step 3: 실패를 고친다**

각 실패의 처리 원칙:

- **`check1`·`check6` 실패** → 어댑터 버그다. `federated.py`를 고친다.
- **`check2` 실패** → `wikiPath` 없이는 링크가 안 풀린다는 증거다. 게이트웨이가 `wikiPath`를 주고 있으므로 통과해야 한다. 통과하면 4절을 "필수"로 올린다 (Step 5).
- **`check3` 실패** → `search_chunks` 병합 로직이다. `federated.py`의 `origin` 분류를 고친다.
- **`check4` 실패** → `query_client._get`의 버전 비교가 안 걸린 것이다.
- **`check5` 실패** → 게이트웨이 `guard`가 `scopeKey`를 안 보는 것이다.

**계약을 바꿔서 고치지 않는다.** 계약이 부족하다는 결론이 나오면 고치지 말고 Step 5에 기록하고 사용자에게 알린다.

- [ ] **Step 4: 여섯 항목이 통과하는 것과 전체 테스트를 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_federated_gateway.py -v
cd ai && uv run pytest -m "not ocr" -q
```

Expected: 게이트웨이 11 passed, 전체 527 passed 2 skipped (기존 506 + 5 + 5 + 1 + 6 + 4)

정확한 총계는 실제 실행값을 쓴다. **추정값을 결과로 보고하지 않는다.**

- [ ] **Step 5: 설계 문서에 판정을 기록한다**

`2026-07-30-wiki-query-federation-contract-design.md` 4절 마지막 문단을 실제 결과로 바꾼다.

```markdown
**판정 (2026-07-30, `tests/mcp/test_federated_gateway.py::test_check2_wiki_links_resolve`).**
`wikiPath` 없이 `pages/{wikiId}.md` 로 주소를 지으면 라이브 본문의 위키 링크가 아무
페이지도 가리키지 못한다. 게이트웨이가 `wikiPath` 를 주면 역링크가 복원된다.
**따라서 필수로 올린다** — 창구 응답에서 빼면 병합·수정에서 링크가 끊긴다.
```

그리고 11절 미결에서 해당 줄을 지운다.

`check2`가 `wikiPath` 없이도 통과했다면 위 문단 대신 **"권장으로 유지"**와 그 근거를 적는다. 실제 결과에 맞춰 쓴다.

- [ ] **Step 6: 커밋한다**

```bash
cd /home/ssafy/workspace/S15P11B106
git add ai/tests/mcp/test_federated_gateway.py ai/src/wiki_mcp/vaultfs/federated.py \
        ai/docs/superpowers/specs/2026-07-30-wiki-query-federation-contract-design.md
git status --short
git commit -m "test(ai): 창구 어댑터 검증 6항목과 wikiPath 판정"
```

---

### Task 7: 세션이 두 경로를 가른다

계약 1.6.0 이 `wikiCapability`·`scopeVersion` 을 요청에 넣었으므로 이제 붙일 수 있다. **선택 필드이므로 없으면 지금 경로가 그대로 돈다.**

**Files:**
- Modify: `src/wiki_api/schemas.py` (`TransformRequest`·`EditRequest`·`SelectedWiki`)
- Modify: `src/wiki_api/session.py:127,145,158`
- Test: `tests/api/test_federated_session.py`

**Interfaces:**
- Consumes: `WikiQueryClient` (Task 2), `FederatedVaultFS` (Task 3)
- Produces: `WikiSession(..., wiki_capability: str | None = None, scope_version: int | None = None, backend_base_url: str | None = None)` — 셋이 다 있으면 창구 경로

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/api/test_federated_session.py` 를 새로 만든다.

```python
"""세션이 요청에 따라 push 경로와 창구 경로를 가르는지 본다."""

import pytest

from wiki_api.schemas import SelectedWiki, TransformRequest


def test_selected_wiki_accepts_wiki_path():
    """계약 1.6.0 이 기존 Wiki 에 wikiPath 를 필수로 했다."""
    wiki = SelectedWiki(wikiId="101", title="휴가 규정",
                        wikiPath="wiki/D1-D2/pages/a3f2c1d4.md",
                        contentMarkdown="# 휴가 규정\n")

    assert wiki.wikiPath == "wiki/D1-D2/pages/a3f2c1d4.md"


def test_transform_request_accepts_capability_and_version():
    request = TransformRequest(jobId="42", documentId="15", scopeKey="D1-D2",
                               wikiCapability="cap-1", scopeVersion=47)

    assert request.wikiCapability == "cap-1"
    assert request.scopeVersion == 47


def test_transform_request_without_capability_is_still_valid():
    """선택 필드다. 백엔드가 창구를 배포하기 전에도 계약이 깨지지 않는다."""
    request = TransformRequest(jobId="42", documentId="15", scopeKey="D1-D2")

    assert request.wikiCapability is None
    assert request.scopeVersion is None


async def test_session_uses_spring_vaultfs_without_capability():
    from wiki_api.session import WikiSession
    from wiki_mcp.vaultfs import SpringVaultFS

    async with WikiSession(scope_key="D1-D2", job_id="job-1",
                           error_code="WIKI_TRANSFORMATION_FAILED",
                           pages=[], index_markdown="# 목차\n") as session:
        assert isinstance(session.fs, SpringVaultFS)
        assert not type(session.fs).__name__.startswith("Federated")


async def test_session_uses_federated_vaultfs_with_capability(monkeypatch):
    """capability·scopeVersion·주소가 다 있으면 창구 경로를 쓴다."""
    import httpx

    from wiki_api.session import WikiSession
    from wiki_mcp.vaultfs import FederatedVaultFS

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/index"):
            return httpx.Response(200, json={"scopeVersion": 47,
                                             "scopeKey": "D1-D2",
                                             "indexMarkdown": "# 목차\n"})
        if path.endswith("/categories"):
            return httpx.Response(200, json={"scopeVersion": 47, "items": []})
        return httpx.Response(200, json={"scopeVersion": 47,
                                         "nextCursor": None, "items": []})

    monkeypatch.setenv("BACKEND_BASE_URL", "http://backend.test")

    async with WikiSession(scope_key="D1-D2", job_id="job-1",
                           error_code="WIKI_TRANSFORMATION_FAILED",
                           wiki_capability="cap-1", scope_version=47,
                           backend_base_url="http://backend.test",
                           query_transport=httpx.MockTransport(handler)) as session:
        assert isinstance(session.fs, FederatedVaultFS)
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
cd ai && uv run pytest tests/api/test_federated_session.py -v
```

Expected: FAIL — `SelectedWiki` 에 `wikiPath` 가 없고 `TransformRequest` 에 `wikiCapability` 가 없다

- [ ] **Step 3: 스키마에 계약 1.6.0 필드를 더한다**

`src/wiki_api/schemas.py` 의 `SelectedWiki` 에 추가한다.

```python
    # 계약 1.6.0. 기존 Wiki 에 필수다 — 본문의 내부 링크가 파일명(pageKey) 기준이라
    # 이것 없이 pages/{wikiId}.md 로 적재하면 링크가 어느 페이지도 가리키지 못한다.
    # Optional 로 두는 이유는 하위호환뿐이다: 이 값이 없으면 링크 관계가 빈다.
    wikiPath: str | None = None
```

`TransformRequest` 와 `EditRequest`(관리자 수정 요청 모델. 실제 이름은 파일에서 확인한다) 양쪽에 추가한다.

```python
    # 계약 1.6.0. 둘 다 선택 필드다 — 없으면 selectedWikis 로 도는 과도기 경로다.
    # wikiCapability 는 로그·예외·telemetry 에 남기지 않는다.
    wikiCapability: str | None = None
    scopeVersion: int | None = None
```

- [ ] **Step 4: 세션이 경로를 가르게 한다**

`src/wiki_api/session.py` 의 `__init__` 시그니처에 셋을 더하고, `__aenter__` 의 하이드레이션 분기를 이렇게 만든다. 기존 `SpringVaultFS.open(...)` 호출은 `else` 로 그대로 남긴다.

```python
            if self._federated():
                self._query_client = WikiQueryClient(
                    self.backend_base_url, api_key=_internal_api_key(),
                    capability=self.wiki_capability, scope_key=self.scope_key,
                    scope_version=self.scope_version,
                    request_id=self.request_id,
                    transport=self.query_transport)
                self.scope_id = await FederatedVaultFS.open(
                    self._root, self.scope_key, self.job_id,
                    client=self._query_client)
                self.fs = FederatedVaultFS(self.scope_key, self.job_id,
                                           self._query_client)
            else:
                self.scope_id = await SpringVaultFS.open(
                    self._root, self.scope_key, self.job_id,
                    pages=self.pages, index_markdown=self.index_markdown)
                self.fs = SpringVaultFS(self.scope_key, self.job_id)
```

판정 헬퍼와 정리를 더한다.

```python
    def _federated(self) -> bool:
        """셋이 다 있어야 창구 경로다. 하나라도 없으면 과도기 push 로 돈다."""
        return bool(self.wiki_capability and self.scope_version is not None
                    and self.backend_base_url)
```

`_teardown` 에 클라이언트 닫기를 더한다 — 안 닫으면 커넥션이 남는다.

```python
        if self._query_client is not None:
            await self._query_client.aclose()
            self._query_client = None
```

`ScopeChangedError`·`QueryBudgetExceeded` 를 계약 응답으로 옮기는 매핑도 더한다.

```python
        except ScopeChangedError as exc:
            raise InternalError(self.error_code, str(exc),
                                FailureStage.SCOPE_CHANGED) from exc
        except QueryBudgetExceeded as exc:
            raise InternalError(self.error_code, str(exc),
                                FailureStage.AGENT_ERROR) from exc
```

`except InternalError: raise` 다음, 기존 `except Exception` 앞에 둔다 — 순서가 바뀌면 일반 예외 처리가 먼저 잡아 `context_load` 로 나간다.

- [ ] **Step 5: 라우터가 값을 넘기게 한다**

`src/wiki_api/routers/wiki.py` 에서 `WikiSession(...)` 을 만드는 곳에 셋을 넘긴다. `backend_base_url` 은 `serve.py` 의 CLI 인자나 환경변수에서 온다 — 실제 배선 위치는 그 파일을 보고 맞춘다.

- [ ] **Step 6: 테스트와 전체를 확인한다**

```bash
cd ai && uv run pytest tests/api/test_federated_session.py -v
cd ai && uv run pytest -m "not ocr" -q
```

Expected: 전부 통과. **push 경로 테스트가 하나도 깨지지 않아야 한다** — `tests/api/test_api_wiki.py` 를 특히 본다.

- [ ] **Step 7: 커밋한다**

```bash
cd /home/ssafy/workspace/S15P11B106
git add ai/src/wiki_api/schemas.py ai/src/wiki_api/session.py \
        ai/src/wiki_api/routers/wiki.py ai/tests/api/test_federated_session.py
git status --short
git commit -m "feat(ai): wikiCapability 가 오면 창구 경로로 가른다"
```

---

### Task 8: 상한 실측과 기록

설계 9.3(작업층 검색 결과 상한)과 9.5(조회 횟수 상한)의 숫자를 정한다. **지금까지는 숫자가 없다.**

**Files:**
- Create: `experiments/measure_federated.py`
- Modify: `experiments/INDEX.md`
- Modify: `src/wiki_mcp/vaultfs/federated.py` (상한 상수)

**Interfaces:**
- Consumes: `build_gateway`·`WikiQueryClient`·`FederatedVaultFS`
- Produces: `federated.py`의 `MAX_WORK_SEARCH_ROWS: int`·`MAX_QUERY_CALLS: int`

- [ ] **Step 1: 측정 스크립트를 쓴다**

`experiments/measure_federated.py`를 새로 만든다.

```python
"""창구 어댑터 상한을 정하기 위한 측정. 설계 9.3·9.5.

100장 규모에서 검색 응답 한 건이 몇 자·몇 토큰인지 재고, 그 값으로 상한을 정한다.
근거 없는 숫자를 코드에 박지 않기 위한 절차다.

    uv run python experiments/measure_federated.py --corpus experiments/corpus-ko
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "src"))
sys.path.insert(0, str(ROOT))

import httpx  # noqa: E402

from query_gateway import build_gateway  # noqa: E402
from wiki_mcp.vaultfs import FederatedVaultFS, LocalVaultFS  # noqa: E402
from wiki_mcp.vaultfs.query_client import WikiQueryClient  # noqa: E402

QUERIES = ["연차", "연차 이월", "재택근무 승인", "장비 반출", "출장 정산"]


async def measure(corpus: Path, scope_key: str) -> dict:
    app = build_gateway(corpus, scope_key=scope_key, capability="cap",
                        api_key="key")
    client = WikiQueryClient("http://gateway.test", api_key="key",
                             capability="cap", scope_key=scope_key,
                             scope_version=47,
                             transport=httpx.ASGITransport(app=app))
    with tempfile.TemporaryDirectory() as work:
        scope_id = await FederatedVaultFS.open(Path(work), scope_key, "job-1",
                                                client=client)
        fs = FederatedVaultFS(scope_key, "job-1", client)
        rows_per_query = []
        chars_per_query = []
        for query in QUERIES:
            rows = await fs.search_chunks(scope_id, query, limit=50)
            rows_per_query.append(len(rows))
            chars_per_query.append(
                sum(len(row.get("content") or "") for row in rows))
        # 에이전트가 실제로 하는 일에 가깝게: 검색한 뒤 상위 결과를 읽는다.
        # 그래야 창구 호출 수가 현실적인 값이 된다.
        for query in QUERIES:
            for row in (await fs.search_chunks(scope_id, query, limit=5))[:3]:
                await fs.get(scope_id, row["address"])
        result = {
            "pages": len(app.state.gateway.pages),
            "queries": len(QUERIES),
            "rows_max": max(rows_per_query),
            "rows_mean": sum(rows_per_query) / len(rows_per_query),
            "chars_max": max(chars_per_query),
            "chars_mean": sum(chars_per_query) / len(chars_per_query),
            "body_fetches": client.body_fetches,
            "query_calls": client.calls,
        }
        await client.aclose()
        await LocalVaultFS.close()
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--scope", default="ALL")
    args = parser.parse_args()
    print(json.dumps(asyncio.run(measure(Path(args.corpus), args.scope)),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 코퍼스 구조를 확인하고 측정한다**

```bash
cd ai && ls experiments/corpus-ko/
cd ai && uv run python experiments/measure_federated.py --corpus experiments/corpus-ko --scope ALL
```

`corpus-ko/pages/`가 `wiki/{scope}/pages/` 구조가 아니면 게이트웨이가 못 읽는다. 그러면 임시 디렉터리에 그 구조로 심볼릭 링크를 만들어 넘긴다.

```bash
cd ai && mkdir -p /tmp/ajt-corpus/wiki/ALL && \
  ln -sfn "$(pwd)/experiments/corpus-ko/pages" /tmp/ajt-corpus/wiki/ALL/pages && \
  uv run python experiments/measure_federated.py --corpus /tmp/ajt-corpus --scope ALL
```

- [ ] **Step 3: 실측값으로 상한을 정한다**

`src/wiki_mcp/vaultfs/federated.py` 상단에 추가한다. **`<측정값>` 자리에 Step 2의 실제 숫자를 넣는다.**

```python
# 작업층 검색 결과 상한. 설계 §7.1 은 "work 결과는 자르지 않는다" 였는데 D8(출력 토큰
# 과다)과 충돌해 문서 자신이 "상한을 둔다" 로 결론했다.
#
# 근거: 위키 <측정값>장에서 질의 5개의 검색 응답이 최대 <측정값>행 · <측정값>자였다
# (`experiments/measure_federated.py`, INDEX.md 참고). 시간 ≈ 출력토큰 ÷ 55 이므로
# 검색 응답이 그대로 다음 턴 입력이 되어 출력을 늘린다.
MAX_WORK_SEARCH_ROWS = <측정값>

```

`MAX_QUERY_CALLS` 는 `query_client.py` 에 있다 (Task 2 에서 초기값 `200` 으로 넣고 `_get` 이 세고 있다). 실측값으로 고친다.

```python
# 조회 횟수 상한. 시간 때문이 아니다 — 창구 호출은 수백 밀리초라 예산에 영향이 없다.
# 목적은 D9(`10-onboarding`: 724초에 툴 77회, 출력 589토큰)처럼 같은 것을 반복 조회하며
# 맴도는 것을 끊고, 조회 결과가 문맥에 쌓여 출력을 늘리는 것을 막는 것이다.
#
# 근거: 위키 <측정값>장 · 질의 5개 시나리오에서 창구 호출이 <측정값>회였다
# (`experiments/measure_federated.py`). 그 값의 3배를 상한으로 둔다 — 정상 작업을
# 막지 않으면서 맴도는 것은 잡는 폭이다.
MAX_QUERY_CALLS = <측정값>
```

그리고 `search_chunks`의 작업층 부분에 상한을 적용한다.

```python
        work_rows = await super().search_chunks(
            scope_id, query, min(limit, MAX_WORK_SEARCH_ROWS), kind_filter)
```

- [ ] **Step 4: 상한이 걸리는 테스트를 쓴다**

`tests/mcp/test_federated_vaultfs.py`에 추가한다.

```python
async def test_work_search_rows_are_capped(federated, monkeypatch):
    """상한을 넘는 작업층 결과는 잘린다. 잘린다는 사실 자체를 고정한다."""
    from wiki_mcp.vaultfs import federated as module

    monkeypatch.setattr(module, "MAX_WORK_SEARCH_ROWS", 2)
    fs, scope_id, _ = federated

    for number in range(5):
        address = await fs.allocate_page(scope_id)
        await fs.write(scope_id, address,
                       f"---\ntitle: 초안 {number}\n---\n\n재택근무 초안 {number}.\n",
                       title=f"초안 {number}")

    rows = await fs.search_chunks(scope_id, "재택근무", limit=50)
    work_rows = [row for row in rows if row["origin"] == "work"]

    assert len(work_rows) <= 2
```

- [ ] **Step 5: 테스트와 전체를 확인한다**

```bash
cd ai && uv run pytest tests/mcp/test_federated_vaultfs.py -v
cd ai && uv run pytest -m "not ocr" -q
```

Expected: 전부 통과

- [ ] **Step 6: `INDEX.md`에 측정을 기록한다**

`experiments/INDEX.md`에 절을 추가한다. **Step 2의 실제 숫자를 쓴다.** 수치의 정본이 이 파일이므로 추정값을 넣지 않는다.

```markdown
### 창구 어댑터 검색 응답 (2026-07-30)

`experiments/measure_federated.py --corpus <경로> --scope <스코프>` 로 재현한다.
가짜 게이트웨이(`query_gateway.py`)를 붙인 값이다 — 진짜 백엔드가 아니므로 네트워크
지연은 포함되지 않는다.

| 항목 | 값 |
| --- | --- |
| 위키 장수 | <측정값> |
| 질의 수 | 5 |
| 검색 응답 행 수 (최대 / 평균) | <측정값> / <측정값> |
| 검색 응답 문자 수 (최대 / 평균) | <측정값> / <측정값> |
| 본문 적재 횟수 | <측정값> |
| 창구 호출 수 | <측정값> |

이 값으로 `MAX_WORK_SEARCH_ROWS = <측정값>`, `MAX_QUERY_CALLS = <측정값>` 을 정했다
(`vaultfs/federated.py`). 근거는 §7.1 과 D8 의 충돌이다 — 자르지 않으면 검색 응답이
그대로 출력 토큰이 되어 10분 목표를 깨뜨린다.
```

- [ ] **Step 7: 커밋한다**

```bash
cd /home/ssafy/workspace/S15P11B106
git add ai/experiments/measure_federated.py ai/experiments/INDEX.md \
        ai/src/wiki_mcp/vaultfs/federated.py ai/tests/mcp/test_federated_vaultfs.py
git status --short
git commit -m "feat(ai): 창구 검색 응답 상한을 실측으로 정하고 INDEX.md에 기록"
```

---

## 완료 판정

- [ ] `uv run pytest -m "not ocr"` 전부 통과. 기존 506건이 하나도 깨지지 않았다
- [ ] `FailureStage.SCOPE_CHANGED` 가 있고 요구사항 v2.11 의 여섯 단계를 모두 덮는다
- [ ] 9.2 검증 6항목이 전부 통과한다
- [ ] 4절 `wikiPath` 판정이 실측 결과로 기록됐다
- [ ] `MAX_WORK_SEARCH_ROWS`·`MAX_QUERY_CALLS` 가 `INDEX.md` 의 실측값과 같고, **둘 다 실제로 걸린다** (선언만 하지 않는다)
- [ ] `404` 가 `code` 로 갈라진다 — `WIKI_CAPABILITY_EXPIRED` 는 중단, 나머지는 대상 없음
- [ ] 창구 7개가 모두 쓰인다 — 카테고리는 하이드레이션, 관계는 `get_backlinks`, 파싱본은 `lint`
- [ ] `wikiCapability` 가 없는 요청이 지금처럼 `SpringVaultFS` 로 돈다
- [ ] `wikiCapability` 가 로그·예외 메시지·telemetry 에 남지 않는다
- [ ] push 경로가 그대로 동작한다 — `tests/mcp/test_spring_vaultfs.py`·`tests/api/test_api_wiki.py` 통과
- [ ] `ai/` 밖 변경이 없다: `git diff --name-only develop..HEAD | grep -v '^ai/'` 가 이 계획의 커밋에서 비어 있다

## 범위 밖

| 항목 | 이유 |
| --- | --- |
| 창구 구현 자체 | 백엔드 |
| **push 경로의 `wikiPath` 배선** | 계약 1.6.0 이 필드를 넣었지만, 기존 위키 링크가 실제로 복원되는지는 별 티켓이다. 4.2 의 확인된 결함 |
| `wiki-context-selections` 축소·제거 | 창구 배포 후 (FR-WIKI-002) |
| 계약·요구사항 추가 개정 | v1.6.0·v2.11 로 이미 반영됐다. 부족한 것이 나오면 고치지 말고 기록한다 |
| `agent_start` 정리 | 요구사항 목록에 없는 값이다. 협의 필요 — Task 1 Step 6 에서 기록만 한다 |
| 파싱본 창구를 `lint` 에 배선 | `find_source` 가 이미 내부 색인으로 돈다. 창구로 바꾸는 것은 원본문서가 요청 본문에 오지 않게 된 뒤 |
| 10분 목표 달성 자체 | 출력 토큰 최적화는 별건 (D8) |
| 실기동 HTTP·Spring 실제 연동 | 이 계획은 인프로세스 테스트까지다. "동작 확인" 의 근거를 구분해 말한다 |
