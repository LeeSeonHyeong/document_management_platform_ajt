# ALL Wiki Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the explicit administrator bootstrap so a freshly reset deployment also receives the `ALL` Wiki scope and one usable `일반` document category without any destructive SQL.

**Architecture:** Keep server startup behavior unchanged and add the Wiki seed statements to the existing `scripts/bootstrap-admin.sh` transaction. The script remains an operator-triggered boundary, reads the deployed super-admin email, and creates only missing system rows. Shell behavior tests capture the generated SQL before implementation and the deployment runbook verifies the complete end state.

**Tech Stack:** Bash, Docker Compose, MySQL 8.4, Git Bash test harness, Markdown runbook

## Global Constraints

- Do not add an application-startup Wiki initializer.
- Do not change `docs/db/erd.sql` or add a new role.
- Do not use `DELETE`, `TRUNCATE`, or `DROP` in the bootstrap script.
- Preserve the existing `SUPER_ADMIN_EMAIL` validation and random BCrypt password behavior.
- Create `wiki_scope ALL` with `visibility_type=ALL`, `department_refs=JSON_ARRAY()`, `index_path=wiki/ALL/index.md`, and `scope_version=0` only when missing.
- Create one `document_category` named `일반` under `scope_key=ALL` only when missing.
- Do not create `document`, `wiki`, or `ai_job` rows.
- The final fresh state is departments 2 (`전체`, `최고관리자`), members 1, Wiki scopes 1, document categories 1, documents 0, Wikis 0, and AI jobs 0.

---

### Task 1: Lock the complete bootstrap SQL contract

**Files:**
- Modify: `scripts/tests/bootstrap-admin-test.sh`
- Test: `scripts/tests/bootstrap-admin-test.sh`

**Interfaces:**
- Consumes: generated SQL written by the fake `docker compose exec -T mysql` implementation
- Produces: regression assertions for the `ALL` scope, `일반` category, single transaction, and absence of destructive SQL

- [ ] **Step 1: Add failing success-case assertions**

Extend `test_creates_one_admin_with_random_password` so the captured SQL must contain:

```bash
normalized_sql="$(tr -d '[:space:]' < "$FAKE_DOCKER_LOG")"
grep -q "INSERT INTO wiki_scope" "$FAKE_DOCKER_LOG" \
  || fail "ALL Wiki 공간 INSERT가 없음"
grep -q "VALUES('ALL','ALL',JSON_ARRAY(),'wiki/ALL/index.md',0)" <<< "$normalized_sql" \
  || fail "ALL Wiki 공간 값이 잘못됨"
grep -q "INSERT INTO document_category" "$FAKE_DOCKER_LOG" \
  || fail "ALL 기본 문서 카테고리 INSERT가 없음"
grep -q "VALUES('ALL','일반','전체공개문서기본카테고리')" <<< "$normalized_sql" \
  || fail "ALL 기본 문서 카테고리 값이 잘못됨"
```

Add transaction and destructive-statement assertions:

```bash
assert_equals "1" "$(grep -c '^START TRANSACTION;' "$FAKE_DOCKER_LOG")" "트랜잭션 시작 횟수"
assert_equals "1" "$(grep -c '^COMMIT;' "$FAKE_DOCKER_LOG")" "트랜잭션 커밋 횟수"
if grep -Eiq '(^|[[:space:];])(DELETE|TRUNCATE|DROP)([[:space:]]|$)' "$FAKE_DOCKER_LOG"; then
  fail "부트스트랩 SQL에 파괴적 명령이 포함됨"
fi
```

- [ ] **Step 2: Run the test and verify RED**

Run from Git Bash:

```bash
bash scripts/tests/bootstrap-admin-test.sh
```

Expected: FAIL with `ALL Wiki 공간 INSERT가 없음` because the current bootstrap inserts only `department` and `member`.

- [ ] **Step 3: Commit only after Task 2 turns the test green**

Do not commit the intentionally failing state. Keep the test change staged only together with the minimal implementation in Task 2.

---

### Task 2: Seed ALL scope and default category in the explicit transaction

**Files:**
- Modify: `scripts/bootstrap-admin.sh`
- Modify: `scripts/tests/bootstrap-admin-test.sh`
- Test: `scripts/tests/bootstrap-admin-test.sh`

**Interfaces:**
- Consumes: the existing `mysql_exec` function and the successful fresh-admin path
- Produces: one transaction that inserts the nominal department, super-admin member, `ALL` Wiki scope, and `일반` document category

- [ ] **Step 1: Add minimal idempotent scope SQL before `COMMIT`**

Append the following inside the existing `mysql_exec` transaction after the member insert:

```sql
INSERT INTO wiki_scope (
  scope_key,
  visibility_type,
  department_refs,
  index_path,
  scope_version
)
VALUES (
  'ALL',
  'ALL',
  JSON_ARRAY(),
  'wiki/ALL/index.md',
  0
)
ON DUPLICATE KEY UPDATE scope_key = 'ALL';
```

- [ ] **Step 2: Add minimal idempotent category SQL before `COMMIT`**

Append immediately after the scope insert:

```sql
INSERT INTO document_category (
  scope_key,
  name,
  description
)
VALUES (
  'ALL',
  '일반',
  '전체 공개 문서 기본 카테고리'
)
ON DUPLICATE KEY UPDATE document_category_id = LAST_INSERT_ID(document_category_id);
```

- [ ] **Step 3: Run syntax and behavior tests and verify GREEN**

Run:

```bash
bash -n scripts/bootstrap-admin.sh
bash scripts/tests/bootstrap-admin-test.sh
```

Expected: both exit `0`; behavior test prints `PASS: bootstrap-admin.sh behavior`.

- [ ] **Step 4: Commit the executable change**

```bash
git add scripts/bootstrap-admin.sh scripts/tests/bootstrap-admin-test.sh
git commit -m "fix(infra): ALL Wiki 초기 데이터를 부트스트랩한다 [S15P11B106-185]"
```

---

### Task 3: Correct the reset verification contract

**Files:**
- Modify: `docs/infra/deployment-runbook.md`
- Modify: `docs/superpowers/plans/2026-08-03-super-admin-bootstrap.md`

**Interfaces:**
- Consumes: the post-deployment state created by backend startup plus `scripts/bootstrap-admin.sh`
- Produces: operator queries and expected counts matching the real fresh deployment

- [ ] **Step 1: Expand the runbook verification query**

Keep the member join and add exact counts:

```sql
SELECT COUNT(*) AS member_count FROM member;
SELECT COUNT(*) AS department_count FROM department;
SELECT COUNT(*) AS wiki_scope_count FROM wiki_scope;
SELECT COUNT(*) AS document_category_count FROM document_category;
SELECT COUNT(*) AS document_count FROM document;
SELECT COUNT(*) AS wiki_count FROM wiki;
SELECT COUNT(*) AS ai_job_count FROM ai_job;
SELECT scope_key, visibility_type, department_refs, index_path, scope_version
FROM wiki_scope;
SELECT scope_key, name, description
FROM document_category;
```

- [ ] **Step 2: Correct expected results**

Document this exact target:

```text
member_count=1
department_count=2
wiki_scope_count=1
document_category_count=1
document_count=0
wiki_count=0
ai_job_count=0
departments=전체,최고관리자
scope_key=ALL
visibility_type=ALL
department_refs=[]
index_path=wiki/ALL/index.md
scope_version=0
category_scope_key=ALL
category_name=일반
```

Update the earlier implementation plan's Task 5 assertion from `department=1` to the same complete count contract.

- [ ] **Step 3: Check documentation consistency**

Run:

```bash
git diff --check
rg -n "department_count=2|wiki_scope_count=1|document_category_count=1|category_name=일반" \
  docs/infra/deployment-runbook.md \
  docs/superpowers/plans/2026-08-03-super-admin-bootstrap.md
```

Expected: no whitespace errors and every target term is present.

- [ ] **Step 4: Commit documentation**

```bash
git add docs/infra/deployment-runbook.md docs/superpowers/plans/2026-08-03-super-admin-bootstrap.md
git commit -m "docs(infra): 초기 DB 검증 기준을 보완한다 [S15P11B106-185]"
```

---

### Task 4: Final verification and push readiness

**Files:**
- Verify: `scripts/bootstrap-admin.sh`
- Verify: `scripts/tests/bootstrap-admin-test.sh`
- Verify: `docs/infra/deployment-runbook.md`
- Verify: `docs/superpowers/specs/2026-08-03-super-admin-bootstrap-design.md`

**Interfaces:**
- Consumes: all prior task outputs
- Produces: merge-ready evidence without contacting or mutating a real database

- [ ] **Step 1: Run final automated checks**

Run from Git Bash:

```bash
bash -n scripts/bootstrap-admin.sh
bash scripts/tests/bootstrap-admin-test.sh
git diff --check origin/develop...HEAD
```

Expected: all commands exit `0` and the shell test prints `PASS: bootstrap-admin.sh behavior`.

- [ ] **Step 2: Inspect the complete change set**

Run:

```bash
git status --short
git log --oneline origin/develop..HEAD
git diff --stat origin/develop...HEAD
```

Expected: only the design, implementation plan, bootstrap script/test, runbook, and corrected earlier plan differ from develop.

- [ ] **Step 3: Commit this implementation plan with the final documentation commit if still uncommitted**

```bash
git add docs/superpowers/plans/2026-08-03-all-wiki-bootstrap.md
git commit -m "docs(infra): ALL Wiki 부트스트랩 구현 계획 추가 [S15P11B106-185]"
```

- [ ] **Step 4: Push only after all checks pass**

```bash
git push -u origin fix/S15P11B106-185-all-wiki-bootstrap
```
