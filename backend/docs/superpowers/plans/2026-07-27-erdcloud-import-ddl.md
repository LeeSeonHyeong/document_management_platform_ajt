# ERDCloud Import DDL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 현재 1주 MVP 요구사항을 ERDCloud에 SQL로 가져올 수 있는 MySQL 8.0 DDL 파일로 제공하고 관련 요구사항 문서의 파일 형식과 답변 출처 정책을 동기화한다.

**Architecture:** 기존 16개 업무 테이블을 유지하면서 PK, FK, UNIQUE, ENUM과 최소 CHECK 제약을 실제 MySQL 문법으로 표현한다. Wiki 답변 출처는 `answer_source`에 여러 Wiki 행으로 저장하고 원본문서는 `wiki.document_refs`로 조회하므로 `answer_source.document_id`는 제거한다.

**Tech Stack:** MySQL 8.0 DDL, ERDCloud SQL Import, Markdown

## Global Constraints

- 새 업무 테이블을 추가하지 않는다.
- 실제 업무 테이블은 16개를 유지하고 도구용 `ENUM` 가상 테이블은 만들지 않는다.
- 일정 원본문서는 `TXT`, `MD`, `DOCX`, `PDF`, `CSV`, `XLSX`만 허용한다.
- 문의 첨부파일은 `PNG`, `JPG`, `JPEG`만 허용하고 최대 5개, 파일당 20MB로 제한한다.
- Wiki 챗봇은 Wiki만 검색하며 원본문서는 Wiki의 근거 자료로만 표시한다.
- 답변 하나는 `answer_source` 행 여러 개로 복수 Wiki 또는 복수 일정 출처를 가질 수 있다.
- 원본문서·Wiki·일정 등 삭제 대상 업무 데이터는 하드 삭제하고 사용자 계정만 비활성화한다.

---

### Task 1: ERDCloud Import용 MySQL DDL 작성

**Files:**
- Create: `erdcloud-import.sql`

**Interfaces:**
- Consumes: `erdTable.sql`의 16개 업무 테이블과 `요구사항정의서_v2 1.md`의 확정 정책
- Produces: ERDCloud SQL Import에 입력할 수 있는 MySQL 8.0 `CREATE TABLE`/`ALTER TABLE` DDL

- [ ] **Step 1: 16개 테이블과 필수 관계의 검증 목록을 만든다**

검증 대상 테이블:

```text
department, member, wiki_scope, document_category, wiki_category,
document, ai_job, wiki, wiki_chat_message,
schedule, schedule_department, inquiry, inquiry_reply,
ai_question, ai_answer, answer_source
```

필수 관계:

```text
member -> department
department.manager_id -> member
document_category/wiki_category -> wiki_scope
document -> member/document_category/wiki_scope
ai_job -> member/wiki_scope
wiki -> wiki_category/wiki_scope
wiki_chat_message -> wiki/member
schedule -> member
schedule_department -> schedule/department
inquiry -> member/department
inquiry_reply -> inquiry/member
ai_question -> member
ai_answer -> ai_question
answer_source -> ai_answer/wiki/schedule
```

- [ ] **Step 2: 실제 MySQL 8.0 DDL을 작성한다**

각 테이블은 `BIGINT UNSIGNED AUTO_INCREMENT` PK를 사용하고, 상태값은 실제 `ENUM('...')`으로 선언한다. `department`와 `member`의 순환 참조는 두 테이블 생성 후 `ALTER TABLE department ADD CONSTRAINT ...`로 연결한다.

`answer_source`는 다음 형태로 만든다.

```sql
CREATE TABLE `answer_source` (
  `answer_source_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ai_answer_id` BIGINT UNSIGNED NOT NULL,
  `wiki_id` BIGINT UNSIGNED NULL,
  `schedule_id` BIGINT UNSIGNED NULL,
  `source_title` VARCHAR(255) NOT NULL,
  PRIMARY KEY (`answer_source_id`),
  CONSTRAINT `chk_answer_source_one_or_deleted`
    CHECK ((`wiki_id` IS NOT NULL) + (`schedule_id` IS NOT NULL) <= 1),
  CONSTRAINT `fk_answer_source_answer`
    FOREIGN KEY (`ai_answer_id`) REFERENCES `ai_answer` (`ai_answer_id`)
    ON DELETE CASCADE,
  CONSTRAINT `fk_answer_source_wiki`
    FOREIGN KEY (`wiki_id`) REFERENCES `wiki` (`wiki_id`)
    ON DELETE SET NULL,
  CONSTRAINT `fk_answer_source_schedule`
    FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`schedule_id`)
    ON DELETE SET NULL
);
```

출처 생성 시에는 애플리케이션에서 `wiki_id`, `schedule_id` 중 정확히 하나를 넣는다. 이후 원본 출처가 하드 삭제되면 FK가 `NULL`이 되고 `source_title`만 이력으로 유지된다.

- [ ] **Step 3: DDL 구조 검증을 실행한다**

Run:

```bash
rg -c '^CREATE TABLE' erdcloud-import.sql
rg -n 'CREATE TABLE `ENUM`|`document_id` BIGINT.*answer_source' erdcloud-import.sql
rg -c 'PRIMARY KEY' erdcloud-import.sql
rg -c 'FOREIGN KEY' erdcloud-import.sql
```

Expected:

```text
CREATE TABLE 수: 16
ENUM 가상 테이블: 0
answer_source.document_id: 0
PRIMARY KEY 수: 16
FOREIGN KEY 수: 필수 관계를 모두 포함
```

### Task 2: 요구사항과 파일 구조 정책 동기화

**Files:**
- Modify: `요구사항정의서_v2 1.md`
- Modify: `backend/docs/superpowers/specs/2026-07-26-file-directory-structure-design.md`
- Modify: `erdTable.sql`

**Interfaces:**
- Consumes: Task 1의 확정 DDL
- Produces: DDL과 동일한 답변 출처 및 업로드 형식 정책

- [ ] **Step 1: 일정·문의 파일 형식을 수정한다**

요구사항과 파일 구조 문서에 다음 값을 정확히 반영한다.

```text
일정 원본문서: TXT, MD, DOCX, PDF, CSV, XLSX
문의 첨부파일: PNG, JPG, JPEG
문의 첨부 제한: 최대 5개, 파일당 20MB
```

- [ ] **Step 2: 답변 출처 정책을 수정한다**

Wiki 질문은 Wiki만 검색한다. `answer_source`에는 사용한 Wiki마다 한 행을 저장하고, 화면에서는 Wiki를 주 출처로 표시한 뒤 `wiki.document_refs`의 원본문서를 접어서 근거 자료로 보여준다. 일정 질문은 사용한 일정마다 한 행을 저장한다.

- [ ] **Step 3: 기존 ERD 도구용 정의를 동기화한다**

`erdTable.sql`의 `answer_source.document_id`를 제거하고, 요구사항에서 답변 출처를 Wiki 또는 일정으로 제한한다. 업무 테이블 수는 16개로 유지한다.

- [ ] **Step 4: 문서 간 불일치 검증을 실행한다**

Run:

```bash
rg -n 'PDF, DOCX, TXT, MD, PNG, JPG, JPEG|answer_source.*document_id|원본문서.*직접 검색' \
  erdTable.sql "요구사항정의서_v2 1.md" \
  backend/docs/superpowers/specs/2026-07-26-file-directory-structure-design.md
```

Expected: 이전 통합 첨부 형식, `answer_source.document_id`, 원본문서 직접 검색 문구가 없어야 한다.

- [ ] **Step 5: 최종 구조 검증을 실행한다**

Run:

```bash
rg -c '^CREATE TABLE' erdcloud-import.sql
rg -c '^CREATE TABLE' erdTable.sql
rg -n 'CSV|XLSX|PNG|JPG|JPEG|Wiki만 검색|document_refs' \
  erdcloud-import.sql "요구사항정의서_v2 1.md" \
  backend/docs/superpowers/specs/2026-07-26-file-directory-structure-design.md
```

Expected: import DDL은 실제 테이블 16개, 도구용 ERD는 업무 테이블 16개와 `ENUM` 가상 테이블 1개이며 새 정책 문구가 모두 확인되어야 한다.
