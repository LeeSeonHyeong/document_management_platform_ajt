# MySQL 8.4 Standalone Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `erdTable.sql` 한 파일로 `ajt` 데이터베이스와 확정된 16개 업무 테이블을 안전하게 생성할 수 있게 한다.

**Architecture:** 기존 `erdTable.sql`을 스키마 단일 원본으로 유지하고 파일 앞부분에 데이터베이스 생성·선택 구문만 추가한다. 기존 일관성 검사에 독립 Import 계약과 파괴적 SQL 금지 검사를 추가해 재발을 막는다.

**Tech Stack:** MySQL 8.4 LTS, SQL DDL, Node.js

## Global Constraints

- 데이터베이스 이름은 `ajt`다.
- 문자셋은 `utf8mb4`, collation은 `utf8mb4_0900_ai_ci`다.
- 기존 업무 테이블은 16개를 유지한다.
- 기존 컬럼, PK, FK, UNIQUE, CHECK와 인덱스를 변경하지 않는다.
- `DROP`, `TRUNCATE`, `DELETE FROM`, `INSERT INTO`를 추가하지 않는다.
- 기존 테이블을 무시하는 `CREATE TABLE IF NOT EXISTS`를 사용하지 않는다.
- 샘플 데이터와 최초 관리자 계정을 추가하지 않는다.
- 실제 사용자 MySQL 서버에는 자동으로 Import하지 않는다.
- 현재 작업 폴더는 Git 저장소가 아니므로 커밋 단계는 수행하지 않는다.

---

### Task 1: 독립 Import 계약 회귀 검사

**Files:**
- Modify: `scripts/validate-artifact-consistency.mjs`
- Test: `scripts/validate-artifact-consistency.mjs`

**Interfaces:**
- Consumes: `erdTable.sql` 원문 문자열 `sql`
- Produces: 주석을 제거한 SQL 문자열 `executableSql`과 Import 계약 실패 메시지

- [ ] **Step 1: SQL 주석 제거와 Import 계약 검사를 추가한다**

`const sql = read("erdTable.sql");` 다음에 실행 SQL만 검사할 문자열을 추가한다.

```js
const executableSql = sql
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/--.*$/gm, "");
```

기존 SQL 검증 시작 부분에 다음 검사를 추가한다.

```js
expect(
  /CREATE\s+DATABASE\s+IF\s+NOT\s+EXISTS\s+`ajt`\s+CHARACTER\s+SET\s+utf8mb4\s+COLLATE\s+utf8mb4_0900_ai_ci\s*;/i.test(
    executableSql,
  ),
  "SQL에 MySQL 8.4용 ajt 데이터베이스 생성 구문이 없음",
);
expect(
  /\bUSE\s+`ajt`\s*;/i.test(executableSql),
  "SQL에 USE ajt 구문이 없음",
);
expect(
  !/\b(?:DROP\s+(?:DATABASE|TABLE)|TRUNCATE(?:\s+TABLE)?|DELETE\s+FROM|INSERT\s+INTO)\b/i.test(
    executableSql,
  ),
  "SQL에 금지된 파괴적 구문 또는 초기 데이터 삽입이 있음",
);
expect(
  !/CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS/i.test(executableSql),
  "SQL 테이블 생성이 기존 테이블을 무시하도록 작성됨",
);
```

- [ ] **Step 2: 검사를 실행해 예상 실패를 확인한다**

Run:

```bash
node scripts/validate-artifact-consistency.mjs
```

Expected:

```text
Artifact consistency validation failed
- SQL에 MySQL 8.4용 ajt 데이터베이스 생성 구문이 없음
- SQL에 USE ajt 구문이 없음
```

금지 구문 검사는 통과해야 하며 기존 16개 테이블·53개 공개 API·6개 내부 API 검사는 새 실패를 만들지 않아야 한다.

---

### Task 2: `erdTable.sql` 독립 Import 진입점

**Files:**
- Modify: `erdTable.sql`
- Test: `scripts/validate-artifact-consistency.mjs`

**Interfaces:**
- Consumes: Task 1의 데이터베이스 생성·선택 회귀 검사
- Produces: 빈 MySQL 8.4 서버에서 직접 실행 가능한 `erdTable.sql`

- [ ] **Step 1: SQL 파일 헤더에 데이터베이스 생성·선택 구문을 추가한다**

기존 파일 설명 주석 다음, 첫 `CREATE TABLE` 전에 다음을 추가한다.

```sql
CREATE DATABASE IF NOT EXISTS `ajt`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `ajt`;
```

기존 `department`부터 `answer_source`까지의 DDL은 변경하지 않는다.

- [ ] **Step 2: 회귀 검사를 실행해 통과를 확인한다**

Run:

```bash
node scripts/validate-artifact-consistency.mjs
```

Expected:

```text
Artifact consistency validation passed: 16 tables, 53 public APIs, 6 internal APIs
```

- [ ] **Step 3: SQL 구조와 금지 구문을 별도로 확인한다**

Run:

```bash
rg -c '^CREATE TABLE' erdTable.sql
rg -n '^(DROP DATABASE|DROP TABLE|TRUNCATE|DELETE FROM|INSERT INTO|CREATE TABLE IF NOT EXISTS)' erdTable.sql
```

Expected:

```text
16
```

두 번째 명령은 검색 결과가 없어야 한다.

---

### Task 3: 최종 전달 검증

**Files:**
- Verify: `erdTable.sql`
- Verify: `scripts/validate-artifact-consistency.mjs`
- Verify: `erdTable-snapshot-ajt.json`

**Interfaces:**
- Consumes: Task 2의 독립 Import SQL
- Produces: 사용자에게 전달할 검증 결과와 실행 명령

- [ ] **Step 1: JavaScript 문법을 검사한다**

Run:

```bash
node --check scripts/validate-artifact-consistency.mjs
```

Expected: 종료 코드 0, 출력 없음.

- [ ] **Step 2: 전체 산출물 정합성을 다시 검사한다**

Run:

```bash
node scripts/validate-artifact-consistency.mjs
node postman/validate-postman-collections.mjs
```

Expected:

```text
Artifact consistency validation passed: 16 tables, 53 public APIs, 6 internal APIs
```

Postman 검사는 공개 API 53개, 내부 API 6개와 빈 오류 목록을 출력해야 한다.

- [ ] **Step 3: 실제 사용 명령을 확인한다**

전달할 Import 명령:

```bash
mysql -u <user> -p < erdTable.sql
```

성공 조건:

- MySQL 8.4 LTS
- 실행 계정에 `CREATE DATABASE`, `CREATE`, `ALTER`, `REFERENCES`, `INDEX` 권한 존재
- 기존 `ajt` 데이터베이스에 같은 이름의 업무 테이블이 없는 상태

실제 서버 접속 정보가 제공되지 않은 상태에서는 사용자 DB에 명령을 실행하지 않는다.
