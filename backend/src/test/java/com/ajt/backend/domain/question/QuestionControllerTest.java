package com.ajt.backend.domain.question;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.global.auth.AccessTokenService;
import com.ajt.backend.global.auth.AuthCookieService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class QuestionControllerTest {

    private final MockMvc mockMvc;
    private final AiQuestionRepository questionRepository;
    private final AiAnswerRepository answerRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;

    @Autowired
    QuestionControllerTest(
            MockMvc mockMvc,
            AiQuestionRepository questionRepository,
            AiAnswerRepository answerRepository,
            AnswerSourceRepository answerSourceRepository,
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService
    ) {
        this.mockMvc = mockMvc;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
    }

    @Test
    @DisplayName("GET /api/v1/questions는 계약 형태의 질문 이력 JSON을 반환한다")
    void questionsReturnsContractShapedHistory() throws Exception {
        Member member = saveMember("me@ajt.com", "AJT-2026-0001");
        AiQuestion question = questionRepository.save(
                AiQuestion.create(member, "chat-123", "연차 규정 알려줘", QuestionType.WIKI, true, null));
        AiAnswer answer = answerRepository.save(AiAnswer.create(question, "연차 규정은 다음과 같습니다."));
        answerSourceRepository.save(AnswerSource.wiki(answer, 101L, "휴가 규정"));

        mockMvc.perform(get("/api/v1/questions").cookie(accessTokenCookie(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[0].conversationId").value("chat-123"))
                .andExpect(jsonPath("$.items[0].questionId").value(String.valueOf(question.getId())))
                .andExpect(jsonPath("$.items[0].questionType").value("wiki"))
                .andExpect(jsonPath("$.items[0].question").value("연차 규정 알려줘"))
                .andExpect(jsonPath("$.items[0].answer").value("연차 규정은 다음과 같습니다."))
                .andExpect(jsonPath("$.items[0].createdAt", endsWith("Z")))
                .andExpect(jsonPath("$.items[0].sources", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sources[0].type").value("wiki"))
                .andExpect(jsonPath("$.items[0].sources[0].wikiId").value("101"))
                .andExpect(jsonPath("$.items[0].sources[0].title").value("휴가 규정"))
                // wiki 출처에는 scheduleId 키가 없어야 한다(NON_NULL, 계약 형태)
                .andExpect(jsonPath("$.items[0].sources[0].scheduleId").doesNotExist());
    }

    @Test
    @DisplayName("일정 출처는 wikiId 없이 scheduleId만 응답한다")
    void scheduleSourceOmitsWikiId() throws Exception {
        Member member = saveMember("me@ajt.com", "AJT-2026-0001");
        AiQuestion question = questionRepository.save(
                AiQuestion.create(member, "chat-1", "다음 휴가 일정 알려줘", QuestionType.SCHEDULE, true, null));
        AiAnswer answer = answerRepository.save(AiAnswer.create(question, "8월 휴가 일정입니다."));
        answerSourceRepository.save(AnswerSource.schedule(answer, 31L, "8월 휴가 일정"));

        mockMvc.perform(get("/api/v1/questions").cookie(accessTokenCookie(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sources[0].type").value("schedule"))
                .andExpect(jsonPath("$.items[0].sources[0].scheduleId").value("31"))
                .andExpect(jsonPath("$.items[0].sources[0].wikiId").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/questions는 인증 쿠키가 없으면 401 INVALID_ACCESS_TOKEN을 반환한다")
    void questionsRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/questions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
    }

    private Member saveMember(String email, String employeeNo) {
        Department department = departmentRepository.save(new Department("개발부"));
        return memberRepository.save(Member.approvedEmployee(
                department,
                email,
                "홍길동",
                passwordEncoder.encode("password123!"),
                employeeNo
        ));
    }

    private Cookie accessTokenCookie(Member member) {
        return new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member));
    }
}
