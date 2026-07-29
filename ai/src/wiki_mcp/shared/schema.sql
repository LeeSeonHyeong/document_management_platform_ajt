-- Local index. Derived state: deletable and rebuildable from the file tree.
-- Originally from lucas-llmwiki `shared/sqlite_schema.sql`; the layout now
-- follows `docs/AJT 파일 디렉터리 구조 설계.md` and the store is two-tier.
--
-- The production store is MySQL per `docs/erdTable.sql`. This exists so the MCP
-- tools can run before Spring does, and `vaultfs/base.py` is the seam that
-- absorbs schema churn.
--
-- Two things drive the shape:
--
-- 1. **Paths are stored, not derived** (DR-016). `wiki.wiki_path` is a real
--    column, so a page's filename does not have to encode its id. Pages are
--    named `pages/{pageKey}.md` with a key the agent allocates, which is what
--    lets it write and cross-link pages before any DB row exists.
-- 2. **Nothing reaches the live tree until it is verified** (DR-007/008,
--    NFR-REL-001). Every agent write lands in `layer='work'`; `layer='live'`
--    rows are read-only to the agent. Reads overlay work over live.
--
-- ## ERD 번역표 — 이름이 다른 것은 우연이 아니라 이식(Lucas) 흔적이다
--
-- 이 스키마는 ERD의 미러가 아니다. 2단 레이어·각주 단위 그래프·청크 FTS는 ERD에
-- 없는, 이 색인만의 일이다. 대신 어휘가 겹치는 곳은 아래처럼 대응하고, 번역은
-- `vaultfs/spring.py`(들어올 때)와 `api/changes.py`(나갈 때) 두 파일에만 있다.
-- 그 밖에서 ERD 어휘와 내부 어휘를 섞으면 안 된다.
--
--   내부                          ↔  ERD (docs/erdTable.sql)
--   ─────────────────────────────────────────────────────────
--   documents (kind='page')       ↔  wiki                ← 이름 충돌 주의:
--   documents (kind='source')     ↔  document               내부 documents ≠ ERD document
--   documents (kind='index')      ↔  wiki_scope.index_path 의 index.md
--   documents.source_id           ↔  document.document_id
--   documents.wiki_id             ↔  wiki.wiki_id (커밋 후에만 채워진다)
--   documents.page_key            ↔  (대응 없음 — wiki.wiki_path 안에 들어간다, DR-016)
--   documents.category (텍스트)    ↔  wiki.wiki_category_id (FK) — 이름→ID 해석은 Spring
--   document_references           ↔  wiki.wiki_refs / wiki.document_refs /
--                                    document.document_wiki_refs (JSON, 무방향, DR-002·003)
--                                    단 내부는 각주 단위(location·quote 포함)로 더 세밀하다
--   wiki_scope                    ↔  wiki_scope (scope_key 는 동일 어휘)
--   document_chunks / chunks_fts  ↔  (대응 없음 — 검색 색인)
--   layer('live'/'work')          ↔  (대응 없음 — work/{jobId} 작업 공간의 색인 표현)
--
-- 컬럼 리네임(source_id→document_id 등)은 Spring 어댑터가 실제로 붙어 이 파일을
-- 손볼 때 같이 한다 — 지금 바꾸면 측정 비교 가능성과 테스트 258건이 흔들린다.

PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS wiki_scope (
    id TEXT PRIMARY KEY,
    -- DR-018: 'ALL', or department ids deduped, sorted ascending, joined: 'D1-D2'.
    scope_key TEXT NOT NULL,
    visibility_type TEXT NOT NULL DEFAULT 'ALL' CHECK (visibility_type IN ('ALL', 'DEPARTMENT')),
    -- wiki_scope.index_path, relative to the storage root.
    index_path TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    UNIQUE(scope_key)
);

CREATE TABLE IF NOT EXISTS documents (
    id TEXT PRIMARY KEY DEFAULT (lower(hex(randomblob(16)))),
    scope_id TEXT NOT NULL REFERENCES wiki_scope(id) ON DELETE CASCADE,

    -- Which tier this row belongs to. 'work' rows shadow 'live' rows with the
    -- same address and are what the agent sees and writes.
    layer TEXT NOT NULL DEFAULT 'live' CHECK (layer IN ('live', 'work')),
    -- Set on a 'work' row that removes a live page. A tombstone, because the
    -- live row must survive until the backend commits the deletion.
    deleted INTEGER NOT NULL DEFAULT 0,

    -- The agent's address for this file, relative to wiki/{scopeKey}/:
    --   'pages/a3f2c1d4.md', 'index.md', 'sources/101/parsed/content.md'
    address TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('page', 'index', 'source')),

    -- Pages: the key in the filename. Stable across a rename or a recategorise,
    -- so a body link never breaks (FR-WIKI-014: 카테고리 변경 시 파일은 이동하지 않는다).
    page_key TEXT,
    -- Assigned by the backend at commit. NULL means never committed.
    wiki_id TEXT,

    -- Sources: the backend's document id and the name a citation names it by.
    -- The path no longer carries the filename (DR-016), so the index must, or
    -- footnotes cannot resolve (NFR-AI-001: 원본문서 ID와 문서명을 추적).
    source_id TEXT,
    original_file_name TEXT,

    title TEXT,
    -- DB only, never in the path (DR-019).
    category TEXT,
    tags TEXT DEFAULT '[]',
    content TEXT,
    file_type TEXT NOT NULL DEFAULT 'md',
    page_count INTEGER,
    date TEXT,
    metadata TEXT,
    version INTEGER DEFAULT 0,
    stale_since TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),

    UNIQUE(scope_id, layer, address)
);

-- The agent's view of the wiki: work shadows live, tombstones disappear.
CREATE VIEW IF NOT EXISTS visible_documents AS
SELECT * FROM (
    SELECT d.*,
           ROW_NUMBER() OVER (
               PARTITION BY d.scope_id, d.address
               ORDER BY CASE d.layer WHEN 'work' THEN 0 ELSE 1 END
           ) AS _rank
    FROM documents d
)
WHERE _rank = 1 AND deleted = 0;

CREATE TABLE IF NOT EXISTS document_pages (
    id TEXT PRIMARY KEY DEFAULT (lower(hex(randomblob(16)))),
    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    page INTEGER NOT NULL,
    content TEXT NOT NULL,
    UNIQUE(document_id, page)
);

CREATE TABLE IF NOT EXISTS document_chunks (
    id TEXT PRIMARY KEY DEFAULT (lower(hex(randomblob(16)))),
    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    -- 원문. 스니펫과 각주 원문 대조가 이것을 읽는다 — 절대 전처리본으로 덮지 않는다.
    content TEXT NOT NULL,
    -- 색인 전용 전처리본. `services/chunker.py` 의 `search_tokens` 가 만든다: 한글 어절을
    -- 2글자 겹침으로 나눈 것. FTS5 `unicode61` 이 공백으로만 자르는데 한국어는 조사가
    -- 붙어 오므로 원문을 그대로 색인하면 `"연차"` 로 `"연차를"` 을 못 찾는다 (측정: R@5
    -- 0.33 → 0.60).
    --
    -- 별도 컬럼인 이유는 `chunks_fts` 가 external content 방식이라서다 — FTS 가 자기
    -- 컬럼과 같은 이름의 컬럼을 이 표에서 읽는다. 같은 컬럼에 둘 다 담을 수 없다.
    search_text TEXT NOT NULL DEFAULT '',
    page INTEGER,
    start_char INTEGER,
    token_count INTEGER NOT NULL,
    header_breadcrumb TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    UNIQUE(document_id, chunk_index)
);

-- Derived edges, parsed out of page content on every write. DR-002 forbids a
-- relation table in the production schema — there the undirected projection
-- lives in `wiki.wiki_refs` / `wiki.document_refs` JSON. This table is the local
-- index that produces those values, rebuilt from content, never authoritative.
--
-- Keyed by **address**, not by document row id. A page has one row per layer, so
-- editing a committed page in a later job mints a new row; an id-keyed graph then
-- shows that page no backlinks at all, which silently kills the write-time
-- feedback loop exactly when the wiki is big enough to need it. Addresses are
-- stable for the life of a page — that is what allocating the key up front buys.
CREATE TABLE IF NOT EXISTS document_references (
    id TEXT PRIMARY KEY DEFAULT (lower(hex(randomblob(16)))),
    scope_id TEXT NOT NULL REFERENCES wiki_scope(id) ON DELETE CASCADE,
    source_address TEXT NOT NULL,
    target_address TEXT NOT NULL,
    reference_type TEXT NOT NULL CHECK (reference_type IN ('cites', 'links_to')),
    -- Per footnote, not per target: one page citing one source at five different
    -- places is five rows. Collapsing them loses the location the frontend needs
    -- to render a citation preview.
    footnote_label TEXT,
    location TEXT,
    quote TEXT,
    page INTEGER,
    UNIQUE(scope_id, source_address, target_address, reference_type, footnote_label)
);

-- FTS 는 `search_text`(전처리본)를 색인한다. `content`(원문)가 아니다 — 한국어는 조사가
-- 붙어 오므로 원문을 그대로 색인하면 2글자 질의가 아무것도 찾지 못한다.
--
-- `tokenize='unicode61'` 그대로다. 전처리가 이미 2글자로 나눠 놓았으므로 토크나이저는
-- 공백만 자르면 된다. `trigram` 을 쓰지 않는 이유는 `chunker.search_tokens` 참고.
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
    search_text,
    content='document_chunks',
    content_rowid='rowid',
    tokenize='unicode61'
);

CREATE TRIGGER IF NOT EXISTS chunks_fts_insert AFTER INSERT ON document_chunks BEGIN
    INSERT INTO chunks_fts(rowid, search_text) VALUES (new.rowid, new.search_text);
END;

CREATE TRIGGER IF NOT EXISTS chunks_fts_delete AFTER DELETE ON document_chunks BEGIN
    INSERT INTO chunks_fts(chunks_fts, rowid, search_text)
        VALUES('delete', old.rowid, old.search_text);
END;

CREATE TRIGGER IF NOT EXISTS chunks_fts_update AFTER UPDATE ON document_chunks BEGIN
    INSERT INTO chunks_fts(chunks_fts, rowid, search_text)
        VALUES('delete', old.rowid, old.search_text);
    INSERT INTO chunks_fts(rowid, search_text) VALUES (new.rowid, new.search_text);
END;

CREATE INDEX IF NOT EXISTS idx_documents_address ON documents(scope_id, address);
CREATE INDEX IF NOT EXISTS idx_documents_kind ON documents(scope_id, kind);
CREATE INDEX IF NOT EXISTS idx_documents_layer ON documents(layer);
CREATE INDEX IF NOT EXISTS idx_documents_page_key ON documents(page_key);
CREATE INDEX IF NOT EXISTS idx_documents_source_name ON documents(original_file_name);
CREATE INDEX IF NOT EXISTS idx_chunks_doc ON document_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_refs_source ON document_references(scope_id, source_address);
CREATE INDEX IF NOT EXISTS idx_refs_target ON document_references(scope_id, target_address);
