# 시연 데이터 준비 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로컬 `ajt` 를 격리 복제한 `ajt_demo` 에서 QA 잔재를 걷어내고 조직을 시연 형태로 맞춰, 발표 시연에 쓸 데이터셋을 만든다.

**Architecture:** 원본을 지우지 않는다. DB 와 파일 루트를 각각 복제하고 복제본에서만 작업한다. 모든 변경은 정합성 점검 스크립트로 검증하며, 그 스크립트를 먼저 만들어 변경 전에는 잔재가 검출되고 변경 후에는 통과하는 것을 확인한다. 되돌리기는 스키마 `DROP` 과 디렉터리 삭제뿐이다.

**Tech Stack:** MySQL 8.4 (docker `ajt-mysql`), bash. 파이썬 의존성을 늘리지 않는다 — `ai/` 는 DB 드라이버를 갖고 있지 않고 이 도구 때문에 넣지 않는다. 기존 `ai/tools/harness/` 와 같은 방식(bash + `docker exec`)을 따른다.

## Global Constraints

- **원본 `ajt`·`ajt_prod`·`backend/build/ajt-documents` 를 어느 단계에서도 수정하지 않는다.** 모든 쓰기는 `ajt_demo` 와 `backend/build/ajt-demo-documents` 안에서만 일어난다.
- **DB 행과 파일은 항상 짝으로 처리한다.** 한쪽만 지우면 고아가 생긴다.
- **위키 본문을 손으로 만들거나 고치지 않는다.** 이 계획의 파일 편집은 `index.md` 의 목차 줄 제거와 잔재 파일 삭제뿐이다. 본문 생성은 에이전트만 한다.
- **SQL 직접 입력은 조직 데이터에 한한다** (`department`·`member`·`wiki_scope`·`document_category`).
- MySQL 접속은 컨테이너 안에서 `mysql -uroot -p$MYSQL_ROOT_PASSWORD` 로 한다. 비밀번호를 스크립트나 커밋에 적지 않는다.
- 모든 `mysql` 호출에 `--default-character-set=utf8mb4` 를 붙인다. 빠뜨리면 한글이 깨진다.
- 커밋 메시지는 `../docs/conventions/git-convention.md` 형식(`<타입>(<범위>): <한국어 설명>`)을 따르고 Jira 키를 넣지 않는다. stage 는 경로를 하나하나 명시한다.

---

## File Structure

| 파일 | 책임 |
| --- | --- |
| `ai/tools/demo-data/README.md` | 이 도구가 무엇이고 어떤 순서로 쓰는지 |
| `ai/tools/demo-data/clone.sh` | `ajt` → `ajt_demo`, 파일 루트 복제 (멱등) |
| `ai/tools/demo-data/check.sh` | 정합성 점검 7종 + 지표 6개. 문제가 있으면 종료코드 1 |
| `ai/tools/demo-data/cleanup.sh` | QA 잔재를 DB 와 파일에서 함께 제거 |
| `ai/tools/demo-data/org.sql` | 조직 재구성 SQL (부서·계정) |
| `ai/tools/demo-data/org.sh` | `org.sql` 적용 래퍼 — 대상 DB 가드와 실패 판정 |

`cleanup` 을 SQL 파일로 나누지 않고 `.sh` 하나로 둔 이유는, 지울 문서 ID 를 **먼저 조회해서** DB 행과 소스 디렉터리를 같은 목록으로 지워야 하기 때문이다. SQL 만으로는 파일을 못 지운다.

---

## 조사로 확정된 제거 대상

이 계획은 아래를 사실로 두고 쓰였다. 실행 시점에 숫자가 다르면 **멈추고 보고한다.**

**고아 문서 26건 — 전부 잔재다** (`document_wiki_refs` 가 빈 문서)

| 성격 | 건수 | ID |
| --- | --- | --- |
| 중복 업로드 잔재 | 20 | 4·16·20·21·22, 5·43, 27, 6·14, 8, 9, 29·30·31·34·35, 11, 51·52 |
| 변환 실패(`FAILED`) | 3 | 7, 10, 26 |
| `[샘플]` 시드 문서 | 3 | 38, 39, 40 |

중복은 QA 중 같은 파일을 반복 업로드한 흔적이다 — `01-service-rules-v1.docx` 7회, `08-compensation.md` 6회 등. 각 그룹에서 위키가 실제로 쓰는 사본은 참조가 남아 있어 고아가 아니다. `사내규정 통합본.md`(51·52)만 두 사본 모두 안 쓰였다.

**인용 없는 관리자 지시 4건** — `49`·`56`·`60`·`62`. 문서를 지우면 아래 위키의 참조도 빼야 양방향이 맞는다.

| 위키 | 지금 | 제거 후 |
| --- | --- | --- |
| 11 수습 기간·퇴직금·계약 및 급여 지급 | `[36, 60]` | `[36]` |
| 20 공통 프로젝트 산출물 제출 안내 | `[48, 49, 56, 57, 63]` | `[48, 57, 63]` |
| 24 경조사 지원 기준 | `[55, 62]` | `[55]` |

관리자 지시 `45`·`57`·`63` 은 **남긴다** — 위키 10·20 의 본문 각주가 인용하고 있다.

**샘플 위키 2건과 고아 페이지 파일 4개**

| 대상 | 경로 |
| --- | --- |
| 샘플 위키 (DB 행 + 파일 + 목차 줄) | 위키 `14`·`15`, `wiki/ALL/pages/14.md`·`15.md` |
| 고아 페이지 파일 (DB 행 없음) | `wiki/ALL/pages/1.md`·`2.md`·`6.md`·`7.md` |

위키 14·15 는 참조하는 곳이 없다(`answer_source`·`wiki_chat_message`·`wiki_search_chunk`·다른 위키의 `wiki_refs` 모두 0건).

**정제 후 예상치**: 위키 16, 문서 21, 계정 16.

문서 21 = 53 − 고아 26 − 관리자 지시 4 − 중복 사본 2(25·32).

---

### Task 1: 격리 환경 복제

**Files:**
- Create: `ai/tools/demo-data/clone.sh`
- Create: `ai/tools/demo-data/README.md`

**Interfaces:**
- Produces: `ajt_demo` 스키마와 `backend/build/ajt-demo-documents` 디렉터리. 이후 모든 태스크가 이 둘만 건드린다.

- [ ] **Step 1: 원본 기준값을 기록한다**

Run:
```bash
docker exec ajt-mysql bash -c 'mysql --default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD -D ajt -e "SELECT (SELECT COUNT(*) FROM wiki) AS wiki, (SELECT COUNT(*) FROM document) AS doc, (SELECT COUNT(*) FROM member) AS mem"'
```

Expected: `wiki 18`, `doc 53`, `mem 13`. 다르면 원본이 이미 바뀐 것이므로 **진행하지 말고 보고한다** — 이 계획의 ID 목록이 더는 맞지 않는다.

별칭에 `AS` 를 붙이고 `member` 를 피한 이유: MySQL 8.0.17 부터 `MEMBER` 가 예약어라(`MEMBER OF`) 맨 별칭으로 쓰면 문법 오류가 난다.

- [ ] **Step 2: `clone.sh` 를 작성한다**

> ⚠️ **정본은 `ai/tools/demo-data/clone.sh` 다.** 아래는 작성 당시 초안이며, 리뷰로 추가된
> 안전장치가 빠져 있다. **그대로 복사해 실행하지 않는다** — 대상 DB 가드가 없어
> `DST_DB=ajt` 로 잘못 실행하면 원본을 지운다.

| 항목 | 이 초안 | 실제 `clone.sh` |
| --- | --- | --- |
| `DST_DB` 가드 | 없음 | `ajt`·`ajt_prod`·`$SRC_DB` 와 같으면 중단 |
| `DST_FILES` 가드 | 없음 | 원본 파일 루트와 경로가 같으면 중단 |
| `DROP`/`CREATE DATABASE` | `--default-character-set` 없음 (한글 깨짐 위험) | 두 호출 모두 명시 |
| DB 복제 실패 판정 | `... \| grep -v ... \|\| true` 로 종료코드를 삼킴 | `set +e` 로 끄고 `PIPESTATUS[0]`(실제 `docker exec` 종료코드)을 직접 읽어 판정 |

핵심 로직(스키마 생성 → `mysqldump \| mysql` 파이프 → 파일 트리 `cp -a`)은 초안과 실제가 같다.
차이는 전부 "잘못된 DB 를 대상으로 돌렸을 때 멈추는가"와 "복제가 실제로 성공했는가"를 판정하는
안전장치다.

- [ ] **Step 3: 실행 권한을 주고 돌린다**

Run:
```bash
cd ai && chmod +x tools/demo-data/clone.sh && ./tools/demo-data/clone.sh
```

Expected: `== 완료` 까지 오류 없이 출력.

- [ ] **Step 4: 복제본이 원본과 같은지 대조한다**

Run:
```bash
docker exec ajt-mysql bash -c 'mysql --default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD -D ajt_demo -e "SELECT (SELECT COUNT(*) FROM wiki) AS wiki, (SELECT COUNT(*) FROM document) AS doc, (SELECT COUNT(*) FROM member) AS mem"'
diff -r backend/build/ajt-documents backend/build/ajt-demo-documents && echo "파일 동일"
```

Expected: Step 1 과 같은 `18 / 53 / 13`, 그리고 `파일 동일`.

- [ ] **Step 5: 한글이 안 깨졌는지 확인한다**

Run:
```bash
docker exec ajt-mysql bash -c 'mysql --default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD -D ajt_demo -e "SELECT title FROM wiki WHERE wiki_id = 5"'
```

Expected: `연차유급휴가 규정`. 물음표로 나오면 `--default-character-set` 이 빠진 것이다.

- [ ] **Step 6: `README.md` 를 작성한다**

````markdown
# 시연 데이터 도구

발표 시연용 데이터셋을 만드는 로컬 전용 도구다. 설계는
`ai/docs/superpowers/specs/2026-08-08-demo-data-preparation-design.md`.

**원본을 지우지 않는다.** 로컬 `ajt` 와 `backend/build/ajt-documents` 는 읽기만 하고,
모든 작업은 복제본(`ajt_demo`, `backend/build/ajt-demo-documents`)에서 한다.

## 순서

```bash
cd ai
./tools/demo-data/clone.sh     # 복제
./tools/demo-data/check.sh     # 잔재가 검출된다 (정상)
./tools/demo-data/cleanup.sh   # QA 잔재 제거
./tools/demo-data/check.sh     # 통과
./tools/demo-data/org.sh       # 조직 재구성
./tools/demo-data/check.sh     # 통과
```

## 백엔드를 복제본에 물려 띄우기

```bash
cd backend
SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/ajt_demo?characterEncoding=UTF-8&serverTimezone=Asia/Seoul' \
DOCUMENT_STORAGE_ROOT="$(cd .. && pwd)/backend/build/ajt-demo-documents" \
sh gradlew bootRun
```

## 되돌리기

```bash
docker exec ajt-mysql bash -c 'mysql -uroot -p$MYSQL_ROOT_PASSWORD -e "DROP DATABASE ajt_demo"'
rm -rf backend/build/ajt-demo-documents
```
````

- [ ] **Step 7: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add ai/tools/demo-data/clone.sh ai/tools/demo-data/README.md
git commit -m "chore(ai): 시연 데이터 복제 스크립트를 추가한다

로컬 ajt 와 build/ajt-documents 를 ajt_demo·build/ajt-demo-documents 로 복제한다.
원본은 읽기만 하므로 되돌리기가 스키마 DROP 과 디렉터리 삭제로 끝난다."
```

---

### Task 2: 정합성 점검 스크립트

**Files:**
- Create: `ai/tools/demo-data/check.sh`

**Interfaces:**
- Consumes: Task 1 이 만든 `ajt_demo` 와 `backend/build/ajt-demo-documents`
- Produces: `check.sh` — 문제가 없으면 종료코드 0, 있으면 1. Task 3·4 가 변경 후 이것으로 검증한다.

이 태스크가 먼저인 이유: 지금 돌리면 **잔재가 검출되어 실패해야 한다.** 그 실패가 Task 3 의 목표를 정의한다.

**검사와 지표를 가른 기준**: 고아 문서(참조가 빈 문서)는 결함이 아니라 **지표**다 — 2단계에서 문서를 올리고 아직 변환하지 않은 동안은 정상적으로 고아다. 결함으로 잡을 것은 **같은 스코프에 같은 파일명이 둘 이상인 중복 업로드**다. prod 의 문서 34·35 가 이 검사에 걸린다.

- [ ] **Step 1: `check.sh` 를 작성한다**

> ⚠️ **정본은 `ai/tools/demo-data/check.sh` 다.** 아래는 작성 당시 초안이고, 리뷰에서 여러
> 결함이 드러났다. **그대로 복사해 실행하지 않는다** — 조회가 실패해도 "결과 없음 = 통과"로
> 오판할 수 있다.

초안은 검사 4종(위키→문서·양방향 일치·중복 업로드·근거 없는 위키)에 고아 페이지 파일·
각주 인용처 검사를 더한 6종이었다. 실제 `check.sh` 는 **검사 7종 + 지표 6개**다.

| 항목 | 이 초안 | 실제 `check.sh` |
| --- | --- | --- |
| `q()` 오류 처리 | `2>/dev/null` 로 stderr 를 버림 — SQL 이 깨져도 "결과 없음"으로 통과 | stderr 를 파일로 받아 `Using a password` 경고만 걸러내고, 나머지는 `QUERY_ERROR:` 마커로 실어 호출부가 `report`/`metric` 에서 FAIL 처리 |
| SQL 문자열 이스케이프 | 없음 — `wiki_path`·`original_file_name` 값에 작은따옴표가 있으면 SQL 이 깨짐 | `sql_escape()` 로 `'` → `''` 처리 후 삽입 |
| 검사 3 (중복 업로드) | 파일명만 묶음 — 크기가 다른 두 판본을 오검출 | `file_size` 까지 묶음 조건에 넣음 (`03-service-rules-amendment.md` 의 1033B/3440B 두 판본을 구분) |
| 검사 순서·개수 | 4종 + 고아 페이지 파일 + 각주 인용처 = 6종 | 위키→문서, **양방향 일치 두 방향**(문서→위키 정참조 + `LEFT JOIN` 으로 위키가 이미 삭제된 역방향 dangling까지), 중복 업로드, 근거 없는 위키, 고아 페이지 파일, 각주 인용처 = **7종** |
| 파일 루트 가드 | 없음 | `$FILES` 디렉터리가 없으면 즉시 종료 |
| 지표 | 위키·문서·계정·아웃링크·고아 문서 5개 | 위 5개 + `ai_job.document_ids` 가 이미 삭제된 문서를 가리키는 **끊긴 ai_job 참조** 건수. 지표 조회 자체가 실패하면 공란이 아니라 `오류(...)` 를 찍고 FAIL 로 잡음 |

역방향 dangling 검사가 필요한 이유: 문서→위키 참조를 검사할 때 `INNER JOIN wiki` 만 쓰면
Task 3(`cleanup.sh`)이 위키 14·15 를 지운 뒤 그 참조를 가리키던 문서 행이 있어도 조인에서
행 자체가 사라져 검출되지 않는다. `LEFT JOIN` 으로 봐야 "문서가 가리키는 위키가 실재한다"를
검증할 수 있다.

- [ ] **Step 2: 실행 권한을 주고 돌린다 — 지금은 실패해야 한다**

Run:
```bash
cd ai && chmod +x tools/demo-data/check.sh && ./tools/demo-data/check.sh; echo "종료코드: $?"
```

Expected: 종료코드 1. 아래가 검출된다.
- `FAIL 중복 업로드가 없다` — `01-service-rules-v1.docx (37755B) x7` 등. `03-service-rules-amendment.md` 는 크기가 다른 두 판본이 섞여 있어 `1033B x3 (6,14,15)` 한 묶음으로만 나온다 — `3440B` 는 42 한 건뿐이라 중복으로 세어지지 않는다
- `FAIL 근거 없는 위키가 없다` — 위키 14 `[샘플] 취업규칙 위키`, 위키 15 `[샘플] 복지제도 위키`
- `FAIL 고아 페이지 파일이 없다` — `wiki/ALL/pages/1.md`·`2.md`·`6.md`·`7.md`
- 지표: `위키 18 · 문서 53 · 계정 13 · 고아 문서 26`

검사 1(위키→문서)·2(양방향 정참조)·3(문서→위키 역방향)·7(각주 인용처)은 통과해야 한다 — 로컬은 양방향이 이미 맞고, 각주가 인용하는 18종 파일명도 모두 실재한다. 여기서 실패하면 원본이 이 계획 작성 시점과 다른 것이므로 보고한다.

아무것도 검출되지 않으면 `JSON_TABLE` 경로 이스케이프(`\\\$`)를 의심한다 — bash 와 `docker exec` 를 거치며 `$` 가 두 번 소비된다.

- [ ] **Step 3: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add ai/tools/demo-data/check.sh
git commit -m "chore(ai): 시연 데이터 정합성 점검 스크립트를 추가한다

양방향 참조·중복 업로드·근거 없는 위키·고아 페이지 파일·각주 인용처를 검사한다.
검사 항목은 모두 prod 과 로컬에서 실제로 발견된 사고에서 나왔다.

각주는 문서를 ID 가 아니라 파일명으로 인용하므로, 중복 사본을 지울 때 그 파일명이
하나도 안 남으면 근거가 끊긴다. 검사 7이 그것을 잡는다.

고아 문서는 결함이 아니라 지표로 둔다 — 올리고 아직 변환하지 않은 문서는
정상적으로 참조가 비어 있다."
```

---

### Task 3: QA 잔재 제거

**Files:**
- Create: `ai/tools/demo-data/cleanup.sh`
- Modify: `backend/build/ajt-demo-documents/wiki/ALL/index.md` (스크립트가 수행)

**Interfaces:**
- Consumes: Task 1 의 `ajt_demo`, Task 2 의 `check.sh`
- Produces: Task 2 의 검사 7종을 통과하는 `ajt_demo` (위키 16 · 문서 21)

**각주 안전성은 확인했다.** 각주가 인용하는 파일명 18종은 정제 후에도 모두 남는다. 중복 그룹마다 위키가 쓰는 사본이 살아남고, `01-training.md` 는 `ALL` 사본이 지워져도 `D1` 사본이 남는다(스코프가 달라 중복이 아니다). 검사 7이 이를 자동으로 확인한다.

제거 대상은 이 문서 위쪽 「조사로 확정된 제거 대상」 절에 있다. 스크립트는 ID 를 하드코딩하지 않고 **조회로 찾는다** — 고아 문서는 `document_wiki_refs` 가 비었다는 성질로 정의되므로, 목록을 박아두면 데이터가 조금만 달라져도 어긋난다. 대신 개수를 검증해 예상과 다르면 멈춘다.

- [ ] **Step 1: `cleanup.sh` 를 작성한다**

> ⚠️ **정본은 `ai/tools/demo-data/cleanup.sh` 다.** 아래는 작성 당시 초안이며, 리뷰로 고친
> 실패 판정 결함이 반영돼 있지 않다. **그대로 복사해 실행하지 않는다** — `run()` 이 실패를
> 삼켜서 SQL 이 중간에 깨져도 스크립트가 `== 완료` 를 찍고 넘어갈 수 있다.

8단계 구성(대상 조회 → 샘플 위키 제거 → 위키 참조 정리 → 문서 행 제거 → 중복 사본 통합 →
문서 소스 파일 제거 → 페이지 파일 제거 → 목차 정리)과 각 단계가 지우는 ID 는 초안과 실제가
같다. 달라진 것은 **오류를 조용히 삼키던 지점들**이다.

| 항목 | 이 초안 | 실제 `cleanup.sh` |
| --- | --- | --- |
| `q()` (조회) | `2>/dev/null` 로 stderr 를 버림 | `check.sh` 와 같은 `QUERY_ERROR:` 마커 방식. 지금은 `ORPHAN_N -ne 26` 가드에 우연히 걸려 안전하지만, 다른 곳에 재사용하면 조회 실패가 "빈 결과"로 묻힌다 |
| `run()` (DDL/DML 실행) | `... \| grep -v ... \|\| true` 로 종료코드를 완전히 버림 — SQL 이 실패해도 다음 단계로 진행 | `docker exec` 종료코드를 `if out="$(...)"` 로 캡처해 실패 시 `SQL 실행 실패 (종료 코드 $rc): $sql` 를 찍고 즉시 `exit` |
| 소스 디렉터리 삭제(6단계) | `find ... -exec rm -rf {} + 2>/dev/null \|\| true` — `rm -rf` 가 권한 등으로 실패해도 무시 | 각 디렉터리를 개별적으로 `rm -rf` 하고 실패를 `RM_FAIL` 로 집계, 1건이라도 있으면 `문서 소스 삭제 실패 N건 — 고아 파일이 남았을 수 있다` 로 종료 |
| DB 가드 | `ajt`·`ajt_prod` 만 확인 | `$SRC_DB` (기본 `ajt`)와 같은 경우까지 확인 |

`run()` 이 실패를 삼키면 왜 위험한가: 예를 들어 4단계 `DELETE FROM document ...` 가 FK 제약에
걸려 실패해도 초안은 그대로 5·6·7단계로 넘어가 DB 행은 남았는데 파일만 지워진 상태가 된다.
이런 반쪽 실패는 `check.sh` 가 다음에 잡아주긴 하지만, `cleanup.sh` 자체가 "여기서 멈췄다"고
말해주는 것과 "끝까지 돌고 나서야 안다"의 차이는 크다.

- [ ] **Step 2: 실행 권한을 주고 돌린다**

Run:
```bash
cd ai && chmod +x tools/demo-data/cleanup.sh && ./tools/demo-data/cleanup.sh
```

Expected: `고아 문서 26건: 4 5 6 7 8 9 10 11 14 16 20 21 22 26 27 29 30 31 34 35 38 39 40 43 51 52` 가 찍히고 `== 완료` 로 끝난다.

`고아 문서가 예상(26건)과 다르다` 로 멈추면 원본이 바뀐 것이다 — 목록을 보고하고 임의로 진행하지 않는다.

- [ ] **Step 3: 점검 스크립트가 통과하는지 확인한다**

Run:
```bash
./tools/demo-data/check.sh; echo "종료코드: $?"
```

Expected: 종료코드 0, `== 통과`. 지표는 `위키 16 · 문서 21 · 계정 13 · 아웃링크 12 · 고아 문서 0`.

- [ ] **Step 4: 목차와 파일을 눈으로 확인한다**

Run:
```bash
grep -n "샘플" backend/build/ajt-demo-documents/wiki/ALL/index.md || echo "샘플 줄 없음"
ls backend/build/ajt-demo-documents/wiki/ALL/pages/ | head -30
```

Expected: `샘플 줄 없음`, 그리고 `pages/` 에 숫자 이름(`1.md`·`14.md` 등)이 하나도 없고 해시 이름만 남는다.

- [ ] **Step 5: 남긴 관리자 지시가 살아 있는지 확인한다**

Run:
```bash
docker exec ajt-mysql bash -c 'mysql --default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD -D ajt_demo -e "SELECT document_id, original_file_name, document_wiki_refs FROM document WHERE document_id IN (45, 57, 63)"'
```

Expected: 3행 모두 남아 있고 참조가 비어 있지 않다.

- [ ] **Step 6: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add ai/tools/demo-data/cleanup.sh
git commit -m "chore(ai): 시연 데이터에서 QA 잔재를 제거하는 스크립트를 추가한다

고아 문서 26건(반복 업로드 20·변환 실패 3·샘플 시드 3)과 인용 없는 관리자 지시
4건을 DB 와 파일에서 함께 지운다. 샘플 위키 2건과 고아 페이지 파일 4개도 목차
줄까지 정리한다.

위키 각주가 인용하는 관리자 지시 45·57·63 은 남기고, 문서를 지우면서 위키
11·20·24 의 document_refs 를 함께 맞춘다."
```

---

### Task 4: 조직 재구성

**Files:**
- Create: `ai/tools/demo-data/org.sql`
- Create: `ai/tools/demo-data/org.sh`

**Interfaces:**
- Consumes: Task 3 을 마친 `ajt_demo`
- Produces: 총관리자 1 + 3부서 × (관리자 1 + 사원 4) = 계정 16

| 부서 | 스코프 | 관리자 | 사원 4 |
| --- | --- | --- | --- |
| 개발부 | D1 | 관리자(1) | 홍길동(2) + 신규 3 |
| 디자인부 | D3 | 박디자인(5) | 최디자인(6) + 신규 3 |
| 인사부 | D4 | 정인사(7) | 강인사(8) + 신규 3 |
| 최고관리자 | — | 최고관리자(9) | — |

기획부(D2)는 제거한다 — 문서·위키·작업·카테고리가 모두 0건이고 `scope_version` 도 0이라 쓰인 적이 없다.

제거 계정은 김기획(3)·이기획(4)·신입일(10)·신입이(11)·거절일(12)·거절이(13) 6명이다. **이기획(4)만 `ai_question` 2건을 갖고 있어** 연쇄 삭제가 필요하다. 나머지는 `schedule.author_id`·`inquiry`·`inquiry_reply`·`wiki_chat_message` 어디서도 참조되지 않는 것을 확인했다.

- [ ] **Step 1: `org.sql` 을 작성한다**

```sql
-- 조직을 시연 형태로 재구성한다. ajt_demo 에서만 실행한다.
-- 목표: 총관리자 1 + 개발부·디자인부·인사부 각 (관리자 1 + 사원 4) = 16명

-- 안전장치는 SQL 이 아니라 org.sh 에 있다. MySQL 은 IF() 의 미평가 분기도 파싱 시점에
-- 식별자를 해석하므로 `IF(DATABASE()='ajt_demo','ok',THROW_ERROR)` 는 올바른 DB 에서도
-- Unknown column 으로 죽는다 (MySQL 8.4 에서 실측). 그래서 clone.sh·cleanup.sh 와 같이
-- bash 쪽에서 대상 DB 를 막는다.

-- 1) 이기획(4)의 챗봇 질문·답변을 먼저 지운다 (FK 역순)
DELETE FROM answer_source
 WHERE ai_answer_id IN (
   SELECT ai_answer_id FROM ai_answer
    WHERE ai_question_id IN (SELECT ai_question_id FROM ai_question WHERE member_id = 4));
DELETE FROM ai_answer
 WHERE ai_question_id IN (SELECT ai_question_id FROM ai_question WHERE member_id = 4);
DELETE FROM ai_question WHERE member_id = 4;

-- 2) 부서장 참조를 끊고 계정을 지운다
UPDATE department SET manager_id = NULL WHERE department_id = 2;
DELETE FROM member WHERE member_id IN (3, 4, 10, 11, 12, 13);

-- 3) 기획부와 D2 스코프를 지운다 (문서·위키·작업 0건)
DELETE FROM document_category WHERE scope_key = 'D2';
DELETE FROM department WHERE department_id = 2;
DELETE FROM wiki_scope WHERE scope_key = 'D2';

-- 4) 최고관리자를 전용 부서로 옮긴다 (지금은 개발부 소속이다)
UPDATE member SET department_id = 6 WHERE member_id = 9;

-- 5) 부서마다 사원 3명씩 추가한다.
--    비밀번호 해시는 홍길동(2)의 것을 복사해 같은 비밀번호로 로그인된다.
INSERT INTO member (department_id, email, name, password_hash, employee_no, role, signup_status, account_status)
SELECT 1, 'dev.kim@ajt.com',     '김서준', password_hash, 'AJT-2026-0101', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 1, 'dev.lee@ajt.com',     '이하은', password_hash, 'AJT-2026-0102', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 1, 'dev.park@ajt.com',    '박도윤', password_hash, 'AJT-2026-0103', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 3, 'design.jung@ajt.com', '정예린', password_hash, 'AJT-2026-0301', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 3, 'design.kang@ajt.com', '강민서', password_hash, 'AJT-2026-0302', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 3, 'design.cho@ajt.com',  '조하늘', password_hash, 'AJT-2026-0303', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 4, 'hr.yoon@ajt.com',     '윤지호', password_hash, 'AJT-2026-0401', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 4, 'hr.lim@ajt.com',      '임채원', password_hash, 'AJT-2026-0402', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 4, 'hr.seo@ajt.com',      '서다인', password_hash, 'AJT-2026-0403', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2;
```

- [ ] **Step 1b: `org.sh` 를 작성한다**

`org.sql` 을 직접 파이프하지 않고 이 래퍼로 적용한다. 가드와 실패 판정이 여기 있다.

> ⚠️ **정본은 `ai/tools/demo-data/org.sh` 다.** 아래는 작성 당시 초안이고, 재실행 가드가
> 빠져 있다. **그대로 복사해 실행하지 않는다** — 이미 적용된 `ajt_demo` 에 다시 돌리면
> `member.email` UNIQUE 제약 위반으로 죽으면서도 1~4번(DELETE·UPDATE)은 이미 반영된
> 상태라 무엇이 성공했는지 헷갈린다.

| 항목 | 이 초안 | 실제 `org.sh` |
| --- | --- | --- |
| DB 가드 | `ajt`·`ajt_prod` 만 확인 | `$SRC_DB` (기본 `ajt`)와 같은 경우까지 확인 |
| 재실행 가드 | 없음 | `dev.kim@ajt.com` 존재 여부로 이미 적용됐는지 먼저 확인하고, 있으면 "이미 적용된 상태다 — 재실행할 수 없다"며 처음부터 다시 만들라고 안내한 뒤 종료 |

재실행 가드가 필요한 이유는 `org.sql` 쪽에 있다. 1~4번(DELETE·UPDATE)은 두 번째 실행에서
no-op 이 되지만, 5번 INSERT 는 `member.email`·`member.employee_no` 의 UNIQUE 제약에 걸려
중복 키 오류로 죽는다. 다시 하려면 `org.sql` 을 고치지 말고 `clone.sh` 로 `ajt_demo` 를
원본에서 새로 복제 → `cleanup.sh` → `org.sh` 순서로 처음부터 다시 만든다.

- [ ] **Step 2: 실행한다**

Run:
```bash
cd ai && chmod +x tools/demo-data/org.sh && ./tools/demo-data/org.sh
```

Expected: `== 완료`. FK 오류가 나면 삭제 순서(1→2→3)를 지켰는지 본다.

가드도 확인한다 — `DST_DB=ajt ./tools/demo-data/org.sh` 가 아무것도 바꾸지 않고 오류로 끝나야 하고, 그 뒤 `ajt` 가 여전히 위키 18 · 문서 53 · 계정 13 이어야 한다.

- [ ] **Step 3: 부서별 인원을 확인한다**

Run:
```bash
docker exec ajt-mysql bash -c 'mysql --default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD -D ajt_demo -e "SELECT d.name 부서, m.role, COUNT(*) n FROM member m JOIN department d ON d.department_id = m.department_id GROUP BY d.name, m.role ORDER BY d.name, m.role"'
```

Expected:
```
개발부       ADMIN     1
개발부       EMPLOYEE  4
디자인부     ADMIN     1
디자인부     EMPLOYEE  4
인사부       ADMIN     1
인사부       EMPLOYEE  4
최고관리자   ADMIN     1
```

- [ ] **Step 4: 총원과 스코프를 확인한다**

Run:
```bash
docker exec ajt-mysql bash -c 'mysql --default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD -D ajt_demo -e "SELECT COUNT(*) 총원 FROM member; SELECT scope_key FROM wiki_scope ORDER BY scope_key"'
```

Expected: `총원 16`, 스코프는 `ALL`·`D1`·`D3`·`D4` 네 개(`D2` 없음).

- [ ] **Step 5: 점검 스크립트가 여전히 통과하는지 확인한다**

Run:
```bash
./tools/demo-data/check.sh; echo "종료코드: $?"
```

Expected: 종료코드 0. 지표는 `위키 16 · 문서 21 · 계정 16 · 아웃링크 12 · 고아 문서 0`.

- [ ] **Step 6: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add ai/tools/demo-data/org.sql ai/tools/demo-data/org.sh
git commit -m "chore(ai): 시연용 조직 재구성 스크립트를 추가한다

총관리자 1명과 개발부·디자인부·인사부 각 관리자 1명·사원 4명으로 16명을 맞춘다.
기획부와 D2 스코프는 문서·위키·작업이 0건이라 제거한다.

가입 승인 테스트 계정은 뺀다. 시연에 승인 흐름이 들어가면 다시 넣는다."
```

---

### Task 5: 실기동 확인

**Files:**
- Modify: `ai/tools/demo-data/README.md` (확인 결과 절 추가)

**Interfaces:**
- Consumes: Task 1~4 를 마친 `ajt_demo` 와 `backend/build/ajt-demo-documents`
- Produces: 화면에서 동작이 확인된 시연 환경

`check.sh` 는 DB 와 파일의 정합성만 본다. 실제로 화면에 뜨는지는 다른 문제다 — 띄워서 본다.

- [ ] **Step 1: 백엔드를 복제본에 물려 띄운다**

Run:
```bash
cd backend && SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/ajt_demo?characterEncoding=UTF-8&serverTimezone=Asia/Seoul' \
  DOCUMENT_STORAGE_ROOT="$(cd .. && pwd)/backend/build/ajt-demo-documents" \
  sh gradlew bootRun
```

Expected: 기동 성공. `Unknown database` 가 나면 Task 1 이 안 돌았다.

DB 사용자·비밀번호가 필요하면 로컬에서 쓰던 값을 그대로 준다 — 이 계획은 그 값을 문서에 적지 않는다.

- [ ] **Step 2: 위키 목록이 16건 뜨는지 본다**

`admin@ajt.com` 으로 로그인해 위키 목록을 연다 (비밀번호는 기존 로컬 계정과 같다).

Expected: 16건. `[샘플]` 로 시작하는 항목이 없다.

- [ ] **Step 3: 문서 목록에 잔재가 없는지 본다**

문서 목록을 연다.

Expected: 21건. `03-service-rules-amendment.md` 두 판본(1033B·3440B)을 빼면 같은 파일명이 두 번 나오지 않고, `FAILED` 상태 문서와 `[샘플] *` 문서가 없다.

- [ ] **Step 4: 링크가 실제로 걸리는지 본다**

「연차유급휴가 규정」 페이지를 연다.

Expected: 본문의 「2024년 3월 인사위원회 주요 의결 사항」과 「경조사 지원 기준」이 링크로 걸려 눌러서 이동되고, 관련 위키 목록에도 나타난다.

깨져 있으면 `wiki_refs` 와 본문 링크가 어긋난 것이므로 **보고한다** — 임의로 DB 를 고치지 않는다.

- [ ] **Step 5: 관리자 지시 각주가 살아 있는지 본다**

「주식 옵션(Equity) 안내」(위키 10)와 「공통 프로젝트 산출물 제출 안내」(위키 20)를 연다.

Expected: 각주에 `관리자지시-*.md` 인용이 남아 있고 근거 문서 목록에서 열린다. Task 3 에서 남기기로 한 45·57·63 이다.

- [ ] **Step 6: 결과를 README 에 적는다**

`ai/tools/demo-data/README.md` 끝에 아래 절을 추가한다. **실제로 확인한 것만 적고, 확인하지 못한 항목은 그렇게 적는다.**

```markdown
## 확인 (2026-08-08)

- `check.sh` 통과 — 위키 16 · 문서 21 · 계정 16 · 아웃링크 12
- 백엔드를 `ajt_demo` 에 물려 기동, 위키 목록 16건·문서 목록 21건 확인
- 「연차유급휴가 규정」의 위키 링크 2개가 화면에서 동작
- 관리자 지시 각주(45·57·63)가 근거 문서로 열림

AI 서버·프론트까지 묶은 전 구간 확인은 아직 하지 않았다.
```

- [ ] **Step 7: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add ai/tools/demo-data/README.md
git commit -m "docs(ai): 시연 데이터 실기동 확인 결과를 적는다

백엔드를 ajt_demo 에 물려 띄워 위키 목록·문서 목록·링크·각주가 화면에서 동작하는
것을 확인했다. 확인하지 못한 범위도 함께 적었다."
```

---

## 다음 단계

이 계획은 1단계(격리·정제·조직)까지다. 2단계는 별도 설계가 필요하다.

- PostHog 한국어판 문서를 추가해 위키를 늘린다
- **적재 순서를 나눈다** — 토대 문서를 먼저 올려 위키를 만들고, 그것을 참조할 문서를 나중에 올린다. 한 작업에 몰아넣으면 아직 없는 페이지를 가리킬 주소가 없어 링크가 붙지 않는다
- 매 적재마다 `check.sh` 의 아웃링크 지표를 기록해 링크가 실제로 느는지 본다 (1단계 종료 시점 12)
