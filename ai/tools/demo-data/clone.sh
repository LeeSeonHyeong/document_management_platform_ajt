#!/usr/bin/env bash
# 로컬 ajt(DB)와 build/ajt-documents(파일)를 시연용 복제본으로 만든다.
# 원본은 읽기만 한다. 다시 실행하면 복제본을 버리고 처음부터 만든다.
#   ./tools/demo-data/clone.sh
set -euo pipefail

CONTAINER="${MYSQL_CONTAINER:-ajt-mysql}"
SRC_DB="${SRC_DB:-ajt}"
DST_DB="${DST_DB:-ajt_demo}"

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd)"
SRC_FILES="$REPO_ROOT/backend/build/ajt-documents"
DST_FILES="$REPO_ROOT/backend/build/ajt-demo-documents"

if [ ! -d "$SRC_FILES" ]; then
  echo "원본 파일 루트가 없다: $SRC_FILES" >&2
  exit 1
fi

# 원본을 절대 건드리지 않는다: DST_DB 가 원본(ajt/ajt_prod)이거나 SRC_DB 와 같으면 중단한다.
if [ "$DST_DB" = "ajt" ] || [ "$DST_DB" = "ajt_prod" ] || [ "$DST_DB" = "$SRC_DB" ]; then
  echo "DST_DB('$DST_DB')가 원본이거나 SRC_DB와 같다 — 원본을 지울 수 있어 중단한다." >&2
  exit 1
fi

# 파일 루트도 같은 이유로 대상이 원본 경로와 같으면 중단한다.
if [ "$DST_FILES" = "$SRC_FILES" ]; then
  echo "DST_FILES('$DST_FILES')가 원본 파일 루트와 같다 — 중단한다." >&2
  exit 1
fi

echo "== DB 복제: $SRC_DB -> $DST_DB"
# 경고 필터링(grep)과 실패 판정을 분리한다. "|| true" 로 종료 코드를 감추면
# 그 뒤에 실행되는 true 자체가 PIPESTATUS 를 덮어써 실패가 조용히 사라진다.
# 그래서 set +e 로 errexit 를 잠깐 끄고 PIPESTATUS[0](docker exec 의 실제 종료 코드)을
# 다른 명령이 끼어들기 전에 바로 읽는다.
set +e
docker exec "$CONTAINER" bash -c "
  set -euo pipefail
  mysql --default-character-set=utf8mb4 -uroot -p\$MYSQL_ROOT_PASSWORD -e 'DROP DATABASE IF EXISTS \`$DST_DB\`'
  mysql --default-character-set=utf8mb4 -uroot -p\$MYSQL_ROOT_PASSWORD -e 'CREATE DATABASE \`$DST_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'
  mysqldump --default-character-set=utf8mb4 --single-transaction --routines --triggers \
    -uroot -p\$MYSQL_ROOT_PASSWORD '$SRC_DB' \
    | mysql --default-character-set=utf8mb4 -uroot -p\$MYSQL_ROOT_PASSWORD '$DST_DB'
" 2>&1 | grep -v 'Using a password'
DB_CLONE_STATUS="${PIPESTATUS[0]}"
set -e
if [ "$DB_CLONE_STATUS" -ne 0 ]; then
  echo "DB 복제 실패 (종료 코드 $DB_CLONE_STATUS)" >&2
  exit "$DB_CLONE_STATUS"
fi

echo "== 파일 복제: $SRC_FILES -> $DST_FILES"
rm -rf "$DST_FILES"
cp -a "$SRC_FILES" "$DST_FILES"

echo "== 완료"
echo "  DB   : $DST_DB"
echo "  파일 : $DST_FILES"
