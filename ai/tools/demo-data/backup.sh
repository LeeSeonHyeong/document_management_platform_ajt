#!/usr/bin/env bash
# 시연 데이터를 통째로 백업하고 되살린다.
#
# 시연 데이터는 DB(`ajt_demo`)와 파일(`backend/build/ajt-demo-documents`) 두 곳에 나뉘어 있고,
# 파일 쪽은 `backend/.gitignore` 의 `build/` 에 걸려 버전 관리 밖이다. `git clean -fdx` 한 번이면
# 사라지므로 둘을 한 묶음으로 남긴다.
#
#   ./tools/demo-data/backup.sh save              # 백업 만들기
#   ./tools/demo-data/backup.sh list              # 백업 목록
#   ./tools/demo-data/backup.sh restore <파일>     # 되살리기 (덮어쓰기 전에 확인을 묻는다)
#
# 백업은 저장소 **밖**(`$HOME/workspace/AJT/backups`)에 둔다. 저장소 안에 두면 git status 에
# 계속 뜨고, `git clean` 대상이 되어 백업까지 함께 날아간다.
set -euo pipefail

CONTAINER="${MYSQL_CONTAINER:-ajt-mysql}"
DB="${DST_DB:-ajt_demo}"

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd)"
FILES="$REPO_ROOT/backend/build/ajt-demo-documents"
BACKUP_DIR="${DEMO_BACKUP_DIR:-$(cd "$REPO_ROOT/.." && pwd)/backups}"

usage() { sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1; }

# 작업 디렉터리는 전역으로 둔다 — 함수 지역 변수로 두면 함수가 끝난 뒤 EXIT 트랩이
# 그 이름을 못 찾아 `set -u` 아래서 스크립트가 0 이 아닌 코드로 끝난다 (백업은 성공했는데).
WORK=""
cleanup() { [ -n "$WORK" ] && rm -rf "$WORK"; }
trap cleanup EXIT

save() {
  [ -d "$FILES" ] || { echo "파일 루트가 없다: $FILES" >&2; exit 1; }
  mkdir -p "$BACKUP_DIR"
  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
  WORK="$(mktemp -d)"; local work="$WORK"

  echo "== DB 덤프: $DB"
  if ! docker exec "$CONTAINER" bash -c \
      "mysqldump --default-character-set=utf8mb4 --single-transaction --routines --triggers \
       --set-gtid-purged=OFF -uroot -p\$MYSQL_ROOT_PASSWORD '$DB'" \
      > "$work/db.sql" 2>"$work/err"; then
    grep -v 'Using a password' "$work/err" >&2 || true
    echo "DB 덤프 실패" >&2; exit 1
  fi

  echo "== 파일 복사"
  cp -a "$FILES" "$work/files"

  # 되살린 뒤 대조할 수치를 함께 남긴다 — 백업이 온전한지 스스로 말할 수 있어야 한다
  echo "== 매니페스트"
  {
    echo "made_at=$(date -Iseconds)"
    echo "db=$DB"
    echo "db_bytes=$(wc -c < "$work/db.sql" | tr -d ' ')"
    echo "file_count=$(find "$work/files" -type f | wc -l | tr -d ' ')"
    for t in wiki document member wiki_search_chunk ai_job wiki_chat_message; do
      n=$(docker exec "$CONTAINER" bash -c \
        "mysql -uroot -p\$MYSQL_ROOT_PASSWORD -N --batch -D $DB -e 'SELECT COUNT(*) FROM $t'" 2>/dev/null)
      echo "rows_$t=$n"
    done
  } > "$work/manifest.txt"

  local out="$BACKUP_DIR/ajt-demo-$stamp.tar.gz"
  tar -czf "$out" -C "$work" db.sql files manifest.txt
  echo "== 완료: $out ($(du -h "$out" | cut -f1))"
  sed 's/^/   /' "$work/manifest.txt"
}

list() {
  [ -d "$BACKUP_DIR" ] || { echo "백업이 없다: $BACKUP_DIR"; exit 0; }
  ls -lht "$BACKUP_DIR"/ajt-demo-*.tar.gz 2>/dev/null || echo "백업이 없다: $BACKUP_DIR"
}

restore() {
  local archive="${1:-}"
  [ -f "$archive" ] || { echo "백업 파일을 찾을 수 없다: $archive" >&2; exit 1; }
  if [ "$DB" = "ajt" ] || [ "$DB" = "ajt_prod" ]; then
    echo "원본 DB 에는 복원하지 않는다: $DB" >&2; exit 1
  fi

  WORK="$(mktemp -d)"; local work="$WORK"
  tar -xzf "$archive" -C "$work"
  echo "== 백업 내용"; sed 's/^/   /' "$work/manifest.txt"

  echo
  echo "!! '$DB' 스키마와 '$FILES' 를 이 백업으로 덮어쓴다."
  printf "계속하려면 정확히 'restore' 를 입력해라: "
  local answer; read -r answer
  [ "$answer" = "restore" ] || { echo "취소했다"; exit 1; }

  echo "== DB 복원"
  docker exec "$CONTAINER" bash -c \
    "mysql -uroot -p\$MYSQL_ROOT_PASSWORD -e 'DROP DATABASE IF EXISTS \`$DB\`; \
     CREATE DATABASE \`$DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'" 2>/dev/null
  docker exec -i "$CONTAINER" bash -c \
    "mysql --default-character-set=utf8mb4 -uroot -p\$MYSQL_ROOT_PASSWORD '$DB'" < "$work/db.sql" 2>/dev/null

  echo "== 파일 복원"
  rm -rf "$FILES"; cp -a "$work/files" "$FILES"

  echo "== 완료. 이어서 정합성을 확인해라:"
  echo "   ./tools/demo-data/check.sh"
}

case "${1:-}" in
  save) save ;;
  list) list ;;
  restore) restore "${2:-}" ;;
  *) usage ;;
esac
