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
