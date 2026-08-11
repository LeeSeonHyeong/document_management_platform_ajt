# AI 작업 영향 Wiki 이력 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 문서 반영으로 실제 생성·변경된 Wiki를 AI 작업 결과에 보존하고 관리자 요약 목록에서 링크로 보여 준다.

**Architecture:** Wiki 반영 트랜잭션의 영향 Wiki ID를 제목 스냅샷과 함께 `AiJob.DocumentParseResult` JSON에 넣는다. 조회 API가 이를 `affectedWikis`로 반환하고, 요약 화면은 이를 우선 사용한 뒤 과거 작업에만 `relatedWikis`를 fallback으로 사용한다.

**Tech Stack:** Java 21, Spring Boot/JPA JSON, JUnit 5·Mockito, React 19, Vitest, Postman 계약 생성기.

## Global Constraints

- AI 서버와 DB 테이블·ERD는 변경하지 않는다.
- 기존 `ai_job.document_results` JSON에 하위 호환 필드를 추가한다.
- 응답 ID는 문자열, JSON 필드는 camelCase다.
- 계약 원본을 고친 뒤 컬렉션을 생성하고 일관성 검증을 통과시킨다.
- 과거 작업은 `affectedWikis: []`로 응답하며 프론트 fallback으로만 기존 표시를 유지한다.

---

### Task 1: 작업 결과에 영향 Wiki 스냅샷을 보존한다

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/model/AiJob.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentWikiTransformationTransactionService.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentParseWorker.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/document/service/DocumentParseWorkerTest.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/document/model/AiJobTest.java`

**Interfaces:**
- Produces: `AiJob.AffectedWiki(long wikiId, String title)` and `DocumentParseResult.affectedWikis()`.
- Produces: `WikiTransformationResult.affectedWikis()`.
- Consumes: applier의 영향 ID와 `WikiRepository.findAllById` 결과의 ID·제목.

- [ ] **Step 1: Write the failing worker test**

```java
given(transactionService.applyAddedDocument(eq(15L), eq("ALL"), any()))
        .willReturn(new WikiTransformationResult(
                List.of(new AiJob.AffectedWiki(101L, "휴가 규정")), "반영 완료"));
worker.parse(job);
assertThat(job.documentResults()).singleElement().satisfies(result ->
        assertThat(result.affectedWikis())
                .containsExactly(new AiJob.AffectedWiki(101L, "휴가 규정")));
```

- [ ] **Step 2: Run test to verify RED**

Run: `./gradlew test --tests com.ajt.backend.domain.document.service.DocumentParseWorkerTest`

Expected: compilation failure because `AffectedWiki` and the new result constructor do not exist.

- [ ] **Step 3: Write the failing legacy-result test**

```java
AiJob.DocumentParseResult old = AiJob.DocumentParseResult.succeeded(15L, "규정.pdf", "반영 완료");
assertThat(old.affectedWikis()).isEmpty();
```

- [ ] **Step 4: Write minimal model and transaction implementation**

```java
public record AffectedWiki(long wikiId, String title) {}
public record DocumentParseResult(..., List<AffectedWiki> affectedWikis) {
    public DocumentParseResult { affectedWikis = affectedWikis == null ? List.of() : List.copyOf(affectedWikis); }
}
```

Load only IDs returned by the applier, retain their order, map existing Wikis to `(id, title)`, return the snapshot list from the transaction result, and pass it only to successful addition/replacement results. Preserve existing three-argument `succeeded` for deletion and legacy uses so those results are empty.

- [ ] **Step 5: Run tests to verify GREEN**

Run: `./gradlew test --tests com.ajt.backend.domain.document.model.AiJobTest --tests com.ajt.backend.domain.document.service.DocumentParseWorkerTest`

Expected: successful additions record title snapshots; failed/deletion results remain empty.

- [ ] **Step 6: Commit**

Run: `git add backend/src/main/java/com/ajt/backend/domain/document/model/AiJob.java backend/src/main/java/com/ajt/backend/domain/document/service/DocumentWikiTransformationTransactionService.java backend/src/main/java/com/ajt/backend/domain/document/service/DocumentParseWorker.java backend/src/test/java/com/ajt/backend/domain/document/model/AiJobTest.java backend/src/test/java/com/ajt/backend/domain/document/service/DocumentParseWorkerTest.java && git commit -m "feat(document): AI 작업별 영향 위키 저장"`

### Task 2: AI 작업 조회 계약에 영향 Wiki를 공개한다

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/api/AiJobResponse.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/AiJobQueryService.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/document/service/AiJobQueryServiceTest.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/document/api/AiJobControllerTest.java`
- Modify: `docs/api/postman-contract-examples.mjs`
- Modify: `docs/api/generate-postman-collections.mjs`
- Generate: `docs/api/AJT-Backend-Public-API.postman_collection.json`

**Interfaces:**
- Consumes: `DocumentParseResult.affectedWikis()`.
- Produces: `DocumentResultResponse.affectedWikis(): List<AffectedWikiResponse>` with string `wikiId`, string `title`.

- [ ] **Step 1: Write the failing query-service test**

```java
assertThat(response.documentResults().getFirst().affectedWikis())
        .containsExactly(new AiJobResponse.AffectedWikiResponse("101", "휴가 규정"));
assertThat(response.documentResults().get(1).affectedWikis()).isEmpty();
```

- [ ] **Step 2: Run test to verify RED**

Run: `./gradlew test --tests com.ajt.backend.domain.document.service.AiJobQueryServiceTest`

Expected: compilation failure because `DocumentResultResponse.affectedWikis()` does not exist.

- [ ] **Step 3: Write minimal response mapping**

```java
public record AffectedWikiResponse(String wikiId, String title) {}
private List<AffectedWikiResponse> affectedWikisOf(AiJob.DocumentParseResult result) {
    return result == null ? List.of() : result.affectedWikis().stream()
            .map(wiki -> new AffectedWikiResponse(String.valueOf(wiki.wikiId()), wiki.title())).toList();
}
```

Append the list to `DocumentResultResponse`, always return `[]`, and update controller constructor fixtures and JSON assertions.

- [ ] **Step 4: Update contract source and generate it**

Add `affectedWikis` to both AI-job success examples in `docs/api/postman-contract-examples.mjs`; document it in both list and single-job descriptions in `docs/api/generate-postman-collections.mjs`; then run `node docs/api/generate-postman-collections.mjs && node scripts/validate-artifact-consistency.mjs`.

- [ ] **Step 5: Run API tests to verify GREEN**

Run: `./gradlew test --tests com.ajt.backend.domain.document.service.AiJobQueryServiceTest --tests com.ajt.backend.domain.document.api.AiJobControllerTest`

Expected: completed results return snapshots; failed/ongoing ones return `[]`.

- [ ] **Step 6: Commit**

Run: `git add backend/src/main/java/com/ajt/backend/domain/document/api/AiJobResponse.java backend/src/main/java/com/ajt/backend/domain/document/service/AiJobQueryService.java backend/src/test/java/com/ajt/backend/domain/document/service/AiJobQueryServiceTest.java backend/src/test/java/com/ajt/backend/domain/document/api/AiJobControllerTest.java docs/api/postman-contract-examples.mjs docs/api/generate-postman-collections.mjs docs/api/AJT-Backend-Public-API.postman_collection.json && git commit -m "feat(document): AI 작업 영향 위키 응답 제공"`

### Task 3: 요약 목록이 작업 결과 Wiki를 링크로 표시한다

**Files:**
- Modify: `frontend/src/features/document/schema.js`
- Modify: `frontend/src/features/document/relatedWikiLink.js`
- Modify: `frontend/src/features/document/relatedWikiLink.test.js`
- Modify: `frontend/src/features/document/pages/AiJobSummaryListPage.jsx`

**Interfaces:**
- Consumes: `result.affectedWikis?: Array<{wikiId: string, title: string}>` and legacy `document.relatedWikis`.
- Produces: `affectedWikiDisplay(result, document): { firstWiki, extraCount }`.

- [ ] **Step 1: Write failing frontend helper tests**

```js
expect(affectedWikiDisplay(
  { affectedWikis: [{ wikiId: '101', title: '휴가 규정' }] }, { relatedWikis: [] },
)).toEqual({ firstWiki: { wikiId: '101', title: '휴가 규정' }, extraCount: 0 })
expect(affectedWikiDisplay({}, { relatedWikis: [{ wikiId: '42', title: '기존 문서' }] }))
  .toEqual({ firstWiki: { wikiId: '42', title: '기존 문서' }, extraCount: 0 })
```

- [ ] **Step 2: Run test to verify RED**

Run: `npm test -- --run src/features/document/relatedWikiLink.test.js`

Expected: failure because `affectedWikiDisplay` is not exported.

- [ ] **Step 3: Write minimal display and rendering implementation**

```js
export function affectedWikiDisplay(result, document) {
  const wikis = Array.isArray(result?.affectedWikis) ? result.affectedWikis : document?.relatedWikis ?? []
  return { firstWiki: wikis[0] ?? null, extraCount: Math.max(0, wikis.length - 1) }
}
```

Use it in `AiJobSummaryListPage`; render the first title as `Link` to `/wiki/${wikiId}` and add ` 외 N건` when needed. Keep `-` with no Wiki and add `affectedWikis` to the response JSDoc.

- [ ] **Step 4: Run frontend verification**

Run: `npm test -- --run src/features/document/relatedWikiLink.test.js && npm run lint && npm run build`

Expected: all commands exit 0.

- [ ] **Step 5: Commit**

Run: `git add frontend/src/features/document/schema.js frontend/src/features/document/relatedWikiLink.js frontend/src/features/document/relatedWikiLink.test.js frontend/src/features/document/pages/AiJobSummaryListPage.jsx && git commit -m "fix(document): 작업 결과 위키를 요약에 표시"`

### Task 4: 통합 검증

**Files:** Verify only.

- [ ] **Step 1: Run complete backend tests**

Run: `./gradlew test`

Expected: PASS.

- [ ] **Step 2: Run full frontend tests**

Run: `npm test -- --run`

Expected: PASS.

- [ ] **Step 3: Inspect scope**

Run: `git diff --check HEAD^ HEAD && git status --short`

Expected: no whitespace errors and no AI-server or ERD change.

### Task 5: 삭제 이력·다건 선택·작업 유형을 작업 결과에 표시한다

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/model/AiJob.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentParseWorker.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentWikiTransformationTransactionService.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/api/AiJobResponse.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/AiJobQueryService.java`
- Modify: `frontend/src/features/document/relatedWikiLink.js`
- Modify: `frontend/src/features/document/relatedWikiLink.test.js`
- Modify: `frontend/src/features/document/pages/AiJobSummaryListPage.jsx`
- Modify: `frontend/src/features/document/schema.js`
- Modify: `docs/api/postman-contract-examples.mjs`
- Modify: `docs/api/generate-postman-collections.mjs`
- Generate: `docs/api/AJT-Backend-Public-API.postman_collection.json`

**Interfaces:**
- `affectedWikis[]` adds `deleted: boolean`; deleted values retain only the pre-delete title and must not be linked.
- `DocumentResultResponse` exposes `changeType` as `document_added`, `document_replaced`, or `document_removed`.
- The UI uses an inline expandable list: first Wiki title opens its page; `외 N건` toggles the remaining entries.

- [ ] **Step 1: Write backend failing tests**

Test that a deletion pre-pass records each deleted Wiki title as `AffectedWiki` with `deleted=true`, a surviving Wiki update as `deleted=false`, and a result response has `changeType="document_removed"`.

- [ ] **Step 2: Verify RED, then implement the minimal snapshot flow**

Run: `./gradlew test --tests com.ajt.backend.domain.document.service.DocumentParseWorkerTest --tests com.ajt.backend.domain.document.service.AiJobQueryServiceTest`

Extend the affected Wiki snapshot with a deletion flag; have prune capture ID and title before deletion; merge its deleted snapshots with surviving transformation snapshots in document removal; map a stored job change type to the response, defaulting missing historical types to `document_added`.

- [ ] **Step 3: Update contract and verify backend GREEN**

Document and exemplify `changeType` and `affectedWikis[].deleted`; generate the collection; run focused backend tests. Preserve `affectedWikis: []` for failures and historical records.

- [ ] **Step 4: Write frontend failing helper tests**

Test result list priority, `deleted=true` non-link semantics, multiple Wiki `extraCount`, and change type labels for add/replace/remove.

- [ ] **Step 5: Verify RED, implement UI, and verify GREEN**

Run: `npm test -- --run src/features/document/relatedWikiLink.test.js`

Render a colored `문서 추가`/`문서 교체`/`문서 삭제` badge under each filename and card-header counts. Render the first affected Wiki title as a link when not deleted; `외 N건` toggles a list of every affected Wiki. Deleted entries show title plus `삭제됨` and are never links.

- [ ] **Step 6: Run quality checks and commit**

Run: `./gradlew test --tests com.ajt.backend.domain.document.service.DocumentParseWorkerTest --tests com.ajt.backend.domain.document.service.AiJobQueryServiceTest && npm test -- --run src/features/document/relatedWikiLink.test.js && npm run lint && npm run build`

Commit the task files with `feat(document): 작업 이력 변경 결과를 구분 표시`.
