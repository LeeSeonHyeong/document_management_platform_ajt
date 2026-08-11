#!/usr/bin/env bash
# 시연 리허설 중 데이터가 어떻게 바뀌는지 전후로 비교한다.
#
# 손으로 만든 위키를 에이전트가 제대로 다루는지 보려면 "무엇이 바뀌었나"를 알아야 한다.
# 화면만 봐서는 content_hash 나 검색 청크가 어긋난 것을 알 수 없다.
#
#   ./tools/demo-data/snapshot.sh take 수정전       # 지금 상태를 저장
#   ./tools/demo-data/snapshot.sh diff 수정전       # 저장한 시점 이후 무엇이 바뀌었나
#   ./tools/demo-data/snapshot.sh list
set -euo pipefail

CONTAINER="${MYSQL_CONTAINER:-ajt-mysql}"
DB="${DST_DB:-ajt_demo}"
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd)"
FILES="$REPO_ROOT/backend/build/ajt-demo-documents"
SNAP_DIR="${DEMO_SNAP_DIR:-$REPO_ROOT/.superpowers/demo-snapshots}"

q() {
  docker exec "$CONTAINER" bash -c \
    "mysql --default-character-set=utf8mb4 -uroot -p\$MYSQL_ROOT_PASSWORD -N --batch -D $DB -e \"$1\"" \
    2>/dev/null
}

capture() {
  local out="$1"
  {
    echo "## wiki (id|scope|title|content_hash|refs|docs)"
    q "SELECT CONCAT_WS('|', wiki_id, scope_key, title, LEFT(content_hash,12), \
        JSON_LENGTH(wiki_refs), JSON_LENGTH(document_refs)) FROM wiki ORDER BY wiki_id;"
    echo "## document (id|scope|name|status|wiki_refs)"
    q "SELECT CONCAT_WS('|', document_id, scope_key, original_file_name, status, \
        JSON_LENGTH(document_wiki_refs)) FROM document ORDER BY document_id;"
    echo "## chunk (wiki_id|개수)"
    q "SELECT CONCAT_WS('|', wiki_id, COUNT(*)) FROM wiki_search_chunk GROUP BY wiki_id ORDER BY wiki_id;"
    echo "## ai_job (id|status|scope)"
    q "SELECT CONCAT_WS('|', job_id, status, scope_key) FROM ai_job ORDER BY job_id;"
    echo "## chat (id|wiki|보낸이)"
    q "SELECT CONCAT_WS('|', message_id, wiki_id, sender_type) FROM wiki_chat_message ORDER BY message_id;"
    echo "## qa (id|질문)"
    q "SELECT CONCAT_WS('|', ai_question_id, LEFT(content,40)) FROM ai_question ORDER BY ai_question_id;"
    echo "## files"
    (cd "$FILES" && find . -type f | sort | while read -r f; do
        echo "$(shasum -a 256 "$f" | cut -c1-12) $f"; done)
  } > "$out"
}

case "${1:-}" in
  take)
    name="${2:?이름을 지정해라 — 예: take 수정전}"
    mkdir -p "$SNAP_DIR"; capture "$SNAP_DIR/$name.txt"
    echo "저장: $SNAP_DIR/$name.txt ($(wc -l < "$SNAP_DIR/$name.txt" | tr -d ' ') 줄)"
    ;;
  diff)
    name="${2:?이름을 지정해라}"
    old="$SNAP_DIR/$name.txt"
    [ -f "$old" ] || { echo "저장된 스냅샷이 없다: $old" >&2; exit 1; }
    new="$(mktemp)"; capture "$new"
    if diff -q "$old" "$new" >/dev/null; then
      echo "== 변화 없음 (기준: $name)"
    else
      echo "== '$name' 이후 바뀐 것"
      diff "$old" "$new" | grep -E '^[<>]' | sed 's/^</  빠짐 /; s/^>/  생김 /'
    fi
    rm -f "$new"
    ;;
  list) ls -1 "$SNAP_DIR" 2>/dev/null || echo "저장된 스냅샷이 없다" ;;
  *) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
