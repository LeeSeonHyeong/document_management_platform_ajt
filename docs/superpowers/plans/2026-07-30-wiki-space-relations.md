# Wiki 범위 링크 그래프 조회 창구 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 범위 전체의 Wiki 참조 그래프를 한 번의 호출로 돌려주는 내부 창구 엔드포인트를 추가한다.

**Architecture:** 기존 Wiki 조회 창구(`InternalWikiQueryController` + `InternalWikiQueryService`)에 8번째 엔드포인트를 붙인다. 새 클래스·새 리포지토리 메서드·ERD 변경이 없다. `wiki.wiki_refs`·`wiki.document_refs` JSON 컬럼을 엔티티로 읽어 문자열 ID 배열로 옮겨 담을 뿐이다.

**Tech Stack:** Java · Spring Boot · Spring Data JPA · JUnit 5 · Mockito(BDD) · AssertJ · MockMvc · Gradle

**설계 문서:** `docs/superpowers/specs/2026-07-30-wiki-space-relations-design.md`

## Global Constraints

- **기존 파일에는 추가만 한다. 삭제·수정 없음.** 백엔드 담당과 합의한 조건이다. 유일한 예외는 `InternalWikiQuerySecurityTest` 의 endpoint 목록과 그 `@DisplayName` 문자열(7개 → 8개)이다.
- **ERD 를 바꾸지 않는다.** `docs/db/erd.sql` 이 정답이고 기존 컬럼을 읽기만 한다.
- **계약이 정본이다.** `docs/api/AJT-FastAPI-Internal-API.postman_collection.json` 의 "Wiki 범위 관계". 계약에 없는 상태·필드·오류 코드를 만들지 않는다.
- **응답 규칙:** ID 는 JSON 문자열, 빈 목록은 `[]`, 필드명은 `camelCase`.
- **끊어진 참조를 걸러내지 않는다.** `wiki_refs` 를 원본 그대로 싣는다 (설계 4절).
- **계약 버전 1.6.1.** 이미 커밋됨 (`18a09e3`). 이 계획에서 계약 파일을 다시 건드리지 않는다.
- **브랜치:** `feature/S15P11B106-153-wiki-space-relations`. 커밋 메시지에 `[S15P11B106-153]` 을 단다.
- 작업 디렉터리는 저장소 루트다. 테스트는 `backend/` 에서 `./gradlew` 로 돌린다.

---

## File Structure

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `backend/src/main/java/com/ajt/backend/domain/wiki/service/InternalWikiQueryService.java` | 범위 그래프 조회 로직 + 응답 레코드 2개 | 추가 |
| `backend/src/main/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQueryController.java` | HTTP 매핑과 capability 인가 | 추가 |
| `backend/src/test/java/com/ajt/backend/domain/wiki/service/InternalWikiQueryServiceTest.java` | 서비스 단위 테스트 | 추가 |
| `backend/src/test/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQueryControllerTest.java` | 라우팅·응답 모양 | 추가 |
| `backend/src/test/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQuerySecurityTest.java` | 인증·capability 숨김 | 추가 + 목록 1줄 수정 |

작업 순서는 Task 1(서비스) → Task 2(컨트롤러) → Task 3(보안 회귀). 각 Task 는 독립적으로 테스트가 돌고 커밋된다.

---

### Task 1: 서비스 — 범위 그래프 조회

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/InternalWikiQueryService.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/wiki/service/InternalWikiQueryServiceTest.java`

**Interfaces:**
- Consumes: 기존 `requireScope(String scopeKey) -> WikiScope` (private, 없는 범위에 `BusinessException(ErrorCode.WIKI_SCOPE_NOT_FOUND)`), `wikiRepository.findAllByScopeKey(String) -> List<Wiki>`, `Wiki.id() -> Long`, `Wiki.wikiRefs() -> List<Long>`, `Wiki.documentRefs() -> List<Long>`
- Produces: `InternalWikiQueryService.spaceRelations(String scopeKey) -> WikiSpaceRelations`, `record WikiSpaceRelations(long scopeVersion, List<WikiRelationItem> items)`, `record WikiRelationItem(String wikiId, List<String> wikiRefs, List<String> documentRefs)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`InternalWikiQueryServiceTest` 클래스 **끝의 닫는 중괄호 바로 앞**에 아래 네 테스트를 추가한다. 클래스 상단의 mock 필드와 `service` 필드는 이미 있으므로 손대지 않는다.

```java
    @Test
    void returnsEveryWikiEdgeInScopeWithCurrentScopeVersion() {
        WikiScope scope = mock(WikiScope.class);
        Wiki first = mock(Wiki.class);
        Wiki second = mock(Wiki.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiRepository.findAllByScopeKey("D1-D2")).willReturn(List.of(first, second));
        given(first.id()).willReturn(101L);
        given(first.wikiRefs()).willReturn(List.of(102L, 115L));
        given(first.documentRefs()).willReturn(List.of(15L));
        given(second.id()).willReturn(102L);
        given(second.wikiRefs()).willReturn(List.of());
        given(second.documentRefs()).willReturn(List.of(15L, 16L));

        InternalWikiQueryService.WikiSpaceRelations response = service.spaceRelations("D1-D2");

        assertThat(response.scopeVersion()).isEqualTo(47L);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).wikiId()).isEqualTo("101");
        assertThat(response.items().get(0).wikiRefs()).containsExactly("102", "115");
        assertThat(response.items().get(0).documentRefs()).containsExactly("15");
        assertThat(response.items().get(1).wikiId()).isEqualTo("102");
        assertThat(response.items().get(1).wikiRefs()).isEmpty();
        assertThat(response.items().get(1).documentRefs()).containsExactly("15", "16");
    }

    @Test
    void returnsEmptyItemsForScopeWithoutWiki() {
        WikiScope scope = mock(WikiScope.class);
        given(scope.scopeVersion()).willReturn(3L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiRepository.findAllByScopeKey("D1-D2")).willReturn(List.of());

        InternalWikiQueryService.WikiSpaceRelations response = service.spaceRelations("D1-D2");

        assertThat(response.scopeVersion()).isEqualTo(3L);
        assertThat(response.items()).isEmpty();
    }

    @Test
    void hidesSpaceRelationsForUnknownScope() {
        given(wikiScopeRepository.findById("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.spaceRelations("NOPE"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WIKI_SCOPE_NOT_FOUND);
    }

    @Test
    void keepsDanglingWikiRefsInsteadOfFilteringThem() {
        // 설계 4절 — 끊어진 참조를 걸러내면 데이터가 이미 깨져 있다는 사실이 숨는다.
        // 999 는 이 범위에 없는 Wiki 다. 그래도 응답에 그대로 실려야 한다.
        WikiScope scope = mock(WikiScope.class);
        Wiki only = mock(Wiki.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiRepository.findAllByScopeKey("D1-D2")).willReturn(List.of(only));
        given(only.id()).willReturn(101L);
        given(only.wikiRefs()).willReturn(List.of(999L));
        given(only.documentRefs()).willReturn(List.of());

        InternalWikiQueryService.WikiSpaceRelations response = service.spaceRelations("D1-D2");

        assertThat(response.items().get(0).wikiRefs()).containsExactly("999");
    }
```

`hasFieldOrPropertyWithValue("errorCode", ...)` 가 이 저장소의 `BusinessException` 과 맞지 않으면, 같은 파일의 기존 404 테스트(`hidesWikiOutsideRequestedScope`)가 쓰는 단언 방식을 그대로 따른다. **기존 방식이 정답이다.**

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd backend && ./gradlew test --tests 'com.ajt.backend.domain.wiki.service.InternalWikiQueryServiceTest'`

Expected: 컴파일 실패. `spaceRelations` 와 `WikiSpaceRelations` 가 없다는 오류.

- [ ] **Step 3: 서비스 메서드를 추가한다**

`InternalWikiQueryService` 의 기존 `relations(String scopeKey, long wikiId)` 메서드 **바로 아래**에 추가한다. 기존 `relations` 는 건드리지 않는다.

```java
    /**
     * 범위 전체의 참조 그래프를 한 번에 돌려줍니다.
     *
     * <p>에이전트가 병합·삭제로 남의 링크를 깨뜨리지 않으려면 범위 전체의 간선을 알아야
     * 합니다. Wiki 1건씩 조회하면 장수만큼 호출이 나가므로 FastAPI 는 이 API 를 1회
     * 호출합니다.
     *
     * <p>역링크는 싣지 않습니다. 전체 간선이 있으면 소비자가 뒤집어 구할 수 있고, 여기서
     * 계산하면 Wiki 마다 전체를 훑는 O(n²) 이 됩니다.
     *
     * <p>{@code wiki_refs} 를 원본 그대로 싣습니다. 존재하지 않는 Wiki 를 가리키는 ID 를
     * 걸러내지 않습니다 — 삭제 정리가 트랜잭션 안에서 돌아 그런 값이 생길 실제 경로가 없고,
     * 필터는 데이터가 이미 깨져 있다는 사실을 숨깁니다.
     */
    @Transactional(readOnly = true)
    public WikiSpaceRelations spaceRelations(String scopeKey) {
        WikiScope scope = requireScope(scopeKey);
        return new WikiSpaceRelations(
                scope.scopeVersion(),
                wikiRepository.findAllByScopeKey(scopeKey).stream()
                        .map(wiki -> new WikiRelationItem(
                                String.valueOf(wiki.id()),
                                wiki.wikiRefs().stream().map(String::valueOf).toList(),
                                wiki.documentRefs().stream().map(String::valueOf).toList()))
                        .toList());
    }
```

응답 레코드는 기존 `WikiRelations` 레코드 **바로 아래**에 추가한다.

```java
    public record WikiSpaceRelations(long scopeVersion, List<WikiRelationItem> items) {
    }

    public record WikiRelationItem(String wikiId, List<String> wikiRefs,
                                   List<String> documentRefs) {
    }
```

기존 레코드들의 중괄호 스타일(같은 줄 vs 다음 줄)을 그대로 따른다.

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `cd backend && ./gradlew test --tests 'com.ajt.backend.domain.wiki.service.InternalWikiQueryServiceTest'`

Expected: PASS. 기존 테스트 포함 전부 초록.

- [ ] **Step 5: 커밋한다**

```bash
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/InternalWikiQueryService.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/service/InternalWikiQueryServiceTest.java
git commit -m "feat(be): 범위 전체의 Wiki 참조 그래프를 한 번에 조회한다 [S15P11B106-153]

Wiki 1건씩 조회하면 장수만큼 호출이 나간다. wiki_refs·document_refs 는
이미 저장돼 있으므로 범위 단위로 한 번에 돌려준다.

역링크는 싣지 않는다 - 전체 간선이 있으면 소비자가 뒤집어 구한다.
끊어진 참조도 걸러내지 않는다 - 필터는 깨진 데이터를 숨긴다."
```

---

### Task 2: 컨트롤러 — HTTP 매핑

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQueryController.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQueryControllerTest.java`

**Interfaces:**
- Consumes: Task 1 의 `queryService.spaceRelations(String) -> WikiSpaceRelations`, 기존 `authorize(String capability, String scopeKey)` (private, `capabilityService.require` 위임)
- Produces: `GET /internal/v1/wiki-spaces/{scopeKey}/relations`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`InternalWikiQueryControllerTest` 클래스 끝의 닫는 중괄호 바로 앞에 추가한다. `setUp()` 이 이미 `capabilityService.require` 를 스텁하므로 손대지 않는다.

```java
    @Test
    @DisplayName("범위 관계 조회는 capability 검증 뒤 경로의 scopeKey로 위임한다")
    void delegatesSpaceRelationsWithPathScopeKey() throws Exception {
        given(queryService.spaceRelations("ALL")).willReturn(
                new InternalWikiQueryService.WikiSpaceRelations(47L, List.of(
                        new InternalWikiQueryService.WikiRelationItem(
                                "101", List.of("102", "115"), List.of("15")))));

        mockMvc.perform(get("/internal/v1/wiki-spaces/ALL/relations")
                        .header("X-Wiki-Capability", "capability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeVersion").value(47))
                .andExpect(jsonPath("$.items[0].wikiId").value("101"))
                .andExpect(jsonPath("$.items[0].wikiRefs[0]").value("102"))
                .andExpect(jsonPath("$.items[0].wikiRefs[1]").value("115"))
                .andExpect(jsonPath("$.items[0].documentRefs[0]").value("15"));

        then(capabilityService).should().require("capability", "ALL");
    }

    @Test
    @DisplayName("Wiki가 없는 범위도 items를 빈 배열로 돌려준다")
    void returnsEmptyArrayForSpaceWithoutWiki() throws Exception {
        given(queryService.spaceRelations("ALL")).willReturn(
                new InternalWikiQueryService.WikiSpaceRelations(47L, List.of()));

        mockMvc.perform(get("/internal/v1/wiki-spaces/ALL/relations")
                        .header("X-Wiki-Capability", "capability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }
```

`.value("101")` 이 문자열인 것이 중요하다. ID 를 숫자로 내면 계약 위반이고 이 단언이 잡는다.

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd backend && ./gradlew test --tests 'com.ajt.backend.domain.wiki.api.internal.InternalWikiQueryControllerTest'`

Expected: FAIL — 404. 매핑이 없다.

- [ ] **Step 3: 매핑을 추가한다**

기존 `@GetMapping("/internal/v1/wiki-spaces/{scopeKey}/categories")` 메서드 **바로 아래**에 추가한다.

```java
    @GetMapping("/internal/v1/wiki-spaces/{scopeKey}/relations")
    public InternalWikiQueryService.WikiSpaceRelations spaceRelations(@PathVariable String scopeKey,
            @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) { authorize(capability, scopeKey); return queryService.spaceRelations(scopeKey); }
```

이 파일은 한 줄에 몰아 쓰는 기존 스타일을 따른다 — `index`·`categories` 와 같은 모양으로 맞춘다.

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `cd backend && ./gradlew test --tests 'com.ajt.backend.domain.wiki.api.internal.InternalWikiQueryControllerTest'`

Expected: PASS.

- [ ] **Step 5: 커밋한다**

```bash
git add backend/src/main/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQueryController.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQueryControllerTest.java
git commit -m "feat(be): 범위 관계 조회 endpoint를 창구에 붙인다 [S15P11B106-153]

GET /internal/v1/wiki-spaces/{scopeKey}/relations. scopeKey는 경로
변수이고 capability 헤더로 인가한다 - index·categories와 같은 형태다."
```

---

### Task 3: 보안 회귀 — capability 없이는 데이터를 읽기 전에 404

**Files:**
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQuerySecurityTest.java`

**Interfaces:**
- Consumes: Task 2 의 `GET /internal/v1/wiki-spaces/{scopeKey}/relations`
- Produces: 없음 (테스트 전용 Task)

새 endpoint 가 인증 그물에 실제로 걸리는지 확인한다. 이 테스트는 `@SpringBootTest` 라 필터·시큐리티 설정을 통째로 태운다 — Task 2 의 standalone MockMvc 가 확인하지 못하는 층이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`hidesMissingCapabilityForEveryQueryEndpoint` 의 목록에 새 요청을 넣고 `@DisplayName` 을 8개로 고친다. **이 계획에서 기존 줄을 수정하는 유일한 지점이다.**

```java
    @Test
    @DisplayName("8개 조회 endpoint 모두 capability가 없으면 데이터를 읽기 전에 404로 숨긴다")
    void hidesMissingCapabilityForEveryQueryEndpoint() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/internal/v1/wiki-pages").param("scopeKey", "ALL"),
                get("/internal/v1/wikis/101/content").param("scopeKey", "ALL"),
                get("/internal/v1/wikis/101/relations").param("scopeKey", "ALL"),
                get("/internal/v1/wiki-spaces/ALL/index"),
                get("/internal/v1/wiki-spaces/ALL/categories"),
                get("/internal/v1/wiki-spaces/ALL/relations"),
                get("/internal/v1/documents/15/parsed").param("scopeKey", "ALL")
        );

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.header("X-Internal-API-Key", "local-dev-key"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("WIKI_CAPABILITY_EXPIRED"));
        }
    }
```

목록은 7줄이고 이름은 8개다 — `wiki-search` 가 위쪽 별도 테스트에 있기 때문이다. 기존 목록이 이미 그 구조다.

이어서 클래스 끝의 닫는 중괄호 바로 앞에 내부 API 키 누락 테스트를 추가한다.

```java
    @Test
    @DisplayName("범위 관계 조회도 내부 API 키가 없으면 401로 거절한다")
    void rejectsSpaceRelationsWithoutInternalApiKey() throws Exception {
        mockMvc.perform(get("/internal/v1/wiki-spaces/ALL/relations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
```

- [ ] **Step 2: 테스트를 돌린다**

Run: `cd backend && ./gradlew test --tests 'com.ajt.backend.domain.wiki.api.internal.InternalWikiQuerySecurityTest'`

Expected: PASS. Task 2 의 매핑이 이미 `authorize` 를 부르고 필터가 키를 막으므로 통과해야 한다.

**통과하지 않으면 인가 배선이 새 endpoint 를 비껴간 것이다.** `SecurityConfig` 와 `InternalApiKeyFilter` 의 경로 패턴이 `/internal/v1/**` 를 덮는지 확인한다. 패턴이 endpoint 를 개별 열거하고 있으면 그것을 고치는 것이 이 Task 의 본론이 된다.

- [ ] **Step 3: 커밋한다**

```bash
git add backend/src/test/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQuerySecurityTest.java
git commit -m "test(be): 범위 관계 조회가 인증 그물에 걸리는지 확인한다 [S15P11B106-153]

capability 없이 부르면 데이터를 읽기 전에 404, 내부 API 키가 없으면
401. endpoint 목록을 8개로 늘린다."
```

---

### Task 4: 전체 회귀와 계약 정합성

**Files:**
- 없음 (검증 전용 Task)

- [ ] **Step 1: 백엔드 전체 테스트를 돌린다**

Run: `cd backend && ./gradlew test`

Expected: 기존 실패 0. 새로 깨진 것이 있으면 그것이 이 작업의 부작용인지 먼저 판단한다 — `main` 대비 새로 생긴 실패만 고친다.

- [ ] **Step 2: 계약 정합성 검사를 돌린다**

Run: `node scripts/validate-artifact-consistency.mjs`

Expected: `Artifact consistency validation passed: 17 tables, 56 public APIs, 15 internal APIs`

계약 파일은 `18a09e3` 에서 이미 갱신했다. 여기서 고칠 것이 나오면 **계약이 아니라 구현을 맞춘다.**

- [ ] **Step 3: 응답이 계약 Example 과 같은지 눈으로 대조한다**

`docs/api/postman-contract-examples.mjs` 의 `"GET /internal/v1/wiki-spaces/:scopeKey/relations"` 블록과 Task 2 테스트의 `jsonPath` 단언을 나란히 놓고 확인한다.

확인 항목: 최상위 키가 `scopeVersion`·`items` 인가. 항목 키가 `wikiId`·`wikiRefs`·`documentRefs` 인가. ID 가 문자열인가.

- [ ] **Step 4: `ai/` 가 안 섞였는지 확인하고 남은 것을 커밋한다**

```bash
git status
```

`backend/` 밖 변경이 없어야 한다 (계약·설계 문서는 앞선 커밋에 있다). 커밋할 것이 남았으면 커밋하고, 없으면 넘어간다.

---

## 완료 판정

- [ ] `cd backend && ./gradlew test` — 기존 실패 0
- [ ] `node scripts/validate-artifact-consistency.mjs` 통과
- [ ] `GET /internal/v1/wiki-spaces/{scopeKey}/relations` 가 계약 Example 과 같은 모양으로 응답한다
- [ ] 기존 `GET /internal/v1/wikis/{wikiId}/relations` 동작 무변경 — 그 테스트가 그대로 통과한다
- [ ] 기존 파일에 삭제·수정이 없다 (`InternalWikiQuerySecurityTest` 의 endpoint 목록·`@DisplayName` 만 예외)
- [ ] ERD 무변경

## MR 에 적을 것

- 백엔드 담당과 「기존 코드 수정이 아니라 추가면 가능」으로 합의한 사실
- 계약 v1.6.0 → 1.6.1 (엔드포인트 추가, patch)
- 설계 4절의 결정 — 끊어진 참조를 걸러내지 않는다
- 설계 3.3 — 기존 `backlinks` 의 참조 종류 구분 협의 항목이 이 MR 로 닫힌다
- 소비자는 AI 서버뿐이고 아직 부르지 않는다. AI 쪽 전환은 별건이다

## 범위 밖

| 항목 | 이유 |
| --- | --- |
| AI 서버가 이 API 를 쓰도록 전환 | 별건. 로컬 그래프 제거를 동반해 순수 추가가 아니다 |
| 기존 `backlinks` 필드 정리 | 삭제·의미 변경은 minor 이상 |
| 프로젝션 쿼리 최적화 | 측정 근거가 없다 (설계 5.5). 응답 모양이 같으므로 나중에 바꿔도 소비자 영향 없음 |
| 끊어진 참조 필터 | 방어할 실제 경로가 없다 (설계 4절) |
