package com.ajt.backend.domain.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.SuperAdminChecker;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.question.QuestionAskService.AuthorizedSources;
import com.ajt.backend.domain.question.dto.QuestionAskRequest;
import com.ajt.backend.domain.question.dto.QuestionAskResponse;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.domain.schedule.service.ScheduleVisibilityPolicy;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.AiClientFailureType;
import com.ajt.backend.global.ai.client.AnswerGenerationRequest;
import com.ajt.backend.global.ai.client.AnswerGenerationResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.FieldErrorResponse;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("챗봇 질문 오케스트레이션 — AI를 한 번만 부른다")
class QuestionAskServiceTest {

    private final AiClient aiClient = mock(AiClient.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final WikiRepository wikiRepository = mock(WikiRepository.class);
    private final WikiFileStorage wikiFileStorage = mock(WikiFileStorage.class);
    private final ScheduleRepository scheduleRepository = mock(ScheduleRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final AiQuestionRepository questionRepository = mock(AiQuestionRepository.class);
    private final QuestionAnswerTransactionService answerTransactionService =
            mock(QuestionAnswerTransactionService.class);
    private final WikiCapabilityService capabilityService = mock(WikiCapabilityService.class);
    private final SuperAdminChecker superAdminChecker = mock(SuperAdminChecker.class);
    private final QuestionAskService service = new QuestionAskService(
            aiClient,
            memberRepository,
            wikiScopeRepository,
            wikiRepository,
            wikiFileStorage,
            scheduleRepository,
            documentRepository,
            questionRepository,
            answerTransactionService,
            new ScheduleVisibilityPolicy(),
            capabilityService,
            superAdminChecker
    );

    @Test
    @DisplayName("AI를 한 번만 부르고, 요청에는 목차만 싣는다")
    void callsTheAiExactlyOnce() throws Exception {
        givenMemberCanSee("ALL", "D1-D2");
        given(aiClient.generateAnswer(any())).willReturn(anyAnswer());
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "연차 며칠이야?"));

        verify(aiClient, times(1)).generateAnswer(any());
        verifyNoMoreInteractions(aiClient);
    }

    @Test
    @DisplayName("최고관리자는 모든 Wiki 공간을 근거로 쓴다(S15P11B106-290)")
    void superAdminSeesEveryScope() throws Exception {
        // 최고관리자의 소속은 명목상 '최고관리자' 부서라, 소속 기준으로 계산하면 부서 공간이
        // 하나도 안 잡혀 「개발부 규정이 뭐야」에 근거를 못 찾았다.
        givenMemberCanSee("ALL");
        WikiScope devScope = mock(WikiScope.class);
        given(devScope.scopeKey()).willReturn("D1");
        given(devScope.scopeVersion()).willReturn(9L);
        given(wikiScopeRepository.findAll()).willReturn(List.of(devScope));
        given(wikiScopeRepository.findById("D1")).willReturn(Optional.of(devScope));
        given(wikiFileStorage.readIndex("D1")).willReturn("# D1");
        given(superAdminChecker.isSuperAdmin(any(Member.class))).willReturn(true);
        given(aiClient.generateAnswer(any())).willReturn(anyAnswer());
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "개발부 규정이 뭐야?"));

        assertThat(capturedRequest().wikiIndexes())
                .extracting(AnswerGenerationRequest.WikiIndex::scopeKey)
                .containsExactlyInAnyOrder("ALL", "D1");
    }

    @Test
    @DisplayName("목차 각 행에 그 범위의 허가값을 담는다 — 범위 수만큼 발급한다")
    void everyIndexRowCarriesItsCapability() throws Exception {
        givenMemberCanSee("ALL", "D1-D2");
        given(capabilityService.issue(eq("ALL"), anyLong(), any())).willReturn("cap-all");
        given(capabilityService.issue(eq("D1-D2"), anyLong(), any())).willReturn("cap-d1");
        given(aiClient.generateAnswer(any())).willReturn(anyAnswer());
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "질문"));

        AnswerGenerationRequest sent = capturedRequest();
        assertThat(sent.wikiIndexes())
                .extracting(AnswerGenerationRequest.WikiIndex::scopeKey,
                        AnswerGenerationRequest.WikiIndex::wikiCapability)
                .containsExactlyInAnyOrder(tuple("ALL", "cap-all"), tuple("D1-D2", "cap-d1"));
    }

    @Test
    @DisplayName("허가값 유효기간은 에이전트 상한(25초)에 재시도를 더한 것보다 넉넉하다")
    void capabilityTtlOutlivesTheAgentAndItsRetry() throws Exception {
        givenMemberCanSee("ALL");
        given(aiClient.generateAnswer(any())).willReturn(anyAnswer());
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "질문"));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(capabilityService).issue(eq("ALL"), anyLong(), ttl.capture());
        assertThat(ttl.getValue()).isGreaterThan(Duration.ofSeconds(25 * 2));
    }

    @Test
    @DisplayName("일정 목록을 싣지 않는다 — 에이전트가 조회한다")
    void schedulesAreNotPushedAnyMore() throws Exception {
        givenMemberCanSee("ALL");
        given(aiClient.generateAnswer(any())).willReturn(anyAnswer());
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "다음 워크샵 언제야?"));

        // 요청에 일정 관련 필드가 아예 없다 — 레코드에 그 필드가 없으므로 컴파일로도 막힌다.
        assertThat(capturedRequest().wikiIndexes()).isNotEmpty();
        // 일정을 후보로 모으지도 않는다.
        verify(scheduleRepository, never()).findAll();
    }

    @Test
    @DisplayName("응답의 questionType을 저장한다 — 1단계가 아니라 여기서 온다")
    void questionTypeComesFromTheAnswerNow() throws Exception {
        givenMemberCanSee("ALL");
        given(aiClient.generateAnswer(any())).willReturn(
                new AnswerGenerationResponse("답", List.of(), "mixed"));
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "질문"));

        ArgumentCaptor<AnswerGenerationResponse> saved =
                ArgumentCaptor.forClass(AnswerGenerationResponse.class);
        verify(answerTransactionService).saveAnswer(
                eq(500L), anyString(), saved.capture(), any(), any());
        assertThat(saved.getValue().questionType()).isEqualTo("mixed");
    }

    @Test
    @DisplayName("근거를 못 찾은 답변도 그대로 저장한다 — 출처가 0건이고 실패가 아니다")
    void answerWithoutEvidenceIsStillSaved() throws Exception {
        givenMemberCanSee("ALL");
        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "관련 정보를 찾지 못했습니다.", List.of(), "wiki"));
        givenSaveAnswerEchoes();

        QuestionAskResponse response = service.ask(employee(), new QuestionAskRequest(null, "질문"));

        assertThat(response.answer()).isEqualTo("관련 정보를 찾지 못했습니다.");
        verify(answerTransactionService, never()).markFailed(anyLong(), anyString());
        assertThat(capturedAuthorizedSources().wikiDocumentRefs()).isEmpty();
        assertThat(capturedAuthorizedSources().scheduleIds()).isEmpty();
    }

    @Test
    @DisplayName("권한 없는 공간의 Wiki를 출처로 신고해도 인정하지 않는다")
    void dropsWikiSourceOutsideAccessibleScopes() throws Exception {
        givenMemberCanSee("ALL");
        Wiki inaccessible = wiki(777L, "D3", "타부서 규정");
        given(wikiRepository.findAllById(List.of(777L))).willReturn(List.of(inaccessible));
        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "답변", List.of(new AnswerGenerationResponse.Source("wiki", "777", null, "타부서 규정")),
                "wiki"));
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "질문"));

        assertThat(capturedAuthorizedSources().allowsWiki(777L)).isFalse();
    }

    @Test
    @DisplayName("접근 가능한 공간의 Wiki 출처는 인정하고 원본문서 연결도 함께 넘긴다")
    void keepsWikiSourceInsideAccessibleScopes() throws Exception {
        givenMemberCanSee("ALL");
        Wiki accessible = wiki(101L, "ALL", "휴가 규정");
        given(accessible.documentRefs()).willReturn(List.of(15L));
        given(wikiRepository.findAllById(List.of(101L))).willReturn(List.of(accessible));
        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "연차는 15일입니다.",
                List.of(new AnswerGenerationResponse.Source("wiki", "101", null, "휴가 규정")),
                "wiki"));
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "질문"));

        AuthorizedSources authorized = capturedAuthorizedSources();
        assertThat(authorized.allowsWiki(101L)).isTrue();
        assertThat(authorized.wikiDocumentRefs().get(101L)).containsExactly(15L);
    }

    @Test
    @DisplayName("볼 수 없는 일정을 출처로 신고해도 인정하지 않는다")
    void dropsScheduleSourceTheAskerCannotSee() throws Exception {
        givenMemberCanSee("ALL");
        Schedule otherDept = schedule(33L, "타부서 일정", ScheduleVisibility.DEPARTMENT, true, 99L, List.of(3L));
        Schedule mine = schedule(31L, "전사 일정", ScheduleVisibility.ALL, true, 99L, List.of());
        given(scheduleRepository.findAllByIdWithDepartments(List.of(31L, 33L))).willReturn(List.of(mine, otherDept));
        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "일정입니다.",
                List.of(
                        new AnswerGenerationResponse.Source("schedule", null, "31", "전사 일정"),
                        new AnswerGenerationResponse.Source("schedule", null, "33", "타부서 일정")),
                "schedule"));
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "질문"));

        AuthorizedSources authorized = capturedAuthorizedSources();
        assertThat(authorized.allowsSchedule(31L)).isTrue();
        assertThat(authorized.allowsSchedule(33L)).isFalse();
    }

    @Test
    @DisplayName("승인 전 초안 일정은 출처로 인정하지 않는다")
    void dropsDraftScheduleSource() throws Exception {
        givenMemberCanSee("ALL");
        Schedule draft = schedule(32L, "초안 일정", ScheduleVisibility.ALL, false, 99L, List.of());
        given(scheduleRepository.findAllByIdWithDepartments(List.of(32L))).willReturn(List.of(draft));
        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "일정입니다.",
                List.of(new AnswerGenerationResponse.Source("schedule", null, "32", "초안 일정")),
                "schedule"));
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "질문"));

        assertThat(capturedAuthorizedSources().allowsSchedule(32L)).isFalse();
    }

    @Test
    @DisplayName("목차가 없는 공간은 요청에서 빼고 허가값도 발급하지 않는다")
    void skipsScopesWithoutAnIndex() throws Exception {
        givenMemberCanSee("ALL", "D1-D2");
        given(wikiFileStorage.readIndex("D1-D2")).willReturn("  ");
        given(aiClient.generateAnswer(any())).willReturn(anyAnswer());
        givenSaveAnswerEchoes();

        service.ask(employee(), new QuestionAskRequest(null, "질문"));

        assertThat(capturedRequest().wikiIndexes())
                .extracting(AnswerGenerationRequest.WikiIndex::scopeKey)
                .containsExactly("ALL");
        verify(capabilityService, never()).issue(eq("D1-D2"), anyLong(), any());
    }

    @Test
    @DisplayName("AI 호출이 실패하면 질문에 실패 사유를 남기고 503으로 응답한다")
    void marksFailureWhenAiCallFails() throws Exception {
        givenMemberCanSee("ALL");
        given(aiClient.generateAnswer(any())).willThrow(timeout());

        assertThatThrownBy(() -> service.ask(employee(), new QuestionAskRequest(null, "질문")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_SERVER_UNAVAILABLE);

        verify(answerTransactionService).markFailed(500L, "FastAPI 응답 시간이 초과되었습니다.");
    }

    @Test
    @DisplayName("고칠 수 있는 실패는 한 번 더 시도한다")
    void retriesOnceOnRecoverableFailure() throws Exception {
        givenMemberCanSee("ALL");
        given(aiClient.generateAnswer(any()))
                .willThrow(answerFailure("AGENT_TIMED_OUT"))
                .willReturn(anyAnswer());
        givenSaveAnswerEchoes();

        QuestionAskResponse response = service.ask(employee(), new QuestionAskRequest(null, "질문"));

        verify(aiClient, times(2)).generateAnswer(any());
        assertThat(response.answer()).isNotBlank();
        verify(answerTransactionService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("다시 해도 같은 실패는 재시도하지 않는다 — 대기만 두 배가 된다")
    void deterministicFailuresAreNotRetried() throws Exception {
        givenMemberCanSee("ALL");
        given(aiClient.generateAnswer(any())).willThrow(answerFailure("NO_WIKI_OR_SCHEDULE_WAS_READ"));

        assertThatThrownBy(() -> service.ask(employee(), new QuestionAskRequest(null, "질문")))
                .isInstanceOf(BusinessException.class);

        verify(aiClient, times(1)).generateAnswer(any());
    }

    @Test
    @DisplayName("모르는 이름도 재시도하지 않는다")
    void unknownFailureNamesAreNotRetried() throws Exception {
        givenMemberCanSee("ALL");
        given(aiClient.generateAnswer(any())).willThrow(answerFailure("SOMETHING_NEW"));

        assertThatThrownBy(() -> service.ask(employee(), new QuestionAskRequest(null, "질문")))
                .isInstanceOf(BusinessException.class);

        verify(aiClient, times(1)).generateAnswer(any());
    }

    @Test
    @DisplayName("두 번 실패하면 그 오류 이름에 맞는 안내를 사용자에게 돌려준다")
    void afterTwoFailuresTheUserGetsTheMappedMessage() throws Exception {
        givenMemberCanSee("ALL");
        given(aiClient.generateAnswer(any())).willThrow(answerFailure("MODEL_CALL_FAILED"));

        assertThatThrownBy(() -> service.ask(employee(), new QuestionAskRequest(null, "질문")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("답변 생성 중 문제가 생겼습니다");

        verify(aiClient, times(2)).generateAnswer(any());
        verify(answerTransactionService).markFailed(eq(500L), anyString());
    }

    @Test
    @DisplayName("다른 사용자의 대화 ID로는 질문할 수 없다")
    void rejectsOtherMembersConversation() throws Exception {
        Member member = member(10L, 1L);
        given(memberRepository.findById(10L)).willReturn(Optional.of(member));
        given(questionRepository.existsByMember_IdAndConversationKey(10L, "chat-남의것")).willReturn(false);

        assertThatThrownBy(() -> service.ask(employee(), new QuestionAskRequest("chat-남의것", "질문")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(aiClient, never()).generateAnswer(any());
    }

    @Test
    @DisplayName("질문이 비어 있으면 400으로 막는다")
    void rejectsBlankQuestion() {
        assertThatThrownBy(() -> service.ask(employee(), new QuestionAskRequest(null, "   ")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(aiClient, never()).generateAnswer(any());
    }

    // ===== 헬퍼 =====

    /** 전사(ALL)와 주어진 부서 공간을 볼 수 있는 사원이 질문한 상태를 만든다. */
    private void givenMemberCanSee(String... scopeKeys) throws Exception {
        Member member = member(10L, 1L);
        AiQuestion question = question(500L, member, "질문");
        given(memberRepository.findById(10L)).willReturn(Optional.of(member));
        given(answerTransactionService.saveQuestion(any(), anyString(), anyString())).willReturn(question);
        given(questionRepository.findByMember_IdAndConversationKeyOrderByCreatedAtAsc(anyLong(), anyString()))
                .willReturn(List.of());

        List<WikiScope> departmentScopes = new java.util.ArrayList<>();
        for (String scopeKey : scopeKeys) {
            given(wikiFileStorage.readIndex(scopeKey)).willReturn("# " + scopeKey);
            WikiScope scope = mock(WikiScope.class);
            given(scope.scopeKey()).willReturn(scopeKey);
            given(scope.scopeVersion()).willReturn(47L);
            given(wikiScopeRepository.findById(scopeKey)).willReturn(Optional.of(scope));
            if (!"ALL".equals(scopeKey)) {
                given(scope.departmentRefs()).willReturn(List.of(1L));
                departmentScopes.add(scope);
            }
        }
        given(wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT))
                .willReturn(List.copyOf(departmentScopes));
        given(capabilityService.issue(anyString(), anyLong(), any())).willReturn("cap");
    }

    private void givenSaveAnswerEchoes() {
        given(answerTransactionService.saveAnswer(anyLong(), anyString(), any(), any(), any()))
                .willAnswer(invocation -> {
                    AnswerGenerationResponse answer = invocation.getArgument(2);
                    return new QuestionAskResponse(
                            invocation.getArgument(1),
                            "500",
                            answer.questionType(),
                            answer.answer(),
                            List.of(),
                            Instant.parse("2026-07-31T00:00:00Z"));
                });
    }

    private AnswerGenerationRequest capturedRequest() {
        ArgumentCaptor<AnswerGenerationRequest> sent =
                ArgumentCaptor.forClass(AnswerGenerationRequest.class);
        verify(aiClient).generateAnswer(sent.capture());
        return sent.getValue();
    }

    private AuthorizedSources capturedAuthorizedSources() {
        ArgumentCaptor<AuthorizedSources> authorized = ArgumentCaptor.forClass(AuthorizedSources.class);
        verify(answerTransactionService).saveAnswer(
                anyLong(), anyString(), any(), authorized.capture(), any());
        return authorized.getValue();
    }

    private AnswerGenerationResponse anyAnswer() {
        return new AnswerGenerationResponse("답변", List.of(), "wiki");
    }

    private AuthenticatedMember employee() {
        return new AuthenticatedMember(10L, "user@ajt.com", Role.EMPLOYEE);
    }

    /** 계약이 정한 이름을 실은 500 실패입니다. 재시도 판단이 이 이름으로 갈린다. */
    private AiClientException answerFailure(String code) {
        return new AiClientException(
                AiClientFailureType.SERVER_ERROR,
                500,
                code,
                code,
                List.<FieldErrorResponse>of(),
                null
        );
    }

    private AiClientException timeout() {
        return new AiClientException(
                AiClientFailureType.TIMEOUT,
                null,
                null,
                "FastAPI 응답 시간이 초과되었습니다.",
                List.<FieldErrorResponse>of(),
                null
        );
    }

    private Member member(long id, Long departmentId) {
        Member member = mock(Member.class);
        given(member.getId()).willReturn(id);
        if (departmentId != null) {
            Department department = mock(Department.class);
            given(department.getId()).willReturn(departmentId);
            given(member.getDepartment()).willReturn(department);
        }
        return member;
    }

    private AiQuestion question(long id, Member member, String content) throws Exception {
        AiQuestion question = AiQuestion.create(member, "chat-1", content, null, false, null);
        assign(question, "id", id);
        return question;
    }

    private Wiki wiki(long id, String scopeKey, String title) {
        Wiki wiki = mock(Wiki.class);
        given(wiki.id()).willReturn(id);
        given(wiki.scopeKey()).willReturn(scopeKey);
        given(wiki.title()).willReturn(title);
        return wiki;
    }

    private Schedule schedule(
            long id,
            String title,
            ScheduleVisibility visibility,
            boolean approved,
            long authorId,
            List<Long> departmentIds
    ) {
        Schedule schedule = mock(Schedule.class);
        given(schedule.id()).willReturn(id);
        given(schedule.title()).willReturn(title);
        given(schedule.visibilityType()).willReturn(visibility);
        given(schedule.status()).willReturn(approved
                ? com.ajt.backend.domain.schedule.model.ScheduleStatus.APPROVED
                : com.ajt.backend.domain.schedule.model.ScheduleStatus.DRAFT);
        given(schedule.authorId()).willReturn(authorId);
        given(schedule.departmentIds()).willReturn(departmentIds);
        return schedule;
    }

    private void assign(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
