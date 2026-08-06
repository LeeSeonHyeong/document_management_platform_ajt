# Wiki 계약·ERD 적응 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** S15P11B106-139의 내부 Wiki 변환 계약과 ERD를 Spring Boot 모델·변환 반영 계층에 적용한다.

**Architecture:** FastAPI가 전달하는 `wikiCategoryRef`와 생성 Wiki의 `wikiPath`를 Spring Boot가 실제 카테고리·Wiki ID로 검증해 저장한다. Wiki 요약과 검색 색인은 Wiki 반영 결과에서 파생하며, 이후 검색 조회와 동시성 제어가 사용할 DB 정본을 유지한다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, MySQL 8.4, Gradle, JUnit 5, Mockito.

## Global Constraints

- `docs/api/AJT-FastAPI-Internal-API.postman_collection.json`과 `docs/db/erd.sql`을 정본으로 사용한다.
- ERD의 테이블·컬럼명·타입·제약을 변경하지 않는다.
- `wikiCategoryRef`는 같은 응답의 `tempCategoryId` 또는 기존 `wikiCategoryId`만 허용한다.
- `wikiPath`는 `action=create`에서만 사용하며 AI가 발급한 상대 Wiki 경로를 보존한다.
- 테스트는 먼저 실패하도록 작성하고, `bash ./gradlew test --rerun-tasks --console=plain`으로 검증한다.

---

### Task 1: 계약 DTO와 Wiki 영속 모델 적응

**Files:**

- Modify: `backend/src/main/java/com/ajt/backend/global/ai/client/WikiTransformationResponse.java`
- Modify: `backend/src/main/java/com/ajt/backend/global/ai/client/WikiEditResponse.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/model/Wiki.java`
- Test: `backend/src/test/java/com/ajt/backend/global/ai/client/RestClientAiClientTest.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/wiki/model/WikiTest.java`

**Consumes:** FastAPI `wikiChanges[].wikiCategoryRef`, 생성 변경의 `wikiPath`, `indexEntries[].summary`.

**Produces:** `WikiChange.wikiCategoryRef()`, `WikiChange.wikiPath()`, Wiki의 `summary`, `contentHash`, `searchIndexedHash` 접근·변경 메서드.

- [ ] **Step 1: 실패하는 JSON 역직렬화 테스트를 작성한다.**

```java
assertThat(response.wikiChanges().getFirst().wikiCategoryRef()).isEqualTo("category-temp-1");
assertThat(response.wikiChanges().getFirst().wikiPath())
        .isEqualTo("wiki/D1-D2/pages/a3f2c1d4.md");
```

- [ ] **Step 2: 테스트가 기존 `categoryId` DTO에서 실패하는지 확인한다.**

Run: `bash ./gradlew test --tests '*RestClientAiClientTest' --rerun-tasks --console=plain`

- [ ] **Step 3: DTO와 `Wiki` 엔티티를 ERD대로 변경한다.**

```java
public record WikiChange(
        String action, String wikiId, String tempWikiId,
        String wikiCategoryRef, String wikiPath, String title,
        String contentMarkdown, List<Evidence> evidence
) {}
```

`Wiki`에는 `summary`, `content_hash`, `search_indexed_hash` 컬럼과 해당 값을 갱신하는 도메인 메서드를 둔다.

- [ ] **Step 4: DTO·엔티티 단위 테스트를 실행한다.**

Run: `bash ./gradlew test --tests '*RestClientAiClientTest' --tests '*WikiTest' --rerun-tasks --console=plain`
Expected: PASS.

### Task 2: 참조 기반 카테고리와 AI 발급 경로 반영

**Files:**

- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java`

**Consumes:** Task 1의 `wikiCategoryRef`와 `wikiPath`.

**Produces:** 카테고리 자동 추정 없이 생성·수정 Wiki에 올바른 카테고리·파일 경로를 저장하는 `apply` 동작.

- [ ] **Step 1: 복수 신규 카테고리에서 각 Wiki가 자신의 임시 참조를 쓰는 실패 테스트를 작성한다.**

```java
assertThat(first.getWikiCategoryId()).isEqualTo(firstCategory.id());
assertThat(second.getWikiCategoryId()).isEqualTo(secondCategory.id());
assertThat(first.getWikiPath()).isEqualTo("wiki/D1/pages/first.md");
```

- [ ] **Step 2: 기존 단일 카테고리 자동 선택 분기가 실패 원인임을 확인한다.**

Run: `bash ./gradlew test --tests '*WikiTransformationApplierTest' --rerun-tasks --console=plain`

- [ ] **Step 3: `resolveCategoryId`의 fallback을 제거한다.**

`wikiCategoryRef`가 임시 참조면 생성된 실제 ID로, 숫자 기존 ID면 동일 scope의 카테고리로 해석하고, 없거나 다른 scope면 `IllegalArgumentException`을 던진다. 생성 시 `assignStoragePath()` 대신 계약의 `wikiPath`를 검증해 저장한다.

- [ ] **Step 4: 변환 반영 테스트를 실행한다.**

Run: `bash ./gradlew test --tests '*WikiTransformationApplierTest' --rerun-tasks --console=plain`
Expected: PASS.

### Task 3: index summary와 검색 정본 동기화

**Files:**

- Create: `backend/src/main/java/com/ajt/backend/domain/wiki/model/WikiSearchChunk.java`
- Create: `backend/src/main/java/com/ajt/backend/domain/wiki/repository/WikiSearchChunkRepository.java`
- Create: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiSearchIndexer.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiSearchIndexerTest.java`

**Consumes:** 반영된 Wiki 본문과 `indexEntries[].summary`.

**Produces:** Wiki summary 갱신, 본문 청크·breadcrumb·SHA-256 hash가 담긴 `wiki_search_chunk` 행, `searchIndexedHash` 동기화.

- [ ] **Step 1: 요약과 검색 청크 갱신 실패 테스트를 작성한다.**

```java
assertThat(wiki.summary()).isEqualTo("정리된 휴가 규정");
assertThat(chunks).extracting(WikiSearchChunk::content)
        .contains("연차 신청은 3일 전까지 등록한다.");
assertThat(wiki.contentHash()).isEqualTo(wiki.searchIndexedHash());
```

- [ ] **Step 2: 테스트가 새 색인 계층 부재로 실패하는지 확인한다.**

Run: `bash ./gradlew test --tests '*WikiSearchIndexerTest' --rerun-tasks --console=plain`

- [ ] **Step 3: 청킹·저장·해시 동기화를 구현한다.**

반영된 Wiki별 기존 청크를 삭제하고, Markdown 헤딩 경로를 breadcrumb로 보존한 순번 청크를 저장한다. SHA-256을 `content_hash`와 `search_indexed_hash`에 동일하게 기록한다.

- [ ] **Step 4: 색인 단위 테스트를 실행한다.**

Run: `bash ./gradlew test --tests '*WikiSearchIndexerTest' --rerun-tasks --console=plain`
Expected: PASS.

### Task 4: 계약·ERD 정합성 및 회귀 검증

**Files:**

- Modify: `backend/src/main/resources/application.yml` (필요 시 JDBC URL의 UTF-8 설정만)
- Test: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java`

**Consumes:** Tasks 1–3의 모델·반영 동작.

**Produces:** 최신 계약을 소비하고 ERD 매핑을 보존하는 회귀 방지 검증.

- [ ] **Step 1: create/update/delete와 category ref 오류의 회귀 테스트를 보강한다.**

```java
assertThatThrownBy(() -> applier.apply("D1", responseWithUnknownCategoryRef))
        .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: 계약 및 아티팩트 검증을 실행한다.**

Run: `node docs/api/validate-postman-collections.mjs && node scripts/validate-artifact-consistency.mjs`
Expected: exit 0.

- [ ] **Step 3: 전체 백엔드 테스트를 캐시 없이 실행한다.**

Run: `bash ./gradlew test --rerun-tasks --console=plain`
Expected: BUILD SUCCESSFUL.

## Execution Handoff

1. **Subagent-Driven** — 작업별 독립 구현과 검토를 병렬화한다.
2. **Inline Execution** — 현재 세션에서 Task 1부터 TDD로 순차 구현한다.

사용자가 “1번부터”를 선택했으므로 Inline Execution으로 Task 1을 시작한다.
