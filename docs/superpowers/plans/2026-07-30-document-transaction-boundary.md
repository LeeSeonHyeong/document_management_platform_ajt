# 문서 단위 Wiki 변환 트랜잭션 경계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FastAPI 호출을 트랜잭션 밖으로 옮기고 Wiki 반영과 문서 완료 상태를 문서 한 건의 독립 트랜잭션으로 커밋한다.

**Architecture:** `DocumentParseWorker`는 AI 호출과 순차 제어만 담당한다. `WikiTransformationService`는 변환 요청을 FastAPI에 보내 응답을 반환하고, 새 트랜잭션 서비스가 응답을 `WikiTransformationApplier`에 반영한 뒤 같은 트랜잭션에서 `Document`를 완료 상태로 전이한다.

**Tech Stack:** Java 21, Spring Boot, Spring Transaction Management, Spring Data JPA, JUnit 5, Mockito, AssertJ

## Global Constraints

- FastAPI 호출 중 `@Transactional` 범위를 열지 않는다.
- Wiki 반영과 문서 `COMPLETED` 상태 전이는 `Propagation.REQUIRES_NEW` 한 트랜잭션에 포함한다.
- 문서별 실패는 다음 문서의 순차 처리를 중단하지 않는다.
- DR-008 파일 작업공간·원자적 교체·파일 복구는 수정하지 않는다.
- DB 스키마와 API 계약은 변경하지 않는다.

---

## File Structure

- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentParseWorker.java` — 트랜잭션 없는 오케스트레이션과 문서별 저장 호출
- Create: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentWikiTransformationTransactionService.java` — Wiki 반영과 문서 완료 상태를 묶는 `REQUIRES_NEW` 경계
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationService.java` — FastAPI 호출만 수행하고 원시 변환 응답 반환
- Modify: `backend/src/test/java/com/ajt/backend/domain/document/service/DocumentParseWorkerTest.java` — 새 트랜잭션 서비스를 모킹해 실패 격리 흐름 검증
- Create: `backend/src/test/java/com/ajt/backend/domain/document/service/DocumentWikiTransformationTransactionServiceTest.java` — Wiki 반영과 문서 완료 상태를 한 경계에서 수행하는 단위 테스트
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationServiceTest.java` — 변환 요청이 응답을 그대로 반환하는지 검증

### Task 1: Wiki 변환 요청과 반영 경계 분리

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationService.java:54-77`
- Create: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentWikiTransformationTransactionService.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationServiceTest.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/document/service/DocumentWikiTransformationTransactionServiceTest.java`

**Interfaces:**
- Consumes: `AiClient.transformWiki(WikiTransformationRequest)`, `WikiTransformationApplier.apply(String, long, WikiTransformationResponse)`, `DocumentRepository.findById(long)`
- Produces: `WikiTransformationService.requestForAddedDocument(long, long, String, String, List<Long>): WikiTransformationResponse`
- Produces: `DocumentWikiTransformationTransactionService.applyAddedDocument(long, String, WikiTransformationResponse): WikiTransformationResult`

- [ ] **Step 1: Write the failing request-service test**

```java
@Test
void requestsTransformationWithoutApplyingIt() {
    WikiTransformationResponse response = transformationResponse("요약");
    given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(response);

    assertThat(service.requestForAddedDocument(42L, 15L, SCOPE_KEY, "# 본문", List.of()))
            .isSameAs(response);
    then(applier).shouldHaveNoInteractions();
}
```

- [ ] **Step 2: Run the request-service test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*WikiTransformationServiceTest.requestsTransformationWithoutApplyingIt'`

Expected: FAIL because `requestForAddedDocument` does not exist.

- [ ] **Step 3: Implement the minimal request-service split**

```java
public WikiTransformationResponse requestForAddedDocument(
        long jobId,
        long documentId,
        String scopeKey,
        String parsedMarkdown,
        List<Long> selectedWikiIds
) {
    String currentIndex = currentIndex(scopeKey);
    return aiClient.transformWiki(new WikiTransformationRequest(
            String.valueOf(jobId), String.valueOf(documentId), scopeKey,
            WikiDocumentChangeType.DOCUMENT_ADDED, parsedMarkdown, null, currentIndex,
            currentCategories(scopeKey), selectedWikis(scopeKey, selectedWikiIds, currentIndex)
    ));
}
```

Remove `@Transactional` and move `WikiTransformationApplier.apply(String, long, WikiTransformationResponse)` out of this class. Preserve current index, category, and selected Wiki request construction exactly.

- [ ] **Step 4: Run the request-service test to verify it passes**

Run: `cd backend && ./gradlew test --tests '*WikiTransformationServiceTest.requestsTransformationWithoutApplyingIt'`

Expected: PASS.

- [ ] **Step 5: Write the failing transactional-application test**

```java
@Test
void appliesWikiChangesThenCompletesTheDocument() {
    Document document = processingDocument(15L);
    given(documentRepository.findById(15L)).willReturn(Optional.of(document));
    given(applier.apply("ALL", 15L, response)).willReturn(List.of(101L));

    WikiTransformationResult result = service.applyAddedDocument(15L, "ALL", response);

    assertThat(document.status()).isEqualTo(DocumentStatus.COMPLETED);
    assertThat(result.affectedWikiIds()).containsExactly(101L);
    assertThat(result.summary()).isEqualTo(response.summary());
}
```

- [ ] **Step 6: Run the transactional-application test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*DocumentWikiTransformationTransactionServiceTest.appliesWikiChangesThenCompletesTheDocument'`

Expected: FAIL because the transaction service does not exist.

- [ ] **Step 7: Implement the minimal transaction service**

```java
@Service
public class DocumentWikiTransformationTransactionService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WikiTransformationResult applyAddedDocument(long documentId, String scopeKey,
            WikiTransformationResponse response) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("문서를 찾을 수 없습니다: " + documentId));
        List<Long> affectedWikiIds = applier.apply(scopeKey, documentId, response);
        document.completeProcessing(affectedWikiIds);
        return new WikiTransformationResult(affectedWikiIds, response.summary());
    }
}
```

Define `WikiTransformationResult` as a public nested record in this service, with `List.copyOf` for affected IDs. Do not call `documentRepository.save`: the managed entity is flushed by this transaction.

- [ ] **Step 8: Run both focused tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*WikiTransformationServiceTest.requestsTransformationWithoutApplyingIt' --tests '*DocumentWikiTransformationTransactionServiceTest.appliesWikiChangesThenCompletesTheDocument'`

Expected: PASS.

- [ ] **Step 9: Commit the boundary split**

```bash
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationService.java \
  backend/src/main/java/com/ajt/backend/domain/document/service/DocumentWikiTransformationTransactionService.java \
  backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationServiceTest.java \
  backend/src/test/java/com/ajt/backend/domain/document/service/DocumentWikiTransformationTransactionServiceTest.java
git commit -m "refactor(wiki): 변환 요청과 반영 트랜잭션 분리"
```

### Task 2: 워커를 트랜잭션 없는 문서별 오케스트레이터로 변경

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentParseWorker.java:53-171`
- Modify: `backend/src/test/java/com/ajt/backend/domain/document/service/DocumentParseWorkerTest.java`

**Interfaces:**
- Consumes: `WikiTransformationService.requestForAddedDocument(long, long, String, String, List<Long>)`, `DocumentWikiTransformationTransactionService.applyAddedDocument(long, String, WikiTransformationResponse)`
- Produces: `DocumentParseWorker.parse(AiJob)` without `@Transactional`

- [ ] **Step 1: Write the failing failure-isolation test**

```java
@Test
void continuesWhenFirstDocumentApplicationFails() throws Exception {
    // 두 문서의 AI 호출은 모두 성공하도록 준비한다.
    given(transactionService.applyAddedDocument(eq(15L), eq("ALL"), any()))
            .willThrow(new IllegalStateException("반영 실패"));
    given(transactionService.applyAddedDocument(eq(16L), eq("ALL"), any()))
            .willReturn(new WikiTransformationResult(List.of(301L), "반영 완료"));

    worker.parse(job);

    assertThat(first.status()).isEqualTo(DocumentStatus.FAILED);
    assertThat(second.status()).isEqualTo(DocumentStatus.COMPLETED);
    then(transactionService).should().applyAddedDocument(eq(16L), eq("ALL"), any());
}
```

- [ ] **Step 2: Run the failure-isolation test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*DocumentParseWorkerTest.continuesWhenFirstDocumentApplicationFails'`

Expected: FAIL because the worker still calls `transformForAddedDocument` and has no transaction service dependency.

- [ ] **Step 3: Add a failing structural transaction test**

```java
@Test
void parseDoesNotOpenTransactionAroundAiCalls() throws Exception {
    Method parse = DocumentParseWorker.class.getMethod("parse", AiJob.class);
    assertThat(parse.isAnnotationPresent(Transactional.class)).isFalse();
}
```

- [ ] **Step 4: Run the structural test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*DocumentParseWorkerTest.parseDoesNotOpenTransactionAroundAiCalls'`

Expected: FAIL because `parse` is annotated with `@Transactional`.

- [ ] **Step 5: Implement the minimal worker refactor**

```java
public void parse(AiJob job) {
    job.start();
    aiJobRepository.save(job);
    // AI 호출 전후의 Document 상태 변경은 documentRepository.save(document)로 짧게 저장한다.
    WikiTransformationResponse response = wikiTransformationService.requestForAddedDocument(
            job.id(), document.id(), document.scopeKey(), parsedMarkdown, selectedWikiIds);
    WikiTransformationResult result = transactionService.applyAddedDocument(document.id(), document.scopeKey(), response);
}
```

Remove `@Transactional` from `parse`. Keep source parsing, parsed Markdown file storage, context selection, failure message conversion, upload order, and `AiJob.DocumentParseResult` construction unchanged. Save `Document` immediately after `startParsing`, `completeParsing`, `failParsing`, and `failProcessing`; on a transactional apply failure, call `failProcessing` then save it in a separate short repository transaction.

- [ ] **Step 6: Run the focused worker tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*DocumentParseWorkerTest.continuesWhenFirstDocumentApplicationFails' --tests '*DocumentParseWorkerTest.parseDoesNotOpenTransactionAroundAiCalls' --tests '*DocumentParseWorkerTest.continuesAfterTransformationFailure'`

Expected: PASS.

- [ ] **Step 7: Run the complete worker test class**

Run: `cd backend && ./gradlew test --tests '*DocumentParseWorkerTest'`

Expected: PASS.

- [ ] **Step 8: Commit the worker refactor**

```bash
git add backend/src/main/java/com/ajt/backend/domain/document/service/DocumentParseWorker.java \
  backend/src/test/java/com/ajt/backend/domain/document/service/DocumentParseWorkerTest.java
git commit -m "refactor(document): AI 호출 트랜잭션 경계 축소"
```

### Task 3: 전체 회귀 검증과 문서 정리

**Files:**
- Modify: `docs/superpowers/plans/2026-07-30-document-transaction-boundary.md` — 완료 체크 갱신

**Interfaces:**
- Consumes: Task 1·2 구현
- Produces: 전체 백엔드 테스트 통과 증적

- [ ] **Step 1: 전체 백엔드 테스트를 실행한다**

Run: `cd backend && ./gradlew test --rerun-tasks --console=plain`

Expected: `BUILD SUCCESSFUL` 및 테스트 실패 0건.

- [ ] **Step 2: 작업 트리와 차이를 확인한다**

Run: `git status --short && git diff origin/develop...HEAD --check`

Expected: 의도한 소스·테스트·설계/계획 문서만 변경되고 공백 오류가 없다.

- [ ] **Step 3: 계획 체크박스를 완료로 갱신하고 커밋한다**

```bash
git add docs/superpowers/plans/2026-07-30-document-transaction-boundary.md
git commit -m "docs(wiki): 트랜잭션 경계 구현 계획 완료 표시"
```
