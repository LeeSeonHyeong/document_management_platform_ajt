"""한국어 검색 평가 — **Spring 실경로**(MySQL `ngram` + `wiki_search_chunk`).

`evaluate_sqlite.py` 의 짝이다. 같은 코퍼스·같은 질의·같은 지표로 재서 **두 검색 경로가 같은
품질인지** 말할 수 있게 한다 (`corpus-ko/README.md` 의 두 번째 용도, DR-029).

README 는 이 하네스가 "스크래치패드에만 있다"고 적어뒀다. 잃어버려서 2026-08-05 에 다시
썼다 — 그때 **현행 질의 방식이 어절 둘 이상을 전부 0건으로 만드는 것**을 발견했기 때문이다
(`InternalWikiQueryService::quotePhrase` 가 질의를 통째로 큰따옴표로 감싼다 = 정확 구문).

## 무엇을 재현하는가

* **색인** — `WikiSearchIndexer::chunkMarkdown` 과 같은 헤딩 단위 분할. 토큰 상한도 겹침도
  없다 (`services/chunker.py` 의 ~512토큰·~128겹침과 **다르다** — 그것이 두 경로의 차이 중
  하나다).
* **질의** — `InternalWikiQueryService::search` 가 쓰는
  `MATCH(content) AGAINST (? IN BOOLEAN MODE)` 와 정렬까지 같다.

## 실행

```bash
cd ai
uv run --with pymysql python experiments/corpus-ko/evaluate_mysql.py            # dev
uv run --with pymysql python experiments/corpus-ko/evaluate_mysql.py --split test
```

`pymysql` 은 `pyproject.toml` 에 넣지 않는다 — 서버가 MySQL 에 붙지 않기 때문이다
(AI 서버는 DB 를 만지지 않는다. 루트 `CLAUDE.md`). 이 스크립트만 필요한 오프라인 도구다.

**별도 데이터베이스(`ajt_search_eval`)를 만들고 거기서만 쓴다.** 운영 스키마(`ajt`)를
건드리지 않는다 — 실측 데이터를 지우지 않는다는 규칙이 있다.

## 분할 규율

`dev` 는 반복 측정용, `test` 는 판정용으로 한 번만 본다 (README). 수정 전/후 대조는 `dev`
로 한다.
"""

from __future__ import annotations

import argparse
import json
import re
import time
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent

SCOPE = "EVAL"
DATABASE = "ajt_search_eval"

# `WikiSearchIndexer.HEADING` 과 같은 모양.
HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
_WORD_RE = re.compile(r"[0-9A-Za-z]+|[가-힣]+")

DDL = """
CREATE TABLE search_chunk (
    chunk_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    page VARCHAR(190) NOT NULL,
    scope_key VARCHAR(255) NOT NULL,
    chunk_index BIGINT UNSIGNED NOT NULL,
    content TEXT NOT NULL,
    PRIMARY KEY (chunk_id),
    KEY idx_scope (scope_key),
    FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
"""

# ----- 질의 방식 -------------------------------------------------------------

_OPERATORS = re.compile(r'[+\-><()~*"@]')


def q_phrase(query: str) -> str:
    """현행 — `InternalWikiQueryService::quotePhrase`. 통째로 큰따옴표 = 정확 구문."""
    return '"' + query.replace('"', "") + '"'


def q_terms(query: str) -> str:
    """제안 — boolean 연산자만 걷어낸다. ngram 토큰이 OR 로 읽힌다.

    큰따옴표를 그냥 빼지 않는 이유: 지금 그 따옴표가 **의도치 않게 연산자를 무해화**하고
    있다. 그것까지 없애면 `-` 가 든 질의가 NOT 으로 읽힌다.
    """
    return _OPERATORS.sub(" ", query).strip()


STRATEGIES = {
    "phrase (현행)": q_phrase,
    "terms (제안)": q_terms,
}

# ----- 코퍼스 ---------------------------------------------------------------


def nfc(text: str) -> str:
    return unicodedata.normalize("NFC", text)


def words(text: str) -> list[str]:
    return _WORD_RE.findall(nfc(text))


def load_pages() -> dict[str, str]:
    return {p.stem: p.read_text(encoding="utf-8")
            for p in sorted((HERE / "pages").glob("*.md"))}


def load_queries(split: str) -> list[dict]:
    data = json.loads((HERE / "queries.json").read_text(encoding="utf-8"))
    if split == "all":
        return data["dev"] + data["test"]
    return data[split]


def title_of(markdown: str) -> str:
    return markdown.split("\n")[1].replace("title:", "").strip()


def mark_body_only(queries: list[dict], titles: dict[str, str]) -> None:
    """제목만으로 맞출 수 있는 질의를 표시한다 (`evaluate_sqlite.py` 와 같은 정의)."""
    for item in queries:
        item["body_only"] = not any(
            all(w in words(titles[g]) for w in words(item["query"]))
            for g in item["gold"])


def chunk_markdown(markdown: str) -> list[str]:
    """`WikiSearchIndexer::chunkMarkdown` 의 이식. 헤딩을 만나면 그 앞까지를 한 청크로 낸다."""
    chunks: list[str] = []
    current: list[str] = []
    for line in markdown.split("\n"):
        if HEADING.match(line):
            body = "\n".join(current).strip()
            if body:
                chunks.append(body)
            current = []
        current.append(line)
    body = "\n".join(current).strip()
    if body:
        chunks.append(body)
    return chunks


# ----- 색인·검색 -------------------------------------------------------------


def build_index(cursor, pages: dict[str, str]) -> tuple[float, int]:
    cursor.execute(f"DROP DATABASE IF EXISTS {DATABASE}")
    cursor.execute(f"CREATE DATABASE {DATABASE} "
                   "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
    cursor.execute(f"USE {DATABASE}")
    cursor.execute(DDL)
    rows = [(page, SCOPE, i, nfc(chunk))
            for page, markdown in pages.items()
            for i, chunk in enumerate(chunk_markdown(markdown))]
    started = time.perf_counter()
    cursor.executemany(
        "INSERT INTO search_chunk (page, scope_key, chunk_index, content) "
        "VALUES (%s, %s, %s, %s)", rows)
    return time.perf_counter() - started, len(rows)


def search(cursor, expression: str, k: int = 20) -> list[str]:
    """페이지 단위로 접어 상위 k 개. 정렬은 운영 질의와 같다."""
    if not expression:
        return []
    cursor.execute(
        "SELECT page FROM search_chunk "
        "WHERE scope_key = %s AND MATCH(content) AGAINST (%s IN BOOLEAN MODE) "
        "ORDER BY MATCH(content) AGAINST (%s IN BOOLEAN MODE) DESC, "
        "page ASC, chunk_index ASC LIMIT %s",
        (SCOPE, expression, expression, k * 8))
    seen: set[str] = set()
    out: list[str] = []
    for (page,) in cursor.fetchall():
        if page not in seen:
            seen.add(page)
            out.append(page)
    return out[:k]


def score(cursor, to_query, subset: list[dict]) -> tuple:
    """`evaluate_sqlite.py::score` 와 같은 계산. 지표 정의를 바꾸면 비교가 깨진다."""
    r5 = r20 = p5 = mrr = 0.0
    zero = 0
    elapsed = 0.0
    for item in subset:
        gold = set(item["gold"])
        started = time.perf_counter()
        top = search(cursor, to_query(item["query"]))
        elapsed += time.perf_counter() - started
        if not top:
            zero += 1
        hit5 = set(top[:5]) & gold
        hit20 = set(top[:20]) & gold
        r5 += len(hit5) / len(gold)
        r20 += len(hit20) / len(gold)
        p5 += len(hit5) / 5
        for rank, page in enumerate(top, 1):
            if page in gold:
                mrr += 1 / rank
                break
    n = len(subset)
    return r5 / n, r20 / n, p5 / n, mrr / n, zero, elapsed / n * 1000


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", default="dev", choices=["dev", "test", "all"])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="rootpw")
    args = parser.parse_args()

    import pymysql

    pages = load_pages()
    queries = load_queries(args.split)
    titles = {key: title_of(md) for key, md in pages.items()}
    mark_body_only(queries, titles)
    exact = [q for q in queries if q["kind"] == "exact"]
    para = [q for q in queries if q["kind"] == "para"]
    body = [q for q in queries if q["body_only"]]

    connection = pymysql.connect(host=args.host, port=args.port, user=args.user,
                                 password=args.password, charset="utf8mb4",
                                 autocommit=True)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SHOW VARIABLES LIKE 'ngram_token_size'")
            ngram = cursor.fetchone()[1]
            index_ms, chunks = build_index(cursor, pages)

            print(f"코퍼스 {len(pages)}장 · 청크 {chunks}개 (헤딩 분할, 겹침 없음) · "
                  f"질의 {len(queries)}개 [{args.split}] · ngram_token_size={ngram}")
            print(f"색인 {index_ms * 1000:.0f}ms\n")

            header = (f"{'질의 방식':<18}{'R@5':>6}{'R@20':>6}{'P@5':>6}{'MRR':>6}{'0건':>5}"
                      f"{'│':>3}{'정확어R@5':>10}{'자연어R@5':>10}"
                      f"{'│':>3}{'본문R@5':>8}{'│':>3}{'질의ms':>8}")
            print(header)
            print("-" * len(header))
            for name, to_query in STRATEGIES.items():
                r5, r20, p5, mrr, zero, ms = score(cursor, to_query, queries)
                e5 = score(cursor, to_query, exact)[0]
                p_5 = score(cursor, to_query, para)[0]
                b5 = score(cursor, to_query, body)[0]
                print(f"{name:<18}{r5:>6.2f}{r20:>6.2f}{p5:>6.2f}{mrr:>6.2f}{zero:>5}"
                      f"{'│':>3}{e5:>10.2f}{p_5:>10.2f}"
                      f"{'│':>3}{b5:>8.2f}{'│':>3}{ms:>8.1f}")

            print(f"\n* 0건 = 아무것도 못 찾은 질의 수 (전체 {len(queries)}개 중)")
            print("* 본문R@5 = 제목에 질의어가 없는 질의만 "
                  f"({len(body)}개) — 본문 검색이 실제로 되는지")
            print("* 정확어/자연어 = 각각 "
                  f"{len(exact)}·{len(para)}개")

            print("\n어절 수별 0건 (현행 질의 방식):")
            by_len: dict[int, list[int]] = {}
            for item in queries:
                n_words = len(item["query"].split())
                hit = bool(search(cursor, q_phrase(item["query"])))
                by_len.setdefault(n_words, [0, 0])
                by_len[n_words][0] += 0 if hit else 1
                by_len[n_words][1] += 1
            for n_words in sorted(by_len):
                zero_count, total = by_len[n_words]
                print(f"  {n_words}어절: {zero_count}/{total} 0건")
    finally:
        connection.close()


if __name__ == "__main__":
    main()
