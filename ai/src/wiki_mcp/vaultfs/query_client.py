"""Wiki 조회 API 클라이언트 — 계약 v1.5.0 「Wiki 조회 API」 7개.

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
맴도는 것을 끊는 것이다 (설계 9.5). 예산은 고정값이 아니라 **카탈로그 크기에 연동**한다
— 이유는 `QUERY_CALL_BUDGET_BASE` 주석에 있다.
"""

from __future__ import annotations

import httpx

from .base import VaultError

# 조회 횟수 예산. 시간 때문이 아니다 — 조회 API 호출은 수백 밀리초라 예산에 영향이 없다.
# 목적은 D9(`10-onboarding`: 724초에 툴 77회, 출력 589토큰)처럼 같은 것을 반복 조회하며
# 맴도는 것을 끊고, 조회 결과가 문맥에 쌓여 출력을 늘리는 것을 막는 것이다 (설계 9.5).
#
# **고정값이면 안 된다.** 첫 판본은 `MAX_QUERY_CALLS = 105` 고정이었는데, 링크가 있는
# 위키에서 정상 `read` **한 번**을 죽였다. `_ensure_body` → `_sync_page_references`
# (`spring.py:106`, 라이브 전체를 다시 훑는다) → `build_edges` → 링크마다 `fs.get` →
# 다시 `_ensure_body` 경로 때문에 read 한 번이 링크 연결 성분 전체를 당긴다. 실측
# (`experiments/measure_federated.py --link-density 1 --phases de`): 사슬 링크 100장에서
# read **1회**가 조회 API 100회·본문 100건이고(누적 104회), 150장에서는 150회다(누적 154회).
# 즉 소비량이 **위키 장수에 비례**하는데 상한이 고정이면 위키가 커지는 것만으로 정상
# 작업이 실패한다. 고정 105 로 같은 시나리오를 돌리면 100장에서도 QueryBudgetExceeded 다.
#
# 그래서 카탈로그 크기에 연동한다. 하이드레이션이 페이지 수를 알므로
# (`federated._hydrate_catalog` → `note_catalog_size`) 그 값으로 예산을 늘린다.
#
#   예산 = QUERY_CALL_BUDGET_BASE + QUERY_CALLS_PER_PAGE × 카탈로그 페이지 수
#
# 기본값 105 의 근거: 위키 100장 · 질의 5개 · 상위 3건 읽기 시나리오에서 링크가 없을 때
# 조회 API 호출이 23회였다(하이드레이션 3 + 검색 15 + 본문 5). 그리고 실제 작업의 상한 —
# `experiments/*/report.json` 8개 실험 문서 34건 중 조회 API 를 건드리는 툴 호출
# (read·search·lint)이 가장 많았던 문서가 32회다(`2026-07-27-opus46-12docs` 의 한 문서.
# D9 로 인용되는 폭주 문서다). 하이드레이션 3 을 더한 ~35회의 3배가 105 다.
#
# 장당 2회의 근거: 실측 fan-out 이 장당 본문 1.0회(100장→100건)다. 본문은 `wikiId` 로
# 캐시되므로 이 항은 장수로 묶인다. 여기에 관계 조회 API(`get_backlinks` → `relations`)를
# 장당 1회로 잡아 합쳐 2 다.
#
# **관계 조회는 캐시가 없어 장당이 아니라 호출당 1회다** — 같은 페이지를 열 번 읽으면
# 열 번 나간다. 즉 이 항은 상한이 아니라 "페이지마다 한 번씩 훑는 한 바퀴" 의 몫이다.
# 그래도 계수를 2 로 두는 근거는 본문 항이 장수로 묶여 여유가 남는 것이다 — 실측 소비가
# 100장에서 108/305, 150장에서 158/405 다. 그 여유를 넘겨 쓰는 것은 같은 페이지를
# 반복해 훑는 것이므로 맴돌기로 본다.
QUERY_CALL_BUDGET_BASE = 105
QUERY_CALLS_PER_PAGE = 2


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
    """Wiki 조회 API 7개. 한 요청의 생애만큼 살고 그때 버린다."""

    def __init__(self, base_url: str, *, api_key: str, capability: str,
                 scope_key: str, scope_version: int,
                 request_id: str | None = None, transport=None):
        self.scope_key = scope_key
        self.scope_version = scope_version
        # 테스트와 telemetry 가 보는 계수기.
        self.body_fetches = 0
        self.calls = 0
        # 하이드레이션 전에는 카탈로그 크기를 모른다. 기본값만으로 시작해
        # `note_catalog_size` 가 늘린다 — 목록 조회 자체가 예산 안에서 돌아야 한다.
        self.catalog_pages = 0
        self.call_budget = QUERY_CALL_BUDGET_BASE
        # **중단 신호다.** `ScopeChangedError` 는 `VaultError` 라서 툴 경계
        # (`tools/read.py:169`·`tools/search.py:248` 등 6곳)가 `except VaultError` 로
        # 잡아 문자열로 돌려준다 — 예외만으로는 세션이 중단을 알 방법이 없다. 그래서
        # 마지막 것을 여기 남긴다. `wiki_api/session.py` 가 에이전트 실행 뒤 이것을 보고
        # `failureStage = scope_changed` 로 올린다 (설계 2.4·2.6).
        #
        # 한계: 이 객체는 **호출한 프로세스**에 있다. MCP 서버가 별도 프로세스로 도는
        # 런타임(claude-code·deepagents)에서는 그쪽 클라이언트를 세션이 볼 수 없다.
        # 자세한 것은 `session.py._raise_if_scope_changed`.
        self.scope_change: ScopeChangedError | None = None
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

    def note_scope_change(self, error: ScopeChangedError) -> ScopeChangedError:
        """범위가 바뀐 사실을 기억하고 그 예외를 돌려준다 (`raise client.note_...` 용).

        어댑터(`federated.py`)도 이 조회 API 를 쓴다 — 본문이 사라진 경합은 버전 비교가 아니라
        `404` 로 드러나므로 거기서 만든 예외도 같은 자리에 남아야 한다.
        """
        self.scope_change = error
        return error

    # ----- 공통 -------------------------------------------------------------

    def note_catalog_size(self, pages: int) -> None:
        """카탈로그 페이지 수를 받아 예산을 다시 잡는다.

        하이드레이션이 부른다. **줄이지 않는다** — 같은 클라이언트가 다시 하이드레이션
        하면(재시도) 이미 쓴 호출이 남아 있으므로 예산이 내려가면 그 자리에서 죽는다.
        """
        self.catalog_pages = max(self.catalog_pages, max(pages, 0))
        self.call_budget = max(
            self.call_budget,
            QUERY_CALL_BUDGET_BASE + QUERY_CALLS_PER_PAGE * self.catalog_pages)

    async def _get(self, path: str, params: dict | None = None) -> dict:
        if self.calls >= self.call_budget:
            raise QueryBudgetExceeded(
                f"조회 횟수 예산 {self.call_budget}회를 넘었습니다 "
                f"(기본 {QUERY_CALL_BUDGET_BASE} + 위키 {self.catalog_pages}장"
                f" × {QUERY_CALLS_PER_PAGE})")
        self.calls += 1
        response = await self._http.get(path, params=dict(params or {}))
        if response.status_code == 404:
            code = self._error_code(response)
            if code == "WIKI_CAPABILITY_EXPIRED":
                raise self.note_scope_change(ScopeChangedError(
                    self.scope_version, reason="열람 허가가 만료되었습니다"))
            raise QueryNotFound(f"요청한 자료를 찾을 수 없습니다 — {path}")
        if response.status_code >= 400:
            # 본문을 그대로 싣지 않는다 — capability 가 되돌아올 여지를 남기지 않는다.
            raise VaultError(f"조회 API 오류 {response.status_code} — {path}")
        body = response.json()
        # 파싱본 조회 API 만 scopeVersion 이 없다 (설계 2.4·3.7). 문서 파싱 결과는 위키
        # 스냅샷과 무관하므로 위키 버전으로 판정하면 무관한 이유로 중단된다.
        actual = body.get("scopeVersion")
        if actual is not None and actual != self.scope_version:
            raise self.note_scope_change(
                ScopeChangedError(self.scope_version, actual))
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

    # ----- 조회 API -----------------------------------------------------------

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
        """공간 목차 마크다운.

        지금은 아무도 부르지 않는다 — 목차는 Spring 이 DB 로 그리므로(S15P11B106-280)
        `FederatedVaultFS._hydrate_catalog` 가 이 응답을 하이드레이션하지 않는다. 계약이
        제공하는 창구라 메서드 자체는 남긴다.
        """
        body = await self._get(
            f"/internal/v1/wiki-spaces/{self.scope_key}/index")
        return body.get("indexMarkdown", "")

    async def categories(self) -> list[dict]:
        body = await self._get(
            f"/internal/v1/wiki-spaces/{self.scope_key}/categories")
        return body.get("items", [])

    async def scope_relations(self) -> list[dict]:
        """범위 전체의 참조 간선. **뒤집기는 여기서 하지 않는다.**

        계약이 이 사용을 명시한다 — "역방향(`backlinks`)은 싣지 않습니다. 범위 전체
        간선이 있으면 소비자가 뒤집어 구합니다." 방향을 정하는 것은 카탈로그의 일이고
        (`federated._hydrate_catalog`), 여기는 조회 API 응답을 그대로 넘긴다.

        페이지별 `relations()` 와 달리 `scopeKey` 쿼리 파라미터가 없다 — 경로에 있다.
        """
        body = await self._get(
            f"/internal/v1/wiki-spaces/{self.scope_key}/relations")
        return body.get("items", [])

    async def parsed_document(self, document_id: str) -> dict:
        return await self._get(
            f"/internal/v1/documents/{document_id}/parsed", self._scoped())
