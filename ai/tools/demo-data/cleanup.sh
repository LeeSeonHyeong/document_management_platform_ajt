#!/usr/bin/env bash
# QA 잔재를 DB 와 파일에서 함께 제거한다. 한쪽만 지우면 고아가 생기므로 한 단위로 묶는다.
#   ./tools/demo-data/cleanup.sh
set -euo pipefail

CONTAINER="${MYSQL_CONTAINER:-ajt-mysql}"
SRC_DB="${SRC_DB:-ajt}"
DB="${DST_DB:-ajt_demo}"

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd)"
FILES="$REPO_ROOT/backend/build/ajt-demo-documents"

if [ "$DB" = "ajt" ] || [ "$DB" = "ajt_prod" ] || [ "$DB" = "$SRC_DB" ]; then
  echo "원본 DB 에는 실행하지 않는다: $DB" >&2
  exit 1
fi
if [ ! -d "$FILES" ]; then
  echo "복제본이 없다. 먼저 clone.sh 를 돌린다: $FILES" >&2
  exit 1
fi

# q() 는 check.sh 와 같은 방식으로 stderr 를 버리지 않고 종료 코드와 함께 판정한다.
# 예전에는 2>/dev/null 로 오류를 삼켜 조회 실패가 "결과 없음"으로 묻혔다 — 지금은
# ORPHAN_N=26 가드에 우연히 걸려 안전하지만, 이 함수가 다른 곳에 쓰이면 깨진다.
# $(q ...) 는 명령 치환(서브셸)이라 여기서 exit 해도 이 스크립트 전체는 안 죽는다 —
# 그래서 exit 대신 문자열 마커(QUERY_ERROR:)로 실패를 실어 호출부가 직접 판정하게 한다.
q() {
  local sql="$1" out err rc
  local errfile
  errfile="$(mktemp)"
  out="$(docker exec "$CONTAINER" bash -c \
    "mysql --default-character-set=utf8mb4 -uroot -p\$MYSQL_ROOT_PASSWORD -N -D $DB -e \"$sql\"" \
    2>"$errfile")"
  rc=$?
  err="$(grep -v 'Using a password' "$errfile")"
  rm -f "$errfile"
  if [ "$rc" -ne 0 ] || [ -n "$err" ]; then
    echo "QUERY_ERROR: ${err:-mysql exited with status $rc}"
    return 1
  fi
  echo "$out"
}
run() {
  # docker exec(=mysql) 의 종료 코드를 grep 이 덮어쓰지 않게 먼저 변수에 담아 판정한다.
  # grep -v 는 걸러낼 줄이 없으면 자체적으로 exit 1 을 내므로, 화면 출력에만 파이프를
  # 쓰고 성공/실패 판정은 그 전에 캡처한 rc 로 한다.
  # out="$(...)" 를 if 조건 밖에서 단독 대입문으로 쓰면, 대입문 자체의 종료 코드가
  # 명령 치환 실패를 그대로 물려받아 set -e 가 바로 그 자리에서 스크립트를 죽인다 —
  # 그러면 아래의 진단 메시지도, exit "$rc" 도 실행되지 못한 채 조용히 죽는다.
  # if 조건 안에 넣으면 errexit 예외 구간이 되어 rc 를 안전하게 캡처할 수 있다.
  local sql="$1" out rc
  if out="$(docker exec "$CONTAINER" bash -c \
    "mysql --default-character-set=utf8mb4 -uroot -p\$MYSQL_ROOT_PASSWORD -D $DB -e \"$sql\"" \
    2>&1)"; then
    rc=0
  else
    rc=$?
  fi
  echo "$out" | grep -v 'Using a password' || true
  if [ "$rc" -ne 0 ]; then
    echo "SQL 실행 실패 (종료 코드 $rc): $sql" >&2
    exit "$rc"
  fi
}

# --- 1) 지울 문서 ID 를 먼저 모은다 (DB 를 지우기 전에 파일 경로가 필요하다) ---
ORPHAN_IDS="$(q "SELECT document_id FROM document WHERE JSON_LENGTH(document_wiki_refs) = 0 ORDER BY document_id")"
if [[ "$ORPHAN_IDS" == QUERY_ERROR:* ]]; then
  echo "고아 문서 조회 실패: ${ORPHAN_IDS#QUERY_ERROR: }" >&2
  exit 1
fi
ORPHAN_N="$(echo "$ORPHAN_IDS" | sed '/^$/d' | wc -l | tr -d ' ')"
ADMIN_IDS="49 56 60 62"

echo "== 제거 대상"
echo "  고아 문서 ${ORPHAN_N}건: $(echo $ORPHAN_IDS | tr '\n' ' ')"
echo "  인용 없는 관리자 지시: $ADMIN_IDS"

if [ "$ORPHAN_N" -ne 26 ]; then
  echo "고아 문서가 예상(26건)과 다르다: ${ORPHAN_N}건. 계획의 전제가 깨졌으므로 멈춘다." >&2
  exit 1
fi

# --- 2) 샘플 위키 2건 ---
echo "== 샘플 위키 제거 (14, 15)"
run "DELETE FROM wiki_search_chunk WHERE wiki_id IN (14, 15);"
run "DELETE FROM wiki WHERE wiki_id IN (14, 15);"

# --- 3) 관리자 지시를 가리키는 위키 참조를 먼저 뺀다 ---
echo "== 위키 document_refs 정리 (11, 20, 24)"
run "UPDATE wiki SET document_refs = JSON_ARRAY(36)         WHERE wiki_id = 11;"
run "UPDATE wiki SET document_refs = JSON_ARRAY(48, 57, 63) WHERE wiki_id = 20;"
run "UPDATE wiki SET document_refs = JSON_ARRAY(55)         WHERE wiki_id = 24;"

# --- 4) 문서 행 제거 (고아 + 관리자 지시) ---
echo "== 문서 행 제거"
run "DELETE FROM document WHERE JSON_LENGTH(document_wiki_refs) = 0;"
run "DELETE FROM document WHERE document_id IN (49, 56, 60, 62);"

# --- 5) 진짜 중복 사본 통합 ---
#    이름도 크기도 같아 내용이 동일한 사본만 합친다. 같은 위키가 두 사본을 동시에
#    근거로 달고 있어 화면의 「근거 문서」에 같은 파일명이 두 번 뜬다.
#      01-service-rules-v1.docx (37755B) 23·25 -> 23 유지
#      02-side-gigs.md          (3381B)  28·32 -> 28 유지
#    03-service-rules-amendment.md 의 15(1033B)·42(3440B)는 합치지 않는다 —
#    크기가 다르고 위키 5의 각주가 양쪽에서 인용문을 끌어왔다. 합치면 근거가 끊긴다.
DUP_IDS="25 32"
echo "== 중복 사본 통합 (제거: $DUP_IDS)"
run "UPDATE wiki SET document_refs = JSON_ARRAY(13, 23, 46)                 WHERE wiki_id = 4;"
run "UPDATE wiki SET document_refs = JSON_ARRAY(15, 23, 24, 42, 46, 55, 59) WHERE wiki_id = 5;"
run "UPDATE wiki SET document_refs = JSON_ARRAY(28)                         WHERE wiki_id = 8;"
run "DELETE FROM document WHERE document_id IN (25, 32);"

# --- 6) 문서 소스 디렉터리 제거 ---
#    rm -rf 실패(권한 등)를 조용히 삼키지 않는다 — DB 행은 지워졌는데 파일이 남으면
#    고아가 생기고, 3단계의 UPDATE 실패와 겹치면 어디서도 드러나지 않는다.
echo "== 문서 소스 파일 제거"
RM_FAIL=0
for id in $ORPHAN_IDS $ADMIN_IDS $DUP_IDS; do
  [ -z "$id" ] && continue
  while IFS= read -r dir; do
    [ -z "$dir" ] && continue
    if ! rm -rf "$dir"; then
      RM_FAIL=$((RM_FAIL + 1))
      echo "삭제 실패: $dir" >&2
    fi
  done < <(find "$FILES" -type d -path "*/sources/$id" -prune 2>/dev/null)
done
if [ "$RM_FAIL" -gt 0 ]; then
  echo "문서 소스 삭제 실패 ${RM_FAIL}건 — 고아 파일이 남았을 수 있다" >&2
  exit 1
fi

# --- 7) 페이지 파일 제거 (샘플 2 + 고아 4) ---
echo "== 페이지 파일 제거"
for n in 1 2 6 7 14 15; do
  rm -f "$FILES/wiki/ALL/pages/$n.md"
done

# --- 8) 목차에서 샘플 위키 줄 제거 ---
echo "== 목차 정리"
INDEX="$FILES/wiki/ALL/index.md"
if [ -f "$INDEX" ]; then
  if sed --version >/dev/null 2>&1; then
    sed -i -E '/\(pages\/1[45]\.md\)/d' "$INDEX"       # GNU sed
  else
    sed -i '' -E '/\(pages\/1[45]\.md\)/d' "$INDEX"    # BSD sed (macOS)
  fi
fi

echo "== 완료"
