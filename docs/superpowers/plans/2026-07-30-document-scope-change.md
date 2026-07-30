# Document Scope Change Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move a Wiki source document to a new visibility scope with reversible file/DB changes and reprocess both affected Wiki scopes.

**Architecture:** Keep the existing `PATCH /api/v1/documents/{documentId}` request. A new document-file mutation owns only original/parsed file moves and reverses them when the surrounding database transaction rolls back. `DocumentManagementService` creates one reprocess job per affected nonempty scope; an empty old scope is cleared by a focused Wiki cleanup service. The existing FastAPI document transformation endpoints remain unchanged.

**Tech Stack:** Java 21, Spring Boot, Spring transactions, JPA, local `java.nio.file` storage, Postman contract generator, JUnit 5/Mockito.

## Global Constraints

- Do not change `docs/db/erd.sql` or entity schema mappings.
- Keep the public PATCH request fields backward compatible; add only optional response fields and raise `contractVersion` by patch.
- AI calls remain outside database transactions; jobs are submitted by the existing after-commit launcher.
- A later AI/job failure records `FAILED`; it does not roll back an already committed scope change.
- All local file edits use `apply_patch`; run `bash ./gradlew test` before final handoff.

---

## File Structure

- `docs/api/postman-contract-examples.mjs` — patch contract version.
- `docs/api/generate-postman-collections.mjs` — PATCH response description and saved examples.
- `docs/api/AJT-Backend-Public-API.postman_collection.json` — generated contract artifact.
- `scripts/validate-artifact-consistency.mjs` — expected contract version assertion.
- `backend/src/main/java/com/ajt/backend/domain/document/api/DocumentUpdateResponse.java` — exposes all reprocess job IDs.
- `backend/src/main/java/com/ajt/backend/domain/document/storage/DocumentFileStorage.java` — declares a reversible scope-move unit.
- `backend/src/main/java/com/ajt/backend/domain/document/storage/DocumentFileMutation.java` — owns original/parsed file movement, rollback and backup disposal.
- `backend/src/main/java/com/ajt/backend/domain/document/storage/LocalDocumentFileStorage.java` — local atomic move implementation.
- `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentManagementService.java` — validates, coordinates file/DB changes, creates both jobs.
- `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiScopeCleanupService.java` — clears a scope that has no source documents.
- Corresponding document API/service/storage and wiki cleanup tests.

### Task 1: Contract and response model

**Files:**
- Modify: `docs/api/postman-contract-examples.mjs`
- Modify: `docs/api/generate-postman-collections.mjs`
- Modify: `scripts/validate-artifact-consistency.mjs`
- Generated: `docs/api/AJT-Backend-Public-API.postman_collection.json`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/api/DocumentUpdateResponse.java`
- Modify: `backend/src/test/java/com/ajt/backend/domain/document/api/DocumentUploadControllerTest.java`

**Interfaces:**
- Produces: `DocumentUpdateResponse(String jobId, String status, List<ReprocessJobResponse> reprocessJobs, DocumentDetailResponse document)`.
- Produces: `ReprocessJobResponse(String scopeKey, String jobId)` with `reprocessJobs` optional only when scope changes.

- [ ] **Step 1: Write failing controller assertions**

```java
assertThat(response.getBody().reprocessJobs())
        .containsExactly(
                new ReprocessJobResponse("ALL", "701"),
                new ReprocessJobResponse("D1-D3", "702")
        );
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.document.api.DocumentUploadControllerTest' --console=plain`

Expected: compilation failure because `reprocessJobs` and `ReprocessJobResponse` do not exist.

- [ ] **Step 3: Add the backward-compatible response field and regenerate the contract**

```java
public record DocumentUpdateResponse(
        String jobId,
        String status,
        List<ReprocessJobResponse> reprocessJobs,
        DocumentDetailResponse document
) {}
```

Update the contract response to document `reprocessJobs: [{scopeKey, jobId}]`, increment `contractVersion` from `1.3.1` to `1.3.2`, then run `node docs/api/generate-postman-collections.mjs` and `node scripts/validate-artifact-consistency.mjs`.

- [ ] **Step 4: Run focused contract and controller verification**

Run: `node scripts/validate-artifact-consistency.mjs && bash ./gradlew test --tests 'com.ajt.backend.domain.document.api.DocumentUploadControllerTest' --console=plain`

Expected: generator consistency succeeds and controller tests pass.

- [ ] **Step 5: Commit**

```bash
git add docs/api scripts/validate-artifact-consistency.mjs backend/src/main/java/com/ajt/backend/domain/document/api backend/src/test/java/com/ajt/backend/domain/document/api/DocumentUploadControllerTest.java
git commit -m "feat(document): 공개 범위 재처리 작업 응답 추가 [S15P11B106-61]"
```

### Task 2: Reversible document file scope movement

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/domain/document/storage/DocumentFileMutation.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/storage/DocumentFileStorage.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/storage/LocalDocumentFileStorage.java`
- Modify: `backend/src/test/java/com/ajt/backend/domain/document/storage/LocalDocumentFileStorageTest.java`

**Interfaces:**
- Produces: `DocumentFileMutation moveToScope(String originalPath, String parsedPath, String targetScopeKey, long documentId)`.
- `DocumentFileMutation` exposes `String originalPath()`, `String parsedPath()`, `void rollback() throws IOException`, `void discardBackup()`.

- [ ] **Step 1: Write failing storage tests**

```java
DocumentFileMutation mutation = storage.moveToScope(
        "wiki/ALL/sources/15/original.pdf",
        "wiki/ALL/sources/15/parsed.md",
        "D1-D3",
        15L
);
assertThat(mutation.originalPath()).isEqualTo("wiki/D1-D3/sources/15/original.pdf");
mutation.rollback();
assertThat(Files.exists(storageRoot.resolve("wiki/ALL/sources/15/original.pdf"))).isTrue();
```

- [ ] **Step 2: Run the focused storage test to verify it fails**

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.document.storage.LocalDocumentFileStorageTest' --console=plain`

Expected: compilation failure because `moveToScope` and `DocumentFileMutation` do not exist.

- [ ] **Step 3: Implement same-filesystem atomic moves and rollback**

Derive destination paths from `documentId` and the original extension. Move original and optional parsed files with `ATOMIC_MOVE`; if the second move fails, reverse the first before throwing. `rollback()` moves every successfully moved path back in reverse order. Reject moves that resolve outside the storage root.

- [ ] **Step 4: Run the focused storage test to verify it passes**

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.document.storage.LocalDocumentFileStorageTest' --console=plain`

Expected: original-only, original-plus-parsed, partial-move failure, and rollback cases pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ajt/backend/domain/document/storage backend/src/test/java/com/ajt/backend/domain/document/storage/LocalDocumentFileStorageTest.java
git commit -m "feat(document): 공개 범위 파일 이동 보상 추가 [S15P11B106-61]"
```

### Task 3: Empty scope Wiki cleanup

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiScopeCleanupService.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/repository/WikiCategoryRepository.java`
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiScopeCleanupServiceTest.java`

**Interfaces:**
- Produces: `void cleanupEmptyScope(String scopeKey)`; caller guarantees there are no `Document` rows in the scope.
- Consumes: `WikiRepository.findAllByScopeKey`, `WikiCategoryRepository.findAllByScopeKeyOrderByNameAsc`, `WikiSearchChunkRepository.deleteByWikiId`, `WikiFileStorage.beginMutation`.

- [ ] **Step 1: Write a failing cleanup test**

```java
cleanupService.cleanupEmptyScope("ALL");
then(fileMutation).should().deleteWikiMarkdown("wiki/ALL/pages/leave.md");
then(fileMutation).should().storeIndex("ALL", "# 목차");
then(wikiSearchChunkRepository).should().deleteByWikiId(101L);
then(wikiRepository).should().delete(wiki);
```

- [ ] **Step 2: Run the focused cleanup test to verify it fails**

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.wiki.service.WikiScopeCleanupServiceTest' --console=plain`

Expected: compilation failure because `WikiScopeCleanupService` does not exist.

- [ ] **Step 3: Implement transactional cleanup with file compensation**

Open one `WikiFileMutation`, delete every Wiki markdown file and search chunk, delete Wiki rows, delete the scope's categories, and store `# 목차`. On any exception roll back the file mutation; on transaction rollback register the same compensation callback used by `WikiTransformationApplier`.

- [ ] **Step 4: Run the focused cleanup test to verify it passes**

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.wiki.service.WikiScopeCleanupServiceTest' --console=plain`

Expected: Wiki rows, categories, search chunks and files are removed together; file failure restores prior files.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiScopeCleanupService.java backend/src/main/java/com/ajt/backend/domain/wiki/repository/WikiCategoryRepository.java backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiScopeCleanupServiceTest.java
git commit -m "feat(wiki): 빈 공개 범위 정리 추가 [S15P11B106-61]"
```

### Task 4: Scope-change orchestration

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentManagementService.java`
- Modify: `backend/src/test/java/com/ajt/backend/domain/document/service/DocumentManagementServiceTest.java`
- Modify: `backend/src/test/java/com/ajt/backend/domain/document/api/DocumentUploadControllerTest.java`

**Interfaces:**
- Consumes: `DocumentFileStorage.moveToScope`, `DocumentFileMutation`, `WikiScopeCleanupService.cleanupEmptyScope`.
- Produces: two `AiJob` records for changed nonempty scopes and a `DocumentUpdateResponse` containing their IDs.

- [ ] **Step 1: Replace the current scope-change test with failing full-scope assertions**

```java
assertThat(response.reprocessJobs()).extracting(ReprocessJobResponse::scopeKey)
        .containsExactly("ALL", "D1-D3");
verify(documentFileStorage).moveToScope(document.originalPath(), document.parsedPath(), "D1-D3", 15L);
verify(documentRepository, times(2)).findByScopeKey(anyString());
verify(parseJobLauncher, times(2)).launch(any(AiJob.class));
```

- [ ] **Step 2: Run the focused service test to verify it fails**

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.document.service.DocumentManagementServiceTest' --console=plain`

Expected: assertions fail because the current code changes only the DB scope and processes the new scope incrementally.

- [ ] **Step 3: Implement scope-changing branch**

For an unchanged scope retain one-document reprocessing. For a changed scope, call `moveToScope`, update document category/scope/paths, register rollback on transaction completion, then call `reprocessScope` for the old and new scopes. If the old scope has no documents, call `cleanupEmptyScope` instead of creating an empty `AiJob`. Populate `reprocessJobs` in old-then-new order and retain the new scope job as `jobId`.

- [ ] **Step 4: Add error-path tests and make them pass**

```java
assertThatThrownBy(() -> service.update(15L, request)).isInstanceOf(UncheckedIOException.class);
then(fileMutation).should().rollback();
then(aiJobRepository).shouldHaveNoInteractions();
```

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.document.service.DocumentManagementServiceTest' --console=plain`

Expected: unchanged-scope behavior remains one job; changed scope creates two jobs; failed movement and rollback preserve metadata.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ajt/backend/domain/document/service/DocumentManagementService.java backend/src/test/java/com/ajt/backend/domain/document/service/DocumentManagementServiceTest.java backend/src/test/java/com/ajt/backend/domain/document/api/DocumentUploadControllerTest.java
git commit -m "feat(document): 공개 범위 양쪽 재처리 적용 [S15P11B106-61]"
```

### Task 5: Full verification and handoff

**Files:**
- Modify if required: generated API contract artifacts only.

- [ ] **Step 1: Run artifact consistency validation**

Run: `node scripts/validate-artifact-consistency.mjs`

Expected: generated collections and contract version checks pass.

- [ ] **Step 2: Run focused regression suites**

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.document.service.DocumentManagementServiceTest' --tests 'com.ajt.backend.domain.document.storage.LocalDocumentFileStorageTest' --tests 'com.ajt.backend.domain.wiki.service.WikiScopeCleanupServiceTest' --console=plain`

Expected: all scope-change, file compensation and empty-scope cleanup tests pass.

- [ ] **Step 3: Run the complete backend suite**

Run: `bash ./gradlew test --rerun-tasks --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Review the final diff and commit verification notes if needed**

```bash
git diff origin/develop...HEAD --check
git status --short
```

Expected: no whitespace errors and no untracked implementation artifacts.
