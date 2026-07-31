package com.ajt.backend.domain.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.question.dto.QuestionAskRequest;
import com.ajt.backend.domain.question.dto.QuestionAskResponse;
import com.ajt.backend.domain.question.dto.QuestionAskSourceResponse;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AnswerGenerationResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 챗봇 출처 권한 판정을 <b>트랜잭션 밖에서</b> 검증합니다. (S15P11B106-171)
 *
 * <p><b>이 테스트에 {@code @Transactional} 을 붙이면 안 된다.</b> 붙이면 세션이 열린 채로 남아
 * lazy 컬렉션이 그냥 채워지고, 바로 이 결함을 통과시킨다 — 실제 요청은 {@code ask()} 가
 * 트랜잭션 밖에서 도는데(AI HTTP 호출을 25초까지 품기 때문) `open-in-view: false` 라 세션이 없다.
 *
 * <p>결함: 부서 공개 일정이 출처에 끼면 {@code Schedule.departments} 를 채울 세션이 없어
 * {@code LazyInitializationException} 이 나고, AI 가 200 을 준 요청에 사용자에게 500 이 나갔다.
 */
@SpringBootTest(properties = {
        // 이 클래스만 쓰는 DB다. @Transactional 이 없어 저장이 실제로 커밋되는데, 공유 H2(ajt_test)는
        //   DB_CLOSE_DELAY=-1 로 컨텍스트 밖까지 남아 다른 테스트 픽스처의 UNIQUE 제약과 충돌한다.
        "spring.datasource.url=jdbc:h2:mem:ajt_test_171;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "ajt.local-data.enabled=false"
})
@DisplayName("챗봇 출처 권한 판정 — 트랜잭션 밖에서 부서 공개 일정을 다룬다")
class QuestionAskSourceAuthorizationIntegrationTest {

    @MockitoBean
    private AiClient aiClient;

    private final QuestionAskService questionAskService;
    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;
    private final ScheduleRepository scheduleRepository;

    @Autowired
    QuestionAskSourceAuthorizationIntegrationTest(
            QuestionAskService questionAskService,
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository,
            ScheduleRepository scheduleRepository
    ) {
        this.questionAskService = questionAskService;
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Test
    @DisplayName("부서 공개 일정이 출처면 200으로 돌아오고 그 일정이 출처에 남는다")
    void departmentScheduleSourceSurvivesOutsideATransaction() {
        Department devTeam = departmentRepository.save(new Department("개발부"));
        Member asker = memberRepository.save(Member.approvedEmployee(
                devTeam, "employee-171@ajt.com", "사원", "encoded", "E1710"));
        Schedule sprint = departmentSchedule("개발부 스프린트 회의", devTeam.getId(), asker.getId());

        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "개발부 스프린트 회의는 8월 3일입니다.",
                List.of(new AnswerGenerationResponse.Source(
                        "schedule", null, String.valueOf(sprint.id()), "개발부 스프린트 회의")),
                "schedule"));

        QuestionAskResponse response = questionAskService.ask(
                employee(asker), new QuestionAskRequest(null, "개발부 스프린트 회의 언제예요?"));

        assertThat(response.answer()).isEqualTo("개발부 스프린트 회의는 8월 3일입니다.");
        assertThat(response.sources()).extracting(QuestionAskSourceResponse::title)
                .containsExactly("개발부 스프린트 회의");
    }

    @Test
    @DisplayName("부서 밖 사용자에게는 그 일정이 출처에서 빠진다 — 권한 판정이 그대로 동작한다")
    void departmentScheduleIsDroppedForAnOutsider() {
        Department devTeam = departmentRepository.save(new Department("개발부2"));
        Department hrTeam = departmentRepository.save(new Department("인사부2"));
        Member outsider = memberRepository.save(Member.approvedEmployee(
                hrTeam, "outsider-171@ajt.com", "타부서 사원", "encoded", "E1711"));
        Schedule sprint = departmentSchedule("개발부 스프린트 회의2", devTeam.getId(), outsider.getId());

        given(aiClient.generateAnswer(any())).willReturn(new AnswerGenerationResponse(
                "일정을 안내합니다.",
                List.of(new AnswerGenerationResponse.Source(
                        "schedule", null, String.valueOf(sprint.id()), "개발부 스프린트 회의2")),
                "schedule"));

        QuestionAskResponse response = questionAskService.ask(
                employee(outsider), new QuestionAskRequest(null, "스프린트 회의 언제예요?"));

        // 답변은 그대로 나가지만 볼 수 없는 일정은 출처로 인정하지 않는다.
        assertThat(response.sources()).isEmpty();
    }

    /**
     * 부서 공개 일정을 저장합니다. 저장 트랜잭션이 끝나면 {@code departments} 는 detached 가 되고,
     * 그 상태를 읽는 것이 이 테스트의 핵심이다.
     */
    private Schedule departmentSchedule(String title, long departmentId, long authorId) {
        Schedule schedule = Schedule.create(
                authorId, title, "스프린트 계획 논의", "개발부", "회의실",
                ScheduleVisibility.DEPARTMENT,
                Instant.parse("2026-08-03T01:00:00Z"), Instant.parse("2026-08-03T03:00:00Z"));
        schedule.replaceDepartments(List.of(departmentId));
        return scheduleRepository.save(schedule);
    }

    private AuthenticatedMember employee(Member member) {
        return new AuthenticatedMember(member.getId(), member.getEmail(), Role.EMPLOYEE);
    }
}
