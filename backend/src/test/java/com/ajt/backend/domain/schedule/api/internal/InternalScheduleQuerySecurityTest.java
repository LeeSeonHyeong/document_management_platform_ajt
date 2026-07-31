package com.ajt.backend.domain.schedule.api.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.question.AiQuestion;
import com.ajt.backend.domain.question.AiQuestionRepository;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
@DisplayName("FastAPI 전용 일정 조회 창구 인증·권한")
class InternalScheduleQuerySecurityTest {

    private static final String INTERNAL_KEY = "local-dev-key";

    private final MockMvc mockMvc;
    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;
    private final AiQuestionRepository questionRepository;
    private final ScheduleRepository scheduleRepository;

    @Autowired
    InternalScheduleQuerySecurityTest(
            MockMvc mockMvc,
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository,
            AiQuestionRepository questionRepository,
            ScheduleRepository scheduleRepository
    ) {
        this.mockMvc = mockMvc;
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
        this.questionRepository = questionRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Test
    @DisplayName("내부 API 키가 없으면 401 이다")
    void rejectsMissingInternalApiKey() throws Exception {
        mockMvc.perform(get("/internal/v1/schedules")
                        .param("questionId", "500")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("일정 상세도 내부 API 키가 없으면 401 이다")
    void rejectsDetailWithoutInternalApiKey() throws Exception {
        mockMvc.perform(get("/internal/v1/schedules/31").param("questionId", "500"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("유효한 키라도 없는 질문 번호는 QUESTION_NOT_FOUND 다")
    void unknownQuestionIsNotFound() throws Exception {
        mockMvc.perform(get("/internal/v1/schedules")
                        .header("X-Internal-API-Key", INTERNAL_KEY)
                        .param("questionId", "999999")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("질문한 사람 기준으로 거른다 — 남의 부서 일정은 목록에도 상세에도 안 나온다")
    void filtersByTheAskingMember() throws Exception {
        Department mine = departmentRepository.save(new Department("개발부"));
        Department other = departmentRepository.save(new Department("인사부"));
        Member asker = memberRepository.save(Member.approvedEmployee(
                mine, "asker@ajt.com", "질문자", "encoded", "E0001"));
        AiQuestion question = questionRepository.save(AiQuestion.create(
                asker, "chat-1", "다음 워크샵 언제야?", null, false, null));

        Schedule companyWide = Schedule.create(asker.getId(), "전사 워크샵", "전사 안내",
                "전사", "본사", ScheduleVisibility.ALL,
                Instant.parse("2026-08-03T01:00:00Z"), Instant.parse("2026-08-03T09:00:00Z"));
        scheduleRepository.save(companyWide);

        Schedule hrOnly = Schedule.create(asker.getId(), "인사부 회의", "인사부 안내",
                "인사부", "본사", ScheduleVisibility.DEPARTMENT,
                Instant.parse("2026-08-04T01:00:00Z"), Instant.parse("2026-08-04T09:00:00Z"));
        hrOnly.replaceDepartments(List.of(other.getId()));
        scheduleRepository.save(hrOnly);

        mockMvc.perform(get("/internal/v1/schedules")
                        .header("X-Internal-API-Key", INTERNAL_KEY)
                        .param("questionId", String.valueOf(question.getId()))
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("전사 워크샵"))
                .andExpect(jsonPath("$.truncated").value(false));

        mockMvc.perform(get("/internal/v1/schedules/{scheduleId}", companyWide.id())
                        .header("X-Internal-API-Key", INTERNAL_KEY)
                        .param("questionId", String.valueOf(question.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("전사 안내"));

        mockMvc.perform(get("/internal/v1/schedules/{scheduleId}", hrOnly.id())
                        .header("X-Internal-API-Key", INTERNAL_KEY)
                        .param("questionId", String.valueOf(question.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }
}
