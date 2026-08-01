# 위키 변환 단일 호출 구현 계획 (S15P11B106-175)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 위키 변환·수정을 Wiki 조회 API 기반 단일 엔드포인트로 합치고, 1단계 문맥 선택
(`wiki-context-selections`)과 push 하이드레이션 경로를 완전히 제거한다.

**Architecture:** `FederatedVaultFS` 하이드레이션에서 `GET /wiki-spaces/{scopeKey}/relations`
를 1회 부르고 `wikiRefs`·`documentRefs` 를 양방향으로 뒤집어 카탈로그에 넣는다. 그 표가
(1) 삭제·교체 재조정의 인용 위키 (2) `merge`·`delete` 역링크 (3) `wiki-edits` 근거 문서를
전부 공급한다. 요청 본문에서 위키를 실어 보내던 경로는 지운다.

**Tech Stack:** Python 3.12 · FastAPI · pydantic v2 · httpx · aiosqlite · pytest ·
uv. 소스는 `ai/src/` src 레이아웃.

**설계 정본:** `ai/docs/superpowers/specs/2026-08-01-wiki-transformation-single-call-design.md`

## Global Constraints

- 작업 디렉터리는 `ai/`. 명령은 전부 거기서 실행한다.
- 테스트: `uv run pytest -m "not ocr"`. 착수 전 기준선을 잡고, 내가 늘린 실패만 본다.
- `ai/` 밖(`backend/`·`frontend/`·`docs/`·루트)은 **수정하지 않는다.** 읽기만 한다.
- 계약 파일(`docs/api/*.json`)과 생성기(`docs/api/generate-postman-collections.mjs`)는
  건드리지 않는다. 재생성은 백엔드 S15P11B106-174 몫이다.
- 커밋 메시지에 Claude 공동저자 트레일러를 붙이지 않는다.
- `git add` 는 **고친 경로를 하나하나 명시한다.** `git add -A`·`git add .` 금지.
- `.env` 는 커밋하지 않는다. `src/.env.example` 만 커밋한다.
- `mcp` 라는 이름의 디렉터리를 만들지 않는다. `tests/__init__.py` 를 지우지 않는다.
- 새 오류 코드 이름을 만들지 않는다. `WIKI_TRANSFORMATION_FAILED`·`WIKI_EDIT_FAILED` 와
  `FailureStage` 로 표현한다.
- 모델명은 정확한 이름을 쓴다 (`claude-opus-4-6` 같은 형태). `opus` 별칭 금지.

---

## File Structure

**새로 만드는 파일 — 없다.** 전부 기존 파일 수정이다.

| 파일 | 책임 | 태스크 |
| --- | --- | --- |
| `src/wiki_mcp/vaultfs/query_client.py` | 조회 API HTTP 클라이언트. `scope_relations()` 추가 | 1 |
| `experiments/query_gateway.py` | 가짜 조회 API. 범위 관계 엔드포인트 추가 | 1 |
| `src/wiki_mcp/vaultfs/federated.py` | 카탈로그 관계 표·역링크·인용 역링크 | 2·3·4 |
| `src/agent_runtime/guards.py` | 읽기 없이 쓴 실행 판정 | 5 |
| `src/wiki_api/session.py` | 하이드레이션 게이트·push 경로 제거 | 5·8 |
| `src/wiki_api/serve.py` | `BACKEND_BASE_URL` 기동 검사 | 6 |
| `src/wiki_api/schemas.py` | 요청 계약 정리 | 7 |
| `src/wiki_api/errors.py` | 1단계 경로 항목 제거 | 7 |
| `src/wiki_api/routers/wiki.py` | 1단계 엔드포인트 삭제·조회 API 전용 배선 | 8 |
| `src/wiki_api/selection.py` | **파일 삭제** | 8 |
| `src/agent_runtime/base.py` | `selection_instruction` 삭제 | 8 |
| `src/wiki_mcp/vaultfs/spring.py` | push 하이드레이션 삭제 | 8 |

---

## Task 1: 범위 관계 조회 API

`GET /internal/v1/wiki-spaces/{scopeKey}/relations` 를 부르는 클라이언트 메서드와, 그것을
흉내내는 가짜 조회 API 엔드포인트를 만든다. 조회 API 8개 중 유일하게 쓰지 않던 것이다.

**Files:**
- Modify: `src/wiki_mcp/vaultfs/query_client.py` (`categories()` 아래)
- Modify: `experiments/query_gateway.py` (`wiki_categories` 아래)
- Test: `tests/mcp/test_query_client.py`

**Interfaces:**
- Produces: `WikiQueryClient.scope_relations() -> list[dict]` — 각 항목은
  `{"wikiId": str, "wikiRefs": list[str], "documentRefs": list[str]}`. 응답 본문의
  `items` 를 그대로 돌려준다. `scopeVersion` 검사는 `_get` 이 이미 한다.
- Produces: 가짜 조회 API 의 `GET /internal/v1/wiki-spaces/{scope_key}/relations`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/mcp/test_query_client.py` 맨 아래에 추가한다. 파일 상단의 기존 헬퍼
(`httpx.MockTransport` 로 클라이언트를 만드는 방식)를 그대로 따른다 — 파일을 먼저 읽고
같은 픽스처 이름을 쓴다.

```python
async def test_scope_relations_returns_items(anyio_backend):
    """범위 관계는 items 를 그대로 돌려준다 — 뒤집기는 카탈로그가 한다."""
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        return httpx.Response(200, json={
            "scopeVersion": 47,
            "items": [
                {"wikiId": "101", "wikiRefs": ["102"], "documentRefs": ["15"]},
                {"wikiId": "102", "wikiRefs": [], "documentRefs": ["15", "16"]},
            ],
        })

    client = WikiQueryClient(
        "http://backend", api_key="k", capability="c", scope_key="D1",
        scope_version=47, transport=httpx.MockTransport(handler))
    try:
        items = await client.scope_relations()
    finally:
        await client.aclose()

    assert seen == ["/internal/v1/wiki-spaces/D1/relations"]
    assert items[0]["documentRefs"] == ["15"]
    assert items[1]["wikiRefs"] == []


async def test_scope_relations_on_empty_scope(anyio_backend):
    """위키 0장인 범위는 빈 배열이다 (계약 정책). 오류가 아니다."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"scopeVersion": 47, "items": []})

    client = WikiQueryClient(
        "http://backend", api_key="k", capability="c", scope_key="D1",
        scope_version=47, transport=httpx.MockTransport(handler))
    try:
        assert await client.scope_relations() == []
    finally:
        await client.aclose()
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `uv run pytest tests/mcp/test_query_client.py -k scope_relations -v`
Expected: FAIL — `AttributeError: 'WikiQueryClient' object has no attribute 'scope_relations'`

- [ ] **Step 3: 클라이언트 메서드를 만든다**

`src/wiki_mcp/vaultfs/query_client.py` 의 `categories()` 바로 아래에 넣는다.

```python
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
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `uv run pytest tests/mcp/test_query_client.py -k scope_relations -v`
Expected: PASS (2 passed)

- [ ] **Step 5: 가짜 조회 API 에 같은 엔드포인트를 붙인다**

`experiments/query_gateway.py` 의 `wiki_categories` 아래에 넣는다. `documentRefs` 는
본문의 각주가 가리키는 원본문서 디렉터리 이름으로 도출한다 — 실제 백엔드가
`wiki.document_refs` JSON 으로 갖고 있는 것과 같은 관계다.

```python
    @app.get("/internal/v1/wiki-spaces/{scope_key}/relations")
    async def scope_relations(request: Request, scope_key: str):
        """범위 전체 간선. 역방향은 싣지 않는다 (계약 정책).

        `documentRefs` 는 본문에 원본문서 디렉터리 이름이 등장하는지로 도출한다.
        각주가 파일명으로 문서를 가리키므로(`tools/references.py`) 실제 백엔드의
        `wiki.document_refs` 와 같은 집합이 나온다.
        """
        if not guard(request, scope_key):
            return _not_found()
        sources = [path.name for path
                   in sorted((state.corpus / "sources").glob("*"))
                   if path.is_dir()]
        items = []
        for page in state.pages.values():
            body = page["_body"]
            items.append({
                "wikiId": page["wikiId"],
                "wikiRefs": [other["wikiId"] for other in state.pages.values()
                             if other["wikiId"] != page["wikiId"]
                             and Path(other["wikiPath"]).name in body],
                "documentRefs": [name for name in sources if name in body],
            })
        return envelope({"items": items})
```

- [ ] **Step 6: 가짜 조회 API 가 뜨는지 확인한다**

Run: `uv run python -c "
from pathlib import Path
from experiments.query_gateway import build_gateway
app = build_gateway(Path('.'), scope_key='ALL', capability='c', api_key='k')
print([r.path for r in app.routes if 'relations' in getattr(r, 'path', '')])
"`
Expected: `['/internal/v1/wikis/{wiki_id}/relations', '/internal/v1/wiki-spaces/{scope_key}/relations']`

경로 임포트가 안 되면 `uv run python -m ...` 대신 `PYTHONPATH=. uv run python -c ...` 로
바꾼다. `experiments/` 는 패키지가 아니라 스크립트 모음이다.

- [ ] **Step 7: 커밋**

```bash
git add src/wiki_mcp/vaultfs/query_client.py experiments/query_gateway.py tests/mcp/test_query_client.py
git commit -m "feat(ai): 범위 전체 참조 관계 조회 API 를 붙인다 [S15P11B106-175]"
```

---

## Task 2: 카탈로그가 관계 표를 든다

하이드레이션에서 `scope_relations()` 를 1회 부르고 결과를 **주소 기준 양방향 표**로
뒤집어 `_Catalog` 에 넣는다. 이후 태스크 3·4 가 이 표만 읽는다.

**Files:**
- Modify: `src/wiki_mcp/vaultfs/federated.py` (`_Catalog`, `_hydrate_catalog`)
- Test: `tests/mcp/test_federated_vaultfs.py`

**Interfaces:**
- Consumes: `WikiQueryClient.scope_relations()` (Task 1)
- Produces: `_Catalog.wiki_backlinks: dict[str, set[str]]` — 주소 → 그 주소를 가리키는
  위키 주소들
- Produces: `_Catalog.wikis_by_document: dict[str, list[str]]` — documentId → 그 문서를
  인용하는 위키 주소들 (카탈로그 등장 순서)
- Produces: `_Catalog.document_ids_by_address: dict[str, list[str]]` — 위키 주소 → 그
  위키가 근거로 쓴 documentId 들 (Task 8 의 `wiki-edits` 가 쓴다)
- Produces: `FederatedVaultFS.page_count -> int` — 하이드레이션이 받은 라이브 위키 장수

- [ ] **Step 1: `FakeClient` 를 넓힌다**

`tests/mcp/test_federated_vaultfs.py` 의 기존 `FakeClient` 를 고친다 — **새 fake 를 만들지
않는다.** 지금 모양은 이렇다 (파일 상단):

```python
PAGES = [
    {"wikiId": "101", ..., "wikiPath": "wiki/D1-D2/pages/a3f2c1d4.md", ...},
    {"wikiId": "108", ..., "wikiPath": "wiki/D1-D2/pages/b7e1f2a9.md", ...},
]
class FakeClient:
    def __init__(self, *, scope_key="D1-D2", bodies=None):
```

**주소는 `wikiPath` 에서 나온다** — `pages/101.md` 가 아니라 `pages/a3f2c1d4.md` 다
(`address_from_wiki_path`). 아래 테스트는 그 주소를 쓴다.

생성자와 메서드를 이렇게 넓힌다. `pages` 를 생성자로 받는 것이 핵심이다 — 모듈 전역
`PAGES` 를 읽으면 「빈 범위」를 시험할 수 없고, S15P11B106-151 에서 `bodies` 를 전역으로
읽어 테스트가 무의미해진 전례가 있다.

```python
    def __init__(self, *, scope_key="D1-D2", bodies=None, pages=None,
                 relations_items=None):
        self.scope_key = scope_key
        self.pages = [dict(p) for p in (PAGES if pages is None else pages)]
        self.bodies = dict(BODIES if bodies is None else bodies)
        self.relations_items = [] if relations_items is None else relations_items
        self.scope_relations_calls = 0
        self.relations_calls = 0
        ...  # 나머지 기존 필드 그대로

    async def list_pages(self):
        return [dict(page) for page in self.pages]

    async def scope_relations(self):
        self.scope_relations_calls += 1
        return self.relations_items

    async def relations(self, wiki_id):
        self.relations_calls += 1
        return {"scopeVersion": 47, "wikiId": wiki_id,
                "wikiRefs": [], "documentRefs": [], "backlinks": []}

    async def page_content(self, wiki_id):
        if wiki_id not in self.bodies:
            raise QueryNotFound(wiki_id)
        self.body_fetches += 1
        page = next(p for p in self.pages if p["wikiId"] == wiki_id)
        ...  # 나머지 그대로
```

`page_content` 가 모듈 전역 `PAGES` 를 보던 것을 `self.pages` 로 바꾸는 것을 빠뜨리지
않는다 — 안 바꾸면 `pages=` 를 준 테스트에서 `StopIteration` 이 난다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

같은 파일 아래에 추가한다. 주소 상수를 파일 상단에 둔다.

```python
A101 = "pages/a3f2c1d4.md"   # PAGES[0] 의 wikiPath 에서 나오는 주소
A108 = "pages/b7e1f2a9.md"   # PAGES[1]


async def test_hydration_pulls_scope_relations_once(tmp_path):
    """범위 관계는 하이드레이션에서 딱 한 번 받는다."""
    client = FakeClient(relations_items=[
        {"wikiId": "101", "wikiRefs": ["108"], "documentRefs": ["15"]},
        {"wikiId": "108", "wikiRefs": [], "documentRefs": ["15", "16"]}])
    await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        assert client.scope_relations_calls == 1
        catalog = fs._catalog
        # 위키→위키를 뒤집는다. 101 이 108 을 가리키므로 108 의 역링크가 101 이다.
        assert catalog.wiki_backlinks[A108] == {A101}
        assert catalog.wiki_backlinks.get(A101, set()) == set()
        # 문서→위키를 뒤집는다. 카탈로그 등장 순서다.
        assert catalog.wikis_by_document["15"] == [A101, A108]
        assert catalog.wikis_by_document["16"] == [A108]
        # 정방향도 남긴다 — wiki-edits 가 근거 문서를 찾을 때 쓴다.
        assert catalog.document_ids_by_address[A108] == ["15", "16"]
        assert fs.page_count == 2
    finally:
        await FederatedVaultFS.close()


async def test_hydration_tolerates_unknown_wiki_ids_in_relations(tmp_path):
    """목록에 없는 wikiId 가 관계에 섞여 있으면 버린다 — 다른 범위이거나 그 사이 지워졌다."""
    client = FakeClient(relations_items=[
        {"wikiId": "101", "wikiRefs": ["999"], "documentRefs": []},
        {"wikiId": "999", "wikiRefs": [], "documentRefs": ["77"]}])
    await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        assert "77" not in fs._catalog.wikis_by_document
        assert fs._catalog.wiki_backlinks == {}
    finally:
        await FederatedVaultFS.close()


async def test_page_count_is_zero_on_empty_scope(tmp_path):
    """위키 0장인 범위는 정상이다 — 오류가 아니다."""
    client = FakeClient(pages=[], bodies={}, relations_items=[])
    await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        assert fs.page_count == 0
        assert client.scope_relations_calls == 1
    finally:
        await FederatedVaultFS.close()
```

이 파일의 기존 테스트는 `federated` 픽스처(`yield fs, scope_id, client`)를 쓴다. 위
셋은 `pages`·`relations_items` 를 달리 줘야 해서 픽스처를 쓰지 않고 직접 연다. `anyio`
표시 방식은 파일 상단 설정(`pytestmark` 등)을 따른다 — **먼저 읽고 맞춘다.**

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run: `uv run pytest tests/mcp/test_federated_vaultfs.py -k "scope_relations or unknown_wiki_ids or page_count" -v`
Expected: FAIL — `AttributeError: '_Catalog' object has no attribute 'wiki_backlinks'`

- [ ] **Step 4: `_Catalog` 에 표 세 개를 더한다**

`src/wiki_mcp/vaultfs/federated.py` 의 `_Catalog.__init__` 에서 `self.categories` 아래에
넣는다.

```python
        # 범위 관계를 뒤집은 표 셋. 하이드레이션 1회로 다 채운다 (설계 §3).
        #
        # 조회 API 는 정방향 간선만 준다("소비자가 뒤집어 구합니다"). 방향을 여기서 정해
        # 두면 소비자 셋(역링크·삭제 재조정·근거 문서)이 각자 뒤집지 않는다.
        #
        # 주소를 키로 쓴다 — wikiId 를 키로 두면 소비자마다 카탈로그로 되돌려야 한다.
        # 카탈로그에 없는 wikiId 는 버린다: 다른 범위이거나 목록 조회와 관계 조회
        # 사이에 지워진 것이고, 어느 쪽이든 이 작업이 손댈 수 없는 페이지다.
        self.wiki_backlinks: dict[str, set[str]] = {}
        self.wikis_by_document: dict[str, list[str]] = {}
        self.document_ids_by_address: dict[str, list[str]] = {}
```

- [ ] **Step 5: 하이드레이션에서 받아 뒤집는다**

같은 파일 `_hydrate_catalog` 안, `index_markdown = await self._client.index_markdown()`
**바로 앞**에 넣는다. 페이지 루프가 끝나 주소 표가 다 채워진 뒤여야 한다.

```python
        # 범위 관계 1회. 페이지 루프 뒤에 둔다 — 뒤집으려면 wikiId↔주소 표가 먼저
        # 다 있어야 한다.
        await self._invert_scope_relations()
```

그리고 `_hydrate_catalog` 아래에 메서드를 만든다.

```python
    async def _invert_scope_relations(self) -> None:
        """범위 전체 간선을 받아 주소 기준 양방향 표로 뒤집는다 (설계 §3).

        이 한 번의 조회가 셋을 공급한다 — `merge`·`delete` 의 역링크, 삭제·교체
        재조정의 인용 위키, `wiki-edits` 의 근거 문서. 예전에는 첫 번째를 위해
        페이지마다 `/wikis/{id}/relations` 를 불렀고 그 호출은 캐시가 없었다.
        """
        catalog = self._catalog
        by_id = catalog.address_by_wiki_id
        for item in await self._client.scope_relations():
            address = by_id.get(item.get("wikiId"))
            if address is None:
                # 다른 범위이거나 목록 조회 뒤에 지워졌다. 이 작업이 못 만지는
                # 페이지이므로 간선도 의미가 없다.
                continue
            for target_id in item.get("wikiRefs") or []:
                target = by_id.get(target_id)
                if target is None:
                    continue
                catalog.wiki_backlinks.setdefault(target, set()).add(address)
            document_ids = [str(d) for d in (item.get("documentRefs") or [])]
            if document_ids:
                catalog.document_ids_by_address[address] = document_ids
            for document_id in document_ids:
                catalog.wikis_by_document.setdefault(document_id, []).append(address)
        # 카탈로그 등장 순서로 맞춘다. 조회 API 응답 순서에 기대면 재조정 지시문의
        # 항목 순서가 호출마다 흔들려 측정 대조가 깨진다.
        order = {address: n for n, address
                 in enumerate(catalog.wiki_id_by_address)}
        for addresses in catalog.wikis_by_document.values():
            addresses.sort(key=lambda a: order.get(a, len(order)))
```

- [ ] **Step 6: `page_count` 를 노출한다**

같은 파일, `categories` 프로퍼티 아래에 넣는다.

```python
    @property
    def page_count(self) -> int:
        """하이드레이션이 받은 라이브 위키 장수.

        `list_documents` 를 세지 않는다 — 그쪽은 작업 층에 새로 쓴 페이지와 원본문서를
        같이 세므로, 「원래 위키가 몇 장이었나」를 묻는 게이트(설계 §4.1)가 실행 중에
        답이 바뀐다.
        """
        return len(self._catalog.wiki_id_by_address)
```

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

Run: `uv run pytest tests/mcp/test_federated_vaultfs.py -v`
Expected: PASS — 새 3건 포함 전부 통과

- [ ] **Step 8: 커밋**

```bash
git add src/wiki_mcp/vaultfs/federated.py tests/mcp/test_federated_vaultfs.py
git commit -m "feat(ai): 하이드레이션이 범위 관계를 받아 양방향으로 뒤집는다 [S15P11B106-175]"
```

---

## Task 3: 역링크를 카탈로그에서 읽는다

`get_backlinks` 의 원격 보강을 페이지별 조회에서 카탈로그 조회로 바꾼다. 조회 API 호출이
호출당 1회씩 사라진다.

**Files:**
- Modify: `src/wiki_mcp/vaultfs/federated.py` (`get_backlinks`)
- Test: `tests/mcp/test_federated_vaultfs.py`

**Interfaces:**
- Consumes: `_Catalog.wiki_backlinks` (Task 2)
- Produces: 동작 변화 없음. `get_backlinks` 반환 모양은 그대로다 —
  `{"address", "title", "kind", "reference_type", "origin"}`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```python
async def test_backlinks_come_from_catalog_without_extra_calls(tmp_path):
    """역링크는 카탈로그에서 읽는다 — 페이지별 relations 를 부르지 않는다."""
    client = FakeClient(relations_items=[
        {"wikiId": "101", "wikiRefs": ["108"], "documentRefs": []},
        {"wikiId": "108", "wikiRefs": [], "documentRefs": []}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        rows = await fs.get_backlinks(scope_id, A108)
        assert client.relations_calls == 0           # 페이지별 조회 0회
        assert [r["address"] for r in rows] == [A101]
        row = rows[0]
        # 소비자가 대괄호로 읽는 키가 다 있어야 한다 (tools/references.py·search.py·write.py).
        assert row["title"] == "휴가 규정"
        assert row["kind"] == "page"
        assert row["reference_type"] == "links_to"
        assert row["origin"] == "live"
    finally:
        await FederatedVaultFS.close()


async def test_backlinks_do_not_pull_bodies(tmp_path):
    """역링크 목록을 그리려고 남의 본문을 당기지 않는다 (S15P11B106-151)."""
    client = FakeClient(relations_items=[
        {"wikiId": "101", "wikiRefs": ["108"], "documentRefs": []}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        before = client.body_fetches
        await fs.get_backlinks(scope_id, A108)
        assert client.body_fetches == before
    finally:
        await FederatedVaultFS.close()
```

`relations_calls` 카운터는 Task 2 Step 1 에서 더했다. 본문 조회 카운터는 이 파일이 이미
갖고 있는 `body_fetches` 다 — 새 이름을 만들지 않는다.

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `uv run pytest tests/mcp/test_federated_vaultfs.py -k backlinks -v`
Expected: FAIL — `assert client.relations_calls == before` 에서 1 != 0

- [ ] **Step 3: `get_backlinks` 를 카탈로그 기반으로 바꾼다**

`src/wiki_mcp/vaultfs/federated.py` 의 `get_backlinks` 를 통째로 교체한다.

```python
    async def get_backlinks(self, scope_id: str, address: str) -> list[dict]:
        """내부 그래프 + 하이드레이션이 받아둔 범위 관계를 합친다.

        **지연 적재의 사각지대를 메우는 곳이다** (설계 9.6). 내부 그래프는 본문을
        당긴 페이지만 안다. 제거·병합은 범위 전체의 역링크를 알아야 하는데, 안 읽은
        페이지의 링크를 놓치면 남의 링크를 조용히 끊는다.

        예전에는 여기서 `/wikis/{wikiId}/relations` 를 불렀다. 그 호출은 캐시가 없어
        **부를 때마다 1회**였고 조회 예산의 가장 큰 항이었다. 이제 하이드레이션이 범위
        전체를 한 번에 받아 뒤집어 두므로(S15P11B106-175) 여기서 나가는 조회가 없다.
        """
        rows = await super().get_backlinks(scope_id, address)
        known = {row["address"] for row in rows}
        for remote_address in sorted(self._catalog.wiki_backlinks.get(address, ())):
            if remote_address in known:
                continue
            # 소비자가 대괄호로 읽는 키를 다 채운다. tools/references.py:142-143 ·
            # tools/search.py:175-176 · tools/write.py:331-332 이 각각
            # reference_type · title · kind 를 KeyError 없이 요구한다. `KeyError` 는
            # `VaultError` 가 아니라 툴의 `except VaultError` 에도 안 걸린다.
            # `super().get` 을 쓰는 이유: `self.get` 은 `_ensure_body` 를 타서 본문을
            # 당긴다 — 역링크 목록을 그리려고 남의 본문을 받을 이유가 없다.
            row = await super().get(scope_id, remote_address)
            rows.append({
                "address": remote_address,
                "title": (row or {}).get("title"),
                "kind": "page",
                # 조회 API 는 인용/링크를 구분해 주지 않는다(계약의 wikiRefs 는 ID 배열).
                # 인용 간선은 각주 기반이라 그 페이지를 읽으면 내부 그래프에 어차피
                # 잡히므로 원격 행은 links_to 로 표시한다. 계약에 없는 값이라 MR
                # 협의 항목으로 올린다.
                "reference_type": "links_to",
                "origin": "live",
            })
        return rows
```

`QueryNotFound` 임포트가 이 파일에서 더 이상 쓰이지 않으면 임포트 줄에서 뺀다. 다른
곳(`_ensure_body`)에서 쓰고 있으면 그대로 둔다 — **먼저 grep 으로 확인한다.**

Run: `uv run grep -rn "QueryNotFound" src/wiki_mcp/vaultfs/federated.py`

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `uv run pytest tests/mcp/ -v`
Expected: PASS — 기존 역링크 테스트 포함 전부

기존 테스트가 「페이지별 relations 를 부른다」를 단언하고 있으면 그 테스트를 이번 동작에
맞게 고친다. 지우지 말고 고친다 — 원격 보강이 여전히 일어난다는 사실은 지켜야 한다.

- [ ] **Step 5: 커밋**

```bash
git add src/wiki_mcp/vaultfs/federated.py tests/mcp/test_federated_vaultfs.py
git commit -m "perf(ai): 역링크를 카탈로그에서 읽어 페이지별 관계 조회를 없앤다 [S15P11B106-175]"
```

---

## Task 4: 삭제·교체 인용 위키를 되찾는다

`get_citation_backlinks` 가 조회 API 모드에서 빈 목록을 내는 것을 고친다. 설계 §3.1 —
**이 작업의 유일한 실질 구멍이다.**

**Files:**
- Modify: `src/wiki_mcp/vaultfs/federated.py` (`get_citation_backlinks` 신규 오버라이드)
- Test: `tests/mcp/test_federated_vaultfs.py`

**Interfaces:**
- Consumes: `_Catalog.wikis_by_document` (Task 2), `_ensure_body`
- Produces: `FederatedVaultFS.get_citation_backlinks(scope_id, address) -> list[dict]`
  — `SpringVaultFS` 와 같은 모양 `{"address", "footnote_label", "location", "quote"}`

**배경.** 원본문서 주소는 `sources/{documentId}/parsed/content.md` 다
(`SpringVaultFS.stage_source`). 부모 구현은 로컬 `document_references` 표를 읽는데, 그
표는 본문을 당긴 페이지만 담는다. 조회 API 는 본문을 지연 적재하므로 하이드레이션 직후에는
비어 있다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

인용문은 각주 정의 줄에서 뽑히고 원문 대조를 통과해야 한다 (`tools/references.py` 의
`_QUOTE_RE`, `location` 은 ` — ` 앞부분). 본문·원본문서 본문·인용문 셋이 정확히 맞아야
하므로 아래 상수를 그대로 쓴다.

```python
DOC_BODY = "연차휴가는 15일로 한다"

CITING_BODY = """---
title: 휴가 규정
---

연차는 15일이다[^1]

[^1]: 15.pdf, 3장 휴가 — "연차휴가는 15일로 한다"
"""

PLAIN_BODY = "---\ntitle: 보안 규정\n---\n\n각주 없는 본문\n"


async def test_citation_backlinks_pull_only_citing_pages(tmp_path):
    """문서를 인용한 위키만 당겨 각주를 뽑는다 — 범위 전체를 당기지 않는다."""
    client = FakeClient(
        bodies={"101": CITING_BODY, "108": PLAIN_BODY},
        relations_items=[{"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]},
                         {"wikiId": "108", "wikiRefs": [], "documentRefs": []}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        address = await fs.stage_source(scope_id, "15", DOC_BODY, "15.pdf")
        before = client.body_fetches
        rows = await fs.get_citation_backlinks(scope_id, address)
        # 인용한 1장만 당겼다. 108 은 안 당긴다.
        assert client.body_fetches - before == 1
        assert [r["address"] for r in rows] == [A101]
        assert rows[0]["footnote_label"] == "1"
        assert rows[0]["quote"] == DOC_BODY
    finally:
        await FederatedVaultFS.close()


async def test_citation_backlinks_empty_when_nobody_cites(tmp_path):
    """아무도 안 쓴 문서는 빈 목록이다. 합법이며 실패가 아니다 (설계 §4.1)."""
    client = FakeClient(
        bodies={"101": CITING_BODY, "108": PLAIN_BODY},
        relations_items=[{"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        address = await fs.stage_source(scope_id, "99", "다른 문서", "99.pdf")
        assert await fs.get_citation_backlinks(scope_id, address) == []
    finally:
        await FederatedVaultFS.close()


async def test_citation_backlinks_drop_pages_without_the_footnote(tmp_path):
    """관계 표가 인용했다고 해도 본문에 각주가 없으면 버린다 (설계 §3.1 대조).

    `documentRefs` 는 백엔드가 관리하는 JSON 이라 낡을 수 있다. 당긴 본문에 각주가
    없으면 `sync_references` 가 간선을 만들지 않으므로 부모 SELECT 가 그 행을 내지
    않는다 — 대조는 별도 코드가 아니라 이 성질이다.
    """
    client = FakeClient(
        bodies={"101": PLAIN_BODY, "108": PLAIN_BODY},
        relations_items=[{"wikiId": "101", "wikiRefs": [], "documentRefs": ["15"]}])
    scope_id = await FederatedVaultFS.open(tmp_path, "D1-D2", "job-1", client=client)
    fs = FederatedVaultFS("D1-D2", "job-1", client)
    try:
        address = await fs.stage_source(scope_id, "15", DOC_BODY, "15.pdf")
        assert await fs.get_citation_backlinks(scope_id, address) == []
        # 그래도 당기기는 했다 — 관계 표가 인용했다고 말했으므로 확인이 필요했다.
        assert client.body_fetches >= 1
    finally:
        await FederatedVaultFS.close()
```

`A101` 은 Task 2 Step 2 에서 만든 주소 상수다. `bodies` 는 이 파일의 `FakeClient` 가 이미
생성자 인자로 받는다.

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `uv run pytest tests/mcp/test_federated_vaultfs.py -k citation_backlinks -v`
Expected: FAIL — 첫 테스트가 `assert [] == ["pages/101.md"]`

- [ ] **Step 3: 오버라이드를 만든다**

`src/wiki_mcp/vaultfs/federated.py` 의 `get_backlinks` 아래에 넣는다. `SOURCES_PREFIX` 는
`vaultfs/base.py:22` 에 있고 이 파일은 이미 `from .base import VaultError` 를 하고 있으니
그 줄에 더한다.

```python
from .base import SOURCES_PREFIX, VaultError
```

```python
    async def get_citation_backlinks(self, scope_id: str, address: str) -> list[dict]:
        """원본문서를 인용한 위키의 본문을 먼저 당기고 부모에게 넘긴다 (설계 §3.1).

        부모(`SpringVaultFS`)는 로컬 `document_references` 표를 읽는다. 그 표는 본문을
        당긴 페이지만 담고, 조회 API 는 본문을 지연 적재하므로(S15P11B106-151) 하이드레이션
        직후에는 비어 있다 — 그대로 두면 삭제·교체 재조정 지시문이 비고 사라진 문서를
        가리키는 각주가 위키에 그대로 남는다.

        **범위 전체를 당기지 않는다.** 하이드레이션이 받아둔 `documentRefs` 를 뒤집어
        인용한 위키만 당긴다. 100장 중 2장이 인용했으면 본문 조회는 2회다.

        **대조는 부모가 한다.** 여기서 당긴 본문은 `_ensure_body` 가 `sync_references`
        를 태워 실제 각주만 표에 넣는다. 관계 표가 낡아 인용하지 않는 위키가 섞여 와도
        각주가 없으면 부모의 SELECT 가 그 행을 내지 않는다.
        """
        if address.startswith(SOURCES_PREFIX):
            document_id = address[len(SOURCES_PREFIX):].split("/", 1)[0]
            for citing in self._catalog.wikis_by_document.get(document_id, ()):
                await self._ensure_body(scope_id, citing)
        return await super().get_citation_backlinks(scope_id, address)
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `uv run pytest tests/mcp/test_federated_vaultfs.py -v`
Expected: PASS

- [ ] **Step 5: 전체 테스트로 회귀를 확인한다**

Run: `uv run pytest -m "not ocr" -q`
Expected: 착수 기준선과 같은 실패 수 (0)

- [ ] **Step 6: 커밋**

```bash
git add src/wiki_mcp/vaultfs/federated.py tests/mcp/test_federated_vaultfs.py
git commit -m "fix(ai): 삭제·교체 재조정이 조회 API 에서 인용 위키를 되찾는다 [S15P11B106-175]"
```

---

## Task 5: 안전장치 두 개

「조회가 비었다」와 「조회하지 않았다」를 가른다 (설계 §4.1·§4.2).

**Files:**
- Modify: `src/agent_runtime/guards.py`
- Modify: `src/wiki_api/session.py`
- Test: `tests/runtime/test_runtime_guards.py`
- Test: `tests/api/test_federated_session.py`

**Interfaces:**
- Produces: `agent_runtime.guards.READ_TOOLS: tuple[str, ...]`
- Produces: `agent_runtime.guards.wrote_without_reading(tool_calls: dict[str, int]) -> bool`
- Produces: `WikiSession.__init__(..., requires_existing_wiki: bool = False)`
- Produces: `WikiSession.live_page_count -> int`

- [ ] **Step 1: 게이트 헬퍼 테스트를 쓴다**

`tests/runtime/test_runtime_guards.py` 에 추가한다.

```python
from agent_runtime.guards import wrote_without_reading


def test_write_only_run_is_blind():
    """읽기 툴 0회는 현재 위키를 못 본 채 쓴 것이다."""
    assert wrote_without_reading({"guide": 1, "create": 3}) is True


def test_reading_either_tool_clears_the_gate():
    assert wrote_without_reading({"read": 1, "create": 1}) is False
    assert wrote_without_reading({"search": 1, "create": 1}) is False


def test_empty_log_is_not_a_verdict():
    """툴 호출을 세지 못한 런타임은 판단 불가다 — bypassed_server 와 같은 규칙."""
    assert wrote_without_reading({}) is False
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/runtime/test_runtime_guards.py -k wrote_without_reading -v`
Expected: FAIL — `ImportError: cannot import name 'wrote_without_reading'`

- [ ] **Step 3: 게이트 헬퍼를 만든다**

`src/agent_runtime/guards.py` 아래에 붙인다.

```python
# 현재 위키를 보는 툴. `guide` 는 지침 문서라 여기 없다 — 그것만 부른 실행은 위키를
# 본 것이 아니다. `lint` 도 없다: 검증은 쓴 뒤에 하는 것이라 읽었다는 증거가 못 된다.
READ_TOOLS = ("read", "search")


def wrote_without_reading(tool_calls: dict[str, int]) -> bool:
    """현재 위키를 한 번도 안 보고 끝난 실행인가 (설계 4.2).

    조회 API 전환이 새로 들여온 실패 방식이다. 요청이 본문을 실어 보내던 때는 라이브 층이
    이미 채워져 있어 「안 읽음」이 곧 「빈 위키」는 아니었다. 이제 안 읽으면 에이전트가
    본 것이 아무것도 없고, 그 상태로 쓴 페이지는 라이브를 덮는다.

    빈 로그는 통과가 아니라 판단 불가다 — `bypassed_server` 와 같은 규칙이다.
    """
    if not tool_calls:
        return False
    return not any(tool_calls.get(name) for name in READ_TOOLS)
```

- [ ] **Step 4: 통과를 확인한다**

Run: `uv run pytest tests/runtime/test_runtime_guards.py -v`
Expected: PASS

- [ ] **Step 5: 세션 게이트 테스트를 쓴다**

`tests/api/test_federated_session.py` 에 추가한다. 이 파일의 기존 세션 생성 헬퍼를 먼저
읽고 같은 방식으로 만든다.

```python
async def test_removal_on_empty_scope_fails_before_the_agent(tmp_path, anyio_backend):
    """삭제·교체인데 위키가 0장이면 모순이다 — 에이전트를 돌리기 전에 끊는다."""
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


async def test_addition_on_empty_scope_is_allowed(tmp_path, anyio_backend):
    """신규 범위에 첫 문서를 넣는 것은 정상이다."""
    session = WikiSession(
        scope_key="D1", job_id="job-1", request_id="r1",
        runtime=_runtime_with_arun(), requires_existing_wiki=False,
        wiki_capability="c", scope_version=47,
        backend_base_url="http://backend", internal_api_key="k",
        query_transport=_transport_with_pages([]))
    async with session:
        assert session.live_page_count == 0
```

`_runtime_with_arun` · `_transport_with_pages` 가 파일에 없으면 만든다. `_transport_with_pages`
는 `httpx.MockTransport` 로 `wiki-pages`·`wiki-spaces/*/index`·`categories`·`relations`
넷에 답하면 된다 — 앞의 셋은 이 파일에 이미 있을 가능성이 높으니 **먼저 읽고 재사용한다.**

- [ ] **Step 6: 실패를 확인한다**

Run: `uv run pytest tests/api/test_federated_session.py -k empty_scope -v`
Expected: FAIL — `TypeError: __init__() got an unexpected keyword argument 'requires_existing_wiki'`

- [ ] **Step 7: 세션에 게이트를 넣는다**

`src/wiki_api/session.py` 의 `__init__` 시그니처에 인자를 더하고(`error_code` 다음),
본문에 저장한다.

```python
                 requires_existing_wiki: bool = False,
```

```python
        # 삭제·교체는 그 문서로 만든 위키가 이미 있다는 전제다 (설계 4.1).
        self.requires_existing_wiki = requires_existing_wiki
```

`__aenter__` 의 하이드레이션 직후, `except InternalError:` 앞에 검사를 넣는다.

```python
                self._assert_the_scope_has_wikis_if_it_must()
```

그리고 메서드 두 개를 `_federated` 아래에 만든다.

```python
    @property
    def live_page_count(self) -> int:
        """하이드레이션이 받은 라이브 위키 장수. 조회 API 가 아니면 0 이다."""
        return getattr(self.fs, "page_count", 0)

    def _assert_the_scope_has_wikis_if_it_must(self) -> None:
        """삭제·교체인데 위키가 0장이면 모순이다 (설계 4.1).

        조회 API 는 오류 없이 빈 목록을 줄 수 있다. HTTP 실패·404·버전 불일치는 예외로
        갈리지만 200 에 빈 배열은 안 갈린다 — 신규 범위면 정상이고, 범위키가 틀렸거나
        권한이 어긋나면 사고인데 응답이 같다.

        지울 문서가 있다는 것은 그 문서로 만든 위키가 있었다는 뜻이다. 그래서 삭제·교체
        에서만 0장을 막는다. 추가는 0장이 정상이다 (첫 문서).

        `selectedWikis` 가 없어지면서 접수 시점 검증(I3)이 설 자리를 잃었고, 그 판단을
        여기로 옮긴 것이다. 에이전트를 돌리기 전이므로 단계는 `context_load` 다.
        """
        if self.requires_existing_wiki and self.live_page_count == 0:
            raise InternalError(
                self.error_code,
                "이 범위에 위키가 없습니다 — 삭제·교체할 원본문서로 만든 위키가 "
                "있어야 합니다. 범위 설정이나 열람 허가를 확인해 주세요.",
                FailureStage.CONTEXT_LOAD)
```

- [ ] **Step 8: 통과를 확인한다**

Run: `uv run pytest tests/api/test_federated_session.py -v`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add src/agent_runtime/guards.py src/wiki_api/session.py tests/runtime/test_runtime_guards.py tests/api/test_federated_session.py
git commit -m "feat(ai): 조회가 비었을 때와 조회하지 않았을 때를 가른다 [S15P11B106-175]"
```

---

## Task 6: `BACKEND_BASE_URL` 기동 검사

조회 API 주소가 없는 채 뜨면 첫 요청에서 빈 위키로 라이브를 덮는다. 기동 시점에 막는다
(설계 §4.3).

**Files:**
- Modify: `src/wiki_api/serve.py`
- Modify: `src/.env.example`
- Test: `tests/api/test_serve.py`

**Interfaces:**
- Produces: `serve.assert_backend_is_reachable_by_config(settings) -> None` — 값이 없으면
  `SystemExit`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/api/test_serve.py` 에 추가한다. 기존 `assert_runtime_is_usable` 테스트를 먼저
읽고 같은 방식(설정 객체를 만들어 `pytest.raises(SystemExit)`)으로 쓴다.

```python
def test_missing_backend_base_url_stops_startup():
    """조회 API 주소 없이 뜨면 첫 요청이 빈 위키로 라이브를 덮는다 — 기동을 막는다."""
    settings = ServerSettings(internal_api_key="k", backend_base_url="")
    with pytest.raises(SystemExit) as caught:
        assert_backend_is_reachable_by_config(settings)
    assert "BACKEND_BASE_URL" in str(caught.value)


def test_present_backend_base_url_passes():
    settings = ServerSettings(internal_api_key="k",
                              backend_base_url="http://backend:8080")
    assert assert_backend_is_reachable_by_config(settings) is None
```

`ServerSettings` 의 실제 생성자 인자가 다르면 파일을 읽고 맞춘다.

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_serve.py -k backend_base_url -v`
Expected: FAIL — `ImportError: cannot import name 'assert_backend_is_reachable_by_config'`

- [ ] **Step 3: 검사를 만든다**

`src/wiki_api/serve.py` 의 `assert_runtime_is_usable` 아래에 넣는다.

```python
def assert_backend_is_reachable_by_config(settings: ServerSettings) -> None:
    """조회 API 주소가 없으면 기동하지 않는다 (설계 4.3).

    S15P11B106-175 부터 요청이 위키 본문을 싣지 않는다. 주소가 없으면 하이드레이션이
    아무것도 받지 못하고, 에이전트가 빈 위키를 보고 "내용이 없다"고 판단해 라이브를
    덮는다. 설정 하나로 나는 사고이므로 기동 시점에 막는다.

    `assert_runtime_is_usable` 이 같은 이유로 있다 — 첫 요청에서 알게 되는 구성 오류는
    기동에서 알려 준다.
    """
    if (settings.backend_base_url or "").strip():
        return
    raise SystemExit(
        "BACKEND_BASE_URL 이 비어 있다 — Wiki 조회 API 주소가 없으면 위키 변환이 "
        "빈 문맥으로 돌아 라이브 Wiki 를 덮는다. 환경변수 BACKEND_BASE_URL 또는 "
        "src/.env 에 Spring 주소를 넣고 다시 띄운다 (예: http://backend:8080).")
```

`main()` 에서 `assert_runtime_is_usable(...)` 를 부르는 바로 다음 줄에 부른다.

```python
    assert_backend_is_reachable_by_config(settings)
```

- [ ] **Step 4: 통과를 확인한다**

Run: `uv run pytest tests/api/test_serve.py -v`
Expected: PASS

- [ ] **Step 5: `.env.example` 에 넣는다**

`src/.env.example` 을 읽고 같은 서식으로 한 줄 더한다.

```
# Spring Boot Wiki 조회 API 주소. **없으면 서버가 뜨지 않는다** — 위키 변환이 이 주소로
# 목차·카테고리·본문을 읽는다 (S15P11B106-175).
BACKEND_BASE_URL=http://localhost:8080
```

- [ ] **Step 6: 실제로 기동이 막히는지 본다**

Run: `cd /home/wolyong/workspace/project/AJT/S15P11B106/ai && BACKEND_BASE_URL= INTERNAL_API_KEY=test uv run python -m wiki_api.serve --port 8123`
Expected: `BACKEND_BASE_URL 이 비어 있다 — ...` 를 출력하고 즉시 종료

- [ ] **Step 7: 커밋**

```bash
git add src/wiki_api/serve.py src/.env.example tests/api/test_serve.py
git commit -m "feat(ai): 조회 API 주소가 없으면 기동을 거부한다 [S15P11B106-175]"
```

---

## Task 7: 요청 계약을 정리한다

`wikiCapability`·`scopeVersion` 을 필수로 올리고 본문 전달 필드를 지운다.

**Files:**
- Modify: `src/wiki_api/schemas.py`
- Modify: `src/wiki_api/errors.py`
- Test: `tests/api/test_schemas_v11.py`
- Test: `tests/api/test_request_size_limits.py`
- Test: `tests/api/test_errors.py`

**Interfaces:**
- Produces: `TransformRequest` — `jobId` · `documentId` · `scopeKey` · `wikiCapability` ·
  `scopeVersion` · `parsedMarkdown` · `changeType` · `removedParsedMarkdown`
- Produces: `EditRequest` — `wikiId` · `scopeKey` · `instruction` · `wikiCapability` ·
  `scopeVersion` · `chatHistory`
- 삭제: `SelectionRequest` · `SelectionResponse` · `SelectedWiki` · `WikiBody` ·
  `EvidenceDocument` · `CategoryRef`

`CategoryRef` 는 요청에서 사라지지만 **응답에는 남는지 먼저 확인한다.**
Run: `uv run grep -rn "CategoryRef" src/`
응답 모델이 쓰고 있으면 남기고, 요청 필드만 지운다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/api/test_schemas_v11.py` 에 추가한다.

```python
def test_transform_request_requires_the_capability():
    """열람 허가(wikiCapability)는 필수다 — 없으면 400 이고 fieldErrors 가 어느 필드인지 알려준다."""
    with pytest.raises(ValidationError) as caught:
        TransformRequest(jobId="1", documentId="2", scopeKey="D1",
                         parsedMarkdown="본문")
    fields = {e["loc"][-1] for e in caught.value.errors()}
    assert {"wikiCapability", "scopeVersion"} <= fields


def test_transform_request_rejects_pushed_context():
    """본문을 실어 보내는 필드는 사라졌다. Strict 라 남아 있으면 400 이다."""
    with pytest.raises(ValidationError):
        TransformRequest(jobId="1", documentId="2", scopeKey="D1",
                         wikiCapability="c", scopeVersion=47,
                         parsedMarkdown="본문", selectedWikis=[])


def test_transform_request_minimal_payload_is_valid():
    payload = TransformRequest(jobId="1", documentId="2", scopeKey="D1",
                               wikiCapability="c", scopeVersion=47,
                               parsedMarkdown="본문")
    assert payload.changeType == "document_added"


def test_edit_request_requires_the_capability():
    with pytest.raises(ValidationError) as caught:
        EditRequest(wikiId="9", scopeKey="D1", instruction="요약을 고쳐라")
    fields = {e["loc"][-1] for e in caught.value.errors()}
    assert {"wikiCapability", "scopeVersion"} <= fields


def test_edit_request_rejects_pushed_context():
    with pytest.raises(ValidationError):
        EditRequest(wikiId="9", scopeKey="D1", instruction="고쳐라",
                    wikiCapability="c", scopeVersion=47,
                    evidenceDocuments=[])
```

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_schemas_v11.py -k "capability or pushed_context or minimal_payload" -v`
Expected: FAIL — 필수 검증이 없어 `ValidationError` 가 안 난다

- [ ] **Step 3: `TransformRequest` 를 고친다**

`src/wiki_api/schemas.py:140-154` 를 교체한다.

```python
class TransformRequest(Strict):
    jobId: str
    documentId: str
    scopeKey: str
    # 계약 1.6.0 에 선택 필드로 들어왔고 S15P11B106-175 에서 필수가 됐다. 요청이 위키
    # 본문을 싣지 않으므로 이것이 없으면 에이전트가 볼 위키가 아예 없다 — 선택으로
    # 두면 빠진 요청이 빈 문맥으로 돌아 라이브를 덮는다.
    # **로그·예외·telemetry 에 남기지 않는다.**
    wikiCapability: str
    scopeVersion: int
    # removed 때는 걷어낼 원본문서가 없어질 뿐 새 원본문서 본문은 없다 — 빈 값을 허용한다.
    parsedMarkdown: str = ""
    changeType: ChangeType = "document_added"
    removedParsedMarkdown: str | None = None
```

`_the_request_must_be_actionable` 안에서 `selectedWikis` 를 보는 분기(I3)를 지운다.
그 판단은 하이드레이션으로 옮겼다 (Task 5). docstring 의 세 번째 항목도 지우고 아래
문장으로 바꾼다.

```
          * 삭제에 인용 위키가 없다 — **여기서 막지 않는다.** 요청이 위키를 싣지
            않으므로 접수 시점에 알 수 없고, 하이드레이션이 카탈로그를 받은 뒤
            판정한다 (`session._assert_the_scope_has_wikis_if_it_must`,
            S15P11B106-175)
```

- [ ] **Step 4: `EditRequest` 를 고친다**

`src/wiki_api/schemas.py:335-347` 을 교체한다.

```python
class EditRequest(Strict):
    wikiId: str
    scopeKey: str
    instruction: str
    # 변환과 같다 — S15P11B106-175 에서 필수가 됐다.
    wikiCapability: str
    scopeVersion: int
    chatHistory: list[ChatMessage] = Field(default_factory=list)
```

`_the_request_must_fit` 에서 `currentWiki`·`evidenceDocuments` 크기를 재던 부분을 지운다.
남는 것은 `instruction` 상한뿐이다 — 원본을 읽고 그 검사만 남긴다. 검사가 하나도 안
남으면 검증자 자체를 지운다.

- [ ] **Step 5: 죽은 스키마를 지운다**

`SelectionRequest` · `SelectionResponse` · `SelectedWiki` · `WikiBody` ·
`EvidenceDocument` 클래스를 통째로 지운다. `SelectionResponse` 뒤의
「`ReconcileRequest` 는 여기 없다」 주석은 남긴다 — 그 사실은 계속 유효하다.

Run: `uv run grep -rn "SelectedWiki\|WikiBody\|EvidenceDocument\|SelectionRequest\|SelectionResponse" src/ tests/`
남는 참조는 Task 8 에서 지운다. 이 단계에서는 `schemas.py` 만 정리한다.

- [ ] **Step 6: `errors.py` 에서 1단계 경로를 지운다**

`_VALIDATION_CODES` 의 `"/internal/v1/wiki-context-selections"` 항목과
`_FAILURE_CODES` 의 같은 키를 지운다. 각 자리에 한 줄 남긴다.

```python
    # `/internal/v1/wiki-context-selections` 는 없다 — S15P11B106-175 가 위키 변환을
    # 단일 호출로 합치면서 1단계 문맥 선택을 지웠다. 남겨두면 Spring 이 계속 부른다.
```

- [ ] **Step 7: 통과를 확인한다**

Run: `uv run pytest tests/api/test_schemas_v11.py -v`
Expected: PASS

이 시점에 `tests/api/test_api_wiki.py`·`test_api_edits.py`·`test_selection.py` 등이
깨진다. **정상이다** — Task 8 에서 함께 고친다.

- [ ] **Step 8: 커밋**

```bash
git add src/wiki_api/schemas.py src/wiki_api/errors.py tests/api/test_schemas_v11.py
git commit -m "feat(ai): 변환·수정 요청에서 위키 본문을 걷어내고 열람 허가를 필수로 올린다 [S15P11B106-175]"
```

---

## Task 8: 1단계와 push 경로를 지운다

라우터·세션·저장 계층에서 요청이 위키를 실어 보내던 길을 통째로 걷어낸다. **서로
얽혀 있어 나눌 수 없다** — 하나만 지우면 다른 쪽이 없는 이름을 부른다.

**Files:**
- Delete: `src/wiki_api/selection.py`
- Delete: `tests/api/test_selection.py`
- Modify: `src/wiki_api/routers/wiki.py`
- Modify: `src/wiki_api/session.py`
- Modify: `src/wiki_mcp/vaultfs/spring.py`
- Modify: `src/agent_runtime/base.py`
- Test: `tests/api/test_api_wiki.py` · `tests/api/test_api_edits.py` ·
  `tests/api/test_errors.py` · `tests/mcp/test_spring_vaultfs.py`

**Interfaces:**
- Consumes: `FederatedVaultFS.categories` · `page_count` · `_Catalog.document_ids_by_address`
  (Task 2), `guards.wrote_without_reading` (Task 5), `WikiSession.requires_existing_wiki`
  (Task 5)
- Produces: `WikiSession.__init__` 에서 `pages`·`index_markdown` 인자가 사라진다
- Produces: `SpringVaultFS.open(root, scope_key, job_id) -> str` — 하이드레이션 인자 없음
- Produces: `WikiSession.evidence_document_ids -> list[str]` — 대상 위키가 근거로 쓴
  documentId 들 (`wiki-edits` 용)

- [ ] **Step 1: 라우터 테스트를 새 계약으로 고친다**

`tests/api/test_api_wiki.py` 를 읽고, 요청 픽스처에서 `currentIndex`·`currentCategories`·
`selectedWikis` 를 빼고 `wikiCapability`·`scopeVersion` 을 넣는다. 조회 API 는
`app.state` 에 주입하는 `httpx.MockTransport` 로 흉내낸다 — `tests/api/test_federated_session.py`
가 쓰는 방식을 그대로 가져온다.

추가로 이 두 개를 쓴다.

```python
async def test_selection_endpoint_is_gone(client):
    """1단계는 남겨두지 않는다 — 404 여야 한다."""
    response = await client.post("/internal/v1/wiki-context-selections",
                                 json={}, headers=AUTH)
    assert response.status_code == 404


async def test_blind_write_run_is_rejected(client, gateway_with_pages):
    """읽기 툴을 한 번도 안 부르고 쓰기만 한 실행은 실패다 (설계 4.2)."""
    response = await client.post("/internal/v1/wiki-transformations",
                                 json=TRANSFORM_PAYLOAD, headers=AUTH)
    assert response.status_code == 500
    assert response.json()["code"] == "WIKI_TRANSFORMATION_FAILED"
    assert "현재 Wiki 를 읽지 않았습니다" in response.json()["message"]
```

`gateway_with_pages` 는 위키 1장 이상을 돌려주는 픽스처다. 런타임 스텁이
`tool_calls={"create": 1}` 만 돌려주게 만든다.

- [ ] **Step 2: 실패를 확인한다**

Run: `uv run pytest tests/api/test_api_wiki.py -v`
Expected: FAIL — 1단계가 아직 등록돼 있어 404 가 아니고, 읽기 게이트가 없다

- [ ] **Step 3: 1단계 엔드포인트와 파일을 지운다**

```bash
git rm src/wiki_api/selection.py tests/api/test_selection.py
```

`src/wiki_api/routers/wiki.py` 에서:
- `from ..selection import select_wikis` 임포트 삭제
- `SelectionRequest`·`SelectionResponse` 임포트 삭제
- `@router.post("/wiki-context-selections", ...)` 핸들러 전체 삭제
- 모듈 docstring 을 아래로 교체

```python
"""위키 계열 엔드포인트 2개 — 변환 · 채팅 수정.

**요청은 위키를 실어 오지 않는다** (S15P11B106-175). 목차·카테고리·본문·근거 문서는
전부 조회 API 에서 읽고, 요청에 남는 것은 작업 번호와 이번 문서의 파싱본뿐이다.
1단계 문맥 선택(`wiki-context-selections`)은 사라졌다 — 무엇을 볼지는 에이전트가
도구로 정한다.

v1.1.0 에서 `wiki-reconciliations` 가 사라지고 `wiki-transformations` 의 `changeType`
분기로 들어왔다 (설계 §1). 걷어내기 로직 자체는 옮겨온 그대로다 — 사라진 문서를 라이브
층에 얹어 참조 그래프를 붙이고, 각주 backlink 로 고칠 곳을 지시문에 적어 준다. 그
backlink 는 하이드레이션이 받아둔 범위 관계를 뒤집어 찾는다
(`FederatedVaultFS.get_citation_backlinks`).
"""
```

`agent_runtime/base.py` 에서 `selection_instruction` 함수를 통째로 지운다.
`from agent_runtime.base import ... selection_instruction` 을 부르는 곳이 남았는지 확인한다.

Run: `uv run grep -rn "selection_instruction\|select_wikis" src/ tests/ experiments/`

- [ ] **Step 4: 라우터를 조회 API 전용으로 다시 배선한다**

`_federation`·`_hydration_pages`·`_edit_pages` 를 지우고, `_category_map` 을 조회 API
카테고리를 받는 형태로 바꾼다.

```python
def _category_map(categories: list[dict]) -> dict[str, str]:
    """이름 → `wikiCategoryId`. 이미 있는 카테고리를 다시 만들지 않게 하는 입력이다
    (FR-WIKI-014, DR-019 — 카테고리는 에이전트가 관리하고 관리자는 조회만 한다).

    입력이 요청 본문에서 조회 API 응답으로 바뀌었다 (S15P11B106-175). 하이드레이션이
    `GET /wiki-spaces/{scopeKey}/categories` 를 이미 부르므로 새로 조회할 것은 없다.
    """
    return {c.get("name"): c.get("wikiCategoryId") for c in categories
            if c.get("name") and c.get("wikiCategoryId")}


def _session_args(app: FastAPI, payload) -> dict:
    """조회 API 세션 인자. 요청에서 오는 것은 허가값 둘뿐이다.

    **`wikiCapability` 를 로그에 찍지 않는다.** 이 함수가 하는 일은 전달뿐이다.
    """
    return {
        "wiki_capability": payload.wikiCapability,
        "scope_version": payload.scopeVersion,
        "backend_base_url": getattr(app.state, "backend_base_url", "") or None,
        "internal_api_key": getattr(app.state, "api_key", "") or None,
    }
```

`transform` 핸들러의 `WikiSession(...)` 호출을 바꾼다.

```python
        async with WikiSession(
            scope_key=payload.scopeKey, job_id=payload.jobId, request_id=rid,
            runtime=app.state.runtime,
            error_code="WIKI_TRANSFORMATION_FAILED",
            requires_existing_wiki=payload.changeType != "document_added",
            **_session_args(app, payload),
        ) as session:
```

`_assemble` 호출의 `current_categories` 를 조회 API 에서 받는다.

```python
            response = await _assemble(
                session, summary=result.text.strip() or "변경이 없습니다.",
                current_categories=_category_map(session.fs.categories))
```

`session.run_agent(...)` 다음, `assert_lint_clean()` 앞에 읽기 게이트를 넣는다.

```python
            session.assert_the_agent_looked_at_the_wiki(result)
```

`edit` 핸들러도 같은 모양으로 바꾼다. `evidenceDocuments` 를 도는 루프를 지우고, 대상
위키가 근거로 쓴 문서를 조회 API 에서 읽어 올린다.

```python
        async with WikiSession(
            scope_key=payload.scopeKey, job_id=None, request_id=rid,
            runtime=app.state.runtime, error_code="WIKI_EDIT_FAILED",
            requires_existing_wiki=True,
            **_session_args(app, payload),
        ) as session:
            address = await session.address_for_wiki_id(payload.wikiId)
            # 근거 문서를 조회 API 에서 읽어 라이브 층에 올린다. lint 가 각주 인용문을 이
            # 본문과 문자열 대조한다 (NFR-AI-002) — 없으면 정상 각주가 error 로 뜬다.
            evidence_length = await session.stage_evidence_documents(payload.wikiId)
            limit = time_limit_seconds(
                len((await session.fs.get(session.scope_id, address) or {})
                    .get("content") or "") + evidence_length)
            result = await session.run_agent(
                edit_instruction(address, payload.scopeKey, payload.instruction,
                                 [m.model_dump() for m in payload.chatHistory]),
                limit)
            session.assert_the_agent_looked_at_the_wiki(result)
            await session.assert_lint_clean()
            base = await _assemble(
                session, summary=result.text.strip() or "변경이 없습니다.",
                current_categories=_category_map(session.fs.categories))
            return EditResponse(agentMessage=result.text.strip(), **base.model_dump())
```

`assert_within_ceiling` 호출은 그대로 둔다 — `parsedMarkdown` 은 여전히 요청에 있다.
`wiki-edits` 쪽 `assert_within_ceiling` 이 `currentWiki` 를 쓰고 있었으면 지운다.

- [ ] **Step 5: 세션에서 push 경로를 걷어낸다**

`src/wiki_api/session.py`:

- `__init__` 에서 `pages`·`index_markdown` 인자와 `self.pages`·`self.index_markdown` 삭제
- `_federated()` 메서드 삭제
- `__aenter__` 의 `if self._federated(): ... else: ...` 분기에서 else 가지를 삭제하고
  조회 API 경로만 남긴다
- `_assert_runtime_can_use_the_gateway` 에서 `self._federated() and` 를 뺀다 —
  이제 항상 조회 API 다
- `_teardown` 의 `else: await LocalVaultFS.close()` 는 남긴다 (조회 API 클라이언트가 만들어
  지기 전에 실패한 경우가 있다)
- 모듈 docstring 을 고친다

```python
"""요청 1건의 생애.

임시 루트를 열고, **조회 API 에서** 라이브 층을 채우고, 에이전트를 돌리고, 폐기한다.
`/data/ajt` 를 만지지 않는다 — 반영은 Spring 이 `work/{jobId}` 에서 수행한다
(DR-007·008).

S15P11B106-175 에서 push 경로가 사라졌다. v1.1.0~1.8.0 사이에는 요청이 실어 온
`selectedWikis`·`currentIndex` 로 라이브 층을 채웠는데, 그 경로는 백엔드가 고른 몇 장만
보이는 부분 가시성이었고 이제 `FederatedVaultFS` 가 범위 전체를 조회 API 에서 읽는다.

`lint` 를 여기서 다시 부르는 이유: 에이전트가 부르고 통과했다고 말해도 그 말을 믿지
않는다. 반영 전 기계 검증이 「승인 게이트 없음」을 성립시키는 유일한 장치다.
"""
```

메서드 두 개를 더한다.

```python
    def assert_the_agent_looked_at_the_wiki(self, result) -> None:
        """읽기 툴을 한 번도 안 부른 실행을 막는다 (설계 4.2).

        위키가 0장인 범위는 읽을 것이 없는 것이 정상이므로 건너뛴다 — 신규 범위에
        첫 문서를 넣는 경우다.
        """
        if not self.live_page_count:
            return
        if wrote_without_reading(getattr(result, "tool_calls", {}) or {}):
            raise InternalError(
                self.error_code,
                "현재 Wiki 를 읽지 않았습니다 — 에이전트가 조회 도구를 한 번도 "
                "부르지 않아 기존 Wiki 를 덮어쓸 수 있습니다.",
                FailureStage.AGENT_ERROR)

    async def stage_evidence_documents(self, wiki_id: str) -> int:
        """대상 위키가 근거로 쓴 원본문서를 조회 API 에서 읽어 라이브 층에 올린다.

        `wiki-edits` 가 `evidenceDocuments` 를 실어 보내던 것을 대신한다
        (S15P11B106-175). 문서 목록은 하이드레이션이 받아둔 범위 관계에 있으므로
        추가 조회는 문서 본문뿐이다.

        올린 본문의 총 길이를 돌려준다 — 호출자가 시간 상한을 계산한다.
        """
        catalog = getattr(self.fs, "_catalog", None)
        address = await self.address_for_wiki_id(wiki_id)
        document_ids = (catalog.document_ids_by_address.get(address, [])
                        if catalog else [])
        total = 0
        for document_id in document_ids:
            body = await self._query_client.parsed_document(document_id)
            text = body.get("parsedMarkdown") or ""
            await self.stage_source(document_id, text,
                                    body.get("originalFileName"))
            total += len(text)
        return total
```

임포트에 `from agent_runtime.guards import wrote_without_reading` 를 더한다.

- [ ] **Step 6: `SpringVaultFS.open` 에서 하이드레이션 인자를 뺀다**

`src/wiki_mcp/vaultfs/spring.py`:

```python
    @staticmethod
    async def open(root: str | Path, scope_key: str, job_id: str | None) -> str:
        """임시 색인을 열고 scope_id 를 돌려준다. 라이브 층은 채우지 않는다.

        S15P11B106-175 이전에는 `pages`·`index_markdown` 을 받아 요청이 실어 온 위키로
        라이브 층을 채웠다. 그 경로가 사라졌으므로 여기는 색인만 연다 — 조회 API 하이드레이션은
        `FederatedVaultFS.open` 이 한다.
        """
        return await LocalVaultFS.open(root, scope_key, job_id)
```

`_hydrate_from_pages` 를 지운다. `address_from_wiki_path` 는 `FederatedVaultFS` 가 쓰므로
남긴다 — grep 으로 확인한다.

Run: `uv run grep -rn "_hydrate_from_pages\|address_from_wiki_path" src/ tests/`

`FederatedVaultFS.open` 이 `LocalVaultFS.open` 을 직접 부르고 있으므로 영향 없다.

- [ ] **Step 7: 남은 참조를 전부 없앤다**

Run: `uv run grep -rn "selectedWikis\|currentIndex\|currentCategories\|currentWiki\|evidenceDocuments\|SelectedWiki\|WikiBody\|EvidenceDocument\|SelectionRequest\|SelectionResponse\|wiki-context-selections\|_hydrate_from_pages" src/ tests/`

나오는 곳을 하나씩 고친다. `experiments/` 도 본다 — `backend_sim.py` 가 1단계를 부르고
있으면 그 단계를 지우고 변환만 부르게 고친다.

Run: `uv run grep -rn "wiki-context-selections\|selection" experiments/*.py`

- [ ] **Step 8: 전체 테스트를 통과시킨다**

Run: `uv run pytest -m "not ocr" -q`
Expected: 0 failed

깨지는 테스트를 **지우지 말고 새 계약으로 고친다.** 지워도 되는 것은 1단계 전용
(`test_selection.py`)뿐이고 그건 이미 지웠다.

- [ ] **Step 9: 라우터가 실제로 3개가 아니라 2개인지 본다**

Run: `uv run python -c "
from wiki_api.serve import build_app, ServerSettings
app = build_app(ServerSettings(internal_api_key='k', backend_base_url='http://x'))
print(sorted(r.path for r in app.routes if r.path.startswith('/internal')))
"`
Expected: `['/internal/v1/answers', '/internal/v1/source-parses', '/internal/v1/wiki-edits', '/internal/v1/wiki-transformations']`

`ServerSettings` 생성자 인자가 다르면 `serve.py` 를 읽고 맞춘다.

- [ ] **Step 10: 커밋**

```bash
git add -u src/wiki_api src/wiki_mcp src/agent_runtime tests experiments
git status --short
git commit -m "feat(ai): 1단계 문맥 선택과 push 하이드레이션 경로를 지운다 [S15P11B106-175]"
```

`git add -u` 는 **이미 추적 중인 파일의 수정·삭제만** 스테이지한다. `git status --short`
로 담당 범위 밖(`backend/`·`frontend/`·`docs/`)이 섞이지 않았는지 반드시 확인하고 커밋한다.
섞였으면 `git restore --staged <경로>` 로 뺀다.

---

## Task 9: 가짜 모델로 배관 회귀를 고정한다

실모델 없이 「조회 API 를 몇 번 부르는지·어떤 순서로 도는지」를 테스트에 박는다. 이것이
회귀를 계속 지키는 장치다 (설계 §7).

**Files:**
- Test: `tests/api/test_wiki_agent_fake_model.py` (신규)
- 참고: `tests/api/test_answer_loop_fake_model.py` · `tests/fake_models.py`

**Interfaces:**
- Consumes: 앞의 모든 태스크

- [ ] **Step 1: 기존 가짜 모델 테스트를 읽는다**

Run: `uv run cat tests/api/test_answer_loop_fake_model.py` 와 `uv run cat tests/fake_models.py`

챗봇 쪽이 `GenericFakeChatModel` 로 도구 호출 시퀀스를 대본화한 방식을 그대로 가져온다.
**새 방식을 발명하지 않는다.**

- [ ] **Step 2: 테스트를 쓴다**

```python
"""위키 변환 에이전트 루프를 가짜 모델로 고정한다 (S15P11B106-175, 설계 §7).

실모델 측정은 한 번 찍는 숫자고, 회귀를 계속 지키는 것은 여기다. 세 가지를 박는다 —
하이드레이션이 조회 API 를 몇 번 부르는지, 삭제 재조정이 인용 위키만 당기는지, 읽기 없이
쓴 실행이 막히는지.
"""


async def test_hydration_calls_the_gateway_four_times(gateway_calls):
    """카탈로그·목차·카테고리·범위 관계. 위키 장수와 무관하게 4회다."""
    ...
    assert gateway_calls == [
        "/internal/v1/wiki-spaces/D1/categories",
        "/internal/v1/wiki-pages",
        "/internal/v1/wiki-spaces/D1/relations",
        "/internal/v1/wiki-spaces/D1/index",
    ]


async def test_removal_pulls_only_citing_bodies(gateway_calls):
    """위키 3장 중 1장만 인용했으면 본문 조회는 1회다 (설계 §3.1)."""
    ...
    content_calls = [c for c in gateway_calls if c.endswith("/content")]
    assert content_calls == ["/internal/v1/wikis/101/content"]


async def test_merge_does_not_call_per_page_relations(gateway_calls):
    """역링크는 카탈로그에서 읽는다 — 페이지별 relations 는 0회다 (Task 3)."""
    ...
    assert not [c for c in gateway_calls
                if c.startswith("/internal/v1/wikis/") and c.endswith("/relations")]
```

`...` 자리에는 앞 파일에서 본 방식대로 앱을 띄우고 `MockTransport` 로 호출 경로를
모으는 코드를 넣는다. 순서 단언이 하이드레이션 구현 순서와 어긋나면 **테스트가 아니라
단언 순서를 실제 순서에 맞춘다** — 순서 자체는 계약이 아니다. 다만 **횟수는 계약이다.**

- [ ] **Step 3: 통과를 확인한다**

Run: `uv run pytest tests/api/test_wiki_agent_fake_model.py -v`
Expected: PASS

- [ ] **Step 4: 전체 테스트**

Run: `uv run pytest -m "not ocr" -q`
Expected: 0 failed

- [ ] **Step 5: 커밋**

```bash
git add tests/api/test_wiki_agent_fake_model.py
git commit -m "test(ai): 조회 API 호출 횟수를 가짜 모델로 고정한다 [S15P11B106-175]"
```

---

## Task 10: 실모델 1~2건 측정과 기록

「저하 없음」을 숫자로 남긴다. 예산이 있으니 **문서 2건을 넘기지 않는다.**

**Files:**
- Modify: `experiments/INDEX.md`
- 생성: `experiments/2026-08-01-*/` (하네스가 만든다)

- [ ] **Step 1: 기준선을 확인한다**

`experiments/INDEX.md` 에서 LocalVaultFS 로 잰 값을 찾는다. 문서당 도구 호출 수·소요
시간·출력 토큰을 적어 둔다. 어느 실험 슬러그를 기준으로 삼았는지 기록한다.

- [ ] **Step 2: 가짜 조회 API 를 띄운다**

```bash
uv run python -m experiments.query_gateway --corpus <기준선 코퍼스> --scope ALL --port 8901
```

플래그 이름이 다르면 `--help` 로 확인한다.

- [ ] **Step 3: 조회 API 경로로 측정한다**

```bash
BACKEND_BASE_URL=http://localhost:8901 \
uv run python -m experiments.backend_sim <문서1> <문서2> \
  --runtime deepagents --transport in-process --scope ALL \
  --purpose "S15P11B106-175 조회 API 단일 호출 — LocalVaultFS 기준선 대조"
```

`--model` 은 기준선과 **같은 모델**을 준다. 별칭(`opus`)을 쓰지 않고 정확한 이름을 쓴다 —
다르면 두 측정의 비교가 조용히 깨진다.

- [ ] **Step 4: 조회 예산 소진을 본다**

`report.json` 에서 조회 API 호출 수를 찾아 `105 + 2 × 위키장수` 와 비교한다. 70% 를 넘으면
`QUERY_CALL_BUDGET_BASE` 를 올리는 것을 별도 커밋으로 제안하고, 근거 수치를 그 주석에
적는다.

- [ ] **Step 5: `INDEX.md` 에 적는다**

기존 항목 서식을 따른다. 최소한 이것들을 적는다.

- 기준선 실험 슬러그와 그 값
- 이번 값 (도구 호출 수 · 조회 API 호출 수 · 소요 시간 · 출력 토큰)
- 문서 수 (1~2건이라 분포가 아니라는 사실)
- 실기동(Spring)은 S15P11B106-172 대기 중이라 아직 안 쟀다는 것

- [ ] **Step 6: 커밋**

```bash
git add experiments/INDEX.md experiments/2026-08-01-*
git commit -m "docs(ai): 조회 API 단일 호출을 기준선과 대조한 수치를 남긴다 [S15P11B106-175]"
```

---

## 마무리

- [ ] `uv run pytest -m "not ocr"` — 0 failed
- [ ] `git status --short` — 담당 범위 밖(`backend/`·`frontend/`·`docs/`·루트) 변경 없음
- [ ] `.env` 가 커밋되지 않았는지 확인 (`git log --stat` 에 `src/.env` 없음)
- [ ] MR 본문에 협의 항목을 적는다
  - 조회 API `wikiRefs` 를 `reference_type: links_to` 로 표시한다 (계약에 인용/링크 구분 없음)
  - `wiki-edits` 도 함께 조회 API 로 전환했다 (티켓은 범위 밖으로 적었으나 설계 §2.1 의 이유로 포함)
  - 계약 재생성·버전 번호는 S15P11B106-174 와 같은 시점에 맞춘다
  - 실기동 검증은 S15P11B106-172 대기

---

## Self-Review 결과

**Spec coverage**

| 설계 절 | 태스크 |
| --- | --- |
| §1.1 툴 동등성 | 1·2·3·4 (툴 반환 모양 불변) |
| §2 계약 | 7·8 |
| §2.1 `wiki-edits` 포함 | 7·8 |
| §3 범위 관계 1회 | 1·2 |
| §3 역링크 | 3 |
| §3 근거 문서 | 2(정방향 표)·8(`stage_evidence_documents`) |
| §3.1 삭제·교체 인용 위키 | 4 |
| §4.1 카탈로그 0장 | 5 |
| §4.2 읽기 툴 게이트 | 5·8 |
| §4.3 기동 검사 | 6 |
| §4.4 각주 대조 | 4 (Step 3 docstring · Step 1 세 번째 테스트) |
| §5 삭제 목록 | 7·8 |
| §6 오류 이름 불변 | 7 (Step 6) |
| §7 검증 | 9·10 |
| §7.2 예산 | 10 Step 4 |
| §8 배포 순서 | 마무리 (MR 협의 항목) |

**Type consistency** — `scope_relations()` 는 Task 1 이 정의하고 Task 2 만 부른다.
`wiki_backlinks`·`wikis_by_document`·`document_ids_by_address` 는 Task 2 가 정의하고
3·4·8 이 읽는다. `wrote_without_reading` 는 Task 5 가 정의하고 8 이 부른다.
`page_count` 는 Task 2 가 정의하고 5·8 이 읽는다. `requires_existing_wiki` 는 Task 5 가
받고 8 이 넘긴다. 이름 불일치 없음.

**알려진 남은 위험** — Task 8 은 파일 6개를 동시에 건드리는 큰 태스크다. 쪼갤 수 없는
이유는 서로가 서로의 이름을 부르기 때문이다. 중간에 테스트가 깨진 상태로 지나가는
구간이 있고, Step 8 이 그 구간의 끝이다.
