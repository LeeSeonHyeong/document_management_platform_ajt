"""라이브 층을 Spring Wiki 조회 API 에서 당겨오는 VaultFS — FR-WIKI-002 의 목표 형태.

`spring.py`(push)와 나란한 세 번째 구현체다. 포트(`base.py`)와 툴 10개는 이 파일을
모른다. 바뀌는 것은 라이브 층의 출처뿐이다.

**카탈로그와 본문을 가른다** (설계 §3.3). `open()` 은 목록·목차만 채운다. 본문은
`get()` 이 처음 요구할 때 당긴다. push 경로가 본문을 한 번에 받아야 했던 이유는
검색이 AI 안의 청크 FTS 로 돌기 때문이었는데, 여기서는 검색이 조회 API 로 나가므로 그
제약이 없다.

**검색은 두 곳에서 온다.** 조회 API 는 반영이 완료된 라이브만 색인한다(DR-029). 작업 중에
에이전트가 쓴 페이지는 백엔드가 모르므로 내부 색인에서 찾아 합친다. 섞지 않고
`origin` 으로 구분해 돌려준다 — 에이전트가 "내가 방금 쓴 것"과 "원래 있던 것"을
구분하지 못하면 남의 페이지를 자기 초안으로 착각한다 (설계 §7.1).
"""

from __future__ import annotations

from pathlib import Path

from .base import SOURCES_PREFIX, VaultError
from .local import PAGES_PREFIX, LocalVaultFS
from .query_client import QueryNotFound, ScopeChangedError, WikiQueryClient
from .spring import SpringVaultFS, address_from_wiki_path

# 작업층 검색 결과 상한. 설계 §7.1 은 "work 결과는 자르지 않는다" 였는데 D8(출력 토큰
# 과다)과 충돌해 문서 자신이 "상한을 둔다" 로 결론했다.
#
# **이 값은 MCP 툴 경로에서 걸릴 수 없다.** `tools/search.py:246` 이 `min(limit,
# MAX_SEARCH)` 로 먼저 자르고 `tools/helpers.MAX_SEARCH = 20` 이므로 어댑터에 도달하는
# `limit` 은 항상 20 이하다 — `min(limit, 20) == limit` 이다. 즉 D8 을 막는 것은 이 상수가
# 아니라 `MAX_SEARCH` 와 툴의 240자 렌더링(`tools/search.py` 의 `_CONTEXT_CHARS`)이다.
# 여기 남기는 이유는 하나다: **어댑터를 툴 밖에서 직접 부르는 호출자에 대한 2차 방어선.**
# 포트 표면(`base.py`)의 `search_chunks` 는 누구나 임의의 `limit` 으로 부를 수 있다.
#
# 20 으로 두는 근거 (`experiments/measure_federated.py`, INDEX.md 「조회 API 어댑터 검색 응답」):
# 위키 100장 + 작업층 초안 12장에서 작업층 검색 응답이 무제한으로 최대 7행 · 25,674자,
# 초안 40장(12문서 작업의 3배 규모)에서 최대 13행 · 73,116자였다. 행당 3~5천자다.
# **측정이 보여준 것은 "자를 필요가 없었다"(13행 ≤ 20)이지 "잘라서 막았다"가 아니다.**
# 실측 최대의 1.5배이자 `MAX_SEARCH` 와 같은 값 — 응답 전체 상한보다 많은 작업층 행을
# 끌어올 이유가 없다.
MAX_WORK_SEARCH_ROWS = 20


class _Catalog:
    """하이드레이션이 채우는 스코프 단위 상태."""

    def __init__(self) -> None:
        # wikiId ↔ address. 양방향을 다 들고 있는다 — 검색은 wikiId 로 오고 본문
        # 적재는 주소로 온다. 한쪽만 두면 호출마다 선형 스캔이나 dict 재구성이 붙는다.
        self.wiki_id_by_address: dict[str, str] = {}
        self.address_by_wiki_id: dict[str, str] = {}
        # 하이드레이션이 받은 카테고리. 본문 지연 적재가 이걸 다시 넘겨야 한다 —
        # 안 넘기면 프론트매터에 category 가 없는 페이지에서 NULL 로 덮인다.
        self.category_by_address: dict[str, str | None] = {}
        # 페이지별 한 줄 요약. 조회 API 가 목록 응답에 이미 실어 주는데
        # (`InternalWikiQueryService.WikiPage.summary`) 예전에는 버렸다. 그러면 `search`
        # 의 목록이 주소와 제목뿐이라, 에이전트가 「출장 식비가 어느 페이지에 있나」를
        # 제목만 보고 추측해야 한다 — 실측(2026-08-05, job 33)에서 그 추측을 `search` 35회로
        # 했고 대부분 0건이었다. 본문을 당기지 않고 답할 수 있는 유일한 필드다.
        #
        # 행(`documents.metadata`)이 아니라 카탈로그에 두는 이유: 페이지 행의 metadata 는
        # 본문 frontmatter 가 정본이고(`spring.py::_insert_live`), 본문 지연 적재가 그것을
        # 덮어쓴다. 그때 요약이 조용히 사라진다.
        self.summary_by_address: dict[str, str | None] = {}
        # 본문을 당긴 주소. 조회 절약이 아니라 **재귀 차단**이 본래 역할이다 —
        # 이유는 `_ensure_body` 의 가드 주석에 있다. 걷어내지 않는다.
        self.hydrated_bodies: set[str] = set()
        self.categories: list[dict] = []
        # "하이드레이션이 없었다"와 "하이드레이션했는데 페이지가 0건이다"는 다르다.
        # 앞의 것은 오류(fail-closed), 뒤의 것은 정상(첫 작업의 빈 위키)이다.
        self.hydrated = False

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


# **인스턴스가 아니라 프로세스가 상태를 가진다.** `open()` 은 `scope_id` 만 돌려주고
# (`LocalVaultFS.open`·`SpringVaultFS.open` 과 같은 계약) 호출자는 필요할 때 어댑터를
# 새로 만든다 — `local.py` 의 `_db`·`_root` 가 모듈 전역인 것과 같은 이유다
# (프로세스 1개 = 스코프 1개 = 작업 1개). 카탈로그를 인스턴스 속성으로 두면
# 하이드레이션한 인스턴스가 사라진 뒤 만든 어댑터가 빈 표를 들고 돌아
# 본문 지연 적재와 라이브 검색 주소 복원이 조용히 죽는다.
_CATALOGS: dict[str, _Catalog] = {}


# `SpringVaultFS` 를 상속하는 이유: 라이브 층을 밖에서 채워 넣는 두 단계 SQLite 와
# `_insert_live`·`_sync_page_references` 가 거기 있다 (`spring.py:196`·`:106`).
# push 와 pull 은 라이브 층의 출처만 다르고 저장 방식이 같다. 다시 쓰면 두 벌이 된다.
class FederatedVaultFS(SpringVaultFS):
    """한 요청·한 스코프. 임시 루트를 쓰고 요청이 끝나면 버린다."""

    def __init__(self, scope_key: str, job_id: str | None,
                 client: WikiQueryClient):
        super().__init__(scope_key, job_id)
        self._client = client

    # 카탈로그는 스코프 단위다 — 아래 세 속성은 모듈 전역을 들여다보는 창일 뿐이다.
    @property
    def _catalog(self) -> _Catalog:
        return _CATALOGS.setdefault(self.scope_key, _Catalog())

    @property
    def _wiki_id_by_address(self) -> dict[str, str]:
        return self._catalog.wiki_id_by_address

    @property
    def _hydrated_bodies(self) -> set[str]:
        return self._catalog.hydrated_bodies

    @property
    def categories(self) -> list[dict]:
        """하이드레이션이 채운다. 카테고리 툴이 읽는다."""
        return self._catalog.categories

    @property
    def page_count(self) -> int:
        """하이드레이션이 받은 라이브 위키 장수.

        `list_documents` 를 세지 않는다 — 그쪽은 작업 층에 새로 쓴 페이지와 원본문서를
        같이 세므로, 「원래 위키가 몇 장이었나」를 묻는 게이트(설계 §4.1)가 실행 중에
        답이 바뀐다.
        """
        return len(self._catalog.wiki_id_by_address)

    @classmethod
    async def open(cls, root: Path | str, scope_key: str, job_id: str | None, *,
                   client: WikiQueryClient) -> str:
        """임시 색인을 열고 카탈로그만 채운다. 본문은 당기지 않는다."""
        scope_id = await LocalVaultFS.open(root, scope_key, job_id)
        # 같은 프로세스가 같은 스코프를 다시 열면(테스트, 재시도) 앞 요청의 표를
        # 물려받지 않는다 — 주소가 그대로여도 wikiId·본문 적재 여부는 그때 것이다.
        _CATALOGS[scope_key] = _Catalog()
        fs = cls(scope_key, job_id, client)
        await fs._hydrate_catalog(scope_id)
        return scope_id

    @staticmethod
    async def close() -> None:
        """색인을 닫고 카탈로그 레지스트리도 비운다.

        `LocalVaultFS.close` 는 `_db` 만 놓는다. 레지스트리를 남기면 여러 스코프를
        처리하는 장수 프로세스에서 항목이 무한 누적되고, 닫힌 뒤 `open()` 없이 만든
        어댑터가 앞 스코프의 표를 들고 돌 수 있다.
        """
        _CATALOGS.clear()
        await LocalVaultFS.close()

    async def _hydrate_catalog(self, scope_id: str) -> None:
        # 카테고리 맵. FR-WIKI-014 의 생성·병합·삭제 판단 근거다 (설계 9.6). 목록의
        # categoryName 만으로는 "빈 카테고리" 와 "사용량" 을 알 수 없다.
        self._catalog.categories = await self._client.categories()
        pages = await self._client.list_pages()
        # 조회 예산을 카탈로그 크기에 연동한다. `read` 한 번이 링크 연결 성분 전체를
        # 당기므로(`_ensure_body` 의 fan-out) 소비량이 위키 장수에 비례한다 — 고정 예산은
        # 위키가 커지는 것만으로 정상 작업을 죽인다. 근거는 `query_client` 의
        # `QUERY_CALL_BUDGET_BASE` 주석.
        self._client.note_catalog_size(len(pages))
        for page in pages:
            wiki_path = page.get("wikiPath")
            wiki_id = page.get("wikiId")
            # wikiPath 가 있으면 그 이름을 쓴다 — 본문의 위키 링크가 pageKey 기준이라
            # 이름이 달라지면 링크가 아무것도 가리키지 못한다 (설계 4절).
            address = (address_from_wiki_path(wiki_path, self.scope_key)
                       if wiki_path else f"{PAGES_PREFIX}{wiki_id}.md")
            catalog = self._catalog
            catalog.wiki_id_by_address[address] = wiki_id
            catalog.address_by_wiki_id[wiki_id] = address
            catalog.category_by_address[address] = page.get("categoryName")
            catalog.summary_by_address[address] = page.get("summary")
            # 본문은 빈 문자열이다. 메타데이터만 있어도 browse·목록·주소 해석이 된다.
            await self._insert_live(
                scope_id, address, "", wiki_id=wiki_id,
                title=page.get("title"), category=page.get("categoryName"))
        # 범위 관계 1회. 페이지 루프 뒤에 둔다 — 뒤집으려면 wikiId↔주소 표가 먼저
        # 다 있어야 한다.
        await self._invert_scope_relations()
        # 목차는 올리지 않는다. Spring 이 DB 로 그리므로(S15P11B106-280) 에이전트가 손댈
        # 것이 없고, 올리면 그것을 고치려 들다 dangling-link 로 잡이 죽는다. 공간에 무엇이
        # 있는지는 `search(mode="list")` 가 카테고리별로 더 낫게 보여준다.
        # 하이드레이션은 한 순간의 스냅샷이다 — t0 에는 아무것도 stale 일 수 없다
        # (`spring.py:100-104` 와 같은 이유). 지금은 여기서 참조 그래프를 만들지
        # 않지만, 라이브 행을 넣는 경로가 stale 을 켜는 일이 생기면 이 한 줄이 막는다.
        await self._clear_staleness(scope_id)
        self._catalog.hydrated = True

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

    async def list_documents(self, scope_id: str, with_content: bool = False) -> list[dict]:
        """부모 목록에 하이드레이션이 받은 한 줄 요약을 붙인다.

        **본문을 당기지 않는다.** 요약은 목록 응답에 이미 실려 온 값이라 조회가 늘지 않는다.
        이것이 없으면 `search(mode="list")` 가 주소와 제목만 주고, 에이전트는 어느 페이지가
        무엇을 다루는지 알아내려고 검색을 반복한다 (`_Catalog.summary_by_address` 주석).

        작업 층에 새로 쓴 페이지에는 요약이 없다 — 그건 아직 조회 API 가 모르는 페이지다.
        그때는 키가 없고 호출자가 빈 값으로 읽는다.
        """
        docs = await super().list_documents(scope_id, with_content)
        summaries = self._catalog.summary_by_address
        for doc in docs:
            summary = summaries.get(doc.get("address"))
            if summary:
                doc["summary"] = summary
        return docs

    async def _ensure_body(self, scope_id: str, address: str) -> None:
        """이 주소의 본문을 아직 안 당겼으면 당겨 채운다."""
        catalog = self._catalog
        if not catalog.hydrated:
            # 카탈로그가 아예 없다 — 하이드레이션을 안 했거나, 같은 스코프의 다른
            # 세션이 `open()` 으로 표를 갈아치웠다. 빈 본문으로 진행하면 에이전트가
            # "내용이 없다" 고 판단해 라이브를 덮는다. fail-open 하지 않는다.
            raise VaultError(
                f"`{self.scope_key}` 범위의 카탈로그가 없다 — "
                "FederatedVaultFS.open() 이 먼저 불려야 한다")
        # 같은 본문을 두 번 받지 않는다. 조회 왕복 한 번을 아낀다.
        #
        # 원래는 캐시가 아니라 재귀 차단이었다 — `_sync_page_references` 가 스코프 전체를
        # 훑고 그 안의 `build_edges` 가 링크마다 `fs.get` 을 불러 이 함수로 되돌아왔고,
        # 순환 링크(A→B, B→A)가 있으면 이 한 줄이 없을 때 `RecursionError` 로 죽었다
        # (2026-07-30 뮤턴트 검증, 설계 9.2.1). S15P11B106-151 에서 그 고리 자체를
        # 끊었다 — 아래 동기화는 이 한 장만 보고, `build_edges` 는 본문을 안 당기는
        # `resolve_address` 를 쓴다. 재귀는 이제 구조적으로 없고 여기는 캐시로 남는다.
        if address in catalog.hydrated_bodies:
            return
        # 카탈로그는 있는데 이 주소가 위키 페이지가 아닌 경우(index.md·sources/*)는
        # 정상이다 — 당길 본문이 없으니 조용히 돌아간다.
        wiki_id = catalog.wiki_id_by_address.get(address)
        if wiki_id is None:
            return
        try:
            body = await self._client.page_content(wiki_id)
        except QueryNotFound as exc:
            # 목록에 있던 페이지의 본문이 없다 — 그 사이 지워졌다. **빈 본문으로
            # 계속 진행하지 않는다** (설계 2.6). 에이전트가 빈 페이지를 보고 "내용이
            # 없다" 고 판단해 덮어쓸 수 있다. 버전 비교만으로는 목록과 본문 조회
            # 사이의 경합을 못 잡으므로 여기서 닫는다.
            # `note_scope_change` 로 클라이언트에도 남긴다 — 툴 층이 이 예외를 문자열로
            # 흡수해도(`tools/read.py:169`) 세션이 실행 뒤 그 흔적을 보고 중단한다.
            raise self._client.note_scope_change(ScopeChangedError(
                self._client.scope_version,
                reason=f"목록에 있던 Wiki {wiki_id} 의 본문이 사라졌습니다")) from exc
        # 카테고리를 함께 넘긴다. `_insert_live` 는 프론트매터 우선이고
        # (`spring.py:218`) 없으면 인자를 쓰는데, 안 넘기면 UPDATE 가 NULL 로 덮어
        # (`spring.py:234-241`) 하이드레이션이 받아둔 categoryName 이 페이지를 읽는
        # 순간 사라진다 — browse 의 카테고리 묶음과 FR-WIKI-014 근거가 함께 날아간다.
        await self._insert_live(
            scope_id, address, body.get("contentMarkdown") or "",
            wiki_id=wiki_id, title=body.get("title"),
            category=catalog.category_by_address.get(address))
        catalog.hydrated_bodies.add(address)
        # 인용 그래프는 본문이 있어야 만들어진다. **방금 받은 이 한 장만** 다시 쓴다 —
        # `_sync_page_references` 는 스코프의 라이브 문서를 전부 훑어 본문 한 장 당길
        # 때마다 O(n) 이 되고, 하이드레이션 직후에는 나머지 n-1 장의 본문이 어차피 비어
        # 있어 다시 훑어봐야 새로 나올 간선이 없다 (S15P11B106-151).
        from wiki_mcp.tools.references import sync_references

        await sync_references(self, scope_id, address,
                              body.get("contentMarkdown") or "")
        # `_sync_page_references` 가 `propagate_staleness` 를 태운다. 지연 적재는
        # 컨텍스트를 처음 채우는 것일 뿐 페이지가 실제로 바뀐 게 아니므로 되돌린다
        # (`spring.py:122-130`). 안 지우면 페이지 하나 읽은 직후 index.md 가
        # stale 로 표시돼 lint·search 가 없는 변경을 보고한다.
        await self._clear_staleness(scope_id)

    async def get(self, scope_id: str, address: str) -> dict | None:
        await self._ensure_body(scope_id, address)
        return await super().get(scope_id, address)

    def _indexes_live_locally(self, kind: str) -> bool:
        """**위키 페이지는 안 넣는다.** 라이브 검색은 조회 API 가 한다 (S15P11B106-154).

        같은 본문을 내부 색인에도 넣으면 두 가지가 깨진다. 하나는 중복이라
        `search_chunks` 가 매번 걸러내야 하는 것이고, 다른 하나가 실제 손해다 — 부모 SQL
        (`local.py`)의 `LIMIT` 이 층을 몰라서 라이브 청크가 순위대로 그 자리를 채우고
        에이전트가 방금 쓴 초안이 검색에서 사라진다 (실측 5행 → 3행).

        원본문서(`sources/**`)와 목차는 그대로 넣는다. 조회 API 가 검색해 주지 않는다 —
        삭제된 문서 재조정에서 `stage_source` 가 넣는 본문이 여기에 있다.
        """
        return kind != "page"

    async def resolve_address(self, scope_id: str, address: str) -> dict | None:
        """**본문을 당기지 않는다.** 카탈로그가 이미 주소를 다 갖고 있다.

        기본 구현(`get` 위임)을 쓰면 안 되는 유일한 구현체다 — 그 `get` 이
        `_ensure_body` 를 타서 조회 API 를 부른다 (S15P11B106-151).
        """
        return await super().get(scope_id, address)

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

    async def search_chunks(self, scope_id: str, query: str, limit: int,
                            kind_filter: str | None = None) -> list[dict]:
        """조회 API(라이브) + 내부 색인(작업층). 섞지 않고 origin 을 붙인다.

        **층별 몫으로 자른다.** 작업층을 앞에 다 놓고 잘라내면 이번 작업에서 쓴 청크가
        `limit` 을 채우는 순간 라이브가 한 건도 안 나온다 — 검색이 존재하는 이유(기존
        페이지를 못 찾아 전부 새로 만드는 것을 막는다)와 정면으로 어긋난다.

        작업층은 `MAX_WORK_SEARCH_ROWS` 로 한 번 더 좁힌다 — 툴 경로에서는 걸릴 수 없는
        2차 방어선이다. 이유는 그 상수의 주석에 있다.

        **예전 결함과 그 고침** (S15P11B106-154): 아래 `min()` 은 부모의 `LIMIT` 에
        걸리는데 부모 SQL 은 층을 모른다 (`local.py` 의 `search_chunks`, WHERE 에 layer
        조건이 없다). 라이브 위키 본문이 내부 색인에 있으면 그 행이 순위대로 `LIMIT` 을
        채워 작업층 행이 이 뒤의 필터에서 사라졌다 — 초안 5장을 쓴 직후 `limit=5` 에
        작업층 5행이던 것이 라이브 본문 적재 뒤 3행이 됐다 (이 저장소 하네스
        `--phases d`. 별도 합성 코퍼스에서 5행 → 0행 관측도 있었다).

        고침은 층 인자 추가가 아니라 **애초에 안 넣는 것**이다 — 조회 API 모드의 라이브
        검색은 조회 API 가 하므로 내부 색인의 라이브 위키 행은 중복일 뿐이었다.
        `_indexes_live_locally` 를 본다. 수치의 정본은 INDEX.md.
        """
        work_rows = await super().search_chunks(
            scope_id, query, min(limit, MAX_WORK_SEARCH_ROWS), kind_filter)
        work_addresses = set()
        work: list[dict] = []
        for row in work_rows:
            # 라이브 행은 조회 API 가 준다. 위키 페이지는 이제 내부 색인에 안 들어가지만
            # (`_indexes_live_locally`) 원본문서·목차의 라이브 행은 들어간다 — 그것을
            # 작업층으로 세면 안 되므로 층으로 거른다.
            if row.get("layer") == "work":
                work_addresses.add(row["address"])
                work.append({**row, "origin": "work"})

        catalog = self._catalog
        live: list[dict] = []
        for item in await self._client.search(query, limit=limit):
            wiki_id = item.get("wikiId")
            address = catalog.address_by_wiki_id.get(
                wiki_id, f"{PAGES_PREFIX}{wiki_id}.md")
            if address in work_addresses:
                # 작업층이 같은 페이지를 이미 고쳤다. 작업층 쪽이 최신이다.
                continue
            live.append({
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
        return _allocate(work, live, limit)


def _allocate(work: list[dict], live: list[dict], limit: int) -> list[dict]:
    """층마다 `limit` 의 절반. 못 채운 몫은 다른 층이 쓴다.

    합이 `limit` 이하면 그대로 둔다. 라이브에 반올림을 주는 이유: 여기서 막으려는
    실패가 라이브 결과의 소멸이다.
    """
    if len(work) + len(live) <= limit:
        return work + live
    live_quota = (limit + 1) // 2
    work_quota = limit - live_quota
    if len(live) < live_quota:
        work_quota += live_quota - len(live)
    if len(work) < work_quota:
        live_quota += work_quota - len(work)
    return work[:work_quota] + live[:live_quota]
