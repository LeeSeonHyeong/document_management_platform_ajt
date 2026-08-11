#!/usr/bin/env python3
"""손으로 고친 위키 본문에 맞춰 DB 의 파생 데이터를 다시 만든다.

위키 본문을 사람이 직접 고치면 `wiki.content_hash` 와 `wiki_search_chunk` 가 어긋난다.
이 스크립트는 백엔드의 `WikiSearchIndexer.replace` 가 하는 일을 그대로 재현해 그 둘을
맞춘다 — 청킹 규칙·breadcrumb·해시 계산이 자바 구현과 같아야 하므로 옮길 때 임의로
바꾸지 않았다 (`backend/.../wiki/service/WikiSearchIndexer.java`).

재현이 정확한지는 `--verify` 로 확인한다. 본문을 고치기 **전에** 돌리면 지금 DB 에 있는
청크와 이 스크립트가 만드는 청크가 완전히 일치해야 한다. 일치하지 않으면 자바 쪽이
바뀐 것이므로, 그 상태로 본문을 고치면 안 된다.

    ./tools/demo-data/reindex.py --verify          # 재현이 정확한지 먼저 확인
    ./tools/demo-data/reindex.py --wiki 8 10 --apply
    ./tools/demo-data/reindex.py --all --apply

DB 접속은 `docker exec` 로 컨테이너 안에서 한다 — 파이썬 DB 드라이버를 새로 들이지
않으려는 것이다 (`ai/` 는 DB 를 직접 보지 않는다는 경계를 지킨다).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")

REPO_ROOT = Path(__file__).resolve().parents[3]
FILES_ROOT = REPO_ROOT / "backend" / "build" / "ajt-demo-documents"


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def chunk_markdown(markdown: str) -> list[tuple[int, str | None, str, str]]:
    """`WikiSearchIndexer.chunkMarkdown` 과 같은 결과를 낸다.

    헤딩을 만나면 그 직전까지를 한 청크로 끊고, 헤딩 줄 자체는 다음 청크의 첫 줄이
    된다. breadcrumb 은 **끊는 시점의** 헤딩 스택이라 그 청크가 속한 절을 가리킨다.
    """
    chunks: list[tuple[int, str | None, str, str]] = []
    headings: list[str] = []
    current: list[str] = []
    index = 0

    def flush(index: int) -> int:
        content = "\n".join(current).strip()
        if content:
            breadcrumb = " > ".join(headings) if headings else None
            chunks.append((index, breadcrumb, content, sha256(content)))
            index += 1
        current.clear()
        return index

    for line in markdown.split("\n"):
        matched = HEADING.match(line)
        if matched:
            index = flush(index)
            level = len(matched.group(1))
            while len(headings) >= level:
                headings.pop()
            headings.append(matched.group(2).strip())
        current.append(line)
    flush(index)
    return chunks


def mysql(sql: str, container: str, db: str, *, json_out: bool = False) -> str:
    """컨테이너 안에서 SQL 을 실행한다. 실패를 삼키지 않는다."""
    flags = "--default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD"
    if json_out:
        flags += " --batch --raw --silent"
    proc = subprocess.run(
        ["docker", "exec", "-i", container, "bash", "-c",
         f'mysql {flags} -D {db}'],
        input=sql, capture_output=True, text=True,
    )
    stderr = "\n".join(
        line for line in proc.stderr.splitlines() if "Using a password" not in line
    )
    if proc.returncode != 0 or stderr.strip():
        raise SystemExit(f"SQL 실패 (코드 {proc.returncode}): {stderr.strip() or sql[:120]}")
    return proc.stdout


def sql_literal(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def load_wikis(container: str, db: str, wiki_ids: list[int] | None) -> list[dict]:
    where = ""
    if wiki_ids:
        where = f"WHERE wiki_id IN ({','.join(str(i) for i in wiki_ids)})"
    rows = mysql(
        f"SELECT wiki_id, scope_key, wiki_path FROM wiki {where} ORDER BY wiki_id;",
        container, db, json_out=True,
    )
    wikis = []
    for line in rows.strip().splitlines():
        wiki_id, scope_key, wiki_path = line.split("\t")
        wikis.append({"id": int(wiki_id), "scope": scope_key, "path": wiki_path})
    return wikis


def current_chunks(container: str, db: str, wiki_id: int) -> list[tuple[int, str]]:
    rows = mysql(
        f"SELECT chunk_index, content_hash FROM wiki_search_chunk "
        f"WHERE wiki_id = {wiki_id} ORDER BY chunk_index;",
        container, db, json_out=True,
    )
    out = []
    for line in rows.strip().splitlines():
        index, digest = line.split("\t")
        out.append((int(index), digest))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="ajt-mysql")
    parser.add_argument("--db", default="ajt_demo")
    parser.add_argument("--wiki", type=int, nargs="*", help="대상 위키 id (없으면 --all 필요)")
    parser.add_argument("--all", action="store_true", help="모든 위키")
    parser.add_argument("--verify", action="store_true",
                        help="DB 를 바꾸지 않고, 재현한 청크가 지금 DB 와 같은지만 본다")
    parser.add_argument("--apply", action="store_true", help="DB 를 실제로 갱신한다")
    args = parser.parse_args()

    if args.db in {"ajt", "ajt_prod"}:
        print(f"원본 DB 에는 실행하지 않는다: {args.db}", file=sys.stderr)
        return 1
    if not args.verify and not args.apply:
        print("--verify 또는 --apply 중 하나를 지정해라", file=sys.stderr)
        return 1
    if not args.all and not args.wiki:
        print("--wiki <id...> 또는 --all 을 지정해라", file=sys.stderr)
        return 1

    wikis = load_wikis(args.container, args.db, None if args.all else args.wiki)
    if not wikis:
        print("대상 위키가 없다", file=sys.stderr)
        return 1

    mismatched = 0
    for wiki in wikis:
        body_path = FILES_ROOT / wiki["path"]
        if not body_path.is_file():
            print(f"  본문 없음  위키 {wiki['id']}  {wiki['path']}", file=sys.stderr)
            return 1
        markdown = body_path.read_text(encoding="utf-8")
        chunks = chunk_markdown(markdown)
        content_hash = sha256(markdown)

        if args.verify:
            before = current_chunks(args.container, args.db, wiki["id"])
            mine = [(i, h) for i, _, _, h in chunks]
            same = before == mine
            if not same:
                mismatched += 1
            mark = "일치" if same else "불일치"
            print(f"  {mark}  위키 {wiki['id']:>3}  DB {len(before)}청크 / 재현 {len(mine)}청크")
            continue

        statements = [
            f"DELETE FROM wiki_search_chunk WHERE wiki_id = {wiki['id']};",
        ]
        for index, breadcrumb, content, digest in chunks:
            crumb = "NULL" if breadcrumb is None else sql_literal(breadcrumb)
            statements.append(
                "INSERT INTO wiki_search_chunk "
                "(wiki_id, scope_key, chunk_index, breadcrumb, content, content_hash, indexed_at) "
                f"VALUES ({wiki['id']}, {sql_literal(wiki['scope'])}, {index}, {crumb}, "
                f"{sql_literal(content)}, {sql_literal(digest)}, NOW(6));"
            )
        statements.append(
            f"UPDATE wiki SET content_hash = {sql_literal(content_hash)}, "
            f"search_indexed_hash = {sql_literal(content_hash)} WHERE wiki_id = {wiki['id']};"
        )
        mysql("START TRANSACTION;\n" + "\n".join(statements) + "\nCOMMIT;",
              args.container, args.db)
        print(f"  갱신  위키 {wiki['id']:>3}  청크 {len(chunks)}개  hash {content_hash[:12]}")

    if args.verify:
        if mismatched:
            print(f"\n불일치 {mismatched}건 — 자바 청킹 규칙이 바뀌었을 수 있다. "
                  f"본문을 고치기 전에 원인을 확인해라", file=sys.stderr)
            return 1
        print("\n재현이 DB 와 완전히 일치한다 — 본문을 고쳐도 안전하다")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
