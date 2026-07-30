# Wiki Query Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FastAPI가 요청 단위 capability로만 Wiki와 원본문서 파싱본을 읽는 내부 API 7개를 제공한다.

**Architecture:** `WikiCapabilityService`가 메모리 TTL capability를 발급·검증·폐기한다. `InternalWikiQueryController`는 내부 키와 capability를 검증한 뒤 `InternalWikiQueryService`에 위임하며, 서비스는 현재 저장소와 파일 저장소로 계약 DTO를 조립한다.

**Tech Stack:** Java 21, Spring Boot, Spring Security, Spring Data JPA, MySQL 8 FULLTEXT ngram, JUnit 5, Mockito, MockMvc.

## Global Constraints

- 계약은 `docs/api` v1.6.0이며 DB DDL을 변경하지 않는다.
- capability·본문·파싱본은 로그에 기록하지 않는다.
- 내부 키 오류만 401이고 capability scope·리소스 비노출 오류는 404다. 버전 변경은 최신 `scopeVersion` 200 응답으로 AI가 감지한다.
- 모든 scope 기반 응답은 `scopeVersion`을 포함한다.
- 검색 scope 조건은 Java 후처리가 아니라 SQL에 포함한다.

---

### Task 1: Scope version과 capability 발급 계층

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/model/WikiScope.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/capability/WikiCapability.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/capability/WikiCapabilityService.java`
- Test: `backend/src/test/java/com/ajt/backend/global/ai/capability/WikiCapabilityServiceTest.java`

**Interfaces:** `String issue(String scopeKey, long scopeVersion, Duration ttl)`, `WikiCapability require(String rawCapability, String scopeKey)`, `void revoke(String rawCapability)`, `WikiScope.scopeVersion(): long`.

- [ ] Write a failing test: `issue("D1-D2", 47L, Duration.ZERO)` then `require(..., "D1-D2")` throws `BusinessException(WIKI_CAPABILITY_EXPIRED)`.
- [ ] Run `bash ./gradlew test --tests 'com.ajt.backend.global.ai.capability.WikiCapabilityServiceTest' --console=plain`; expect compile/test failure before implementation.
- [ ] Implement a `ConcurrentHashMap<String, WikiCapability>` registry with opaque `SecureRandom` Base64URL keys; remove expired entries before validation and remove on revoke.
- [ ] Map the existing ERD `scope_version` column with `@Column(name = "scope_version", nullable = false)` and add `scopeVersion()`.
- [ ] Re-run the focused test; expect pass.
- [ ] Commit `feat(ai): Wiki 조회 capability 발급 추가`.

### Task 2: 내부 읽기 서비스와 검색 SQL

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/repository/WikiSearchChunkRepository.java`
- Create: `backend/src/main/java/com/ajt/backend/domain/wiki/service/InternalWikiQueryService.java`
- Create: `backend/src/main/java/com/ajt/backend/domain/wiki/api/internal/*Response.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/wiki/service/InternalWikiQueryServiceTest.java`

**Interfaces:** `search(scopeKey, version, query, limit)`, `pages(scopeKey, version, limit, cursor)`, `content(scopeKey, version, wikiId)`, `relations(scopeKey, version, wikiId)`, `index(scopeKey, version)`, `categories(scopeKey, version)`, `parsedDocument(scopeKey, documentId)`.

- [ ] Write a failing Mockito test verifying `search("D1-D2", 47L, "연차 이월", 10)` calls the SQL-scoped repository query, not a Java filtered list.
- [ ] Run `bash ./gradlew test --tests 'com.ajt.backend.domain.wiki.service.InternalWikiQueryServiceTest' --console=plain`; expect failure.
- [ ] Add `searchByScopeKey(scopeKey, query, limit)` native query using `MATCH(content) AGAINST (:query IN NATURAL LANGUAGE MODE)` and `WHERE scope_key = :scopeKey`.
- [ ] Implement DTO assembly: file-backed content/index/parsed markdown, same-scope page listing, same-scope backlinks, category wiki counts, and `scopeVersion` copied to each response.
- [ ] Translate missing files and out-of-scope IDs to contract 404 error codes without logging contents.
- [ ] Re-run the focused test; expect pass. Commit `feat(wiki): 내부 조회 서비스와 검색 추가`.

### Task 3: 내부 인증과 7개 controller route

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQueryController.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/capability/InternalApiKeyFilter.java`
- Modify: `backend/src/main/java/com/ajt/backend/global/config/SecurityConfig.java`
- Modify: `backend/src/main/java/com/ajt/backend/global/error/ErrorCode.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/wiki/api/internal/InternalWikiQueryControllerTest.java`

**Routes:** `/internal/v1/wiki-search`, `/wiki-pages`, `/wikis/{wikiId}/content`, `/wikis/{wikiId}/relations`, `/wiki-spaces/{scopeKey}/index`, `/wiki-spaces/{scopeKey}/categories`, `/documents/{documentId}/parsed`.

- [ ] Write failing MockMvc cases for all seven 200 payloads plus invalid internal key 401 and expired/out-of-scope capability 404.
- [ ] Run `bash ./gradlew test --tests 'com.ajt.backend.domain.wiki.api.internal.InternalWikiQueryControllerTest' --console=plain`; expect failure.
- [ ] Implement filter that checks `/internal/**` only and compares `X-Internal-API-Key` with `AiApiProperties.internalApiKey()` without exposing expected values.
- [ ] Implement controller parameter validation: `scopeKey`, `query`, `limit` and `cursor`, and pass `X-Wiki-Capability` to `WikiCapabilityService.require` before every service call.
- [ ] Re-run the focused test; expect pass. Commit `feat(wiki): AI 내부 조회 API 추가`.

### Task 4: AI invocation capability forwarding

**Files:**
- Modify: existing Wiki transformation and edit request builders under `backend/src/main/java/com/ajt/backend/domain/wiki/service/`
- Modify: corresponding AI client request DTOs under `backend/src/main/java/com/ajt/backend/global/ai/`
- Test: existing Wiki transformation and Wiki chat client/service tests

- [ ] Write a failing assertion that transform/edit request contains a non-null capability and current scopeVersion, and `revoke` runs in `finally`.
- [ ] Run `bash ./gradlew test --tests '*WikiTransformation*Test' --tests '*WikiChat*Test' --console=plain`; expect failure.
- [ ] Issue capability immediately before the existing non-transactional AI call, attach `wikiCapability` and `scopeVersion`, and revoke in `finally`.
- [ ] Re-run focused tests and `node scripts/validate-artifact-consistency.mjs`; expect pass. Commit `feat(ai): Wiki 조회 capability를 AI 요청에 전달`.

### Task 5: Full verification and MR

**Files:**
- Modify: this plan, checking completed tasks.

- [ ] Run `bash ./gradlew test --console=plain` and `node scripts/validate-artifact-consistency.mjs`.
- [ ] Run `git diff origin/develop...HEAD --check` and inspect `git status --short`.
- [ ] Commit completed checklist, push `feature/S15P11B106-150-wiki-query-gateway`, and create an MR targeting `develop` after !82 has merged.
