# 목차를 DB에서 그린다 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 목차(`index.md`)가 이제 없는 페이지를 가리켜 문서 처리가 영구히 막히는 것을 없앤다.

**Architecture:** 목차의 **모든 이름을 `wiki.wiki_path` 하나에서 유도**한다. 지금은 Spring 목차가 `pages/{wikiId}.md`, 실제 페이지는 `wiki_path` 기반 주소라 두 이름이 갈리고, AI 쪽 변환(`rewrite_index_links`)이 그 간극을 메우다 실패한다. 유도 출처를 하나로 만들면 변환이 필요 없어지고 불일치가 생길 수 없다. 동시에 목차를 **AI가 준 항목이 아니라 DB의 살아 있는 Wiki 전체**로 그려, 죽은 항목이 구조적으로 들어올 수 없게 한다. 그 결과 목차를 되읽는 코드가 전부 사라져 `WikiIndex.parse`를 삭제한다.

**Tech Stack:** Java 21, Spring Boot, Gradle, JUnit 5 + AssertJ + Mockito(BDD 스타일), MySQL 8.4

## Global Constraints

- **ERD 무변경.** `wiki.summary`·`wiki.wiki_path` 는 이미 있다. 새 컬럼·표·제약을 만들지 않는다
- **계약 무변경.** `indexEntries` 의 모양(`wikiRef`·`order`·`title`·`summary`)은 그대로 받는다. 역할만 바뀐다 — 「목차 제안」이 아니라 「요약 제안 채널」이다
- **프론트엔드 무변경.** 응답 필드가 바뀌지 않는다. `WikiSummaryResponse.summary` 가 더 자주 채워질 뿐이다
- **AI 서버(`ai/`)를 이 브랜치에서 건드리지 않는다.** AI 쪽 정리(하이드레이션에서 목차 제거, 지침 수정)는 **별건·별 MR** 이다. 이 계획은 백엔드만 다룬다
- **목차 파일은 사용자에게 보이지 않는다.** Spring↔AI 교환 형식이고 챗봇 프롬프트 재료다. 그래서 편집 의도(선별·순서)를 담을 필요가 없다
- **링크 주소의 정본은 `wiki_path` 다. `wikiId` 로 링크를 만들지 않는다.** 이 계획이 닫는 함정이 그것이다
- 커밋 규칙: `../docs/conventions/git-convention.md`. 타입은 영어 소문자, 설명은 한국어, 마침표 없음, **커밋 메시지에 Jira 키를 쓰지 않는다**. 한 커밋에 하나의 목적
- **stage 경로를 하나하나 명시한다.** `git add -A`, `git add .` 금지
- **백엔드 변경이므로 기준선을 먼저 잡는다**: `sh gradlew test` 로 기존 실패 목록을 기록하고, 내가 늘린 실패가 있는지 그 차이로만 판단한다
- 브랜치: `fix/S15P11B106-<티켓번호>-index-from-db` (착수 전 티켓을 백로그에서 먼저 찾고, 없으면 만든다)

## 근거 문서

- 설계: `ai/docs/superpowers/specs/2026-08-05-derived-data-ownership-design.md` §2.1 개정, §3①
- 선행 완료: ②(무방향 관계) — S15P11B106-279, MR !254

---

## File Structure

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiQueryService.java` | Wiki 목록·상세 조회. 요약을 컬럼에서 읽는다 | 수정 (Task 1) |
| `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiIndex.java` | 목차 파일 형식. **쓰기 전용이 된다** | 수정 (Task 2·3) |
| `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java` | 목차를 DB에서 그린다 | 수정 (Task 2) |
| `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiQueryServiceTest.java` | 요약 출처 검증 | 수정 (Task 1) |
| `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiIndexTest.java` | 목차 형식 검증 | 수정 (Task 3) |
| `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java` | 목차 조립 검증 | 수정 (Task 2·3) |

**태스크 순서에 의존이 있다.** Task 1이 `WikiIndex.parse` 호출처 하나를 없애고, Task 2가 나머지 하나를 없앤다. **둘이 끝난 뒤에야** Task 3에서 링크 형식을 바꿀 수 있다 — 되읽는 코드가 남아 있으면 형식을 바꾸는 순간 그 코드가 깨진다.

---

## Task 0: 기준선과 착수 준비

**Files:** 없음 (측정만)

**Interfaces:**
- Consumes: 없음
- Produces: 기존 테스트 실패 목록

- [ ] **Step 1: 백엔드 전체 테스트로 기준선을 기록한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

실패가 있으면 목록을 적어둔다. 0건이 아니어도 진행한다 — 판단 기준은 "내가 늘렸는지"뿐이다.

- [ ] **Step 2: 티켓을 확보하고 브랜치를 만든다**

Jira `S15P11B106` 백로그에서 목차·`dangling-link` 관련 티켓을 먼저 찾는다. 없으면 버그 유형으로 만든다. **백엔드 티켓은 버려진 티켓을 갈아 쓰지 않는다.**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git fetch origin
git switch -c fix/S15P11B106-<티켓번호>-index-from-db origin/develop
```

- [ ] **Step 3: 목차 파일의 현재 상태를 증거로 남긴다**

```bash
cat /Users/wolyong/workspace/AJT/S15P11B106/backend/build/ajt-documents/wiki/ALL/index.md
ls /Users/wolyong/workspace/AJT/S15P11B106/backend/build/ajt-documents/wiki/ALL/pages/
```

목차가 `pages/{숫자}.md` 를 가리키는데 실제 파일 이름은 해시라는 것을 확인해 기록한다. Task 3의 검증에서 이 대조를 다시 쓴다.

---

## Task 1: 요약을 `wiki.summary` 컬럼에서 읽는다

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiQueryService.java` — `listWikis` 부근(요약 채우기), `readIndexSafely` 삭제
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiQueryServiceTest.java`

**Interfaces:**
- Consumes: `Wiki#summary() -> String` (이미 있다), `WikiSummaryResponse(String wikiId, String title, String summary, ...)`
- Produces: 없음 (내부 조회 경로만 바뀐다). **`WikiIndex.parse` 호출처가 둘에서 하나로 줄어든다** — Task 2가 그 전제로 삼는다

**왜 먼저인가.** 지금 `WikiQueryService` 가 목차 마크다운을 파싱해 `wikiId` 로 요약을 찾는다. 목차 링크 형식을 바꾸면 이 조회가 깨진다. 그리고 이 변경만으로 **지금 있는 버그 하나가 고쳐진다** — 목차에 없는 위키의 요약이 화면에 안 뜨는 것.

- [ ] **Step 1: 현재 코드를 읽고 지금 상태를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sed -n '120,145p' src/main/java/com/ajt/backend/domain/wiki/service/WikiQueryService.java
sed -n '305,316p' src/main/java/com/ajt/backend/domain/wiki/service/WikiQueryService.java
```

`// 요약은 wiki 테이블에 없어 공간 목차(index.md)에서 읽는다` 주석과 `index.summaryOf(wiki.id())` 호출, 그리고 `readIndexSafely` 를 확인한다. **그 주석은 이제 사실이 아니다** — `wiki.summary` 컬럼이 있다(`docs/db/erd.sql` 의 `wiki` 표).

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`WikiQueryServiceTest.java` 에 추가한다. 이 파일은 mock 기반이고 헬퍼 `wiki(scopeKey, categoryId, title, id)`·`wikiCategory(id, name)` 가 있다.

핵심은 **목차 파일에 없는 위키의 요약이 응답에 실리는지**다.

```java
    @Test
    @DisplayName("목차에 없는 Wiki 의 요약도 컬럼에서 읽어 채운다")
    void fillsSummaryFromColumnEvenWhenAbsentFromIndex() throws Exception {
        // 목차 파일에 이 위키 항목이 없다. 예전에는 목차에서 요약을 읽어 null 이 됐다.
        // 실데이터에서 확인된 상태다 — 시드로 만든 위키(14·15)는 목차에 들어간 적이 없어,
        // 컬럼에 요약이 있는데도 목록 화면에서 비어 보였다.
        Wiki wiki = wiki("ALL", 9L, "[샘플] 취업규칙 위키", 14L);
        wiki.changeSummary("근무·휴가·복무 규정 요약(시연용)");
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(wikiRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(wiki), PageRequest.of(0, 20), 1));
        given(wikiCategoryRepository.findAllById(any()))
                .willReturn(List.of(wikiCategory(9L, "샘플")));
        given(wikiFileStorage.readIndex("ALL")).willReturn("# 목차");

        WikiListResponse response = service.findWikis(1, 20, null, null, null, null);

        assertThat(response.items().get(0).summary())
                .isEqualTo("근무·휴가·복무 규정 요약(시연용)");
    }
```

**기존 테스트 두 개가 깨진다 — 예상된 것이고, 고치는 방식이 중요하다.**

`adminListsWikis`(:96)와 `employeeListsAccessibleWikis`(:129)가 `summary()` 를 단정하는데, 그 값을 **목차 스텁에서** 얻고 있다. 요약이 컬럼에서 오면 `null` 이 된다.

**단정을 지우지 말고, 요약을 컬럼에 넣어 통과시킨다:**

```java
        Wiki wiki = wiki("ALL", 9L, "휴가 규정", 101L);
        wiki.changeSummary("연차와 반차 사용 기준");     // ← 추가
```

그리고 그 두 테스트의 `given(wikiFileStorage.readIndex(...))` 스텁을 지운다 — 더 이상 쓰이지 않는다. `@DisplayName` 의 "요약은 목차에서 채운다" 도 "요약은 컬럼에서 채운다" 로 고친다.

- [ ] **Step 3: 테스트를 돌려 실패를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiQueryServiceTest*'
```

Expected: FAIL — `summary` 가 `null` 이다 (목차에 그 항목이 없어 `summaryOf` 가 `null` 을 준다).

- [ ] **Step 4: 요약을 컬럼에서 읽게 바꾼다**

`WikiQueryService` 의 목록 조립에서 목차 파싱을 걷어낸다.

- `index.summaryOf(wiki.id())` → `wiki.summary()`
- `Map<String, WikiIndex> indexByScope` 와 그것을 채우는 `computeIfAbsent(...)` 를 지운다
- `readIndexSafely` 를 지운다 (다른 호출처가 없는지 grep 으로 확인한 뒤)
- 주석 `// 요약은 wiki 테이블에 없어 공간 목차(index.md)에서 읽는다 ...` 를 지우고, 그 자리에 왜 컬럼인지 한 줄을 남긴다:

```java
        // 요약은 `wiki.summary` 컬럼이 정본이다. 예전에는 목차 파일을 파싱해 읽었고, 그래서
        // 목차에 항목이 없는 Wiki 는 컬럼에 요약이 있어도 화면에서 비어 보였다.
```

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
grep -rn "readIndexSafely" src/main/java src/test/java
```

- [ ] **Step 5: 테스트를 돌려 통과를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiQueryServiceTest*'
```

Expected: PASS. **`wikiFileStorage` 스텁이 더는 필요 없어져 깨지는 기존 테스트가 있으면** 그 스텁만 지운다 — 단정은 건드리지 않는다.

- [ ] **Step 6: `WikiIndex.parse` 호출처가 하나로 줄었는지 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
grep -rn "WikiIndex.parse\|summaryOf(" src/main/java
```

Expected: `WikiTransformationApplier` 의 한 곳과 `WikiIndex` 자신의 선언만 남는다. `WikiQueryService` 에는 없다.

- [ ] **Step 7: 전체 테스트로 회귀를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

Expected: Task 0 기준선 대비 새 실패 없음.

- [ ] **Step 8: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiQueryService.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiQueryServiceTest.java
git commit -m "$(cat <<'EOF'
fix(wiki): Wiki 요약을 목차 파일이 아니라 컬럼에서 읽는다

wiki.summary 컬럼이 있는데 목록 조회가 목차 마크다운을 파싱해 읽고 있었다. 그래서 목차에
항목이 없는 Wiki 는 컬럼에 요약이 있어도 화면에서 비어 보였다 — 시드로 만든 위키가 그렇다.

목차를 되읽는 코드를 하나 줄인다. 목차 링크 형식을 바꾸려면 되읽는 곳이 없어야 한다.
EOF
)"
```

---

## Task 2: 목차를 DB의 살아 있는 Wiki 전체로 그린다

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java` — `apply`(previousIndex 제거), `writeIndex`, `resolvedEntries`, `survivingPreviousEntries`(삭제)
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiIndex.java` — `parse`·`summaryOf`·`entriesByWikiId` 삭제
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java`

**Interfaces:**
- Consumes: `WikiRepository#findAllByScopeKey(String) -> List<Wiki>`, `Wiki#summary()`·`title()`·`wikiCategoryId()`, `WikiCategoryRepository#findAllByScopeKeyOrderByNameAsc(String)`, `Wiki#changeSummary(String)`
- Produces: `WikiIndex.render(List<Entry>)` 만 남는다 (되읽기 없음). Task 3이 `Entry` 의 링크 표현을 바꾼다

**핵심 전환.** 지금은 **AI가 준 항목만** 목차에 쓴다. 그래서 AI가 언급하지 않은 위키는 빠지고(실측: ALL 공간 9개 중 7개만), AI가 `indexEntries` 를 안 주면 옛 목차를 그대로 유지해 **죽은 항목이 남는다.** 그 두 경로가 B의 입력이다.

바꾼 뒤에는 **그 공간의 살아 있는 Wiki 전체**로 매번 그린다. `indexEntries` 는 요약·제목을 갱신하는 데만 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`WikiTransformationApplierTest.java` 끝(마지막 `}` 앞)에 추가한다. 기존 픽스처(`SCOPE_KEY`·`DOCUMENT_ID`·`applier`·`existingWiki`·`existingCategory`·`wikiFileMutation`)를 그대로 쓴다.

```java
    @Test
    @DisplayName("AI 가 언급하지 않은 Wiki 도 목차에 싣는다")
    void writesEveryLiveWikiIntoTheIndex() throws Exception {
        // 실측(ALL 공간): DB 에 9개인데 목차에는 7개만 있었다. AI 가 만들지 않은 위키(시드)는
        // 목차에 들어간 적이 없어, 그 위키의 요약이 화면에서 비어 보였다.
        existingCategory(10L, "인사");
        Wiki mentioned = existingWiki(101L, 10L, "휴가 규정");
        Wiki unmentioned = existingWiki(108L, 10L, "복지 제도");
        mentioned.changeSummary("연차와 반차 기준");
        unmentioned.changeSummary("사내 복지 안내");

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(), List.of(),
                List.of(new IndexEntry("101", 1, null, "연차와 반차 기준"))));

        ArgumentCaptor<String> index = ArgumentCaptor.forClass(String.class);
        then(wikiFileMutation).should().storeIndex(eq(SCOPE_KEY), index.capture());
        assertThat(index.getValue()).contains("휴가 규정").contains("복지 제도");
    }

    @Test
    @DisplayName("AI 가 목차 항목을 주지 않아도 살아 있는 Wiki 로 다시 그린다")
    void redrawsIndexEvenWithoutIndexEntries() throws Exception {
        // 예전에는 옛 목차를 그대로 유지했다. 그 경로로 죽은 항목이 목차에 남았고,
        // 다음 작업에서 lint 가 dangling-link 로 실패시켜 그 공간이 영구히 막혔다.
        existingCategory(10L, "인사");
        Wiki alive = existingWiki(101L, 10L, "휴가 규정");
        alive.changeSummary("연차와 반차 기준");
        given(wikiFileStorage.readIndex(SCOPE_KEY))
                .willReturn("# 목차\n\n- [사라진 페이지](pages/999.md) — 옛 항목");

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(), List.of(), List.of()));

        ArgumentCaptor<String> index = ArgumentCaptor.forClass(String.class);
        then(wikiFileMutation).should().storeIndex(eq(SCOPE_KEY), index.capture());
        assertThat(index.getValue()).contains("휴가 규정").doesNotContain("사라진 페이지");
    }
```

필요한 import 를 추가한다 (이미 있으면 생략): `org.mockito.ArgumentCaptor`, `static org.mockito.ArgumentMatchers.eq`.

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiTransformationApplierTest*'
```

Expected: 두 테스트 FAIL. 첫째는 목차에 "복지 제도" 가 없어서, 둘째는 "사라진 페이지" 가 남아서.

- [ ] **Step 3: `writeIndex` 를 DB 기준으로 바꾼다**

`WikiTransformationApplier` 에서:

**(a) `resolvedEntries` 를 「요약 갱신」과 「목차 조립」으로 나눈다.** 요약 갱신은 남기고, 조립은 DB 전체로 바꾼다.

```java
    private void writeIndex(
            String scopeKey,
            List<IndexEntry> indexEntries,
            WikiChangeResult wikiResult,
            WikiFileMutation fileMutation
    ) {
        applySummaries(scopeKey, indexEntries, wikiResult.wikiIdsByRef());
        storeIndex(fileMutation, scopeKey, WikiIndex.render(liveEntries(scopeKey)));
    }

    /**
     * AI 가 준 항목은 목차의 모양이 아니라 <b>요약·제목의 제안</b>이다. 목차 파일 자체는
     * DB 로 그린다({@link #liveEntries}) — AI 가 언급하지 않은 Wiki 가 빠지거나, AI 가 항목을
     * 주지 않은 요청에서 옛 목차의 죽은 항목이 남는 것이 이 파일이 깨지는 두 경로였다.
     */
    private void applySummaries(
            String scopeKey, List<IndexEntry> indexEntries, Map<String, Long> wikiIdsByRef) {
        Map<Long, Wiki> wikisById = new LinkedHashMap<>();
        for (Wiki wiki : wikiRepository.findAllByScopeKey(scopeKey)) {
            wikisById.put(wiki.id(), wiki);
        }
        for (IndexEntry indexEntry : indexEntries) {
            long wikiId = resolveWikiRef(indexEntry.wikiRef(), wikiIdsByRef);
            Wiki wiki = wikisById.get(wikiId);
            // 같은 응답에서 삭제됐거나 다른 공간의 Wiki 를 가리키는 항목은 무시한다.
            if (wiki == null) {
                continue;
            }
            wiki.changeSummary(indexEntry.summary());
        }
    }

    /**
     * 목차는 그 공간의 <b>살아 있는 Wiki 전체</b>다. 정렬은 카테고리명 → 제목(사전순) —
     * 목차 파일은 사용자에게 보이지 않으므로 편집 의도를 담을 필요가 없고, 순서가 요청마다
     * 흔들리지 않는 것이 더 중요하다.
     */
    private List<WikiIndex.Entry> liveEntries(String scopeKey) {
        Map<Long, String> categoryNames = new LinkedHashMap<>();
        wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(scopeKey)
                .forEach(category -> categoryNames.put(category.id(), category.name()));
        return wikiRepository.findAllByScopeKey(scopeKey).stream()
                .sorted(Comparator
                        .comparing((Wiki wiki) ->
                                categoryNames.getOrDefault(wiki.wikiCategoryId(), ""))
                        .thenComparing(Wiki::title))
                .map(wiki -> new WikiIndex.Entry(wiki.id(), wiki.title(), wiki.summary()))
                .toList();
    }
```

**(b) `survivingPreviousEntries` 와 `resolvedEntries` 를 삭제한다.**

**(c) `apply` 에서 `previousIndex` 를 걷어낸다.** `WikiIndex previousIndex = WikiIndex.parse(readIndex(scopeKey));` 줄과 `writeIndex(...)` 의 그 인자를 지운다. `readIndex(scopeKey)` 헬퍼가 미사용이 되면 삭제한다 (grep 으로 확인).

**(d) `wikiIdsInScope` 가 미사용이 되면 삭제한다** (grep 으로 확인).

필요한 import: `java.util.Comparator`.

- [ ] **Step 4: `WikiIndex` 를 쓰기 전용으로 만든다**

`WikiIndex.java` 에서 삭제한다:
- `parse(String)`, `summaryOf(long)`, `entries()`, 필드 `entriesByWikiId`, 생성자, `ENTRY_PATTERN`
- **`OrderedEntry`·`sortedByOrder`** — `liveEntries` 가 스스로 정렬하므로 미사용이 된다.
  삭제 전에 grep 으로 확인한다:

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
grep -rn "OrderedEntry\|sortedByOrder" src/main/java src/test/java
```

- `normalize(String)` 은 `Entry` 가 쓰므로 남긴다

클래스 Javadoc 을 고친다 — **되읽지 않는다는 사실을 명시한다:**

```java
/**
 * Wiki 공간 목차(index.md)의 <b>쓰기</b> 형식입니다.
 *
 * <p>목차는 FastAPI 변환 요청의 {@code currentIndex}로 그대로 전달되므로 사람이 읽을 수 있는
 * Markdown이어야 합니다.
 *
 * <p><b>되읽지 않습니다.</b> 예전에는 이 파일을 파싱해 Wiki 요약을 복원했는데, 요약은
 * {@code wiki.summary} 컬럼이 정본이 되어 그 경로가 사라졌습니다. 되읽는 코드가 없으므로
 * 형식을 잘못 가정할 곳도 없습니다.
 */
```

`WikiIndexTest.java` 에서 `parse`·`summaryOf` 를 검증하던 테스트를 삭제한다. **`render` 를 검증하는 테스트는 남긴다.**

- [ ] **Step 5: 테스트를 돌려 통과를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiTransformationApplierTest*' --tests '*WikiIndexTest*'
```

Expected: PASS. 기존 테스트 중 **「AI 가 준 항목만 목차에 실린다」를 전제한 단정**이 깨진다 — 예: `appliesUpdateAndRelation` 의 `storeIndex` 기대 문자열. 그 기대값을 **살아 있는 Wiki 전체·카테고리→제목 순**으로 고친다. 단정을 지우지 말고 새 기대값으로 바꾼다.

- [ ] **Step 6: 되읽는 코드가 남지 않았는지 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
grep -rn "WikiIndex.parse\|summaryOf\|ENTRY_PATTERN" src/main/java src/test/java
```

Expected: **0건.** 하나라도 남으면 Task 3에서 링크 형식을 바꿀 수 없다.

- [ ] **Step 7: 전체 테스트로 회귀를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

Expected: Task 0 기준선 대비 새 실패 없음.

- [ ] **Step 8: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java \
        backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiIndex.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiIndexTest.java
git commit -m "$(cat <<'EOF'
refactor(wiki): 목차를 AI 응답이 아니라 DB 의 살아 있는 Wiki 로 그린다

AI 가 준 항목만 실어서 두 가지가 새고 있었다 — AI 가 언급하지 않은 Wiki 가 목차에서 빠지고
(ALL 공간 9개 중 7개만 있었다), AI 가 항목을 주지 않은 요청에서는 옛 목차를 그대로 둬
죽은 항목이 남았다. 뒤쪽이 그 공간의 문서 처리를 영구히 막는 경로였다.

이제 매번 공간 전체로 그린다. AI 가 준 항목은 요약·제목 제안으로만 쓴다.

목차를 되읽는 코드가 전부 사라져 WikiIndex.parse·summaryOf 를 삭제한다 — 되읽지 않으면
형식을 잘못 가정할 곳도 없다.
EOF
)"
```

---

## Task 3: 목차 링크를 `wiki_path` 에서 유도한다 — B 해결

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiIndex.java` — `Entry` 와 `toMarkdown`
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java` — `liveEntries` 가 링크 키를 넘긴다
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiIndexTest.java`
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java`

**Interfaces:**
- Consumes: `Wiki#wikiPath() -> String` (형태: `wiki/{scopeKey}/pages/{키}.md`)
- Produces: `WikiIndex.Entry(String linkTarget, String title, String summary)` — `wikiId` 를 더 갖지 않는다. `toMarkdown()` 이 `- [title](linkTarget) — summary` 를 낸다

**이것이 B 의 수정이다.** 지금 목차는 `pages/{wikiId}.md` 로 링크하고, AI 하이드레이션은 `wiki_path` 에서 주소를 만든다. 두 이름이 갈려 AI 쪽 변환이 필요하고, 그 변환이 실패한 항목이 `dangling-link` 로 그 공간을 막았다. **유도 출처를 `wiki_path` 하나로 만들면 변환이 필요 없다.**

- [ ] **Step 1: 지금 어긋나 있는 것을 눈으로 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
cat build/ajt-documents/wiki/ALL/index.md
ls build/ajt-documents/wiki/ALL/pages/
```

목차는 `pages/3.md`·`pages/4.md` … 를 가리키는데 실제 파일은 `a67336b0c716.md` 처럼 해시다. **이 불일치가 B다.** (시드로 만든 위키는 파일도 숫자다 — 그래서 섞여 있다.)

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`WikiIndexTest.java` 에 추가한다.

```java
    @Test
    @DisplayName("목차 링크는 wiki_path 에서 유도한다 — wikiId 로 만들지 않는다")
    void linksAreDerivedFromWikiPath() {
        // 하이드레이션이 wiki_path 로 페이지 주소를 만들기 때문에, 목차도 같은 출처를 써야
        // 두 이름이 갈리지 않는다. wikiId 로 만들면 그 간극을 메우는 변환이 필요해지고,
        // 변환이 실패한 항목은 dangling-link 로 그 공간의 문서 처리를 막는다.
        String markdown = WikiIndex.render(List.of(
                new WikiIndex.Entry("pages/a67336b0c716.md", "정보보안 지침", "계정·비밀번호 관리"),
                new WikiIndex.Entry("pages/14.md", "[샘플] 취업규칙 위키", null)));

        assertThat(markdown).isEqualTo("""
                # 목차

                - [정보보안 지침](pages/a67336b0c716.md) — 계정·비밀번호 관리
                - [[샘플] 취업규칙 위키](pages/14.md)""");
    }
```

`WikiTransformationApplierTest.java` 에 추가한다.

```java
    @Test
    @DisplayName("목차 링크가 wiki_path 의 파일 이름을 따른다")
    void indexLinksFollowTheStoredWikiPath() throws Exception {
        // 해시 경로(AI 가 만든 위키)와 숫자 경로(시드가 만든 위키)가 섞여 있어도
        // 둘 다 wiki_path 에서 유도되어야 한다.
        existingCategory(10L, "인사");
        Wiki hashed = existingWiki(101L, 10L, "휴가 규정");
        hashed.assignStoragePath("wiki/ALL/pages/a67336b0c716.md");

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(), List.of(), List.of()));

        ArgumentCaptor<String> index = ArgumentCaptor.forClass(String.class);
        then(wikiFileMutation).should().storeIndex(eq(SCOPE_KEY), index.capture());
        assertThat(index.getValue())
                .contains("(pages/a67336b0c716.md)")
                .doesNotContain("(pages/101.md)");
    }
```

> `existingWiki` 는 `assignStoragePath()`(무인자, wikiId 기반)를 부른다. 위 테스트는 그 뒤에 인자 있는 `assignStoragePath(String)` 으로 해시 경로를 덮어쓴다. 그 메서드의 검증(`wiki/{scope}/pages/` 접두어, `.md` 로 끝남)을 만족하는 값을 쓴다.

- [ ] **Step 3: 테스트를 돌려 실패를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiIndexTest*' --tests '*WikiTransformationApplierTest*'
```

Expected: `linksAreDerivedFromWikiPath` 는 컴파일 실패(`Entry` 가 `long` 을 받는다), `indexLinksFollowTheStoredWikiPath` 는 `(pages/101.md)` 가 나와 FAIL.

- [ ] **Step 4: `Entry` 가 링크 대상을 직접 받게 바꾼다**

`WikiIndex.java`:

```java
    /**
     * @param linkTarget 목차가 링크할 주소. <b>{@code wiki.wiki_path} 에서 유도한다.</b>
     *                   {@code wikiId} 로 만들면 하이드레이션 주소와 갈려 dangling-link 가 된다
     */
    public record Entry(String linkTarget, String title, String summary) {

        public Entry {
            linkTarget = normalize(linkTarget);
            title = normalize(title);
            summary = normalize(summary);
            if (linkTarget == null) {
                throw new IllegalArgumentException("목차 항목 링크 주소는 비어 있을 수 없습니다.");
            }
            if (title == null) {
                throw new IllegalArgumentException("목차 항목 제목은 비어 있을 수 없습니다.");
            }
        }

        String toMarkdown() {
            String link = "- [%s](%s)".formatted(title, linkTarget);
            return summary == null ? link : link + SUMMARY_SEPARATOR + summary;
        }
    }
```

`OrderedEntry`·`sortedByOrder` 가 미사용이 되면 삭제한다 (grep 으로 확인).

- [ ] **Step 5: `liveEntries` 가 `wiki_path` 에서 링크 키를 만든다**

`WikiTransformationApplier` 에 헬퍼를 추가하고 `liveEntries` 의 `map` 을 바꾼다.

```java
    /**
     * 저장 경로에서 목차 링크 주소를 만든다: {@code wiki/{scopeKey}/pages/x.md} → {@code pages/x.md}.
     *
     * <p><b>{@code wikiId} 로 만들지 않는다.</b> AI 하이드레이션이 같은 {@code wiki_path} 로
     * 페이지 주소를 만들기 때문에(그쪽 {@code address_from_wiki_path}), 여기서 다른 출처를 쓰면
     * 두 이름이 갈린다. 갈린 이름을 메우는 변환이 실패한 항목은 {@code dangling-link} 로 그
     * 공간의 문서 처리를 영구히 막았다.
     */
    private static String indexLinkTarget(String scopeKey, String wikiPath) {
        String prefix = "wiki/" + scopeKey + "/";
        return wikiPath.startsWith(prefix) ? wikiPath.substring(prefix.length()) : wikiPath;
    }
```

```java
                .map(wiki -> new WikiIndex.Entry(
                        indexLinkTarget(scopeKey, wiki.wikiPath()), wiki.title(), wiki.summary()))
```

- [ ] **Step 6: 테스트를 돌려 통과를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiIndexTest*' --tests '*WikiTransformationApplierTest*'
```

Expected: PASS. Task 2 에서 고친 `storeIndex` 기대 문자열이 다시 깨진다 — 링크가 `pages/{wikiId}.md` 에서 `wiki_path` 기반으로 바뀌었다. 기대값을 실제 `wiki_path` 에 맞게 고친다.

- [ ] **Step 7: `wikiId` 로 링크를 만드는 곳이 남지 않았는지 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
grep -rn 'pages/%d\|pages/" *+ *wikiId\|pages/{wikiId}' src/main/java
```

Expected: `Wiki.storagePathOf`(저장 경로를 만드는 곳)만 남는다 — 그것은 정본을 **쓰는** 곳이라 맞다. 목차·링크를 만드는 곳에는 없어야 한다.

- [ ] **Step 8: 전체 테스트로 회귀를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

Expected: Task 0 기준선 대비 새 실패 없음.

- [ ] **Step 9: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiIndex.java \
        backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiIndexTest.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java
git commit -m "$(cat <<'EOF'
fix(wiki): 목차 링크를 wiki_path 에서 유도한다

목차는 pages/{wikiId}.md 로 링크했고 AI 하이드레이션은 wiki_path 로 페이지 주소를 만들었다.
같은 페이지를 두 이름으로 부른 셈이라 그 간극을 메우는 변환이 필요했고, 변환이 실패한 항목이
dangling-link 로 그 공간의 문서 처리를 영구히 막았다 — 재처리해도 같은 자리에서 실패했다.

유도 출처를 wiki_path 하나로 만든다. 해시 경로(AI 가 만든 위키)와 숫자 경로(시드가 만든
위키)가 섞여 있어도 둘 다 맞는다. 기존 데이터 마이그레이션이 필요 없다.
EOF
)"
```

---

## Task 4: 실기동 확인과 MR

**Files:** 없음 (확인·문서)

**Interfaces:**
- Consumes: Task 1~3 의 결과
- Produces: MR

- [ ] **Step 1: 담당 범위 밖 변경이 섞이지 않았는지 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git status
git diff --name-only origin/develop
```

Expected: `backend/src/**` 만. **`ai/` 변경이 있으면 이 브랜치에서 빼낸다** — AI 쪽 정리는 별건이다.

- [ ] **Step 2: develop 최신을 반영한다**

컨벤션이 rebase 를 쓰지 않는다.

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git fetch origin && git merge origin/develop
```

- [ ] **Step 3: 전체 테스트를 다시 돌린다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

- [ ] **Step 4: 실기동으로 확인한다 — 사용자 확인 후에만**

**실 DB 에 직접 붙이지 않는다.** `local` 프로파일이 `ddl-auto: create-drop` 이라 스키마가 드롭된다. 실데이터를 복제한 스키마에 대고 확인한다 (S15P11B106-279 에서 쓴 방법).

확인할 것:

1. 목차 파일이 **그 공간의 위키 전체**를 담는다 (ALL 공간이면 9개)
2. 목차의 모든 링크가 **실제 파일 이름과 일치**한다 — 이것이 B 의 종료 조건이다:

```bash
cd <복제 저장 루트>/wiki/ALL
grep -o '(pages/[^)]*)' index.md | tr -d '()' | sort > /tmp/linked.txt
ls pages/ | sed 's#^#pages/#' | sort > /tmp/actual.txt
comm -23 /tmp/linked.txt /tmp/actual.txt   # 목차에만 있는 것 = 깨진 링크
```

Expected: **빈 출력.**

3. 위키 목록 화면의 요약이 컬럼에서 온다 — 목차에 없던 위키(14·15)의 요약이 뜬다

**MySQL 데이터는 초기화하지 않는다.**

- [ ] **Step 5: MR 을 올린다 — 사용자 지시가 있을 때만**

승인 없이 push·MR 하지 않는다. 제목:

```
fix(wiki): 목차를 DB 에서 그리고 링크를 wiki_path 에서 유도한다 [S15P11B106-<티켓번호>]
```

본문에 담을 것:
- 증상: `[dangling-link] index.md — 본문 링크 pages/3.md 가 이 범위의 어떤 페이지도 가리키지 않는다`. **재처리해도 같은 자리에서 실패**해 그 공간이 영구히 막혔다
- 원인: 같은 페이지를 두 이름으로 불렀다 (목차는 wikiId, 페이지는 `wiki_path`)
- 왜 `wiki_path` 로 통일했는지 — wikiId 로 통일하면 파일 이름·컬럼·본문 링크·검색 색인을 원자적으로 맞추는 마이그레이션이 필요하고, 절반만 바뀐 순간이 이 버그의 상태다
- 부수 효과: 목차에 없던 위키의 요약이 화면에 뜬다 (`wiki.summary` 컬럼 사용)
- `WikiIndex.parse` 삭제 — 되읽지 않으면 형식을 잘못 가정할 곳도 없다
- **협의 항목**: 목차 순서가 「AI 가 쓴 순서」에서 「카테고리명 → 제목」으로 바뀐다. 목차는 사용자에게 보이지 않아 영향이 없다고 판단했다
- **후속(별건)**: AI 쪽에서 목차 하이드레이션과 `rewrite_index_links` 를 걷어내는 작업. 이 MR 만으로도 B 는 끝나지만, 그 변환 코드는 이제 할 일이 없다

---

## 이 계획에서 하지 않는 것

- **AI 서버 변경.** 하이드레이션에서 목차를 걷어내고 에이전트 지침을 정리하는 것은 별건·별 MR 이다. 이 MR 만으로 B 가 끝나므로 묶지 않는다
- **주소를 wikiId 로 통일하는 것** (설계 ③). 2.1 개정으로 보류했다 — 마이그레이션이 필요하고 절반만 바뀐 상태가 이 버그다
- **목차 파일 자체를 없애는 것.** 챗봇(`QuestionAskService`)이 아직 공간 개요로 읽는다. 별건
- **ERD 변경.** `wiki.summary`·`wiki.wiki_path` 를 그대로 쓴다
