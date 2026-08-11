#!/usr/bin/env python3
"""손으로 만든 원본문서·위키를 DB 에 등록한다.

위키 에이전트를 돌리면 비용이 들어 시연 데이터의 문서·위키를 사람이 직접 쓴다.
파일만 만들면 화면에 안 보이므로, 백엔드가 적재 때 넣었을 행들을 여기서 넣는다.

넣는 것:
- `document` (업로더·카테고리·크기·상태·역참조)
- `wiki` (제목·요약·경로·카테고리·참조)
- `wiki_category` (없으면 만든다)
- `wiki_search_chunk` 와 `content_hash` 는 `reindex.py` 가 맡는다 — 이 스크립트는 안 건드린다
- `index.md` 목차 줄

등록 대상은 `--spec` 이 가리키는 JSON 이다. 형식은 `register-spec.example.json` 참고.

    ./tools/demo-data/register.py --spec /path/to/spec.json --dry-run
    ./tools/demo-data/register.py --spec /path/to/spec.json --apply
    ./tools/demo-data/reindex.py --wiki 25 26 27 --apply

DB 접속은 `docker exec` 로 컨테이너 안에서 한다 (`ai/` 에 DB 드라이버를 들이지 않는다).
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
FILES_ROOT = REPO_ROOT / "backend" / "build" / "ajt-demo-documents"


def mysql(sql: str, container: str, db: str, *, read: bool = False) -> str:
    flags = "--default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD"
    if read:
        flags += " -N --batch"
    proc = subprocess.run(
        ["docker", "exec", "-i", container, "bash", "-c", f"mysql {flags} -D {db}"],
        input=sql, capture_output=True, text=True,
    )
    stderr = "\n".join(l for l in proc.stderr.splitlines() if "Using a password" not in l)
    if proc.returncode != 0 or stderr.strip():
        raise SystemExit(f"SQL 실패 (코드 {proc.returncode}): {stderr.strip() or sql[:150]}")
    return proc.stdout


def lit(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def front_matter(body: str, key: str) -> str:
    m = re.search(rf"^{key}:\s*(.+)$", body, re.M)
    return m.group(1).strip() if m else ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True, help="등록 명세 JSON")
    parser.add_argument("--container", default="ajt-mysql")
    parser.add_argument("--db", default="ajt_demo")
    parser.add_argument("--dry-run", action="store_true", help="검사만 하고 DB 는 안 건드린다")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    if args.db in {"ajt", "ajt_prod"}:
        print(f"원본 DB 에는 실행하지 않는다: {args.db}", file=sys.stderr)
        return 1
    if not (args.dry_run or args.apply):
        print("--dry-run 또는 --apply 를 지정해라", file=sys.stderr)
        return 1

    spec = json.loads(pathlib.Path(args.spec).read_text(encoding="utf-8"))
    problems: list[str] = []

    # --- 사전 검사: 파일이 다 있고, ID 가 안 겹치고, 링크 대상이 실재하는가 ---
    existing_docs = set(mysql("SELECT document_id FROM document;", args.container, args.db, read=True).split())
    existing_wikis = set(mysql("SELECT wiki_id FROM wiki;", args.container, args.db, read=True).split())

    for item in spec["items"]:
        scope, did, wid = item["scope"], item["document_id"], item["wiki_id"]
        src = FILES_ROOT / f"wiki/{scope}/sources/{did}"
        page = FILES_ROOT / f"wiki/{scope}/pages/{item['page_key']}.md"
        for f in (src / "original.md", src / "parsed.md", page):
            if not f.is_file():
                problems.append(f"파일 없음: {f.relative_to(FILES_ROOT)}")
        if str(did) in existing_docs:
            problems.append(f"문서 id {did} 가 이미 있다")
        if str(wid) in existing_wikis:
            problems.append(f"위키 id {wid} 가 이미 있다")
        if page.is_file():
            body = page.read_text(encoding="utf-8")
            for link in re.findall(r"\]\((pages/[^)]+)\)", body):
                if not (page.parent.parent / link).is_file():
                    problems.append(f"끊긴 링크: 위키 {wid} -> {link}")

    if problems:
        print("사전 검사 실패:", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1
    print(f"사전 검사 통과 — 등록 대상 {len(spec['items'])}건")

    if args.dry_run:
        for item in spec["items"]:
            print(f"  [dry-run] 문서 {item['document_id']} · 위키 {item['wiki_id']} "
                  f"({item['scope']}) {item['title']}")
        return 0

    # --- 위키 카테고리 확보 ---
    statements: list[str] = []
    cat_ids: dict[tuple[str, str], int] = {}
    next_cat = int(mysql("SELECT COALESCE(MAX(wiki_category_id),0)+1 FROM wiki_category;",
                         args.container, args.db, read=True).strip())
    for item in spec["items"]:
        key = (item["scope"], item["wiki_category"])
        if key in cat_ids:
            continue
        found = mysql(
            f"SELECT wiki_category_id FROM wiki_category "
            f"WHERE scope_key={lit(item['scope'])} AND name={lit(item['wiki_category'])} LIMIT 1;",
            args.container, args.db, read=True).strip()
        if found:
            cat_ids[key] = int(found)
        else:
            cat_ids[key] = next_cat
            statements.append(
                f"INSERT INTO wiki_category (wiki_category_id, scope_key, name) "
                f"VALUES ({next_cat}, {lit(item['scope'])}, {lit(item['wiki_category'])});")
            next_cat += 1

    # --- 문서·위키 행 ---
    for item in spec["items"]:
        scope, did, wid = item["scope"], item["document_id"], item["wiki_id"]
        src = FILES_ROOT / f"wiki/{scope}/sources/{did}"
        page = FILES_ROOT / f"wiki/{scope}/pages/{item['page_key']}.md"
        size = (src / "original.md").stat().st_size
        body = page.read_text(encoding="utf-8")
        summary = item.get("summary") or front_matter(body, "description")

        statements.append(
            "INSERT INTO document (document_id, uploader_id, document_category_id, scope_key, "
            "original_file_name, original_path, parsed_path, mime_type, file_size, "
            "document_wiki_refs, status) VALUES ("
            f"{did}, {spec['uploader_id']}, {item['document_category_id']}, {lit(scope)}, "
            f"{lit(item['file_name'])}, {lit(f'wiki/{scope}/sources/{did}/original.md')}, "
            f"{lit(f'wiki/{scope}/sources/{did}/parsed.md')}, {lit('text/markdown')}, {size}, "
            f"{lit(json.dumps([wid]))}, {lit('COMPLETED')});")

        statements.append(
            "INSERT INTO wiki (wiki_id, wiki_category_id, scope_key, title, summary, wiki_path, "
            "content_hash, wiki_refs, document_refs) VALUES ("
            f"{wid}, {cat_ids[(scope, item['wiki_category'])]}, {lit(scope)}, {lit(item['title'])}, "
            f"{lit(summary)}, {lit(f'wiki/{scope}/pages/' + item['page_key'] + '.md')}, "
            f"{lit('')}, {lit(json.dumps(item.get('wiki_refs', [])))}, {lit(json.dumps([did]))});")

    mysql("START TRANSACTION;\n" + "\n".join(statements) + "\nCOMMIT;", args.container, args.db)
    print(f"등록 완료 — 문서 {len(spec['items'])}건, 위키 {len(spec['items'])}건")

    # --- 목차 갱신 ---
    for scope in sorted({i["scope"] for i in spec["items"]}):
        idx = FILES_ROOT / f"wiki/{scope}/index.md"
        lines = idx.read_text(encoding="utf-8").rstrip("\n").split("\n") if idx.is_file() else ["# 목차", ""]
        for item in (i for i in spec["items"] if i["scope"] == scope):
            page = FILES_ROOT / f"wiki/{scope}/pages/{item['page_key']}.md"
            summary = item.get("summary") or front_matter(page.read_text(encoding="utf-8"), "description")
            lines.append(f"- [{item['title']}](pages/{item['page_key']}.md) — {summary}")
        idx.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"  목차 갱신: wiki/{scope}/index.md")

    print("\n다음: ./tools/demo-data/reindex.py --wiki " +
          " ".join(str(i["wiki_id"]) for i in spec["items"]) + " --apply")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
