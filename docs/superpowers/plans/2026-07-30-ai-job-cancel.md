# AI Job Cancel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 현재 문서 반영 뒤 남은 Wiki 변환 문서를 취소할 수 있다.

**Architecture:** `AiJob`은 기존 JSON 결과 컬럼에 문서 결과를 누적하고, 취소 요청은 작업 상태와 아직 시작하지 않은 문서 상태를 `CANCELLED`로 저장한다. `DocumentParseWorker`는 매 문서 시작 전 최신 작업 상태를 읽는다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito, MockMvc.

## Global Constraints

- DB 스키마·엔티티 컬럼을 변경하지 않는다.
- 현재 `PARSING`/`PROCESSING` 문서는 강제 중단하지 않는다.
- 관리자만 취소할 수 있고 종료된 작업은 409이다.

---

### Task 1: 취소 상태와 결과 누적 도메인

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/model/AiJob.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/document/model/AiJobTest.java`

- [ ] Write a failing test proving `cancel()` preserves `CANCELLED` after `recordResult(...)`.
- [ ] Run `bash ./gradlew test --tests '*AiJobTest'` and verify failure.
- [ ] Implement `cancel()` and `recordResult(DocumentParseResult)` without changing entity mappings.
- [ ] Re-run the focused test and verify pass.
- [ ] Commit `feat(ai): 취소 작업 결과 누적 추가`.

### Task 2: 취소 서비스와 공개 API

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/domain/document/service/AiJobCancelService.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/api/AiJobController.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/document/service/AiJobCancelServiceTest.java`

- [ ] Write failing tests for admin cancellation, pending document cancellation, and terminated-job conflict.
- [ ] Run `bash ./gradlew test --tests '*AiJobCancelServiceTest'` and verify failure.
- [ ] Implement a transactional service that changes only `UPLOADED` documents, then returns a 202 response.
- [ ] Re-run focused tests and verify pass.
- [ ] Commit `feat(ai): 진행 중 Wiki 작업 취소 API 추가`.

### Task 3: 워커 취소 경계

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentParseWorker.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/document/service/DocumentParseWorkerTest.java`

- [ ] Write a failing test proving a cancellation observed after one document prevents the next FastAPI call.
- [ ] Run `bash ./gradlew test --tests '*DocumentParseWorkerTest'` and verify failure.
- [ ] Reload the job before each document and persist each finished document result before continuing.
- [ ] Re-run focused tests and verify pass.
- [ ] Commit `feat(ai): 취소된 작업의 잔여 문서 처리 중단`.

### Task 4: Full verification

- [ ] Run `bash ./gradlew test`.
- [ ] Run `git diff --check` and inspect the changed files.
- [ ] Push the feature branch and create a `develop` target MR.
