# Document Scope Key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wiki 원본문서 업로드 요청의 공개 범위를 계약상 `ALL` 또는 정렬된 부서 조합 `D1-D2` `scopeKey`로 만든다.

**Architecture:** HTTP·DB 의존성이 없는 작은 도메인 값 객체로 공개 범위 정규화를 분리한다. 이후 업로드 서비스는 이 객체의 결과를 `wiki_scope`와 `document.scope_key`에 그대로 사용한다.

**Tech Stack:** Java 21, Spring Boot 4, JUnit 5, AssertJ

## Global Constraints

- 공개 API 계약의 `visibilityType` 값은 `all` 또는 `department`다.
- 전체 공개는 `ALL`, 부서 공개는 중복 제거·오름차순 정렬한 `D{departmentId}` 결합 문자열이다.
- 부서 공개에는 양수 부서 ID가 하나 이상 있어야 한다.
- 계획 문서는 Git과 Jira에 올리지 않는다.

---

### Task 1: 공개 범위 `scopeKey` 정규화

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/domain/document/ScopeKey.java`
- Create: `backend/src/test/java/com/ajt/backend/domain/document/ScopeKeyTest.java`

**Interfaces:**
- Produces: `ScopeKey.from(String visibilityType, Collection<Long> departmentIds): ScopeKey`
- Produces: `ScopeKey.value(): String`
- Throws: `IllegalArgumentException` when visibility type is unknown, `all` has department IDs, or `department` has no positive IDs.

- [ ] **Step 1: Write the failing test**

```java
@Test
void department공개는중복제거와오름차순정렬로ScopeKey를만든다() {
    ScopeKey scopeKey = ScopeKey.from("department", List.of(3L, 1L, 3L, 2L));

    assertThat(scopeKey.value()).isEqualTo("D1-D2-D3");
}
```

Add separate tests for `ScopeKey.from("all", List.of())` returning `ALL`, an empty `department` list, a zero department ID, an unknown visibility type, and `all` with a department ID.

- [ ] **Step 2: Run test to verify it fails**

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.document.ScopeKeyTest'`

Expected: FAIL because `ScopeKey` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
public record ScopeKey(String value) {
    public static ScopeKey from(String visibilityType, Collection<Long> departmentIds) {
        // all -> ALL; department -> sorted unique D{id} join
    }
}
```

Reject invalid combinations with `IllegalArgumentException`; do not add persistence, HTTP, or authentication code.

- [ ] **Step 4: Run test to verify it passes**

Run: `bash ./gradlew test --tests 'com.ajt.backend.domain.document.ScopeKeyTest'`

Expected: PASS.

- [ ] **Step 5: Commit code only**

```bash
git add backend/src/main/java/com/ajt/backend/domain/document/ScopeKey.java backend/src/test/java/com/ajt/backend/domain/document/ScopeKeyTest.java
git commit -m "feat(document): 공개 범위 scope key 정규화 추가"
```
