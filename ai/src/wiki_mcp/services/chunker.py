"""Derived from lucas-llmwiki `mcp/services/chunker.py`.

Splits content into ~512-token chunks with ~128-token overlap, keeping the
markdown header trail so a search hit can say where in the document it sits.

Changed from upstream: the Postgres writer and the annotation columns are gone.
`tokenize='unicode61'` in the schema replaces `porter unicode61`, because the
Porter stemmer is English-only and the corpus is Korean.
"""

import re
from dataclasses import dataclass

import aiosqlite

CHUNK_SIZE = 512
CHUNK_OVERLAP = 128
MIN_CHUNK_TOKENS = 32
MAX_CHUNK_CHARS = 10_000

SENTENCE_RE = re.compile(r"(?<=[.!?。！？])\s+")
HEADER_RE = re.compile(r"^(#{1,6})\s+(.+)$", re.MULTILINE)


def _estimate_tokens(text: str) -> int:
    """Rough token count. Undercounts Korean by roughly 2x, which only makes
    chunks smaller than the target — harmless for retrieval."""
    return max(1, len(text) // 4)


@dataclass
class Chunk:
    index: int
    content: str
    page: int | None
    start_char: int
    token_count: int
    header_breadcrumb: str = ""


def chunk_text(content: str, chunk_size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP,
               page: int | None = None) -> list[Chunk]:
    if not content or not content.strip():
        return []

    paragraphs = _split_paragraphs(content)
    header_stack: list[tuple[int, str]] = []
    chunks: list[Chunk] = []
    current_blocks: list[str] = []
    current_tokens = 0
    current_start = 0
    char_pos = 0

    for para in paragraphs:
        para_tokens = _estimate_tokens(para)

        m = HEADER_RE.match(para)
        if m:
            level = len(m.group(1))
            heading = m.group(2).strip()
            header_stack = [(lvl, t) for lvl, t in header_stack if lvl < level]
            header_stack.append((level, heading))

        if current_tokens + para_tokens > chunk_size and current_blocks:
            emitted = _emit(chunks, current_blocks, page, current_start, header_stack)
            if emitted is not None:
                chunks.append(emitted)
            overlap_blocks, overlap_tokens = _get_overlap(current_blocks, overlap)
            current_blocks = overlap_blocks
            current_tokens = overlap_tokens
            current_start = char_pos - sum(len(b) + 2 for b in overlap_blocks)

        current_blocks.append(para)
        current_tokens += para_tokens
        char_pos += len(para) + 2

    if current_blocks:
        tail = _emit(chunks, current_blocks, page, current_start, header_stack)
        if tail is not None:
            chunks.append(tail)

    return _enforce_max_chars(chunks)


def _emit(chunks: list, blocks: list[str], page: int | None, start: int,
          header_stack: list[tuple[int, str]]) -> Chunk | None:
    text = "\n\n".join(blocks)
    if _estimate_tokens(text) < MIN_CHUNK_TOKENS:
        return None
    return Chunk(
        index=len(chunks),
        content=text,
        page=page,
        start_char=start,
        token_count=_estimate_tokens(text),
        header_breadcrumb=" > ".join(t for _, t in header_stack),
    )


def _enforce_max_chars(chunks: list[Chunk]) -> list[Chunk]:
    """Split any chunk past MAX_CHUNK_CHARS.

    One paragraph bigger than CHUNK_SIZE becomes one chunk, and Korean prose or
    a long code block routinely blows past the limit. Split on sentence
    boundaries; hard-slice only when there is no boundary.
    """
    if not any(len(c.content) > MAX_CHUNK_CHARS for c in chunks):
        return chunks

    result: list[Chunk] = []
    for c in chunks:
        if len(c.content) <= MAX_CHUNK_CHARS:
            result.append(Chunk(index=len(result), content=c.content, page=c.page,
                                start_char=c.start_char, token_count=c.token_count,
                                header_breadcrumb=c.header_breadcrumb))
            continue
        base = c.start_char or 0
        offset = 0
        for piece in _split_oversized(c.content):
            result.append(Chunk(index=len(result), content=piece, page=c.page,
                                start_char=base + offset, token_count=_estimate_tokens(piece),
                                header_breadcrumb=c.header_breadcrumb))
            offset += len(piece)
    return result


def _split_oversized(text: str) -> list[str]:
    parts = SENTENCE_RE.split(text)
    pieces: list[str] = []
    current = ""
    for part in parts:
        candidate = (current + " " + part).strip() if current else part
        if len(candidate) <= MAX_CHUNK_CHARS:
            current = candidate
            continue
        if current:
            pieces.append(current)
        if len(part) <= MAX_CHUNK_CHARS:
            current = part
        else:
            for i in range(0, len(part), MAX_CHUNK_CHARS):
                pieces.append(part[i:i + MAX_CHUNK_CHARS])
            current = ""
    if current:
        pieces.append(current)
    return pieces


def _split_paragraphs(text: str) -> list[str]:
    parts = re.split(r"\n\s*\n", text)
    return [p.strip() for p in parts if p.strip()]


def _get_overlap(blocks: list[str], target_tokens: int) -> tuple[list[str], int]:
    result: list[str] = []
    tokens = 0
    for block in reversed(blocks):
        bt = _estimate_tokens(block)
        if tokens + bt > target_tokens:
            break
        result.insert(0, block)
        tokens += bt
    return result, tokens


async def store_chunks(db: aiosqlite.Connection, document_id: str, chunks: list[Chunk]) -> None:
    """Replace this document's chunks. Triggers keep chunks_fts in sync."""
    await db.execute("DELETE FROM document_chunks WHERE document_id = ?", (document_id,))
    if not chunks:
        return
    await db.executemany(
        "INSERT INTO document_chunks "
        "(document_id, chunk_index, content, page, start_char, token_count, header_breadcrumb) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        [(document_id, c.index, c.content, c.page, c.start_char, c.token_count,
          c.header_breadcrumb) for c in chunks],
    )
