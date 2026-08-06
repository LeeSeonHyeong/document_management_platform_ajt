# AI 발급 Wiki 경로 반영 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FastAPI가 발급한 Wiki 상대 경로에 본문을 저장하고 같은 scope의 유효한 Wiki 페이지 링크만 반영한다.

**Architecture:** `WikiFileStorage`가 완성된 `wikiPath`를 직접 저장해 ID 기반 경로 파생을 없앤다. `WikiTransformationApplier`는 기존·같은 응답의 신규 Wiki 경로를 합친 집합으로 Markdown 링크를 검증한 후 파일과 검색 색인을 갱신한다.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, Gradle.

## Global Constraints

- 생성 변경의 `wikiPath`는 `wiki/{scopeKey}/pages/{pageKey}.md` 전체 상대 경로로 필수다.
- DB 스키마와 FastAPI 계약은 변경하지 않는다.
- `pages/{pageKey}.md` 링크는 ID로 치환하지 않는다.
- 모든 production code는 실패하는 테스트를 확인한 다음에 작성한다.

---

## File Structure

- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/storage/WikiFileStorage.java` — 전체 상대 경로 저장 인터페이스.
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/storage/LocalWikiFileStorage.java` — AI 경로 실제 저장.
- Create: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiMarkdownLinkValidator.java` — 본문 Wiki 링크 검증.
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java` — 생성 경로·중복·링크 검증 반영.
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/storage/LocalWikiFileStorageTest.java`
- Create: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiMarkdownLinkValidatorTest.java`
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java`

### Task 1: AI 발급 경로로 Markdown 저장

**Files:** `WikiFileStorage.java`, `LocalWikiFileStorage.java`, `LocalWikiFileStorageTest.java`

**Produces:** `void storeWikiMarkdown(String wikiPath, String contentMarkdown) throws IOException`.

- [ ] **Step 1: Write the failing storage test**

```java
String path = "wiki/ALL/pages/leave-policy-a3f2.md";
storage.storeWikiMarkdown(path, "# 휴가 규정");
assertThat(tempDirectory.resolve(path)).hasContent("# 휴가 규정");
```

- [ ] **Step 2: Verify RED**

Run: `bash ./gradlew test --tests com.ajt.backend.domain.wiki.storage.LocalWikiFileStorageTest --rerun-tasks --console=plain`

Expected: FAIL because storage only accepts `scopeKey` and `wikiId`.

- [ ] **Step 3: Implement the path-based signature**

```java
void storeWikiMarkdown(String wikiPath, String contentMarkdown) throws IOException;

public void storeWikiMarkdown(String wikiPath, String contentMarkdown) throws IOException {
    writeString(wikiPath, contentMarkdown);
}
```

- [ ] **Step 4: Verify GREEN and commit**

Run: `bash ./gradlew test --tests com.ajt.backend.domain.wiki.storage.LocalWikiFileStorageTest --rerun-tasks --console=plain`

Expected: PASS.

```bash
git add backend/src/main/java/com/ajt/backend/domain/wiki/storage/WikiFileStorage.java backend/src/main/java/com/ajt/backend/domain/wiki/storage/LocalWikiFileStorage.java backend/src/test/java/com/ajt/backend/domain/wiki/storage/LocalWikiFileStorageTest.java
git commit -m "refactor(wiki): AI 발급 경로로 본문 저장"
```

### Task 2: Markdown Wiki 페이지 링크 검증

**Files:** `WikiMarkdownLinkValidator.java`, `WikiMarkdownLinkValidatorTest.java`

**Produces:** `void validate(String markdown, Set<String> allowedAddresses)`.

- [ ] **Step 1: Write failing validation tests**

```java
Set<String> allowed = Set.of("pages/leave-policy-a3f2.md", "pages/security-b7c1.md");
assertThatCode(() -> validator.validate("[보안](pages/security-b7c1.md)", allowed)).doesNotThrowAnyException();
assertThatThrownBy(() -> validator.validate("[없는 문서](pages/missing.md)", allowed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Wiki 페이지 링크");
```

- [ ] **Step 2: Verify RED**

Run: `bash ./gradlew test --tests com.ajt.backend.domain.wiki.service.WikiMarkdownLinkValidatorTest --rerun-tasks --console=plain`

Expected: FAIL because `WikiMarkdownLinkValidator` does not exist.

- [ ] **Step 3: Implement the minimal validator**

```java
private static final Pattern MARKDOWN_LINK = Pattern.compile("(?<!!)\\[[^]]*]\\(([^)\\s]+)(?:\\s+[^)]*)?\\)");

public void validate(String markdown, Set<String> allowedAddresses) {
    Matcher matcher = MARKDOWN_LINK.matcher(markdown);
    while (matcher.find()) {
        String target = matcher.group(1);
        if (target.startsWith("pages/") && (!target.endsWith(".md") || target.contains("..") || !allowedAddresses.contains(target))) {
            throw new IllegalArgumentException("Wiki 페이지 링크를 찾을 수 없습니다: " + target);
        }
    }
}
```

- [ ] **Step 4: Add `pages/../secret.md` coverage, verify GREEN, and commit**

Run: `bash ./gradlew test --tests com.ajt.backend.domain.wiki.service.WikiMarkdownLinkValidatorTest --rerun-tasks --console=plain`

Expected: PASS.

```bash
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiMarkdownLinkValidator.java backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiMarkdownLinkValidatorTest.java
git commit -m "feat(wiki): 본문 Wiki 링크 검증 추가"
```

### Task 3: 변환 반영에 경로·링크 검증 연결

**Files:** `WikiTransformationApplier.java`, `WikiTransformationApplierTest.java`

**Consumes:** `storeWikiMarkdown(String, String)` and `validate(String, Set<String>)`.

- [ ] **Step 1: Write failing applier tests**

```java
then(wikiFileStorage).should().storeWikiMarkdown(
        "wiki/ALL/pages/leave-policy-a3f2.md", "[보안](pages/security-b7c1.md)"
);
assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, createWithoutPath))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wikiPath");
```

- [ ] **Step 2: Verify RED**

Run: `bash ./gradlew test --tests com.ajt.backend.domain.wiki.service.WikiTransformationApplierTest --rerun-tasks --console=plain`

Expected: FAIL because the applier uses `scopeKey` and `wikiId` and permits missing creation paths.

- [ ] **Step 3: Implement validation before every write**

```java
Set<String> allowedAddresses = allWikiPaths(scopeKey, createdWikis);
linkValidator.validate(contentMarkdown, allowedAddresses);
wikiFileStorage.storeWikiMarkdown(wiki.wikiPath(), contentMarkdown);
```

Require each create path, reject duplicate create paths, and use the stored `wiki.wikiPath()` for update and delete operations.

- [ ] **Step 4: Verify GREEN and complete backend regression**

Run: `bash ./gradlew test --tests com.ajt.backend.domain.wiki.service.WikiTransformationApplierTest --rerun-tasks --console=plain`

Expected: PASS.

Run: `bash ./gradlew test --rerun-tasks --console=plain`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java
git commit -m "feat(wiki): AI 경로 기반 Wiki 변환 반영"
```
