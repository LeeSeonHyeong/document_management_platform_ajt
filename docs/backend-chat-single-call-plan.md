# 챗봇 단일 호출 — 백엔드 구현 계획

> **백엔드 담당자에게 넘기는 문서다.** AI 서버 쪽에서 설계·계약을 먼저 세우고 그 위에 백엔드 몫을 정리했다. 계약(**v1.8.0**)과 공통 문서는 **이미 반영돼 있다** — Task 5·6 이 그 일이고 완료 상태다. 남은 것은 자바 구현(Task 1~4)과 검증(Task 7)이다.
>
> ⚠️ **버전이 1.7.0 에서 1.8.0 으로 바뀌었다.** 이 브랜치가 develop 에서 갈라진 뒤 S15P11B106-101(AI 작업 수동 시작 API, `5e2ad9a`)이 먼저 머지되면서 1.7.0 을 가져갔다. 요구사항정의서도 2.18 로 올렸다.
>
> ⚠️ **2026-07-31 저녁에 응답 계약 세 곳이 바뀌었다.** 첫 설계가 확정 요구사항 FR-QNA-002·FR-QNA-007 과 어긋나 있었다. Task 3·4 에 반영돼 있으니 그대로 따르면 된다 — `questionType` 의 출처, 근거를 못 찾았을 때의 상태 코드, `sources[].title`.
>
> 계약과 다르게 구현해야 할 이유를 찾으면 임의로 바꾸지 말고 알려주기 바란다. 계약이 정본이다.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 백엔드가 챗봇에서 AI 를 한 번만 부르게 하고, 에이전트가 스스로 조회할 수 있도록 일정 조회 API 두 개를 신설한다.

**Architecture:** `QuestionAskService` 가 1단계 호출을 없애고 `answers` 한 번만 부른다. 요청에는 목차(범위별 허가값 포함)만 싣고 일정 목록은 싣지 않는다. 에이전트는 새 일정 조회 API 를 질문 번호로 인증해 부른다. 로직은 기존 `ScheduleService` 조회를 재사용하고 입구만 새로 만든다.

**Tech Stack:** Java 21, Spring Boot, JPA, JUnit 5, Gradle. 계약 생성기는 Node.js.

## Global Constraints

- 설계 정본: `ai/docs/superpowers/specs/2026-07-31-agent-endpoint-merge-design.md`. 어긋나면 설계가 정답이다.
- 티켓: **S15P11B106-169** (백엔드 담당). AI 서버 몫은 별도 티켓이고 **두 쪽이 동시에 진행한다** — 계약이 이미 서 있으므로 서로 기다리지 않는다. 실연동 확인만 양쪽이 끝난 뒤에 한다.
- **ERD 를 바꾸지 않는다.** 일정 조회에 필요한 색인(`idx_schedule_period_status`)이 이미 있다.
- **계약 JSON 을 직접 고치지 않는다.** 생성기(`docs/api/generate-postman-collections.mjs`)를 고쳐 재생성한다. 직접 고치면 재생성 때 날아간다.
- 호환되지 않는 변경이므로 `contractVersion` 을 올린다.
- **옛 내용을 남기지 않는다.** 1단계를 가리키는 문서·계약·검증 스크립트를 모두 고친다.
- AI 가 내는 상태는 400·401·500 뿐이다. 그 밖의 상태가 오면 `UNEXPECTED_STATUS` 로 뭉개진다.
- 커밋은 손댄 경로만 stage 한다. `git add -A` 금지.

---

## File Structure

**새로 만드는 것**

| 파일 | 책임 |
| --- | --- |
| `backend/src/main/java/com/ajt/backend/domain/schedule/api/internal/InternalScheduleQueryController.java` | 일정 목록·상세 조회 입구 2개 |
| `backend/src/main/java/com/ajt/backend/domain/schedule/service/InternalScheduleQueryService.java` | 질문 번호로 사람을 찾고 기존 조회 로직을 재사용 |
| `backend/src/test/java/.../InternalScheduleQueryControllerTest.java` | 입구 계약 테스트 |
| `backend/src/test/java/.../InternalScheduleQuerySecurityTest.java` | 권한·인증 테스트 |

**고치는 것**

| 파일 | 무엇 |
| --- | --- |
| `domain/question/QuestionAskService.java` | 1단계 호출 삭제, 목차에 허가값, 일정 목록 안 싣기, 응답의 `questionType` 저장 |
| `global/ai/client/AiClient.java` | `selectAnswerContext` 삭제 |
| `global/ai/client/RestClientAiClient.java` | 위와 같음 |
| `global/ai/client/AnswerGenerationRequest.java`·`AnswerGenerationResponse.java` | 요청·응답 필드 개정 |
| `global/ai/client/AnswerContextSelectionRequest.java`·`AnswerContextSelectionResponse.java` | **삭제** |
| `global/ai/client/AiClientErrorMapper.java` | 오류 이름 8개 매핑 |
| `global/ai/capability/WikiCapabilityService.java` | 그대로 쓴다 (발급 호출만 늘어난다) |
| `docs/api/generate-postman-collections.mjs` | 1단계 삭제, `answers` 개정, 일정 2개 신설 |
| `docs/api/postman-contract-examples.mjs` | 예시·`contractVersion` |
| `docs/api/README.md` | 버전·엔드포인트 수 |
| `docs/requirements/요구사항정의서.md` | FR-QNA-012 개정 |
| `docs/conventions/rest-api-convention.md` | 827·843줄 |
| `scripts/validate-artifact-consistency.mjs` | 내부 API 개수 기대값 |

---

### Task 1: 일정 조회 서비스 — 질문 번호로 사람을 찾는다

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/domain/schedule/service/InternalScheduleQueryService.java`
- Test: `backend/src/test/java/com/ajt/backend/domain/schedule/service/InternalScheduleQueryServiceTest.java`

**Interfaces:**
- Consumes: `AiQuestionRepository`, `MemberRepository`, `ScheduleRepository`
- Produces:
  - `ScheduleListResult list(long questionId, LocalDate from, LocalDate to, String keyword, int limit)` — `items` + `truncated`
  - `ScheduleDetail detail(long questionId, long scheduleId)`
  - 둘 다 질문이 없으면 `BusinessException(ErrorCode.QUESTION_NOT_FOUND)`, 권한 밖이면 `SCHEDULE_NOT_FOUND`

- [ ] **Step 1: 실패 테스트를 쓴다**

```java
@Test
@DisplayName("질문 번호로 질문한 사람을 찾아 그 사람이 볼 수 있는 일정만 돌려준다")
void listFiltersByTheAskingMember() {
    // given: 개발부 사원이 물었고, 전사 일정 1건과 인사부 전용 일정 1건이 있다
    AiQuestion question = questionAskedBy(developerInDepartment(1L));
    Schedule companyWide = approvedSchedule("전사 워크샵", VisibilityType.ALL, null);
    Schedule hrOnly = approvedSchedule("인사부 회의", VisibilityType.DEPARTMENT, 2L);
    given(questionRepository.findById(question.getId())).willReturn(Optional.of(question));
    given(scheduleRepository.findByPeriod(any(), any())).willReturn(List.of(companyWide, hrOnly));

    // when
    ScheduleListResult result = service.list(question.getId(),
            LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), null, 50);

    // then: 인사부 전용은 빠진다
    assertThat(result.items()).extracting(ScheduleListItem::title)
            .containsExactly("전사 워크샵");
    assertThat(result.truncated()).isFalse();
}

@Test
@DisplayName("제목 부분 일치로 좁힌다 — 조사가 붙어도 걸린다")
void keywordMatchesPartOfTheTitle() {
    AiQuestion question = questionAskedBy(developerInDepartment(1L));
    given(questionRepository.findById(question.getId())).willReturn(Optional.of(question));
    given(scheduleRepository.findByPeriod(any(), any())).willReturn(List.of(
            approvedSchedule("하계 워크샵을 안내합니다", VisibilityType.ALL, null),
            approvedSchedule("월간 회의", VisibilityType.ALL, null)));

    ScheduleListResult result = service.list(question.getId(),
            LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), "워크샵", 50);

    assertThat(result.items()).hasSize(1);
}

@Test
@DisplayName("상한을 넘으면 가까운 날짜부터 남기고 잘렸다고 알린다")
void listTruncatesAndSaysSo() {
    AiQuestion question = questionAskedBy(developerInDepartment(1L));
    given(questionRepository.findById(question.getId())).willReturn(Optional.of(question));
    given(scheduleRepository.findByPeriod(any(), any()))
            .willReturn(manyApprovedSchedules(120));

    ScheduleListResult result = service.list(question.getId(),
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), null, 50);

    assertThat(result.items()).hasSize(50);
    assertThat(result.truncated()).isTrue();
}

@Test
@DisplayName("없는 질문 번호로 부르면 거절한다 — 아무 번호나 넣어 조회할 수 없다")
void unknownQuestionIsRejected() {
    given(questionRepository.findById(999L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.list(999L, LocalDate.now(), LocalDate.now(), null, 50))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("질문");
}

@Test
@DisplayName("권한 밖 일정의 상세는 못 읽는다")
void detailOfAnInvisibleScheduleIsNotFound() {
    AiQuestion question = questionAskedBy(developerInDepartment(1L));
    Schedule hrOnly = approvedSchedule("인사부 회의", VisibilityType.DEPARTMENT, 2L);
    given(questionRepository.findById(question.getId())).willReturn(Optional.of(question));
    given(scheduleRepository.findById(hrOnly.getId())).willReturn(Optional.of(hrOnly));

    assertThatThrownBy(() -> service.detail(question.getId(), hrOnly.getId()))
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd backend && sh gradlew test --tests '*InternalScheduleQueryServiceTest*'`
Expected: 컴파일 실패 — `InternalScheduleQueryService` 가 없다

- [ ] **Step 3: 서비스를 구현한다**

`QuestionAskService.accessibleSchedules()`·`isVisibleTo()` 의 판정을 그대로 쓴다. **판정 로직을 복사하지 말고 꺼내 공유한다** — 두 곳에 두면 한 곳이 낡는다.

```java
package com.ajt.backend.domain.schedule.service;

/**
 * AI 에이전트가 부르는 일정 조회. 공개 API(`GET /api/v1/schedules`)와 달리 로그인 세션이
 * 없으므로 <b>질문 번호로 질문한 사람을 찾아</b> 그 사람 기준으로 거른다.
 *
 * <p>요청에 실린 값을 믿지 않는다. 내부 API 키는 "AI 서버다"만 증명하고 "누구 대신
 * 묻는지"는 증명하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class InternalScheduleQueryService {

    private static final int MAX_LIMIT = 50;

    // 생성자 주입: AiQuestionRepository, ScheduleRepository, ScheduleVisibility

    public ScheduleListResult list(long questionId, LocalDate from, LocalDate to,
                                   String keyword, int limit) {
        Member asker = askerOf(questionId);
        int capped = Math.min(limit <= 0 ? MAX_LIMIT : limit, MAX_LIMIT);
        List<Schedule> visible = scheduleRepository.findByPeriod(from, to).stream()
                .filter(schedule -> visibility.isVisibleTo(schedule, asker))
                .filter(schedule -> matchesKeyword(schedule, keyword))
                .sorted(Comparator.comparing(Schedule::getStartAt))
                .toList();
        List<ScheduleListItem> items = visible.stream().limit(capped)
                .map(ScheduleListItem::from).toList();
        return new ScheduleListResult(items, visible.size() > items.size());
    }

    public ScheduleDetail detail(long questionId, long scheduleId) {
        Member asker = askerOf(questionId);
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .filter(found -> visibility.isVisibleTo(found, asker))
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        return ScheduleDetail.from(schedule);
    }

    private Member askerOf(long questionId) {
        return questionRepository.findById(questionId)
                .map(AiQuestion::getMember)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
    }

    private boolean matchesKeyword(Schedule schedule, String keyword) {
        // 제목 부분 일치. 한국어는 조사가 붙어("워크샵을") 단어 단위 비교가 어긋난다.
        return keyword == null || keyword.isBlank()
                || schedule.getTitle().contains(keyword.trim());
    }
}
```

`ScheduleVisibility` 는 `QuestionAskService.isVisibleTo` 를 꺼낸 컴포넌트다. `QuestionAskService` 도 그것을 쓰게 바꾼다.

> ⚠️ **기간 경계의 시간대를 확인할 것.** `from`·`to` 는 `LocalDate` 인데 `schedule.start_at` 은
> UTC `DATETIME(6)` 이다. 에이전트는 사용자 감각(KST)으로 「8월 3일」을 보내는데 그날 오전
> 일정은 UTC 로 8월 2일이다. 그대로 비교하면 하루 경계의 일정이 조용히 빠진다. `from` 00:00
> KST ~ `to` 24:00 KST 를 UTC 로 변환해 비교하는 쪽이 맞다 — 어느 쪽으로 정할지 AI 담당과
> 맞추고, 정한 것을 계약 설명에 적는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `cd backend && sh gradlew test --tests '*InternalScheduleQueryServiceTest*' --tests '*QuestionAskServiceTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ajt/backend/domain/schedule/ \
        backend/src/main/java/com/ajt/backend/domain/question/QuestionAskService.java \
        backend/src/test/java/com/ajt/backend/domain/schedule/
git commit -m "feat(schedule): AI 에이전트용 일정 조회 서비스 — 질문 번호로 권한을 판정한다 [S15P11B106-169]"
```

---

### Task 2: 일정 조회 입구 두 개

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/domain/schedule/api/internal/InternalScheduleQueryController.java`
- Test: `backend/src/test/java/.../InternalScheduleQueryControllerTest.java`, `.../InternalScheduleQuerySecurityTest.java`

**Interfaces:**
- Consumes: `InternalScheduleQueryService` (Task 1)
- Produces:
  - `GET /internal/v1/schedules?questionId&from&to&keyword&limit` → `{items:[{scheduleId,title,startAt,endAt,targetText,location}], truncated}`
  - `GET /internal/v1/schedules/{scheduleId}?questionId` → `{scheduleId,title,content,startAt,endAt,targetText,location}`

- [ ] **Step 1: 실패 테스트를 쓴다**

`InternalWikiQueryControllerTest` 의 형태를 따른다 (이미 있는 내부 조회 테스트다).

```java
@Test
@DisplayName("일정 목록은 items 와 truncated 를 돌려준다")
void listReturnsItemsAndTruncated() throws Exception {
    given(queryService.list(eq(500L), any(), any(), isNull(), anyInt()))
            .willReturn(new ScheduleListResult(List.of(
                    new ScheduleListItem("31", "8월 워크샵",
                            "2026-08-03T01:00:00Z", "2026-08-03T09:00:00Z",
                            "전사", "본사")), false));

    mockMvc.perform(get("/internal/v1/schedules")
                    .param("questionId", "500")
                    .param("from", "2026-08-01")
                    .param("to", "2026-08-31")
                    .header("X-Internal-API-Key", INTERNAL_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].scheduleId").value("31"))
            .andExpect(jsonPath("$.items[0].title").value("8월 워크샵"))
            .andExpect(jsonPath("$.truncated").value(false));
}

@Test
@DisplayName("일정 상세는 content 를 포함한다")
void detailIncludesContent() throws Exception {
    given(queryService.detail(500L, 31L)).willReturn(new ScheduleDetail(
            "31", "8월 워크샵", "전사 워크샵 안내",
            "2026-08-03T01:00:00Z", "2026-08-03T09:00:00Z", "전사", "본사"));

    mockMvc.perform(get("/internal/v1/schedules/31")
                    .param("questionId", "500")
                    .header("X-Internal-API-Key", INTERNAL_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("전사 워크샵 안내"));
}

@Test
@DisplayName("내부 API 키가 없으면 401 이다")
void withoutTheInternalKeyItIs401() throws Exception {
    mockMvc.perform(get("/internal/v1/schedules")
                    .param("questionId", "500")
                    .param("from", "2026-08-01").param("to", "2026-08-31"))
            .andExpect(status().isUnauthorized());
}

@Test
@DisplayName("questionId 가 없으면 400 이다 — 권한을 판정할 근거가 없다")
void withoutQuestionIdItIs400() throws Exception {
    mockMvc.perform(get("/internal/v1/schedules")
                    .param("from", "2026-08-01").param("to", "2026-08-31")
                    .header("X-Internal-API-Key", INTERNAL_KEY))
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd backend && sh gradlew test --tests '*InternalScheduleQuery*'`
Expected: 컴파일 실패 — 컨트롤러가 없다

- [ ] **Step 3: 컨트롤러를 구현한다**

```java
@RestController
public class InternalScheduleQueryController {

    private final InternalScheduleQueryService queryService;

    public InternalScheduleQueryController(InternalScheduleQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/internal/v1/schedules")
    public ScheduleListResult list(@RequestParam long questionId,
                                   @RequestParam LocalDate from,
                                   @RequestParam LocalDate to,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(defaultValue = "50") int limit) {
        return queryService.list(questionId, from, to, keyword, limit);
    }

    @GetMapping("/internal/v1/schedules/{scheduleId}")
    public ScheduleDetail detail(@PathVariable long scheduleId,
                                 @RequestParam long questionId) {
        return queryService.detail(questionId, scheduleId);
    }
}
```

내부 API 키 검사는 기존 `/internal/v1/**` 보안 설정을 그대로 쓴다. `InternalWikiQuerySecurityTest` 가 그 경로를 이미 검사하고 있으므로 같은 방식으로 걸린다.

- [ ] **Step 4: 통과를 확인한다**

Run: `cd backend && sh gradlew test --tests '*InternalScheduleQuery*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ajt/backend/domain/schedule/api/ \
        backend/src/test/java/com/ajt/backend/domain/schedule/
git commit -m "feat(schedule): 일정 목록·상세 내부 조회 입구를 추가한다 [S15P11B106-169]"
```

---

### Task 3: AI 호출을 한 번으로 줄인다

**Files:**
- Modify: `domain/question/QuestionAskService.java`
- Modify: `global/ai/client/AiClient.java`, `RestClientAiClient.java`
- Modify: `global/ai/client/AnswerGenerationRequest.java`, `AnswerGenerationResponse.java`
- Delete: `global/ai/client/AnswerContextSelectionRequest.java`, `AnswerContextSelectionResponse.java`
- Test: `backend/src/test/java/.../QuestionAskServiceTest.java`, `RestClientAiClientTest.java`

**Interfaces:**
- Consumes: `WikiCapabilityService.issue(scopeKey, scopeVersion, ttl)` (이미 있다)
- Produces:
  - `AnswerGenerationRequest(questionId, conversationId, question, conversationMessages, wikiIndexes)` — `wikiIndexes[]` 는 `(scopeKey, indexMarkdown, wikiCapability)`
  - `AnswerGenerationResponse(answer, sources, questionType)`

- [ ] **Step 1: 실패 테스트를 쓴다**

```java
@Test
@DisplayName("AI 를 한 번만 부른다 — 1단계가 없다")
void callsTheAiExactlyOnce() {
    givenMemberCanSee("ALL");
    given(aiClient.generateAnswer(any())).willReturn(
            new AnswerGenerationResponse("연차는 15일입니다.",
                    List.of(new AnswerGenerationResponse.Source("wiki", "101", null,
                            "휴가 규정")),
                    "wiki"));

    service.ask(loginMember, new QuestionAskRequest("연차 며칠이야?", null));

    then(aiClient).should(times(1)).generateAnswer(any());
    then(aiClient).shouldHaveNoMoreInteractions();
}

@Test
@DisplayName("목차 각 행에 그 범위의 허가값을 담는다")
void everyIndexRowCarriesItsCapability() {
    givenMemberCanSee("ALL", "D1");
    given(capabilityService.issue(eq("ALL"), anyLong(), any())).willReturn("cap-all");
    given(capabilityService.issue(eq("D1"), anyLong(), any())).willReturn("cap-d1");
    given(aiClient.generateAnswer(any())).willReturn(anyAnswer());

    service.ask(loginMember, new QuestionAskRequest("질문", null));

    ArgumentCaptor<AnswerGenerationRequest> sent =
            ArgumentCaptor.forClass(AnswerGenerationRequest.class);
    then(aiClient).should().generateAnswer(sent.capture());
    assertThat(sent.getValue().wikiIndexes())
            .extracting(WikiIndex::scopeKey, WikiIndex::wikiCapability)
            .containsExactlyInAnyOrder(tuple("ALL", "cap-all"), tuple("D1", "cap-d1"));
}

@Test
@DisplayName("일정 목록을 싣지 않는다 — 에이전트가 조회한다")
void schedulesAreNotPushedAnyMore() {
    givenMemberCanSee("ALL");
    given(aiClient.generateAnswer(any())).willReturn(anyAnswer());

    service.ask(loginMember, new QuestionAskRequest("다음 워크샵 언제야?", null));

    ArgumentCaptor<AnswerGenerationRequest> sent =
            ArgumentCaptor.forClass(AnswerGenerationRequest.class);
    then(aiClient).should().generateAnswer(sent.capture());
    // 요청에 일정 관련 필드가 아예 없다 — 레코드에 그 필드가 없으므로 컴파일로도 막힌다
    assertThat(sent.getValue().wikiIndexes()).isNotEmpty();
}

@Test
@DisplayName("응답의 questionType 을 저장한다")
void questionTypeComesFromTheAnswerNow() {
    givenMemberCanSee("ALL");
    given(aiClient.generateAnswer(any())).willReturn(
            new AnswerGenerationResponse("답", List.of(), "mixed"));

    service.ask(loginMember, new QuestionAskRequest("질문", null));

    then(answerTransactionService).should()
            .save(argThat(saved -> saved.questionType() == QuestionType.MIXED));
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd backend && sh gradlew test --tests '*QuestionAskServiceTest*'`
Expected: 컴파일 실패 — `AnswerGenerationResponse` 에 `questionType` 이 없고 `WikiIndex` 에 `wikiCapability` 가 없다

- [ ] **Step 3: DTO 를 개정한다**

`AnswerGenerationRequest` 를 아래 모양으로 바꾼다. `selectedWikis`·`selectedSchedules`·`questionType` 을 지운다.

```java
public record AnswerGenerationRequest(
        String questionId,
        String conversationId,
        String question,
        List<ConversationMessage> conversationMessages,
        List<WikiIndex> wikiIndexes
) {
    /**
     * 범위 하나의 목차와 그 범위 조회 허가값.
     *
     * <p>허가값을 별도 배열로 두지 않고 이 행에 넣는다. 두 배열로 나누면 한쪽에만 있는
     * 범위가 생기고, 그러면 에이전트가 목차는 읽었는데 본문은 못 읽는 상태가 된다.
     */
    public record WikiIndex(String scopeKey, String indexMarkdown, String wikiCapability) {}

    public record ConversationMessage(String role, String content) {}
}
```

`AnswerGenerationResponse` 에 `questionType` 을 더한다.

```java
public record AnswerGenerationResponse(
        String answer,
        List<Source> sources,
        // 1단계 응답이던 값이 여기로 옮겨왔다. AI 에이전트가 질문 맥락을 보고 판단한다
        // (FR-QNA-002). "무엇을 읽었는지"가 아니다 — 일정 질문을 Wiki 로 답하는 경우가
        // 있어 결과로 정하면 유형이 뒤집힌다.
        String questionType
) {
    /** {@code title} 은 {@code answer_source.source_title}(NOT NULL)에 저장한다. */
    public record Source(String type, String wikiId, String scheduleId, String title) {}
}
```

**근거를 못 찾은 답변도 200 이다.** AI 가 조회를 해봤지만 근거가 없으면 `sources` 가 빈 배열이고
`answer` 는 정보가 부족하다는 안내다 (FR-QNA-007). 오류가 아니므로 그대로 저장하고 사용자에게
보여준다. `answer_source` 행은 0 건이 된다. 실패는 조회를 **아예 시도하지 않은** 실행뿐이고
그때 `NO_WIKI_OR_SCHEDULE_WAS_READ` 500 이 온다.

`AnswerContextSelectionRequest`·`AnswerContextSelectionResponse` 를 지운다.

- [ ] **Step 4: `AiClient` 에서 1단계를 지운다**

`AiClient` 인터페이스의 `selectAnswerContext` 를 지우고 `RestClientAiClient` 의 구현도 지운다.

- [ ] **Step 5: `QuestionAskService` 를 고친다**

`ask()` 에서 1단계 호출·`verifiedWikis`·`verifiedSchedules`·`selectedWikiPayloads`·`selectedSchedulePayloads`·`scheduleSummaries`·`readWikiMarkdownQuietly` 를 지운다. `wikiIndexes()` 가 허가값을 함께 담게 한다.

```java
    private List<AnswerGenerationRequest.WikiIndex> wikiIndexes(Set<String> scopeKeys) {
        return scopeKeys.stream()
                .map(scopeKey -> {
                    WikiScope scope = wikiScopeRepository.findByScopeKey(scopeKey)
                            .orElse(null);
                    if (scope == null) {
                        return null;
                    }
                    // 범위마다 하나씩 발급한다. 챗봇은 여러 범위를 함께 본다.
                    String capability = wikiCapabilityService.issue(
                            scopeKey, scope.getScopeVersion(), CAPABILITY_TTL);
                    return new AnswerGenerationRequest.WikiIndex(
                            scopeKey, readIndexQuietly(scopeKey), capability);
                })
                .filter(Objects::nonNull)
                .toList();
    }
```

`CAPABILITY_TTL` 은 챗봇 한 번의 생애를 덮을 값으로 잡는다. **에이전트 시간 상한(25초)에 재시도(1회)를 더한 것보다 넉넉해야 한다** — 짧으면 정상 질문이 만료로 실패한다. `Duration.ofMinutes(5)` 로 시작한다.

- [ ] **Step 6: 통과를 확인한다**

Run: `cd backend && sh gradlew test --tests '*QuestionAsk*' --tests '*RestClientAiClient*'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add backend/src/main/java/com/ajt/backend/domain/question/ \
        backend/src/main/java/com/ajt/backend/global/ai/client/ \
        backend/src/test/java/com/ajt/backend/
git commit -m "feat(question): 챗봇 AI 호출을 한 번으로 줄이고 목차에 허가값을 담는다 [S15P11B106-169]"
```

---

### Task 4: 오류 이름 매핑과 재시도

**Files:**
- Modify: `global/ai/client/AiClientErrorMapper.java`
- Modify: `domain/question/QuestionAskService.java` (재시도)
- Test: `backend/src/test/java/.../AiClientErrorMapperTest.java`, `QuestionAskServiceTest.java`

**Interfaces:**
- Consumes: Task 3 의 `AiClient`
- Produces: 여덟 개 이름이 사용자에게 보일 메시지로 매핑된다. **고칠 수 있는 실패만** 1회 재시도.

- [ ] **Step 1: 실패 테스트를 쓴다**

```java
@ParameterizedTest
@DisplayName("AI 오류 이름을 사용자 메시지로 옮긴다")
@ValueSource(strings = {
        "NO_WIKI_OR_SCHEDULE_WAS_READ", "WIKI_QUERY_FAILED", "SCHEDULE_QUERY_FAILED",
        "AGENT_TURN_LIMIT_REACHED", "AGENT_TIMED_OUT", "MODEL_CALL_FAILED",
        "ANSWER_WAS_EMPTY"})
void everyNewCodeIsMapped(String code) {
    assertThat(mapper.messageFor(code)).isNotBlank();
}

@Test
@DisplayName("모르는 이름도 사용자에게는 같은 안내로 나간다")
void unknownCodeStillHasAMessage() {
    assertThat(mapper.messageFor("SOMETHING_NEW")).contains("처리 중 문제");
}

@Test
@DisplayName("한 번 실패하면 한 번 더 시도한다")
void retriesOnce() {
    givenMemberCanSee("ALL");
    given(aiClient.generateAnswer(any()))
            .willThrow(new AiClientException(AiClientFailureType.SERVER_ERROR, "AGENT_TIMED_OUT"))
            .willReturn(anyAnswer());

    QuestionAskResponse response = service.ask(loginMember,
            new QuestionAskRequest("질문", null));

    then(aiClient).should(times(2)).generateAnswer(any());
    assertThat(response.answer()).isNotBlank();
}

@Test
@DisplayName("다시 해도 같은 실패는 재시도하지 않는다 — 대기만 두 배가 된다")
void deterministicFailuresAreNotRetried() {
    givenMemberCanSee("ALL");
    given(aiClient.generateAnswer(any())).willThrow(
            new AiClientException(AiClientFailureType.SERVER_ERROR,
                    "NO_WIKI_OR_SCHEDULE_WAS_READ"));

    assertThatThrownBy(() -> service.ask(loginMember, new QuestionAskRequest("질문", null)))
            .isInstanceOf(BusinessException.class);

    then(aiClient).should(times(1)).generateAnswer(any());
}

@Test
@DisplayName("두 번 실패하면 사용자에게 안내를 돌려준다")
void afterTwoFailuresTheUserGetsAMessage() {
    givenMemberCanSee("ALL");
    given(aiClient.generateAnswer(any()))
            .willThrow(new AiClientException(AiClientFailureType.SERVER_ERROR, "MODEL_CALL_FAILED"));

    assertThatThrownBy(() -> service.ask(loginMember, new QuestionAskRequest("질문", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("처리 중 문제");
    then(aiClient).should(times(2)).generateAnswer(any());
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd backend && sh gradlew test --tests '*AiClientErrorMapper*' --tests '*QuestionAskServiceTest*'`
Expected: FAIL — 새 이름이 매핑에 없고 재시도가 없다

- [ ] **Step 3: 매핑과 재시도를 구현한다**

`AiClientErrorMapper` 에 여덟 개를 더한다. **모르는 이름도 같은 안내로 떨어지게 둔다** — AI 가 이름을 늘려도 사용자 화면이 깨지지 않는다.

`QuestionAskService.ask()` 에서 AI 호출을 한 번 재시도한다. **다시 불러도 결과가 같은 실패는 재시도하지 않는다** — 사용자가 기다리는 중이라 25초가 50초가 된다.

```java
    /**
     * 다시 부르면 달라질 수 있는 실패만 재시도한다. 요청 형식 오류와 「근거 없음」은
     * 같은 요청에 같은 결과이므로 대기 시간만 두 배가 된다.
     */
    private static final Set<String> RETRYABLE = Set.of(
            "AGENT_TIMED_OUT", "AGENT_TURN_LIMIT_REACHED", "MODEL_CALL_FAILED",
            "WIKI_QUERY_FAILED", "SCHEDULE_QUERY_FAILED", "ANSWER_WAS_EMPTY");

    private AnswerGenerationResponse generateWithOneRetry(AnswerGenerationRequest request) {
        try {
            return aiClient.generateAnswer(request);
        } catch (AiClientException first) {
            if (!RETRYABLE.contains(first.getCode())) {
                throw first;
            }
            log.warn("AI 답변 생성 1차 실패 — 한 번 더 시도한다. code={}", first.getCode());
            // 재시도가 안전한 이유: AI 는 작업 공간에만 쓰고 반영은 백엔드가 한다. 실패한
            // 실행은 위키에 아무 흔적을 남기지 않는다.
            return aiClient.generateAnswer(request);
        }
    }
```

**모르는 이름은 재시도하지 않는다.** AI 가 이름을 늘렸을 때 조용히 대기 시간을 두 배로 만드는 것보다, 한 번 실패하고 이름을 계약에 넣는 편이 낫다.

- [ ] **Step 4: 통과를 확인한다**

Run: `cd backend && sh gradlew test --tests '*AiClient*' --tests '*QuestionAsk*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ajt/backend/global/ai/client/AiClientErrorMapper.java \
        backend/src/main/java/com/ajt/backend/domain/question/QuestionAskService.java \
        backend/src/test/java/com/ajt/backend/
git commit -m "feat(question): 챗봇 오류 이름 여덟 개를 매핑하고 1회 재시도를 넣는다 [S15P11B106-169]"
```

---

### Task 5: 계약을 고친다 — 옛 내용을 남기지 않는다 ✅ 완료 (2026-07-31, AI 담당이 반영)

**Files:**
- Modify: `docs/api/generate-postman-collections.mjs`
- Modify: `docs/api/postman-contract-examples.mjs`
- Modify: `docs/api/README.md`
- Modify: `scripts/validate-artifact-consistency.mjs`

**Interfaces:**
- Consumes: Task 1~4 의 실제 모양
- Produces: `contractVersion` 이 올라간 계약. 내부 API 개수가 맞는 검증 스크립트.

- [ ] **Step 1: 무엇이 바뀌는지 목록을 만든다**

| 대상 | 변경 |
| --- | --- |
| `POST /internal/v1/answer-context-selections` | **삭제** |
| `POST /internal/v1/answers` 요청 | `questionType`·`selectedWikis`·`selectedSchedules`·`scheduleSummaries` 삭제. `wikiIndexes[].wikiCapability` 추가 |
| `POST /internal/v1/answers` 응답 | `questionType` 추가, `sources[].title` 필수 |
| `GET /internal/v1/schedules` | **신설** |
| `GET /internal/v1/schedules/{scheduleId}` | **신설** |
| 오류 코드 | 여덟 개로 나뉜다. 사라지는 이름 셋 |
| `contractVersion` | `1.8.0` (호환되지 않는 변경. 1.7.0 은 S15P11B106-101 이 가져갔다) |

- [ ] **Step 2: 생성기를 고친다**

`docs/api/generate-postman-collections.mjs` 에서 1단계 요청을 지우고, `answers` 의 필드를 위 표대로 고치고, 일정 조회 두 개를 「일정 처리」 폴더에 더한다. **JSON 을 직접 고치지 않는다.**

- [ ] **Step 3: 예시와 버전을 고친다**

`docs/api/postman-contract-examples.mjs` 의 Saved Example 을 새 모양으로 바꾸고 `contractVersion` 을 올린다.

- [ ] **Step 4: 재생성하고 검증한다**

```bash
node docs/api/generate-postman-collections.mjs
node docs/api/validate-postman-collections.mjs
node scripts/validate-artifact-consistency.mjs
```

`validate-artifact-consistency.mjs` 의 내부 API 개수 기대값을 새 값으로 고친다 (엔드포인트 하나가 빠지고 둘이 생기므로 +1).

- [ ] **Step 5: 커밋**

```bash
git add docs/api/ scripts/validate-artifact-consistency.mjs
git commit -m "docs(api): 챗봇 1단계를 계약에서 지우고 일정 조회 두 개를 추가한다 [S15P11B106-169]"
```

---

### Task 6: 요구사항·컨벤션 문서를 고친다 ✅ 완료 (2026-07-31, AI 담당이 반영)

**Files:**
- Modify: `docs/requirements/요구사항정의서.md` (FR-QNA-012)
- Modify: `docs/conventions/rest-api-convention.md` (827·843줄 주변)

- [ ] **Step 1: FR-QNA-012 를 개정한다**

지금 문장이 2단계 선택을 못 박고 있다.

> FR-QNA-012 | 답변 자료 2단계 선택 | 시스템은 `answer-context-selections` 단계에서 …

새 문장은 이렇게 한다.

> **FR-QNA-012 | 답변 자료 자율 조회** | 시스템은 `answers` 단계에서 권한 내 Wiki 공간의 `index.md` 를 에이전트에 전달하고, 에이전트가 필요한 Wiki 본문과 일정을 내부 조회 API 로 직접 읽어 답변해야 한다. | 에이전트는 실제로 읽은 자료만 출처로 반환한다. 일정은 에이전트가 기간을 정해 조회한다. 조회 권한은 Wiki 는 요청에 실린 허가값, 일정은 질문 번호로 판정한다. | 필수

- [ ] **Step 2: 컨벤션 문서의 흐름 설명을 고친다**

827줄의 `POST /internal/v1/answer-context-selections` 항목을 지우고, 843줄의 「1. 백엔드가 권한 내 Wiki 공간의 `index.md`와 일정 요약을 …에 전달합니다」를 새 흐름으로 바꾼다.

- [ ] **Step 3: 남은 흔적을 찾는다**

```bash
grep -rn "answer-context-selections\|scheduleSummaries\|selectedSchedules" docs/ backend/src scripts/
```

Expected: 결과 없음. 남아 있으면 그 파일도 고친다.

- [ ] **Step 4: 커밋**

```bash
git add docs/requirements/요구사항정의서.md docs/conventions/rest-api-convention.md
git commit -m "docs: 챗봇 답변 자료 조회 방식을 자율 조회로 개정한다 [S15P11B106-169]"
```

---

### Task 7: 전체 검증

- [ ] **Step 1: 백엔드 전체 테스트**

Run: `cd backend && sh gradlew test --rerun-tasks`
Expected: 실패 0

- [ ] **Step 2: 계약·산출물 검증**

```bash
node docs/api/validate-postman-collections.mjs
node scripts/validate-artifact-consistency.mjs
```

- [ ] **Step 3: 옛 흔적이 없는지 마지막 확인**

```bash
grep -rn "answer-context-selections" . --exclude-dir=.git --exclude-dir=node_modules \
  --exclude-dir=build | grep -v "docs/superpowers"
```

Expected: 결과 없음. `docs/superpowers/` 의 설계·계획 문서에는 「없앤다」는 서술로 남아 있어도 된다.

- [ ] **Step 4: MR 을 올린다**

MR 본문에 **협의 항목**으로 적는다.

- 오류 이름 여덟 개는 계약에 없던 것이다. 이 MR 로 확정을 제안한다
- 일정 조회 두 입구의 응답 모양도 이 MR 이 제안이다
- `questionType` 이 1단계 응답에서 `answers` 응답으로 옮겨간다

---

## 이 계획 밖

| 무엇 | 어디서 |
| --- | --- |
| AI 서버 구현 | `ai/docs/superpowers/plans/2026-07-31-chat-single-agent-endpoint.md` (동시 진행) |
| 위키 변환·관리자 수정 합치기 | S15P11B106-151·152·154 뒤 |
| 챗봇 비동기 전환 | 응답 시간 측정 뒤 |
