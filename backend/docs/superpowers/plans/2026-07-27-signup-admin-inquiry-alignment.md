# Signup Approval, Admin, and Inquiry Assignee Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 가입 승인·거부 보존과 문의 담당자 직접 선택 정책을 요구사항, 컨벤션, SQL, ERDCloud 및 Postman에 일관되게 반영한다.

**Architecture:** 신규 테이블을 만들지 않고 `member.signup_status`로 가입 검수 상태를 저장하며, `inquiry.target_department_id`를 `inquiry.assignee_id`로 교체한다. 관리자 역할은 `ADMIN` 하나로 유지하고 부서 관리자 관계는 기존 `department.manager_id`로 관리한다. 생성 스크립트를 단일 원본으로 수정한 뒤 ERDCloud와 Postman JSON을 재생성한다.

**Tech Stack:** Markdown, MySQL 8.4 LTS, Node.js, ERDCloud snapshot JSON, Postman Collection v2.1

## Global Constraints

- 기존 16개 업무 테이블을 유지하고 신규 테이블을 만들지 않는다.
- `account_status`는 `ACTIVE`, `INACTIVE`만 사용한다.
- `signup_status`는 `PENDING`, `APPROVED`, `REJECTED`만 사용한다.
- `member.role`은 `ADMIN`, `EMPLOYEE`만 사용한다.
- 전체 관리자 여부를 저장하는 별도 역할이나 플래그를 만들지 않는다.
- 문의 등록 요청은 `assigneeId`를 필수로 받고 `targetDepartmentId`를 받지 않는다.
- 문의 담당자 변경·처리 이력·답변 임시 저장·알림 메일 API를 만들지 않는다.

---

### Task 1: 정합성 회귀 검사

**Files:**
- Modify: `scripts/validate-artifact-consistency.mjs`

**Interfaces:**
- Consumes: 요구사항, SQL, ERDCloud JSON, Postman JSON
- Produces: 가입 상태·문의 담당자·API 개수를 검증하는 종료 코드

- [ ] 가입 상태, `assignee_id`, 신규 가입 관리 API 3개, 공개 API 50개를 기대하는 검사를 추가한다.
- [ ] 기존 산출물에서 검사기를 실행해 `signup_status`, `assignee_id`, 가입 관리 API 부재로 실패하는지 확인한다.

### Task 2: 요구사항과 개발 컨벤션

**Files:**
- Modify: `요구사항정의서_v2 1.md`
- Modify: `REST API 개발 컨벤션.md`

**Interfaces:**
- Consumes: `backend/docs/superpowers/specs/2026-07-27-signup-admin-inquiry-alignment-design.md`
- Produces: SQL과 API가 따라야 할 상태 전이·권한·요청 필드

- [ ] 회원가입을 승인 대기로 변경하고 승인·거부·재신청 상태 전이를 명시한다.
- [ ] 전체·부서 관리자가 동일한 `ADMIN` 역할임을 명시한다.
- [ ] 문의 대상 부서 정책을 담당자 직접 선택 정책으로 교체한다.
- [ ] 처리 이력·메일·임시 저장이 없음을 명시한다.
- [ ] 컨벤션의 상태값과 공개 API 목록을 새 계약으로 갱신한다.

### Task 3: MySQL DDL과 ERDCloud

**Files:**
- Modify: `erdTable.sql`
- Modify: `scripts/generate-erdcloud-snapshot.mjs`
- Regenerate: `erdTable-snapshot-ajt.json`

**Interfaces:**
- Consumes: `signup_status`, `assignee_id` 데이터 계약
- Produces: 동일한 16개 테이블과 컬럼·FK를 가진 SQL과 ERDCloud JSON

- [ ] `member.signup_status VARCHAR(30) NOT NULL`과 CHECK 제약조건을 추가한다.
- [ ] `inquiry.target_department_id`를 제거하고 `assignee_id`와 `member` FK를 추가한다.
- [ ] ERD 생성 스크립트의 필드, 관계와 설명을 SQL에 맞춘다.
- [ ] ERDCloud JSON을 재생성한다.

### Task 4: Postman 공개 API

**Files:**
- Modify: `postman/generate-postman-collections.mjs`
- Regenerate: `postman/AJT-Backend-Public-API.postman_collection.json`
- Regenerate: `postman/AJT-FastAPI-Internal-API.postman_collection.json`
- Regenerate: `postman/AJT-Local.postman_environment.json`

**Interfaces:**
- Consumes: 가입 상태 전이와 문의 담당자 계약
- Produces: 공개 API 50개, 내부 API 6개 Postman Collection

- [ ] 회원가입을 `202 pending` 응답으로 변경한다.
- [ ] 가입 요청 목록·승인·거부 API를 추가한다.
- [ ] 부서 응답과 생성·수정 요청에 선택 `managerId`를 반영한다.
- [ ] 문의 등록·목록·상세·답변에서 `targetDepartmentId`를 `assigneeId`로 교체한다.
- [ ] 관리자 문의 조회와 답변 권한을 담당자 기준으로 변경한다.
- [ ] Collection과 Environment를 재생성한다.

### Task 5: 최종 검증

**Files:**
- Verify: all artifacts above

**Interfaces:**
- Consumes: 최신 생성물
- Produces: 개발 착수 가능한 정합성 검증 결과

- [ ] 생성 스크립트 문법을 검사하고 ERDCloud·Postman을 새로 생성한다.
- [ ] Postman 자체 검증기와 공식 v2.1 스키마 검증을 실행한다.
- [ ] JSON 파싱과 교차 산출물 정합성 검사를 실행한다.
- [ ] `target_department_id`, `targetDepartmentId`, 즉시 활성 가입 정책이 남아 있지 않은지 검색한다.
