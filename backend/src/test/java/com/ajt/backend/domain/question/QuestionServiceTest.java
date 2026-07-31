package com.ajt.backend.domain.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.question.dto.QuestionHistoryItemResponse;
import com.ajt.backend.domain.question.dto.QuestionHistoryResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@Transactional
class QuestionServiceTest {

    private final QuestionService questionService;
    private final AiQuestionRepository questionRepository;
    private final AiAnswerRepository answerRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    QuestionServiceTest(
            QuestionService questionService,
            AiQuestionRepository questionRepository,
            AiAnswerRepository answerRepository,
            AnswerSourceRepository answerSourceRepository,
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.questionService = questionService;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Test
    @DisplayName("질문 이력은 로그인한 본인의 질문만 반환한다")
    void findMyQuestionsReturnsOnlyOwnQuestions() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member me = saveMember(department, "me@ajt.com", "AJT-2026-0001");
        Member other = saveMember(department, "other@ajt.com", "AJT-2026-0002");
        saveQuestion(me, "c1", "내 질문", QuestionType.WIKI, true);
        saveQuestion(other, "c2", "남의 질문", QuestionType.WIKI, true);

        QuestionHistoryResponse response = questionService.findMyQuestions(auth(me), null, null, null, null);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.items().get(0).question()).isEqualTo("내 질문");
    }

    @Test
    @DisplayName("질문 이력은 답변과 출처(Wiki·일정)를 함께 반환한다")
    void findMyQuestionsIncludesAnswerAndSources() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member me = saveMember(department, "me@ajt.com", "AJT-2026-0001");
        AiQuestion question = saveQuestion(me, "c1", "연차 규정과 일정 알려줘", QuestionType.MIXED, true);
        AiAnswer answer = answerRepository.save(AiAnswer.create(question, "답변입니다"));
        answerSourceRepository.save(AnswerSource.wiki(answer, 101L, "휴가 규정"));
        answerSourceRepository.save(AnswerSource.schedule(answer, 31L, "8월 휴가 일정"));

        QuestionHistoryItemResponse item = questionService.findMyQuestions(auth(me), null, null, null, null)
                .items().get(0);

        assertThat(item.answer()).isEqualTo("답변입니다");
        assertThat(item.sources()).hasSize(2);
        assertThat(item.sources())
                .anySatisfy(source -> {
                    assertThat(source.type()).isEqualTo("wiki");
                    assertThat(source.wikiId()).isEqualTo("101");
                    assertThat(source.title()).isEqualTo("휴가 규정");
                })
                .anySatisfy(source -> {
                    assertThat(source.type()).isEqualTo("schedule");
                    assertThat(source.scheduleId()).isEqualTo("31");
                });
    }

    @Test
    @DisplayName("실패한 질문은 답변이 null이고 출처가 비어 있다")
    void findMyQuestionsReturnsNullAnswerForFailedQuestion() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member me = saveMember(department, "me@ajt.com", "AJT-2026-0001");
        saveQuestion(me, "c1", "실패한 질문", null, false);

        QuestionHistoryItemResponse item = questionService.findMyQuestions(auth(me), null, null, null, null)
                .items().get(0);

        assertThat(item.answer()).isNull();
        assertThat(item.sources()).isEmpty();
        assertThat(item.questionType()).isNull();
    }

    @Test
    @DisplayName("conversationId로 특정 대화의 질문만 조회한다")
    void findMyQuestionsFiltersByConversationId() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member me = saveMember(department, "me@ajt.com", "AJT-2026-0001");
        saveQuestion(me, "c1", "첫 대화", QuestionType.WIKI, true);
        saveQuestion(me, "c2", "다른 대화", QuestionType.WIKI, true);

        QuestionHistoryResponse response = questionService.findMyQuestions(auth(me), "c1", null, null, null);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.items().get(0).conversationId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("questionType으로 질문 유형을 필터링한다")
    void findMyQuestionsFiltersByQuestionType() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member me = saveMember(department, "me@ajt.com", "AJT-2026-0001");
        saveQuestion(me, "c1", "위키 질문", QuestionType.WIKI, true);
        saveQuestion(me, "c1", "일정 질문", QuestionType.SCHEDULE, true);

        QuestionHistoryResponse response = questionService.findMyQuestions(auth(me), null, "schedule", null, null);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.items().get(0).questionType()).isEqualTo("schedule");
    }

    @Test
    @DisplayName("질문 이력은 최신순으로 정렬된다")
    void findMyQuestionsReturnsNewestFirst() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member me = saveMember(department, "me@ajt.com", "AJT-2026-0001");
        saveQuestion(me, "c1", "먼저", QuestionType.WIKI, true);
        saveQuestion(me, "c1", "나중", QuestionType.WIKI, true);

        QuestionHistoryResponse response = questionService.findMyQuestions(auth(me), null, null, null, null);

        assertThat(response.items().get(0).question()).isEqualTo("나중");
    }

    @Test
    @DisplayName("잘못된 questionType은 400으로 거절한다")
    void findMyQuestionsRejectsInvalidQuestionType() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member me = saveMember(department, "me@ajt.com", "AJT-2026-0001");

        assertThatThrownBy(() -> questionService.findMyQuestions(auth(me), null, "invalid", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_QUESTION_FILTER);
    }

    private Member saveMember(Department department, String email, String employeeNo) {
        return memberRepository.save(Member.approvedEmployee(
                department,
                email,
                "홍길동",
                passwordEncoder.encode("password123!"),
                employeeNo
        ));
    }

    private AiQuestion saveQuestion(Member member, String conversationKey, String content, QuestionType type, boolean success) {
        return questionRepository.save(AiQuestion.create(
                member,
                conversationKey,
                content,
                type,
                success,
                success ? null : "AI 답변 서버 이용 불가"
        ));
    }

    private AuthenticatedMember auth(Member member) {
        return new AuthenticatedMember(member.getId(), member.getEmail(), Role.EMPLOYEE);
    }
}
