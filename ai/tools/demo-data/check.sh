#!/usr/bin/env bash
# 시연 데이터의 정합성을 점검한다. 문제가 있으면 종료코드 1.
# 검사 항목은 모두 prod·로컬에서 실제로 발견된 사고에서 나왔다.
#   ./tools/demo-data/check.sh
set -uo pipefail

CONTAINER="${MYSQL_CONTAINER:-ajt-mysql}"
DB="${DST_DB:-ajt_demo}"

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd)"
FILES="$REPO_ROOT/backend/build/ajt-demo-documents"

if [ ! -d "$FILES" ]; then
  echo "파일 루트가 없다: $FILES" >&2
  exit 1
fi

FAIL=0

# q() 는 "결과 없음"과 "조회 실패"를 구분해서 알려야 한다.
# 명령 치환( $(q "...") ) 안에서 죽으면 그 실패는 set -o pipefail 로도 상위에 전파되지
# 않으므로, stderr 를 버리지 않고 직접 검사해 실패를 문자열 마커(QUERY_ERROR:)로 실어
# 돌려준다. "Using a password" 경고만 걸러내고 그 외 stderr 는 오류로 취급한다.
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

# SQL 문자열 리터럴에 넣기 전에 작은따옴표를 이스케이프한다 (표준 SQL: ' -> '').
# 안 하면 파일명에 작은따옴표가 들어갈 때 문법이 깨지고, q() 의 오류 감지가 없던
# 시절엔 그 깨짐이 "결과 없음 = OK" 로 조용히 묻혔다.
sql_escape() {
  printf '%s' "${1//\'/\'\'}"
}

report() {  # report <제목> <결과>
  if [[ "$2" == QUERY_ERROR:* ]]; then
    echo "  ERR  $1"
    echo "${2#QUERY_ERROR: }" | sed 's/^/         /'
    FAIL=1
  elif [ -z "$2" ]; then
    echo "  OK   $1"
  else
    echo "  FAIL $1"
    echo "$2" | sed 's/^/         /'
    FAIL=1
  fi
}

echo "== 정합성 점검 ($DB)"

# 1. 위키가 가리키는 문서가 실재하는가
report "위키가 가리키는 문서가 실재한다" "$(q "
  SELECT CONCAT('위키 ', w.wiki_id, ' -> 없는 문서 ', j.did)
  FROM wiki w
  JOIN JSON_TABLE(w.document_refs, '\\\$[*]' COLUMNS (did BIGINT PATH '\\\$')) j
  LEFT JOIN document d ON d.document_id = j.did
  WHERE d.document_id IS NULL")"

# 2. 양방향 일치 — 문서가 가리키는 위키도 그 문서를 가리켜야 한다
report "문서와 위키의 참조가 양방향으로 맞는다" "$(q "
  SELECT CONCAT('문서 ', d.document_id, ' -> 위키 ', j.wid, ' (위키 쪽에 역참조 없음)')
  FROM document d
  JOIN JSON_TABLE(d.document_wiki_refs, '\\\$[*]' COLUMNS (wid BIGINT PATH '\\\$')) j
  JOIN wiki w ON w.wiki_id = j.wid
  WHERE NOT JSON_CONTAINS(w.document_refs, CAST(d.document_id AS JSON))")"

# 2b. 역방향 dangling — 문서가 가리키는 위키가 실재하는가
#     검사 2 는 wiki 와의 INNER JOIN 이라, 위키가 이미 삭제됐으면 행 자체가 사라져
#     검출되지 않는다. cleanup.sh 가 위키를 지우므로 이 방향도 LEFT JOIN 으로 봐야 한다.
report "문서가 가리키는 위키가 실재한다" "$(q "
  SELECT CONCAT('문서 ', d.document_id, ' -> 없는 위키 ', j.wid)
  FROM document d
  JOIN JSON_TABLE(d.document_wiki_refs, '\\\$[*]' COLUMNS (wid BIGINT PATH '\\\$')) j
  LEFT JOIN wiki w ON w.wiki_id = j.wid
  WHERE w.wiki_id IS NULL")"

# 3. 중복 업로드 — 같은 스코프에 같은 파일명·같은 크기가 둘 이상
#    file_size 를 묶음 조건에 넣는 이유: 같은 파일명이어도 내용이 다르면 다른 문서다.
#    03-service-rules-amendment.md 는 sources/(간이판, 1033B)와 sources-realistic/(상세판,
#    3440B) 두 판본이 각각 업로드된 것이고, 위키 5의 각주가 양쪽에서 서로 다른 인용문을
#    끌어왔다. file_size 없이 파일명만으로 묶으면 이 둘을 중복으로 오판해 하나를 지우게
#    되고, 그러면 남은 판본에 없는 인용문의 각주 근거가 끊긴다.
report "중복 업로드가 없다" "$(q "
  SELECT CONCAT(scope_key, ' ', original_file_name, ' (', file_size, 'B) x', COUNT(*), ' (', GROUP_CONCAT(document_id ORDER BY document_id), ')')
  FROM document
  GROUP BY scope_key, original_file_name, file_size
  HAVING COUNT(*) > 1")"

# 4. 근거 문서가 없는 위키
report "근거 없는 위키가 없다" "$(q "
  SELECT CONCAT('위키 ', wiki_id, ' ', title)
  FROM wiki
  WHERE JSON_LENGTH(document_refs) = 0")"

# 5. DB 에 행이 없는 고아 페이지 파일
ORPHAN_PAGES=""
ORPHAN_ERRORS=""
while IFS= read -r f; do
  rel="${f#$FILES/}"
  hit="$(q "SELECT 1 FROM wiki WHERE wiki_path = '$(sql_escape "$rel")' LIMIT 1")"
  if [[ "$hit" == QUERY_ERROR:* ]]; then
    ORPHAN_ERRORS="${ORPHAN_ERRORS}${rel}: ${hit#QUERY_ERROR: }"$'\n'
  elif [ -z "$hit" ]; then
    ORPHAN_PAGES="${ORPHAN_PAGES}${rel}"$'\n'
  fi
done < <(find "$FILES" -path '*/pages/*.md' 2>/dev/null)
if [ -n "$(echo "$ORPHAN_ERRORS" | sed '/^$/d')" ]; then
  report "고아 페이지 파일이 없다" "QUERY_ERROR: $(echo "$ORPHAN_ERRORS" | sed '/^$/d')"
else
  report "고아 페이지 파일이 없다" "$(echo "$ORPHAN_PAGES" | sed '/^$/d')"
fi

# 6. 각주가 인용하는 문서가 실재하는가
#    각주는 문서를 ID 가 아니라 파일명으로 인용한다. 중복 사본을 지울 때 그 파일명이
#    남은 문서에 하나도 없게 되면 근거가 끊긴다 — 이 검사가 그것을 잡는다.
CITED_MISSING=""
CITED_ERRORS=""
while IFS= read -r name; do
  [ -z "$name" ] && continue
  hit="$(q "SELECT 1 FROM document WHERE original_file_name = '$(sql_escape "$name")' LIMIT 1")"
  if [[ "$hit" == QUERY_ERROR:* ]]; then
    CITED_ERRORS="${CITED_ERRORS}${name}: ${hit#QUERY_ERROR: }"$'\n'
  elif [ -z "$hit" ]; then
    CITED_MISSING="${CITED_MISSING}${name}"$'\n'
  fi
done < <(grep -rhoE '^\[\^[^]]+\]: [^,]+' "$FILES"/wiki/*/pages/*.md 2>/dev/null \
         | sed -E 's/^\[\^[^]]+\]: //' | sort -u)
if [ -n "$(echo "$CITED_ERRORS" | sed '/^$/d')" ]; then
  report "각주가 인용하는 문서가 실재한다" "QUERY_ERROR: $(echo "$CITED_ERRORS" | sed '/^$/d')"
else
  report "각주가 인용하는 문서가 실재한다" "$(echo "$CITED_MISSING" | sed '/^$/d')"
fi

# 7. 목차(index.md)의 링크가 실재하는가 (스코프별)
#    스코프 목차가 같은 스코프의 pages/ 아래에 없는 파일을 가리키는 사고가 있었다
#    (D1 이 pages/13.md·pages/16.md 를 가리켰는데 실제 파일은 해시 이름이었다).
#    DB 조회가 필요 없는 순수 파일 검사다.
INDEX_LINK_MISSING=""
while IFS= read -r idx; do
  scope_dir="$(dirname "$idx")"
  while IFS= read -r link; do
    [ -z "$link" ] && continue
    if [ ! -f "$scope_dir/$link" ]; then
      INDEX_LINK_MISSING="${INDEX_LINK_MISSING}${idx#$FILES/}: $link"$'\n'
    fi
  done < <(grep -ohE '\]\(pages/[^)]+\)' "$idx" 2>/dev/null | sed -E 's/^\]\(//; s/\)$//')
done < <(find "$FILES"/wiki/*/index.md 2>/dev/null)
report "목차 링크가 실재한다" "$(echo "$INDEX_LINK_MISSING" | sed '/^$/d')"

# 8. 스코프의 index_path 파일이 실재하는가
#    스코프는 DB 에 있는데 목차 파일이 없는 경우가 있었다 (D3·D4).
SCOPE_INDEX_RESULT="$(q "SELECT scope_key, index_path FROM wiki_scope")"
if [[ "$SCOPE_INDEX_RESULT" == QUERY_ERROR:* ]]; then
  report "스코프의 index_path 파일이 실재한다" "$SCOPE_INDEX_RESULT"
else
  SCOPE_INDEX_MISSING=""
  while IFS=$'\t' read -r scope_key index_path; do
    [ -z "$scope_key" ] && continue
    if [ ! -f "$FILES/$index_path" ]; then
      SCOPE_INDEX_MISSING="${SCOPE_INDEX_MISSING}스코프 ${scope_key} -> 없는 목차 ${index_path}"$'\n'
    fi
  done <<< "$SCOPE_INDEX_RESULT"
  report "스코프의 index_path 파일이 실재한다" "$(echo "$SCOPE_INDEX_MISSING" | sed '/^$/d')"
fi

# 9. 위키 관계가 대칭인가 — wiki.wiki_refs 는 무방향 관계라 양쪽이 서로를 가리켜야
#    한다. 백엔드도 기동할 때 WikiRelationIntegrityConfig 로 이 대칭성을 점검한다.
report "위키 관계가 대칭이다" "$(q "
  SELECT CONCAT('위키 ', a.wiki_id, ' -> ', j.t, ' (역참조 없음)')
  FROM wiki a
  JOIN JSON_TABLE(a.wiki_refs, '\\\$[*]' COLUMNS(t BIGINT PATH '\\\$')) j
  JOIN wiki b ON b.wiki_id = j.t
  WHERE NOT JSON_CONTAINS(b.wiki_refs, CAST(a.wiki_id AS JSON))")"

# 10. 위키가 가리키는 위키가 실재하는가 (끊긴 위키 참조)
report "위키가 가리키는 위키가 실재한다" "$(q "
  SELECT CONCAT('위키 ', a.wiki_id, ' -> 없는 위키 ', j.t)
  FROM wiki a
  JOIN JSON_TABLE(a.wiki_refs, '\\\$[*]' COLUMNS(t BIGINT PATH '\\\$')) j
  LEFT JOIN wiki b ON b.wiki_id = j.t
  WHERE b.wiki_id IS NULL")"

# 11. 문의 상태와 답변이 짝을 이루는가
# `inquiry_reply.inquiry_id` 가 UNIQUE 라 문의 하나에 답변 하나다. DONE 인데 답변이
# 없거나 PENDING 인데 답변이 있으면 화면과 데이터가 어긋난다.
report "문의 상태와 답변이 맞는다" "$(q "
  SELECT CONCAT('문의 ', i.inquiry_id, ' 상태 ', i.status,
                CASE WHEN r.inquiry_reply_id IS NULL THEN ' 인데 답변 없음' ELSE ' 인데 답변 있음' END)
  FROM inquiry i LEFT JOIN inquiry_reply r ON r.inquiry_id = i.inquiry_id
  WHERE (i.status = 'DONE' AND r.inquiry_reply_id IS NULL)
     OR (i.status = 'PENDING' AND r.inquiry_reply_id IS NOT NULL)")"

# 12. 부서 일정에 부서가 지정되어 있는가
# DEPARTMENT 인데 schedule_department 가 비면 아무 부서에도 안 보인다 — 등록은 됐는데
# 화면에서 사라지는 유형이라 눈으로는 못 찾는다.
report "부서 일정에 부서가 있다" "$(q "
  SELECT CONCAT('일정 ', s.schedule_id, ' (', s.title, ') 에 부서가 없다')
  FROM schedule s LEFT JOIN schedule_department sd ON sd.schedule_id = s.schedule_id
  WHERE s.visibility_type = 'DEPARTMENT' AND sd.schedule_id IS NULL")"

# 13. 재택근무가 주 2일 한도를 지키는가
# 「근무시간 및 근무형태 안내」 위키가 정한 한도다. 데이터가 규정을 어기면 챗봇 답과
# 캘린더가 서로 다른 말을 한다. KST 로 환산해 주차를 센다 (저장은 UTC).
# CONCAT 안에서 바로 묶으면 sql_mode=only_full_group_by 가 막는다 — 집계를 먼저 끝내고
# 바깥에서 문장으로 만든다.
report "재택근무가 주 2일 한도를 지킨다" "$(q "
  SELECT CONCAT(t.name, ' — ', t.wk, ' 주차 ', t.days, '일')
  FROM (
    SELECT m.name AS name,
           YEARWEEK(DATE_ADD(s.start_at, INTERVAL 9 HOUR), 3) AS wk,
           COUNT(*) AS days
    FROM schedule s JOIN member m ON m.member_id = s.author_id
    WHERE s.visibility_type = 'PERSONAL' AND s.title = '재택근무'
    GROUP BY m.name, YEARWEEK(DATE_ADD(s.start_at, INTERVAL 9 HOUR), 3)
    HAVING COUNT(*) > 2
  ) t")"

# 15. 출처가 무엇을 가리키는지 남아 있는가
# `answer_source` 의 FK 는 ON DELETE SET NULL 이다. 위키나 일정을 지워도 행은 남고
# 가리키던 id 만 NULL 이 된다 — 화면에는 제목만 있고 눌러도 안 열리는 출처가 된다.
# 끊긴 참조가 아니라 이 「껍데기」가 실제로 생기는 고장 모양이다.
report "챗봇 출처가 대상을 가리킨다" "$(q "
  SELECT CONCAT('출처 ', answer_source_id, ' (', source_title, ') 가 가리키는 대상이 없다')
  FROM answer_source
  WHERE wiki_id IS NULL AND schedule_id IS NULL")"

# 16. 설정된 최고관리자가 명부에 있는가
# 백엔드는 `ajt.super-admin.email` 과 이메일이 일치하는 계정만 최고관리자로 본다
# (SuperAdminChecker, S15P11B106-146). 명부를 다시 세우며 이메일을 바꾸면 최고관리자가
# 0명이 되고, 위키 수정 채팅과 가입 승인 메뉴가 화면에서 통째로 사라진다 — DB 만 봐서는
# 멀쩡해 보인다(2026-08-09 실제 사고). 기동에 쓰는 값을 SUPER_ADMIN_EMAIL 로 넘겨 대조한다.
SUPER_ADMIN_EMAIL="${SUPER_ADMIN_EMAIL:-seojun.lee@ajt.com}"
SUPER_ADMIN_HIT="$(q "
  SELECT COUNT(*) FROM member
  WHERE email = '$(sql_escape "$SUPER_ADMIN_EMAIL")'
    AND role = 'ADMIN' AND signup_status = 'APPROVED' AND account_status = 'ACTIVE'")"
if [[ "$SUPER_ADMIN_HIT" == QUERY_ERROR:* ]]; then
  report "설정된 최고관리자가 명부에 있다" "$SUPER_ADMIN_HIT"
elif [ "$SUPER_ADMIN_HIT" = "0" ]; then
  report "설정된 최고관리자가 명부에 있다" \
    "설정 이메일 $SUPER_ADMIN_EMAIL 에 해당하는 활성 관리자가 없다 — 최고관리자가 0명이다"
else
  report "설정된 최고관리자가 명부에 있다" ""
fi

# 지표 — 실패 조건이 아니다.
# 고아 문서는 결함이 아니다: 올리고 아직 변환하지 않은 문서는 정상적으로 고아다.
# 단, 지표 조회 자체가 실패하면 공란으로 찍지 않고 오류를 드러내며 FAIL 로 잡는다.
metric() {  # metric <query>
  local val
  val="$(q "$1")"
  if [[ "$val" == QUERY_ERROR:* ]]; then
    FAIL=1
    echo "오류(${val#QUERY_ERROR: })"
  else
    echo "$val"
  fi
}
WIKIS=$(metric "SELECT COUNT(*) FROM wiki")
DOCS=$(metric "SELECT COUNT(*) FROM document")
MEMBERS=$(metric "SELECT COUNT(*) FROM member")
ORPHAN_DOCS=$(metric "SELECT COUNT(*) FROM document WHERE JSON_LENGTH(document_wiki_refs) = 0")
ISOLATED_WIKIS=$(metric "SELECT COUNT(*) FROM wiki WHERE JSON_LENGTH(wiki_refs) = 0")
LINKS=$(grep -rhoE '\]\(pages/[^)]+\)' "$FILES"/wiki/*/pages/*.md 2>/dev/null | wc -l | tr -d ' ')
# ai_job.document_ids 가 이미 삭제된 문서를 가리키는 경우는 지표로만 본다 — 실패로
# 잡지 않는다. 과거 작업 이력이라 결함인지 판단이 애매하기 때문이다.
BROKEN_AI_JOB_REFS=$(metric "
  SELECT COUNT(*)
  FROM ai_job j
  JOIN JSON_TABLE(j.document_ids, '\\\$[*]' COLUMNS (did BIGINT PATH '\\\$')) t
  LEFT JOIN document d ON d.document_id = t.did
  WHERE d.document_id IS NULL")
# DB 에 없는 문서의 sources/<id>/ 디렉터리 — 지우고 나서도 다시 생길 수 있어 지표로 지켜본다.
# 조회 실패는 공란으로 찍지 않고 오류를 드러내며 FAIL 로 잡는다 (다른 지표들과 동일한 원칙).
DOC_IDS_RESULT="$(q "SELECT document_id FROM document")"
if [[ "$DOC_IDS_RESULT" == QUERY_ERROR:* ]]; then
  FAIL=1
  ORPHAN_SOURCE_DIRS="오류(${DOC_IDS_RESULT#QUERY_ERROR: })"
else
  ORPHAN_SOURCE_DIRS=$(comm -23 \
    <(find "$FILES"/wiki/*/sources/* -maxdepth 0 -type d 2>/dev/null | xargs -n1 basename 2>/dev/null | sort -u) \
    <(echo "$DOC_IDS_RESULT" | sort -u) \
    | sed '/^$/d' | wc -l | tr -d ' ')
fi
SCHEDULES=$(metric "SELECT COUNT(*) FROM schedule")
SCHEDULES_AI=$(metric "SELECT COUNT(*) FROM schedule WHERE source_group_key IS NOT NULL")
INQUIRIES=$(metric "SELECT COUNT(*) FROM inquiry")
INQUIRIES_OPEN=$(metric "SELECT COUNT(*) FROM inquiry WHERE status = 'PENDING'")
SIGNUP_PENDING=$(metric "SELECT COUNT(*) FROM member WHERE signup_status = 'PENDING'")
# 출처 0건 답변은 실패가 아니다 — 찾아봤지만 없어서 「모른다」고 답한 것이 정상이기
# 때문이다(FR-QNA-007). 권한 밖 위키를 물었을 때도 여기 잡힌다. 다만 갑자기 늘면
# 도구 호출이 깨진 신호라 눈에 보이게 세어 둔다 (2026-08-09 read_wiki 400 사고).
NO_SOURCE_ANSWERS=$(metric "SELECT COUNT(*) FROM ai_answer a
  LEFT JOIN answer_source s ON s.ai_answer_id = a.ai_answer_id
  WHERE s.answer_source_id IS NULL")

echo "-- 지표: 위키 $WIKIS · 문서 $DOCS · 계정 $MEMBERS · 아웃링크 $LINKS · 고아 문서 $ORPHAN_DOCS · 끊긴 ai_job 참조 $BROKEN_AI_JOB_REFS · 고아 원본문서 디렉터리 $ORPHAN_SOURCE_DIRS · 고립 위키 $ISOLATED_WIKIS"
echo "-- 지표: 일정 $SCHEDULES (AI 추출 $SCHEDULES_AI) · 문의 $INQUIRIES (미답변 $INQUIRIES_OPEN) · 가입 승인 대기 $SIGNUP_PENDING · 출처 0건 답변 $NO_SOURCE_ANSWERS"

if [ "$FAIL" -eq 0 ]; then echo "== 통과"; else echo "== 실패"; fi
exit "$FAIL"
