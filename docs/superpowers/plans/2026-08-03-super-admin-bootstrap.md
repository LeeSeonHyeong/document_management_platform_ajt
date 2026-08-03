# Super Admin Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a freshly reset develop deployment create exactly one active super administrator in the `최고관리자` department using the same email configured in the running backend.

**Architecture:** Keep the existing email-based `SuperAdminChecker` and ERD unchanged. Strengthen the bootstrap boundary so it reads the deployed backend's `SUPER_ADMIN_EMAIL`, rejects conflicting input, creates the dedicated department, and documents a destructive reset scoped only to `ajt-develop`.

**Tech Stack:** Bash, Docker Compose, MySQL 8.4, Spring Boot/Gradle, Jenkins

## Global Constraints

- Do not change `docs/db/erd.sql` or add a `SUPER_ADMIN` role.
- The created member must be `ADMIN`, `APPROVED`, and `ACTIVE`.
- The created member must belong to department `최고관리자`; that department's `manager_id` remains `NULL`.
- The member email must match the running backend container's `SUPER_ADMIN_EMAIL`.
- Destructive reset commands may target only `ajt-develop-mysql-data` and `ajt-develop-files` after their mounts are verified.
- Never stop or delete the 443 `s15p11b106` project or its volumes.

---

### Task 1: Lock the bootstrap contract with shell tests

**Files:**
- Modify: `scripts/tests/bootstrap-admin-test.sh`
- Test: `scripts/tests/bootstrap-admin-test.sh`

**Interfaces:**
- Consumes: `scripts/bootstrap-admin.sh [optional-admin-email]`
- Produces: fake Docker behavior for reading backend `SUPER_ADMIN_EMAIL` and assertions for the generated SQL

- [ ] **Step 1: Extend the fake Docker executable**

Make `docker compose ... exec -T backend ...` return `FAKE_SUPER_ADMIN_EMAIL`, defaulting to `superadmin@ajt.com`. Preserve the existing MySQL SQL capture behavior.

- [ ] **Step 2: Add failing behavior tests**

Add tests which assert:

```bash
FAKE_SUPER_ADMIN_EMAIL=superadmin@ajt.com bash "$BOOTSTRAP_SCRIPT"
```

uses `superadmin@ajt.com`, and:

```bash
FAKE_SUPER_ADMIN_EMAIL=superadmin@ajt.com \
  bash "$BOOTSTRAP_SCRIPT" admin@ajt.local
```

exits with code `2` before any `INSERT INTO member` statement.

Add a successful-case assertion that the captured SQL contains:

```sql
INSERT INTO department (name)
VALUES ('최고관리자')
```

- [ ] **Step 3: Run the test and confirm the new expectations fail**

Run:

```bash
bash scripts/tests/bootstrap-admin-test.sh
```

Expected: FAIL because the current script defaults to `admin@ajt.local`, does not read the backend setting, and inserts department `본사`.

### Task 2: Implement the synchronized bootstrap

**Files:**
- Modify: `scripts/bootstrap-admin.sh`
- Test: `scripts/tests/bootstrap-admin-test.sh`

**Interfaces:**
- Consumes: optional positional email, running backend `SUPER_ADMIN_EMAIL`, deployed MySQL service
- Produces: one dedicated department, one BCrypt-backed member, and a one-time random password

- [ ] **Step 1: Add a reusable Compose executor**

Factor the common Compose invocation into a function that preserves `DEPLOY_ENV_FILE`, `IMAGE_TAG`, `COMPOSE_PROJECT_NAME`, and `DEPLOY_COMPOSE_FILE`.

- [ ] **Step 2: Resolve and validate the authoritative email**

Read `SUPER_ADMIN_EMAIL` from the running backend container. If the caller omitted the positional email, use the configured value. If an email was supplied and differs case-insensitively, exit with code `2` before querying MySQL.

- [ ] **Step 3: Change the department seed**

Replace:

```sql
VALUES ('본사')
```

with:

```sql
VALUES ('최고관리자')
```

Do not update `department.manager_id`.

- [ ] **Step 4: Run syntax and behavior tests**

Run:

```bash
bash -n scripts/bootstrap-admin.sh
bash scripts/tests/bootstrap-admin-test.sh
```

Expected: both commands exit `0`; behavior test prints `PASS: bootstrap-admin.sh behavior`.

- [ ] **Step 5: Commit the bootstrap change**

```bash
git add scripts/bootstrap-admin.sh scripts/tests/bootstrap-admin-test.sh
git commit -m "fix(infra): 최고관리자 부트스트랩 설정 일치 보장"
```

### Task 3: Document the guarded develop reset

**Files:**
- Modify: `docs/infra/deployment-runbook.md`

**Interfaces:**
- Consumes: Compose project `ajt-develop`, Jenkins secret file `/var/lib/jenkins/ajt-secrets/develop.env`, state directory `/var/lib/jenkins/ajt-deploy/develop`
- Produces: repeatable operator commands that cannot accidentally target the 443 deployment

- [ ] **Step 1: Update the bootstrap command**

Document invoking `scripts/bootstrap-admin.sh` without an email argument so the script uses the deployed backend's authoritative `SUPER_ADMIN_EMAIL`.

- [ ] **Step 2: Add read-only preflight commands**

Document commands that print `ajt-develop` containers and mounts and verify the only deletion targets are:

```text
ajt-develop-mysql-data
ajt-develop-files
```

- [ ] **Step 3: Add exact reset and recreation commands**

Document `docker compose --project-name ajt-develop ... down`, explicit `docker volume rm` for the two verified names, Jenkins rebuild/approval, health checks, bootstrap, and final SQL assertions. Do not use `docker volume prune` or wildcard deletion.

- [ ] **Step 4: Check the documentation diff**

Run:

```bash
git diff --check
rg -n "ajt-develop-mysql-data|ajt-develop-files|bootstrap-admin.sh|SUPER_ADMIN_EMAIL" docs/infra/deployment-runbook.md
```

Expected: no whitespace errors and all reset guard terms are present.

### Task 4: Regression verification

**Files:**
- Verify: `backend/src/main/java/com/ajt/backend/domain/member/SuperAdminChecker.java`
- Verify: `backend/src/test/java/com/ajt/backend/domain/auth/AuthServiceTest.java`
- Verify: `backend/src/test/java/com/ajt/backend/domain/member/MemberServiceTest.java`
- Verify: `backend/src/test/java/com/ajt/backend/domain/department/DepartmentServiceTest.java`

**Interfaces:**
- Consumes: unchanged backend email-based super-admin policy
- Produces: evidence that department membership does not remove super-admin status

- [ ] **Step 1: Run focused backend tests**

From `backend/`, run:

```bash
./gradlew test \
  --tests 'com.ajt.backend.domain.auth.AuthServiceTest' \
  --tests 'com.ajt.backend.domain.member.MemberServiceTest' \
  --tests 'com.ajt.backend.domain.department.DepartmentServiceTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run final repository checks**

```bash
bash scripts/tests/bootstrap-admin-test.sh
git diff --check
git status --short
```

Expected: shell test passes, no whitespace errors, and only planned files are modified.

- [ ] **Step 3: Commit documentation**

```bash
git add docs/infra/deployment-runbook.md docs/superpowers/plans/2026-08-03-super-admin-bootstrap.md
git commit -m "docs(infra): develop 초기화 절차 추가"
```

### Task 5: Server execution after merge

**Files:**
- No repository files modified

**Interfaces:**
- Consumes: merged develop commit, Jenkins develop job, verified develop volumes
- Produces: empty develop storage plus one usable super-admin account

- [ ] **Step 1: Verify exact server targets**

Inspect Compose labels and mounts before any deletion. Stop if either target is not owned by project `ajt-develop`.

- [ ] **Step 2: Remove only the verified develop deployment and volumes**

Stop `ajt-develop`, remove `ajt-develop-mysql-data` and `ajt-develop-files` explicitly, and verify the 443 `s15p11b106` containers are still running.

- [ ] **Step 3: Rebuild and approve develop in Jenkins**

Build the latest develop commit, confirm tests and images succeed, then approve deployment to port 8090.

- [ ] **Step 4: Bootstrap and validate**

Run the bootstrap as `jenkins`, store the one-time password, and verify the complete fresh state: `member=1`, `department=2` (`전체`, `최고관리자`), `wiki_scope=1` (`ALL`), `document_category=1` (`ALL`/`일반`), `document=0`, `wiki=0`, and `ai_job=0`. Verify the super-admin department name is `최고관리자`, its `manager_id IS NULL`, and login returns `isSuperAdmin=true`.
