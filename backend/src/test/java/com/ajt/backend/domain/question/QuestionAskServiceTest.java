package com.ajt.backend.domain.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.question.dto.QuestionAskRequest;
import com.ajt.backend.domain.question.dto.QuestionAskResponse;
import com.ajt.backend.domain.question.dto.QuestionAskSourceResponse;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.AiClientFailureType;
import com.ajt.backend.global.ai.client.AnswerContextSelectionRequest;
import com.ajt.backend.global.ai.client.AnswerContextSelectionResponse;
import com.ajt.backend.global.ai.client.AnswerGenerationRequest;
import com.ajt.backend.global.ai.client.AnswerGenerationResponse;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.FieldErrorResponse;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("챗봇 질문 오케스트레이션")
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
    private final QuestionAskService service = new QuestionAskService(
            aiClient,
            memberRepository,
            wikiScopeRepository,
            wikiRepository,
            wikiFileStorage,
            scheduleRepository,
            documentRepository,
            questionRepository,
            answerTransactionService
    );

    @Test
    @DisplayName("접근 가능한 목차만 1단계로 보내고, 고른 자료의 본문을 2단계로 보낸다")
    void sendsIndexesThenSelectedBodies() throws Exception {
        Member member = member(10L, 1L);
        given(memberRepository.findById(10L)).willReturn(Optional.of(member));
        given(answerTransactionService.saveQuestion(any(), anyString(), anyString()))
                .willReturn(question(500L, member, "연차 규정 알려줘"));
        given(questionRepository.findByMember_IdAndConversationKeyOrderByCreatedAtAsc(anyLong(), anyString()))
                .willReturn(List.of());
        // 부서 1을 포함하는 D1-D2와 전체 공개 ALL이 접근 가능하다. D3은 접근 불가.
        WikiScope myScope = scope("D1-D2", List.of(1L));
        WikiScope otherScope = scope("D3", List.of(3L));
        given(wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT))
                .willReturn(List.of(myScope, otherScope));
        given(wikiFileStorage.readIndex("ALL")).willReturn("# 전체\n- [사규](pages/9.md)");
        given(wikiFileStorage.readIndex("D1-D2")).willReturn("# 개발부\n- [휴가 규정](pages/101.md)");
        given(scheduleRepository.findAll()).willReturn(List.of());

        Wiki wiki = wiki(101L, "D1-D2", "휴가 규정");
        given(aiClient.selectAnswerContext(any())).willReturn(
                new AnswerContextSelectionResponse("wiki", List.of("101"), List.of(), "휴가 규정 질문"));
        given(wikiRepository.findAllById(List.of(101L))).willReturn(List.of(wiki));
        given(wikiFileStorage.readWikiMarkdown("wiki/D1-D2/pages/101.md")).willReturn("# 휴가 규정\n연차는...");
        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "연차는 15일입니다.",
                List.of(new AnswerGenerationResponse.Source("wiki", "101", null, "휴가 규정"))));
        given(answerTransactionService.saveAnswer(anyLong(), anyString(), anyString(), any(), any(), any()))
                .willReturn(new QuestionAskResponse("chat-1", "500", "wiki", "연차는 15일입니다.",
                        List.of(QuestionAskSourceResponse.wiki("101", "휴가 규정", List.of())),
                        Instant.parse("2026-07-31T00:00:00Z")));

        QuestionAskResponse response = service.ask(employee(),
                new QuestionAskRequest(null, "연차 규정 알려줘"));

        assertThat(response.answer()).isEqualTo("연차는 15일입니다.");

        // 1단계: 접근 가능한 공간의 목차만 실린다. 본문은 없다.
        ArgumentCaptor<AnswerContextSelectionRequest> selection =
                ArgumentCaptor.forClass(AnswerContextSelectionRequest.class);
        verify(aiClient).selectAnswerContext(selection.capture());
        assertThat(selection.getValue().wikiIndexes())
                .extracting(AnswerContextSelectionRequest.WikiIndex::scopeKey)
                .containsExactly("ALL", "D1-D2");
        assertThat(selection.getValue().questionId()).isEqualTo("500");

        // 2단계: 고른 Wiki의 본문이 실린다.
        ArgumentCaptor<AnswerGenerationRequest> generation =
                ArgumentCaptor.forClass(AnswerGenerationRequest.class);
        verify(aiClient).generateAnswer(generation.capture());
        assertThat(generation.getValue().selectedWikis())
                .extracting(AnswerGenerationRequest.SelectedWiki::wikiId)
                .containsExactly("101");
        assertThat(generation.getValue().selectedWikis().get(0).contentMarkdown())
                .isEqualTo("# 휴가 규정\n연차는...");
        assertThat(generation.getValue().questionType()).isEqualTo("wiki");
    }

    @Test
    @DisplayName("권한 없는 공간의 Wiki를 AI가 골라도 2단계 문맥에서 제외한다")
    void dropsWikiOutsideAccessibleScopes() throws Exception {
        Member member = member(10L, 1L);
        given(memberRepository.findById(10L)).willReturn(Optional.of(member));
        given(answerTransactionService.saveQuestion(any(), anyString(), anyString()))
                .willReturn(question(500L, member, "질문"));
        given(questionRepository.findByMember_IdAndConversationKeyOrderByCreatedAtAsc(anyLong(), anyString()))
                .willReturn(List.of());
        WikiScope otherScope = scope("D3", List.of(3L));
        given(wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT))
                .willReturn(List.of(otherScope));
        given(wikiFileStorage.readIndex("ALL")).willReturn("# 전체");
        given(scheduleRepository.findAll()).willReturn(List.of());

        // AI가 접근 불가 공간(D3)의 Wiki를 골랐다.
        given(aiClient.selectAnswerContext(any())).willReturn(
                new AnswerContextSelectionResponse("wiki", List.of("777"), List.of(), "선택"));
        Wiki inaccessible = wiki(777L, "D3", "타부서 규정");
        given(wikiRepository.findAllById(List.of(777L))).willReturn(List.of(inaccessible));
        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "답변",
                List.of(new AnswerGenerationResponse.Source("wiki", "777", null, "타부서 규정"))));
        given(answerTransactionService.saveAnswer(anyLong(), anyString(), anyString(), any(), any(), any()))
                .willReturn(new QuestionAskResponse("chat-1", "500", "wiki", "답변", List.of(), Instant.now()));

        service.ask(employee(), new QuestionAskRequest(null, "질문"));

        ArgumentCaptor<AnswerGenerationRequest> generation =
                ArgumentCaptor.forClass(AnswerGenerationRequest.class);
        verify(aiClient).generateAnswer(generation.capture());
        assertThat(generation.getValue().selectedWikis()).isEmpty();
        // 본문도 읽지 않는다.
        verify(wikiFileStorage, never()).readWikiMarkdown("wiki/D3/pages/777.md");
    }

    @Test
    @DisplayName("AI 호출이 실패하면 질문에 실패 사유를 남기고 503으로 응답한다")
    void marksFailureWhenAiCallFails() throws Exception {
        Member member = member(10L, 1L);
        given(memberRepository.findById(10L)).willReturn(Optional.of(member));
        given(answerTransactionService.saveQuestion(any(), anyString(), anyString()))
                .willReturn(question(500L, member, "질문"));
        given(questionRepository.findByMember_IdAndConversationKeyOrderByCreatedAtAsc(anyLong(), anyString()))
                .willReturn(List.of());
        given(wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT))
                .willReturn(List.of());
        given(wikiFileStorage.readIndex("ALL")).willReturn("# 전체");
        given(scheduleRepository.findAll()).willReturn(List.of());
        given(aiClient.selectAnswerContext(any())).willThrow(timeout());

        assertThatThrownBy(() -> service.ask(
                employee(), new QuestionAskRequest(null, "질문")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_SERVER_UNAVAILABLE);

        verify(answerTransactionService).markFailed(500L, "FastAPI 응답 시간이 초과되었습니다.");
        verify(aiClient, never()).generateAnswer(any());
    }

    @Test
    @DisplayName("다른 사용자의 대화 ID로는 질문할 수 없다")
    void rejectsOtherMembersConversation() throws Exception {
        Member member = member(10L, 1L);
        given(memberRepository.findById(10L)).willReturn(Optional.of(member));
        given(questionRepository.existsByMember_IdAndConversationKey(10L, "chat-남의것")).willReturn(false);

        assertThatThrownBy(() -> service.ask(
                employee(), new QuestionAskRequest("chat-남의것", "질문")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(aiClient, never()).selectAnswerContext(any());
    }

    @Test
    @DisplayName("질문이 비어 있으면 400으로 막는다")
    void rejectsBlankQuestion() {
        assertThatThrownBy(() -> service.ask(
                employee(), new QuestionAskRequest(null, "   ")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(aiClient, never()).selectAnswerContext(any());
    }

    @Test
    @DisplayName("승인 전 초안 일정과 타부서 일정은 후보에 넣지 않는다")
    void offersOnlyVisibleApprovedSchedules() throws Exception {
        Member member = member(10L, 1L);
        given(memberRepository.findById(10L)).willReturn(Optional.of(member));
        given(answerTransactionService.saveQuestion(any(), anyString(), anyString()))
                .willReturn(question(500L, member, "일정 알려줘"));
        given(questionRepository.findByMember_IdAndConversationKeyOrderByCreatedAtAsc(anyLong(), anyString()))
                .willReturn(List.of());
        given(wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT))
                .willReturn(List.of());
        given(wikiFileStorage.readIndex("ALL")).willReturn("# 전체");

        Schedule approvedAll = schedule(31L, "8월 전체 일정", ScheduleVisibility.ALL, true, 99L, List.of());
        Schedule draft = schedule(32L, "초안 일정", ScheduleVisibility.ALL, false, 99L, List.of());
        Schedule otherDept = schedule(33L, "타부서 일정", ScheduleVisibility.DEPARTMENT, true, 99L, List.of(3L));
        given(scheduleRepository.findAll()).willReturn(List.of(approvedAll, draft, otherDept));

        given(aiClient.selectAnswerContext(any())).willReturn(
                new AnswerContextSelectionResponse("schedule", List.of(), List.of("31"), "일정 질문"));
        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "8월 일정입니다.",
                List.of(new AnswerGenerationResponse.Source("schedule", null, "31", "8월 전체 일정"))));
        given(answerTransactionService.saveAnswer(anyLong(), anyString(), anyString(), any(), any(), any()))
                .willReturn(new QuestionAskResponse("chat-1", "500", "schedule", "8월 일정입니다.",
                        List.of(QuestionAskSourceResponse.schedule("31", "8월 전체 일정")), Instant.now()));

        service.ask(employee(), new QuestionAskRequest(null, "일정 알려줘"));

        ArgumentCaptor<AnswerContextSelectionRequest> selection =
                ArgumentCaptor.forClass(AnswerContextSelectionRequest.class);
        verify(aiClient).selectAnswerContext(selection.capture());
        assertThat(selection.getValue().scheduleSummaries())
                .extracting(AnswerContextSelectionRequest.ScheduleSummary::scheduleId)
                .containsExactly("31");
    }

    // ===== 헬퍼 =====

    private AuthenticatedMember employee() {
        return new AuthenticatedMember(10L, "user@ajt.com", Role.EMPLOYEE);
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

    private Member member(long id, Long departmentId) throws Exception {
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

    private WikiScope scope(String scopeKey, List<Long> departmentIds) {
        WikiScope scope = mock(WikiScope.class);
        given(scope.scopeKey()).willReturn(scopeKey);
        given(scope.departmentRefs()).willReturn(departmentIds);
        return scope;
    }

    private Wiki wiki(long id, String scopeKey, String title) throws Exception {
        Wiki wiki = mock(Wiki.class);
        given(wiki.id()).willReturn(id);
        given(wiki.scopeKey()).willReturn(scopeKey);
        given(wiki.title()).willReturn(title);
        given(wiki.wikiPath()).willReturn("wiki/" + scopeKey + "/pages/" + id + ".md");
        given(wiki.documentRefs()).willReturn(List.of());
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
        given(schedule.startAt()).willReturn(Instant.parse("2026-08-03T01:00:00Z"));
        given(schedule.endAt()).willReturn(Instant.parse("2026-08-03T03:00:00Z"));
        return schedule;
    }

    private void assign(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
