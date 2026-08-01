# 위키 변환 단일 호출 — 백엔드 구현 계획

> 챗봇(S15P11B106-169·170)에서 한 것을 **위키 변환**에 적용한다. 설계 정본은
> `ai/docs/superpowers/specs/2026-07-31-agent-endpoint-merge-design.md` §4.1 이고, 그 문서가
> 「다음 단계」로 남겨 둔 것이 이 계획이다. 어긋나면 설계가 정답이다.
>
> **관리자 수정(`wiki-edits`)은 이번 범위가 아니다.** 설계 §4.3 이 함께 다루지만 「가정」으로
> 적혀 있고, 위키 변환은 쓰기라서 한 번에 두 경로를 바꾸면 실연동에서 어느 쪽이 깨졌는지
> 가르기 어렵다. 변환만 먼저 한다.

**Goal:** 위키 변환에서 AI 를 한 번만 부른다. 요청에 현재 목차·카테고리·선택된 Wiki 본문을 싣지
않고, 에이전트가 Wiki 조회 API 로 직접 읽는다.

**Architecture:** `DocumentParseWorker` 가 `selectWikiContext` 호출을 없애고
`wikiTransformationService.requestForDocumentChange` 만 부른다. 그 요청은 `jobId`·`documentId`·
`scopeKey`·`changeType`·파싱 본문과 **허가값·범위 버전**으로 끝난다.

**Tech Stack:** Java 21, Spring Boot, JPA, JUnit 5, Gradle. 계약 생성기는 Node.js.

---

## 착수 전에 알아야 하는 것

### 선행은 다 풀렸다

`a990892`(develop)가 S15P11B106-151·154·152 를 한 번에 닫았다. **152 를 in-process 도구로
해결했다** — 별도 MCP 프로세스에 허가값을 넘기는 문제와 프로세스 밖 중단 신호 문제가 그 방식으로
사라졌다. `wiki_api/session.py` 의 가드가 남아 있지만 조건이 좁혀졌다: 창구 모드 + **도구를
프로세스 안에서 돌리지 못하는 런타임**만 거절한다. 배포 런타임(`deepagents`)은 통과하고
`claude-code`(CLI 하위 프로세스)는 계속 거절된다 — 로컬 시험용이라 무관하다.

### 허가값은 이미 보내고 있다

`WikiTransformationService:97` 이 `wikiCapability` 와 `scopeVersion` 을 **이미 발급해 보내고
호출 후 `revoke` 한다.** TTL 은 `Duration.ofMinutes(30)` 이다.

**TTL 을 손댈 필요가 없다.** 위키 변환은 분 단위(실측 2분 반)이고 쓰기를 큐로 직렬화하므로 큐
대기까지 덮어야 하는데 30분이면 넉넉하다. 챗봇의 5분과 다른 값인 것이 맞다.

즉 **창구를 켜는 일은 남아 있지 않다.** 남은 것은 본문·목차를 밀어 보내던 것을 걷어내는 일이다.

### AI 쪽 구현이 선행이다 — 백엔드 단독으로 배포할 수 없다

`a990892` 는 **배관만** 열었다. 위키 에이전트는 여전히 요청에 실려 온 것만 쓴다. 백엔드가
`selectedWikis`·`currentIndex`·`currentCategories` 를 끊으면 에이전트는 그것을 창구로 직접 읽어야
하는데 그 구현이 없다.

**챗봇과 같은 모양이다** — 계약을 세우고 양쪽이 병행한다. 다만 챗봇은 계약과 구현 사이가 몇
시간이었다. 여기서는 AI 쪽 티켓을 먼저 세우고 진척을 보며 **계약 머지 시점을 맞춘다** (아래
「계약 머지 시점」).

### DB 는 건드릴 것이 없다

1단계 결과가 `document.completeParsing(parsedPath, selectedWikiIds)` 로 `document_wiki_refs` 에
저장되지만 **중간값이다.** 변환이 끝나면 `completeProcessing(result.affectedWikiIds())` 가 실제
영향받은 Wiki 로 덮어쓴다. 1단계를 지우면 기존 1-인자 오버로드(`completeParsing(parsedPath)` →
`List.of()`)를 쓰면 된다. **ERD 변경 없다.**

---

## Global Constraints

- **계약 JSON 을 직접 고치지 않는다.** 생성기(`docs/api/generate-postman-collections.mjs`)를 고쳐
  재생성한다. 직접 고치면 재생성 때 날아간다.
- 계약을 올릴 때 `scripts/validate-artifact-consistency.mjs` 를 **같이 올린다.** 그것을 빠뜨려
  원래부터 깨져 있던 전례가 있다.
- **옛 내용을 남기지 않는다.** 1단계를 가리키는 계약·문서·검증 스크립트를 모두 고친다.
- AI 가 내는 상태는 400·401·500 뿐이다. 그 밖이 오면 `UNEXPECTED_STATUS` 로 뭉개진다.
- **ERD 를 바꾸지 않는다.**
- 커밋은 손댄 경로만 stage 한다. `git add -A` 금지.
- 브랜치·커밋·MR 전에 `docs/conventions/git-convention.md` 를 읽는다.

---

## 계약 변경

```
POST /internal/v1/wiki-context-selections        삭제 (남겨두지 않는다)

POST /internal/v1/wiki-transformations
  남는 것   jobId · documentId · scopeKey · changeType
            parsedMarkdown            (added·replaced)
            removedParsedMarkdown     (removed·replaced)
            wikiCapability · scopeVersion   ← 선택 → 필수
  빠지는 것 currentIndex · currentCategories · selectedWikis
  응답      그대로 — summary · categoryChanges · wikiChanges · relationChanges · indexEntries
```

**응답을 건드리지 않는다.** 백엔드가 이미 받아 반영하는 모양이고 바꿀 이유가 없다.

**`wikiCapability` 를 선택 필드로 두지 않는다.** 빠지면 에이전트가 창구를 못 불러 현재 위키를
전혀 못 보고, 그 상태로 라이브를 덮을 수 있다. 이미 엔드포인트를 삭제하는 비호환 변경이므로
「배포 전 호환」 논거도 성립하지 않는다.

### 계약 버전과 머지 시점

**버전 번호를 미리 잡지 않는다.** 1.8.0 다음이지만, S15P11B106-101 이 1.7.0 을 가져간 전례가
있다 — 계약이 develop 밖에 오래 있으면 다른 티켓이 그 번호를 쓴다. **재생성·머지 직전에 확정**
한다.

머지 순서는 챗봇과 다르다.

| 챗봇 | 이번 |
| --- | --- |
| 계약을 먼저 머지 (구현까지 몇 시간) | **AI 쪽 구현이 눈에 보일 때 계약을 머지** |

이유: 계약이 「창구로 읽는다」인데 양쪽 구현이 push 인 상태로 develop 이 며칠 있으면, 그 사이
누가 봐도 무엇이 정본인지 알 수 없다. 백엔드 자바는 계약 머지와 같은 MR 로 묶어도 된다 —
이번에는 자바 몫이 작다.

---

## File Structure

**삭제**

| 파일 | 이유 |
| --- | --- |
| `global/ai/client/WikiContextSelectionRequest.java` | 1단계 전용 |
| `global/ai/client/WikiContextSelectionResponse.java` | 1단계 전용 |

**고치는 것**

| 파일 | 무엇 |
| --- | --- |
| `global/ai/client/AiClient.java` | `selectWikiContext` 삭제 |
| `global/ai/client/RestClientAiClient.java` | 구현·경로 상수·응답 검증 삭제 |
| `global/ai/client/WikiTransformationRequest.java` | `currentIndex`·`currentCategories`·`selectedWikis` 제거. `wikiCapability`·`scopeVersion` 필수화 |
| `domain/wiki/service/WikiTransformationService.java` | 요청 조립에서 3개 제거. `selectedWikis()`·`currentCategories()`·`currentIndex()` 헬퍼 정리. `selectedWikiIds` 인자 제거 |
| `domain/document/service/DocumentParseWorker.java` | 1단계 호출 2곳(153·202줄) 삭제. `wikiIds()` 헬퍼 삭제. `completeParsing` 1-인자 사용 |
| `docs/api/generate-postman-collections.mjs` | 1단계 삭제, `wiki-transformations` 필드 개정 |
| `docs/api/postman-contract-examples.mjs` | 예시·`contractVersion` |
| `docs/api/README.md` | 버전·엔드포인트 수 (내부 API 16 → 15) |
| `docs/conventions/rest-api-convention.md` | Wiki 변환 흐름 설명 |
| `docs/requirements/요구사항정의서.md` | 해당 FR 항목·문서 버전 |
| `scripts/validate-artifact-consistency.mjs` | 계약 버전·내부 API 개수 기대값 |

**영향받는 테스트**

`domain/wiki/service/WikiTransformationServiceTest.java` ·
`domain/document/service/DocumentParseWorkerTest.java` ·
`global/ai/client/RestClientAiClientTest.java`

---

### Task 1: 요청 DTO 를 개정하고 1단계 클라이언트를 지운다

**Files:**
- Modify: `global/ai/client/WikiTransformationRequest.java`, `AiClient.java`, `RestClientAiClient.java`
- Delete: `global/ai/client/WikiContextSelectionRequest.java`, `WikiContextSelectionResponse.java`
- Test: `global/ai/client/RestClientAiClientTest.java`

- [ ] **Step 1: 실패 테스트를 쓴다**

`RestClientAiClientTest` 에 새 요청 모양을 고정한다. 챗봇 때 쓴 방식을 그대로 쓴다 — **없어진
필드가 실리지 않는 것까지 검사한다.**

```java
@Test
@DisplayName("변환 요청에 목차·카테고리·선택 Wiki를 싣지 않는다")
void sendsTransformationWithoutPushedContext() {
    server.expect(requestTo("http://localhost:8000/internal/v1/wiki-transformations"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                    {
                      "jobId": "42",
                      "documentId": "15",
                      "scopeKey": "D1-D2",
                      "changeType": "document_added",
                      "parsedMarkdown": "# 취업 규칙\\n본문...",
                      "wikiCapability": "capability",
                      "scopeVersion": 47
                    }
                    """))
            .andExpect(content().string(not(containsString("currentIndex"))))
            .andExpect(content().string(not(containsString("currentCategories"))))
            .andExpect(content().string(not(containsString("selectedWikis"))))
            .andRespond(withSuccess(/* 기존 성공 예시 그대로 */, MediaType.APPLICATION_JSON));

    client.transformWiki(transformationRequest());
    server.verify();
}

@Test
@DisplayName("허가값이 없으면 요청을 만들 수 없다 — 창구를 못 불러 라이브를 덮는다")
void capabilityIsRequired() {
    assertThatThrownBy(() -> new WikiTransformationRequest(
            "42", "15", "D1-D2", WikiDocumentChangeType.DOCUMENT_ADDED,
            "# 본문", null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd backend && sh gradlew test --tests '*RestClientAiClient*'`
Expected: 컴파일 실패 — 레코드 필드가 아직 있다

- [ ] **Step 3: DTO 와 클라이언트를 고친다**

`WikiTransformationRequest` 에서 세 필드를 지우고 `wikiCapability`·`scopeVersion` 을 필수로
검증한다. `AiClient.selectWikiContext` 와 `RestClientAiClient` 의 구현·`WIKI_CONTEXT_SELECTION_PATH`
·해당 `validateResponse` 를 지운다. `WikiContextSelection*` 두 파일을 지운다.

- [ ] **Step 4: 통과를 확인한다** — `sh gradlew test --tests '*RestClientAiClient*'`

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ajt/backend/global/ai/client/ \
        backend/src/test/java/com/ajt/backend/global/ai/client/
git commit -m "refactor(wiki): 변환 요청에서 밀어 보내던 문맥을 걷어낸다 [<티켓>]"
```

---

### Task 2: 요청 조립에서 목차·카테고리·본문을 걷어낸다

**Files:**
- Modify: `domain/wiki/service/WikiTransformationService.java`
- Test: `domain/wiki/service/WikiTransformationServiceTest.java`

**Interfaces:**
- `requestForDocumentChange(jobId, documentId, scopeKey, changeType, parsedMarkdown, removedParsedMarkdown)` — `selectedWikiIds` 인자가 사라진다

- [ ] **Step 1: 실패 테스트를 쓴다**

```java
@Test
@DisplayName("허가값과 범위 버전은 그대로 보내고 호출 후 회수한다")
void stillIssuesAndRevokesTheCapability() { /* 기존 동작이 유지되는지 */ }

@Test
@DisplayName("본문·목차를 읽지 않는다 — 에이전트가 창구로 읽는다")
void doesNotReadBodiesOrIndex() {
    service.requestForDocumentChange(42L, 15L, "D1-D2",
            WikiDocumentChangeType.DOCUMENT_ADDED, "# 본문", null);

    then(wikiFileStorage).should(never()).readIndex(anyString());
    then(wikiFileStorage).should(never()).readWikiMarkdown(anyString());
    then(wikiCategoryRepository).shouldHaveNoInteractions();
}
```

- [ ] **Step 2: 실패를 확인한다** — 인자 수가 맞지 않아 컴파일 실패

- [ ] **Step 3: 구현한다**

요청 조립에서 세 값을 빼고 `selectedWikis()`·`currentCategories()`·`currentIndex()` 를 지운다.
**`currentIndex()` 는 public 이지만 호출자가 `DocumentParseWorker` 의 1단계 두 곳뿐이다** — Task 3
에서 그것도 사라지므로 함께 지운다. `wikiRepository`·`wikiCategoryRepository`·`wikiFileStorage`
의존이 이 서비스에서 필요 없어지면 생성자에서도 뺀다 (다른 메서드가 쓰는지 확인하고 판단한다).

- [ ] **Step 4: 통과를 확인한다** — `sh gradlew test --tests '*WikiTransformationService*'`

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ajt/backend/domain/wiki/ \
        backend/src/test/java/com/ajt/backend/domain/wiki/
git commit -m "refactor(wiki): 변환 요청 조립에서 목차·카테고리·본문 적재를 없앤다 [<티켓>]"
```

---

### Task 3: 1단계 호출을 없앤다

**Files:**
- Modify: `domain/document/service/DocumentParseWorker.java`
- Test: `domain/document/service/DocumentParseWorkerTest.java`

- [ ] **Step 1: 실패 테스트를 쓴다**

```java
@Test
@DisplayName("AI를 변환 한 번만 부른다 — 자료 선택 단계가 없다")
void callsTheAiOnceForTransformation() {
    // given 문서 추가 경로
    worker.process(job);

    then(aiClient).should(times(1)).transformWiki(any());
    // selectWikiContext 는 인터페이스에서 사라졌으므로 컴파일로도 막힌다
}

@Test
@DisplayName("파싱 완료 시 선택 Wiki를 남기지 않는다 — 변환 결과가 참조 목록의 정본이다")
void parsingCompletesWithoutSelectedRefs() {
    worker.process(job);

    // completeParsing 은 빈 목록으로 두고, completeProcessing 이 실제 영향 Wiki로 덮는다
    assertThat(document.documentWikiRefs()).containsExactlyElementsOf(affectedWikiIds);
}
```

- [ ] **Step 2: 실패를 확인한다** — `sh gradlew test --tests '*DocumentParseWorker*'`

- [ ] **Step 3: 구현한다**

두 경로에서 `selectWikiContext` 호출을 지운다.

- 문서 추가·교체(153줄 부근): `selectedWikiIds` 변수와 `wikiIds(selection)` 을 없애고
  `document.completeParsing(parsedPath)` 1-인자 오버로드를 쓴다
- 문서 제거(202줄 부근): 호출을 지우고 `requestForDocumentChange` 에 선택 목록을 넘기지 않는다
- `wikiIds(WikiContextSelectionResponse)` 헬퍼를 지운다

> ⚠️ **제거 경로의 `removedParsedMarkdown` 은 그대로 필수다.** 계약이 요구하고, 호출자가 파일을
> 옮기거나 지우기 전에 읽어 두어야 한다. 1단계와 무관하다.

- [ ] **Step 4: 통과를 확인한다** — `sh gradlew test --tests '*DocumentParseWorker*' --tests '*Wiki*'`

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ajt/backend/domain/document/ \
        backend/src/test/java/com/ajt/backend/domain/document/
git commit -m "feat(wiki): 위키 변환 AI 호출을 한 번으로 줄인다 [<티켓>]"
```

---

### Task 4: 계약과 공통 문서를 고친다

**Files:**
- Modify: `docs/api/generate-postman-collections.mjs`, `docs/api/postman-contract-examples.mjs`,
  `docs/api/README.md`, `docs/conventions/rest-api-convention.md`,
  `docs/requirements/요구사항정의서.md`, `scripts/validate-artifact-consistency.mjs`

- [ ] **Step 1: 무엇이 바뀌는지 목록을 만든다** — 위 「계약 변경」 표

- [ ] **Step 2: 생성기를 고치고 재생성한다**

```bash
node docs/api/generate-postman-collections.mjs
node docs/api/validate-postman-collections.mjs
node scripts/validate-artifact-consistency.mjs
```

내부 API 개수 기대값을 **16 → 15** 로 고친다 (엔드포인트 하나가 빠진다). 계약 버전은 이 시점에
확정한다.

- [ ] **Step 3: 요구사항·컨벤션 문서를 고친다**

Wiki 변환 흐름을 「백엔드가 목차·본문을 전달」에서 「에이전트가 조회 API 로 직접 읽는다」로
바꾸고 문서 버전·변경 요약을 올린다.

- [ ] **Step 4: 남은 흔적을 찾는다**

```bash
grep -rn "wiki-context-selections\|selectWikiContext\|currentCategories" \
  docs/ backend/src scripts/ | grep -v docs/superpowers
```

Expected: 결과 없음.

- [ ] **Step 5: 커밋**

```bash
git add docs/api/ docs/conventions/ docs/requirements/ scripts/validate-artifact-consistency.mjs
git commit -m "docs(api): 위키 변환을 단일 호출로 개정한다 [<티켓>]"
```

---

### Task 5: 전체 검증

- [ ] **Step 1:** `cd backend && sh gradlew clean test` — 실패 0
- [ ] **Step 2:** 계약 검증 세 명령 통과
- [ ] **Step 3:** 1단계 흔적 없음 확인 (Task 4 Step 4 의 grep)
- [ ] **Step 4:** MR 을 올린다. **사람에게 확인받은 뒤 올린다.**

MR 본문에 적을 것:

- 계약 버전과 내부 API 개수 변경 (16 → 15)
- **AI 쪽 구현(별도 티켓)이 없으면 위키 변환이 동작하지 않는다** — 배포 시점을 맞춰야 한다
- `wikiCapability` 를 필수로 올린 이유
- TTL 30분은 손대지 않았다 (위키 규모에 이미 맞다)

---

## 이 계획 밖

| 무엇 | 어디서 |
| --- | --- |
| **AI 쪽 구현** | 별도 티켓. 에이전트가 목차·카테고리·본문을 창구로 직접 읽게 한다 |
| 관리자 수정(`wiki-edits`) 합치기 | 다음. 설계 §4.3 은 「가정」으로 적혀 있다 |
| 챗봇 비동기 전환 | 응답 시간 측정 뒤 |
| 권한 판정을 작업 번호로 통일 | 설계 §3.1. Wiki 쪽은 허가값이 이미 돈다 |

## 미결 — 사람이 정해야 한다

1. **`wiki-reconciliations` 가 계약에 없다.** AI 서버 코드(`ai/src/wiki_api/`)에는 있는데 계약
   1.8.0 에 없고 백엔드도 부르지 않는다. `ai/CLAUDE.md` 가 「계약에 없는 API 를 임의로 만들지
   않는다」고 하므로 어긋난 상태다. **이번 계약 개정 전에 무엇인지 확인하는 것이 낫다** — 위키
   흐름을 새로 짜는데 계약 밖 창구가 떠 있으면 어느 쪽이 정본인지 흐려진다.
2. **계약 버전 번호와 머지 시점** — 위 「계약 버전과 머지 시점」.
3. **`BACKEND_BASE_URL`** — AI 서버가 창구를 부르려면 필요한 환경변수다. 배포 설정이라 인프라와
   맞춰야 한다. 없으면 백엔드가 허가값을 보내도 AI 가 push 로 돈다(지금 상태).
