"""Derived from lucas-llmwiki `mcp/services/chunker.py`.

Splits content into ~512-token chunks with ~128-token overlap, keeping the
markdown header trail so a search hit can say where in the document it sits.

Changed from upstream: the Postgres writer and the annotation columns are gone.
`tokenize='unicode61'` in the schema replaces `porter unicode61`, because the
Porter stemmer is English-only and the corpus is Korean.

Added here: `search_tokens` / `search_query`, the Korean indexing pair. Upstream
indexed the chunk text as-is, which does not work for Korean — particles attach,
so `"연차"` never matches `"연차를"`. Both sides of the search (the index in
`store_chunks`, the query in `vaultfs/local.py`) run the same preprocessing.
Read `search_tokens` first; the measurements that decided it are there.
"""

import re
import unicodedata
from dataclasses import dataclass

import aiosqlite

CHUNK_SIZE = 512
CHUNK_OVERLAP = 128
MIN_CHUNK_TOKENS = 32
MAX_CHUNK_CHARS = 10_000

SENTENCE_RE = re.compile(r"(?<=[.!?。！？])\s+")
HEADER_RE = re.compile(r"^(#{1,6})\s+(.+)$", re.MULTILINE)

# 어절 = 문자·숫자가 이어진 덩어리. 구두점·공백이 경계다.
_WORD_RE = re.compile(r"[0-9A-Za-z가-힣]+")
_HANGUL_RE = re.compile(r"[가-힣]")


def _bigrams(word: str) -> list[str]:
    return [word] if len(word) < 2 else [word[i:i + 2] for i in range(len(word) - 1)]


def search_tokens(text: str) -> str:
    """검색 색인·질의 양쪽에 거는 전처리. 한글 어절만 2글자 겹침으로 나눈다.

    ## 왜 필요한가

    FTS5 `unicode61` 은 공백으로만 자른다. 한국어는 조사·어미가 붙어 오므로
    `"연차를 사용하려면"` 이 `[연차를] [사용하려면]` 두 토큰이 되고 **질의 `"연차"` 가
    아무것도 찾지 못한다.**

    측정 (`experiments/corpus-ko/`, 질의 40개 · `evaluate_sqlite.py --split all`):

        unicode61 AND (현행)   R@5 0.33   정확어 0.65   0건 26/40
        trigram AND            R@5 0.30   정확어 0.60   0건 28/40
        2글자 분해 OR          R@5 0.60   정확어 0.95   0건  7/40

    ## `trigram` 을 쓰지 않는 이유

    FTS5 에 `tokenize='trigram'` 이 있어 한 줄로 끝날 것 같지만 **3글자 이상만** 토큰이
    된다. 한국어 핵심어가 대부분 2글자다 — 연차·이월·승인·부여. 그것들이 전부 빠져
    현행보다 나빠진다 (0.30).

    ## 영문·숫자는 나누지 않는다

    공백 분리가 이미 맞는 언어다. 나누면 `expense` 가 `ex pe ns se` 가 되어 무관한 단어와
    겹치고 영어 재현율이 떨어진다.

    ## NFC 정규화

    한글은 조합형(`ᄋ+ᅧ+ᆫ`)과 완성형(`연`) 두 표현이 있다. 정규화하지 않으면 같은 글자가
    다른 토큰으로 색인돼 검색이 조용히 실패한다.

    ## 색인과 질의에 **같은** 함수를 쓴다

    한쪽만 걸면 아무것도 맞지 않는다. 그래서 전처리를 하나의 함수로 두고 두 경로가 그것을
    부른다 — `store_chunks`(색인)와 `vaultfs/local.py` 의 `search_chunks`(질의).
    """
    out: list[str] = []
    for word in _WORD_RE.findall(unicodedata.normalize("NFC", text)):
        if _HANGUL_RE.search(word):
            out.extend(_bigrams(word))
        else:
            out.append(word.lower())
    return " ".join(out)


def search_query(text: str) -> str:
    """검색 질의를 FTS5 MATCH 식으로 바꾼다. `search_tokens` 와 같은 전처리를 쓴다.

    bigram 전부를 OR 로 잇는다. 측정 (`experiments/corpus-ko/`, 질의 40개):

        bigram OR              R@5 0.60   정확어 0.95   자연어 0.25   P@5 0.12   0건 7
        어절 phrase OR         R@5 0.57   정확어 0.95   자연어 0.20   P@5 0.12   0건 9
        bigram AND             R@5 0.47   정확어 0.95   자연어 0.00   P@5 0.10   0건 20

    **AND 를 쓰지 않는다.** 나빠지는 쪽은 자연어 질의다 — `"쉬는 날 며칠 받나"` 의 토큰
    전부를 요구하면 0건이 20/40 이 된다. FTS5 는 공백을 AND 로 읽으므로 **OR 를 명시하지
    않으면 그 나쁜 쪽이 기본값이다.**

    **어절 단위 phrase 묶음도 쓰지 않는다.** 설계 단계에서는 그것을 쓰려 했다 — 묶지 않으면
    bigram 이 순서와 무관하게 맞아 오탐이 늘 것이라고 봤다. 측정은 그 이득을 보여주지
    않았다: `P@5` 가 같고 재현율만 0.60 → 0.57 로 떨어진다.

    이유는 bigram 이 이미 연속 부분문자열이라서다. 질의 `"차연"` 의 bigram 은 `[차연]` 이고
    본문 `"연차를"` 의 색인은 `[연차, 차를]` 이라 애초에 맞지 않는다. 남는 오탐은 3글자
    이상 어절의 일부 bigram 이 다른 단어와 겹치는 경우(`"승인이"` 의 `[인이]` 가 `"확인이"`
    와 겹침)뿐인데, 그때는 bigram 하나만 맞아 BM25 순위가 낮다.

    코퍼스가 커진 뒤 오탐이 실제로 문제가 되면 다시 재고 묶는다.
    """
    tokens = search_tokens(text).split()
    if not tokens:
        return ""
    return " OR ".join(f'"{t}"' for t in tokens)


def _estimate_tokens(text: str) -> int:
    """Rough token count: 4 characters to a token.

    **The old comment had the direction backwards.** It said undercounting "only
    makes chunks smaller than the target". It does the opposite: this function is
    the ruler `chunk_text` uses to decide when to cut, so undercounting means it
    cuts *late* and the chunks come out **larger** than `CHUNK_SIZE`.

    Measured against a real tokenizer:

      * Korean — 3,454 real tokens per chunk on average against a 512 target (6.7x)
      * English — 1,053 (2.1x)

    Kept anyway, and the reason is measured too. Shrinking chunks to 512 real
    tokens drops paraphrase R@5 from 0.60 to 0.50 on the 40-query Korean set:
    a claim and the sentence that supports it end up in different chunks.

    The oversize does not reach the prompt either. `tools/search.py` prints a
    snippet, not the chunk — `_CONTEXT_CHARS` 120 either side of the hit, or the
    first 240 characters when the query has no literal match. Chunk size sets
    what a hit *means*, not how much text is spent on it. `MAX_CHUNK_CHARS` is
    the real ceiling on how big a chunk gets.

    So this is a bad token estimate that produces good retrieval. Do not "fix"
    the arithmetic without re-running the retrieval measurement — the number that
    matters is R@5, not how close the estimate is to a tokenizer.
    """
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
    """Replace this document's chunks. Triggers keep chunks_fts in sync.

    `search_text` 를 여기서 채운다 — FTS 가 색인하는 것은 그 컬럼이다. `content` 는 원문
    그대로 둔다: 스니펫과 각주 원문 대조가 그것을 읽는다 (`search_tokens` 참고).
    """
    await db.execute("DELETE FROM document_chunks WHERE document_id = ?", (document_id,))
    if not chunks:
        return
    await db.executemany(
        "INSERT INTO document_chunks "
        "(document_id, chunk_index, content, search_text, page, start_char, token_count, "
        "header_breadcrumb) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        [(document_id, c.index, c.content, search_tokens(c.content), c.page, c.start_char,
          c.token_count, c.header_breadcrumb) for c in chunks],
    )
