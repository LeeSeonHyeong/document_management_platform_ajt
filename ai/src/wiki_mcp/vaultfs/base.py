"""The port. Tools know this and nothing else.

Originally lucas-llmwiki `mcp/vaultfs/base.py`, reshaped to
`docs/AJT 파일 디렉터리 구조 설계.md`:

  * Addresses are relative to `wiki/{scopeKey}/` — `pages/{pageKey}.md`,
    `index.md`, `sources/{documentId}/parsed/content.md`. No category and no
    uploaded filename appears in a path (DR-016, DR-019).
  * Writes land in the job's work space; the live tree is read-only to the agent
    (DR-006/007/008, NFR-REL-001). `pending_changes` is the handover — the
    backend validates it and decides what is committed (API convention 10.2).
  * `allocate_page` hands out a page key, so a new page can be written and
    cross-linked before any DB row exists.

Spring replaces the implementation, not this interface.
"""

from abc import ABC, abstractmethod

INDEX_ADDRESS = "index.md"
PAGES_PREFIX = "pages/"
SOURCES_PREFIX = "sources/"


class VaultError(Exception):
    """A refusal the agent should be able to act on, not a crash."""


class ReadOnlyLayerError(VaultError):
    """Raised when a write targets something the agent may not change."""


class VaultFS(ABC):
    """Store for one visibility scope, during one job."""

    scope_key: str
    job_id: str | None

    # ----- scope ------------------------------------------------------------

    @abstractmethod
    async def resolve_scope(self, scope_key: str) -> dict | None:
        """The scope row, or None. Never creates one — the backend owns scopes."""

    @abstractmethod
    async def list_scopes(self) -> list[dict]: ...

    # ----- reading (live overlaid by work) ----------------------------------

    @abstractmethod
    async def get(self, scope_id: str, address: str) -> dict | None: ...

    @abstractmethod
    async def live_content(self, scope_id: str, address: str) -> str | None:
        """라이브 층 본문만. 겹쳐 읽기(`get`)와 달리 작업 층을 보지 않는다.

        에이전트 쓰기는 작업 층으로만 가므로 이 값은 **에이전트 실행 전 상태**다. 「이 각주가
        원래 있던 것인가」를 나이로 판정하는 유일한 근거다
        (`wiki_mcp/services/footnotes.py::legacy_footnote_labels`).

        규약에 올려둔 이유가 있다. 구현이 `SpringVaultFS` 에만 있던 동안
        `api/session.py::assert_lint_clean` 이 이것을 무조건 불렀고, 즉 게이트가 구체 클래스에
        조용히 의존했다 — 세션이 마침 항상 `FederatedVaultFS` 라 안 터진 잠재 결함이었다.
        판정을 `lint` 로 내리면서 하네스·개발 도구가 쓰는 `LocalVaultFS` 도 이 경로를 탄다.
        """

    async def resolve_address(self, scope_id: str, address: str) -> dict | None:
        """이 주소가 이 공간에 있으면 그 행을, 없으면 `None`.

        **존재 확인 전용이다. 본문 적재를 유발하지 않는다** — 돌려주는 dict 의 `content`
        는 비어 있을 수 있다. 본문이 필요하면 `get` 을 쓴다.

        `get` 과 나눈 이유는 조회 API 경로 때문이다 (S15P11B106-151). 참조 그래프를 만드는
        `build_edges` 는 링크마다 대상의 **주소만** 확인하는데 그것을 `get` 으로 하면
        `FederatedVaultFS` 가 본문을 한 장씩 당긴다. 사슬로 이어진 위키 100장에서 `read`
        한 번이 조회 100회가 됐다 (2026-07-31 실측).

        기본 구현은 `get` 위임이다 — 본문이 이미 로컬에 있는 구현체는 이것으로 맞다.
        본문을 원격에서 당기는 구현체만 재정의한다.
        """
        return await self.get(scope_id, address)

    @abstractmethod
    async def find_source(self, scope_id: str, name: str) -> dict | None:
        """Resolve a source by original filename, document id, or address.

        Footnotes name a source by its human filename, which no longer appears in
        any path, so this is the only way a citation can be checked.
        """

    @abstractmethod
    async def list_documents(self, scope_id: str, with_content: bool = False) -> list[dict]: ...

    @abstractmethod
    async def get_source_pages(self, doc_id: str, page_nums: list[int] | None = None) -> list[dict]: ...

    @abstractmethod
    async def search_chunks(self, scope_id: str, query: str, limit: int,
                            kind_filter: str | None = None) -> list[dict]: ...

    # ----- writing (work layer only) ----------------------------------------

    @abstractmethod
    async def allocate_page(self, scope_id: str) -> str:
        """Return a fresh `pages/{pageKey}.md` address. Reserves nothing in the DB."""

    @abstractmethod
    async def write(self, scope_id: str, address: str, content: str, *,
                    title: str | None = None, category: str | None = None,
                    tags: list[str] | None = None, date: str | None = None,
                    metadata: dict | None = None) -> dict:
        """Create or replace a page in the work layer. Raises for a source address."""

    @abstractmethod
    async def remove(self, scope_id: str, address: str) -> bool:
        """Tombstone a page in the work layer. The live file stays until commit."""

    # ----- handover ---------------------------------------------------------

    @abstractmethod
    async def pending_changes(self, scope_id: str) -> list[dict]:
        """What this job would change, in the shape FR-AI-009 asks for.

        `create` / `update` / `remove`, each carrying the address, title,
        category and the sources it cites. The backend assigns `wikiId` and
        decides what lands.
        """

    # ----- reference graph --------------------------------------------------

    # Addressed by `address`, not by a document row id. A page has one row per
    # layer, so an id-keyed graph loses every backlink the moment a committed page
    # is edited in a later job — which is the write-time feedback loop going quiet.
    @abstractmethod
    async def replace_references(self, scope_id: str, source_address: str,
                                 edges: list[dict]) -> None:
        """Rebuild this page's outgoing edges. One edge per footnote."""

    @abstractmethod
    async def propagate_staleness(self, scope_id: str, address: str) -> None: ...

    @abstractmethod
    async def get_backlinks(self, scope_id: str, address: str) -> list[dict]: ...

    @abstractmethod
    async def get_forward_references(self, scope_id: str, address: str) -> list[dict]: ...

    @abstractmethod
    async def find_uncited_sources(self, scope_id: str) -> list[dict]: ...

    @abstractmethod
    async def find_stale_pages(self, scope_id: str) -> list[dict]: ...
