#!/usr/bin/env bash
# 조직 재구성 SQL 을 ajt_demo 에 적용한다.
#   ./tools/demo-data/org.sh
set -euo pipefail

CONTAINER="${MYSQL_CONTAINER:-ajt-mysql}"
SRC_DB="${SRC_DB:-ajt}"
DB="${DST_DB:-ajt_demo}"

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL="$DEMO_DIR/org.sql"

# 안전장치 — SQL 안에서는 못 한다 (MySQL 이 IF() 의 미평가 분기도 파싱 시점에 해석한다)
if [ "$DB" = "ajt" ] || [ "$DB" = "ajt_prod" ] || [ "$DB" = "$SRC_DB" ]; then
  echo "원본 DB 에는 실행하지 않는다: $DB" >&2
  exit 1
fi
if [ ! -f "$SQL" ]; then
  echo "org.sql 이 없다: $SQL" >&2
  exit 1
fi

# 재실행 가드 — org.sql 은 두 번 돌릴 수 없다 (5번 INSERT 가 member.email·
# member.employee_no UNIQUE 제약에 걸려 중복 키 오류로 죽는다). 신규 계정 하나
# (dev.kim@ajt.com)가 이미 있으면 이미 적용된 것으로 보고 아무것도 하지 않는다.
MARKER_EMAIL="dev.kim@ajt.com"
if ! marker_out="$(docker exec "$CONTAINER" bash -c \
  "mysql --default-character-set=utf8mb4 -uroot -p\$MYSQL_ROOT_PASSWORD -N -B $DB \
    -e \"SELECT COUNT(*) FROM member WHERE email = '$MARKER_EMAIL'\"" 2>&1)"; then
  echo "이미 적용 여부를 확인하지 못했다: $marker_out" >&2
  exit 1
fi
marker_count="$(echo "$marker_out" | grep -v 'Using a password' | tail -n1 | tr -d '[:space:]')"
if [ "$marker_count" != "0" ]; then
  echo "이미 적용된 상태다 ($MARKER_EMAIL 존재) — org.sql 은 재실행할 수 없다." >&2
  echo "다시 하려면 clone.sh 로 ajt_demo 를 새로 복제 → cleanup.sh → org.sh 순서로 처음부터 만든다." >&2
  exit 2
fi

echo "== 조직 재구성 적용: $DB"
if out="$(docker exec -i "$CONTAINER" bash -c \
  "mysql --default-character-set=utf8mb4 -uroot -p\$MYSQL_ROOT_PASSWORD $DB" \
  < "$SQL" 2>&1)"; then
  rc=0
else
  rc=$?
fi
echo "$out" | grep -v 'Using a password' || true
if [ "$rc" -ne 0 ]; then
  echo "org.sql 적용 실패 (종료 코드 $rc)" >&2
  exit "$rc"
fi

echo "== 완료"
