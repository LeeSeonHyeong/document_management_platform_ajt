# -*- coding: utf-8 -*-
"""SQLite FTS5 한국어 토큰화 방식 비교.

`../INDEX.md` 의 「한국어 검색 — 토큰화 방식」 표를 재현한다. 스크래치패드에만 있던
하네스를 저장소로 옮긴 것이다 — 경로는 이 파일 기준이고 외부 키가 필요 없다.

    cd ai && uv run python experiments/corpus-ko/evaluate_sqlite.py
    cd ai && uv run python experiments/corpus-ko/evaluate_sqlite.py --split test

**`--split test` 는 판정할 때 한 번만 쓴다.** 보고 나서 구현을 고쳤으면 그 질의는 dev 로
강등해야 한다 (`README.md` 「dev / test」).

여기서 만드는 색인은 메모리 SQLite 다. 실제 구현(`wiki_mcp/shared/schema.sql`)과 같은
청킹(`chunk_text`)을 쓰지만 스키마는 같지 않다 — 재는 것이 토큰화 방식의 효과이므로 최소
구성으로 격리한다. 구현 자체의 회귀는 `tests/` 가 본다.
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
import time
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "src"))

from wiki_mcp.services.chunker import chunk_text  # noqa: E402

WORD_RE = re.compile(r"[0-9A-Za-z가-힣]+")


def nfc(text: str) -> str:
    """NFC 정규화. 한글은 조합형·완성형 두 표현이 있어 정규화하지 않으면 같은 글자가
    다른 바이트로 색인된다."""
    return unicodedata.normalize("NFC", text)


def words(text: str) -> list[str]:
    return WORD_RE.findall(nfc(text))


def bigrams(word: str) -> list[str]:
    """어절 하나를 2글자 겹침으로 나눈다. 1글자는 그대로 둔다."""
    word = word.lower()
    return [word] if len(word) < 2 else [word[i:i + 2] for i in range(len(word) - 1)]


def bigram_tokens(text: str) -> str:
    return " ".join(b for w in words(text) for b in bigrams(w))


def q_plain_and(query: str) -> str:
    """어절을 공백으로 잇는다. **FTS5 에서 공백은 AND 다.**

    이것이 현행 동작에 가장 가깝다 — `vaultfs/local.py` 의 `search_chunks` 는 에이전트가
    준 질의 문자열을 `MATCH ?` 에 그대로 넘기고, FTS5 는 맨 term 들을 AND 로 읽는다.
    """
    return " ".join(f'"{w.lower()}"' for w in words(query))


def q_plain_or(query: str) -> str:
    """어절을 OR 로 잇는다. 현행 동작은 아니지만 공백 분리 색인의 상한을 보여 준다.

    두 방식을 따로 재는 이유는 실수 때문이다. 초기 측정에서 `unicode61` 을 OR 로 재고
    bigram 을 AND 계열로 재 비교가 어긋났다 — 색인 방식의 차이로 보고한 것에 질의 방식의
    차이가 섞여 있었다.
    """
    return " OR ".join(f'"{w.lower()}"' for w in words(query))


def q_bigram_or(query: str) -> str:
    return " OR ".join(bigram_tokens(query).split())


def q_bigram_and(query: str) -> str:
    return " ".join(bigram_tokens(query).split())


def q_bigram_phrase(query: str) -> str:
    """어절마다 phrase 로 묶고 어절 사이는 AND(공백). 가장 엄격하다."""
    return " ".join('"' + " ".join(bigrams(w)) + '"' for w in words(query))


def q_bigram_phrase_or(query: str) -> str:
    """어절마다 phrase 로 묶고 어절 사이는 OR. **구현이 쓰는 방식**이다.

    어절 안을 phrase 로 묶는 이유는 순서다 — 묶지 않으면 `"연차"` 의 bigram 이 순서와
    무관하게 맞아 `"차연"` 같은 무관한 어절에도 걸린다. 어절 사이를 OR 로 잇는 이유는
    자연어 질의다 — 전부 요구하면 `"쉬는 날 며칠 받나"` 가 0건이 된다.
    """
    return " OR ".join('"' + " ".join(bigrams(w)) + '"' for w in words(query))


# (tokenize, 색인 전처리, 질의 변환)
#
# 색인 방식과 질의 방식을 짝지어 전부 적는다. 하나만 적으면 두 축의 차이가 섞인다.
STRATEGIES = {
    "unicode61 AND (현행)": ("unicode61", nfc, q_plain_and),
    "unicode61 OR": ("unicode61", nfc, q_plain_or),
    "trigram AND": ("trigram", nfc, q_plain_and),
    "trigram OR": ("trigram", nfc, q_plain_or),
    "bigram OR": ("unicode61", bigram_tokens, q_bigram_or),
    "bigram AND": ("unicode61", bigram_tokens, q_bigram_and),
    "bigram phrase AND": ("unicode61", bigram_tokens, q_bigram_phrase),
    "bigram phrase OR (구현)": ("unicode61", bigram_tokens, q_bigram_phrase_or),
}


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
    """제목만으로 맞출 수 있는 질의를 표시한다.

    `unicode61` 이 제목 덕에 맞추는 경우를 분리하지 않으면 본문 검색이 실제로 되는지
    알 수 없다 — 제목은 짧아서 조사가 붙지 않고, 그래서 공백 분리로도 맞는다."""
    for item in queries:
        item["body_only"] = not any(
            all(w in words(titles[g]) for w in words(item["query"]))
            for g in item["gold"])


def build_index(pages: dict[str, str], tokenize: str, prepare):
    db = sqlite3.connect(":memory:")
    db.execute(f"CREATE VIRTUAL TABLE f USING fts5(body, tokenize='{tokenize}')")
    db.execute("CREATE TABLE meta (rowid_ INTEGER PRIMARY KEY, page TEXT)")
    rid = 0
    started = time.perf_counter()
    for key, markdown in pages.items():
        for chunk in chunk_text(markdown):
            rid += 1
            db.execute("INSERT INTO f(rowid, body) VALUES (?, ?)",
                       (rid, prepare(chunk.content)))
            db.execute("INSERT INTO meta VALUES (?, ?)", (rid, key))
    db.commit()
    return db, time.perf_counter() - started, rid


def search(db, expression: str, k: int = 20) -> list[str]:
    """페이지 단위로 접어 상위 k 개. 청크가 아니라 페이지를 세는 이유는 정답 라벨이
    페이지 단위라서다."""
    try:
        rows = db.execute(
            "SELECT m.page FROM f JOIN meta m ON m.rowid_ = f.rowid "
            "WHERE f MATCH ? ORDER BY bm25(f) LIMIT ?", (expression, k * 8)).fetchall()
    except sqlite3.OperationalError:
        return []
    seen: set[str] = set()
    out: list[str] = []
    for (page,) in rows:
        if page not in seen:
            seen.add(page)
            out.append(page)
    return out[:k]


def score(db, to_query, subset: list[dict]) -> tuple:
    r5 = r20 = p5 = mrr = 0.0
    zero = 0
    elapsed = 0.0
    for item in subset:
        gold = set(item["gold"])
        started = time.perf_counter()
        top = search(db, to_query(item["query"]))
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
    parser.add_argument("--split", choices=["dev", "test", "all"], default="dev",
                        help="test 는 판정용이다 — 한 번만 본다")
    args = parser.parse_args()

    pages = load_pages()
    queries = load_queries(args.split)
    mark_body_only(queries, {k: title_of(v) for k, v in pages.items()})

    total_chars = sum(len(v) for v in pages.values())
    body_only = [q for q in queries if q["body_only"]]
    exact = [q for q in queries if q["kind"] == "exact"]
    para = [q for q in queries if q["kind"] == "para"]

    print(f"코퍼스 {len(pages)}장 · 평균 {total_chars // len(pages):,}자 "
          f"· 총 {total_chars:,}자")
    print(f"질의 {len(queries)}개 [{args.split}] — 정확어 {len(exact)} · "
          f"자연어 {len(para)} · 본문 전용 {len(body_only)}\n")

    header = (f"{'전략':<22}{'R@5':>6}{'R@20':>6}{'P@5':>6}{'MRR':>6}{'0건':>5}"
              f"{'│':>3}{'정확어R@5':>10}{'자연어R@5':>10}"
              f"{'│':>3}{'본문R@5':>8}{'│':>3}{'색인ms':>8}{'질의ms':>8}{'청크':>6}")
    print(header)
    print("-" * len(header))
    for name, (tokenize, prepare, to_query) in STRATEGIES.items():
        try:
            db, index_seconds, chunks = build_index(pages, tokenize, prepare)
        except sqlite3.OperationalError as exc:
            print(f"{name:<22} 미지원 — {exc}")
            continue
        overall = score(db, to_query, queries)
        print(f"{name:<22}{overall[0]:>6.2f}{overall[1]:>6.2f}{overall[2]:>6.2f}"
              f"{overall[3]:>6.2f}{overall[4]:>5}"
              f"{'│':>3}{score(db, to_query, exact)[0]:>10.2f}"
              f"{score(db, to_query, para)[0]:>10.2f}"
              f"{'│':>3}{score(db, to_query, body_only)[0]:>8.2f}"
              f"{'│':>3}{index_seconds * 1000:>8.1f}{overall[5]:>8.2f}{chunks:>6}")
        db.close()

    print("\n* 본문R@5 = 제목에 질의어가 없는 질의만 — 본문 검색이 실제로 되는지")
    print("* 절대 수치를 목표로 쓰지 않는다. 라벨이 블라인드 재라벨되지 않았다 (README)")


if __name__ == "__main__":
    main()
