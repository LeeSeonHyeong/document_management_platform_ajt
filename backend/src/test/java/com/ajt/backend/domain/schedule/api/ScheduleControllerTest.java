package com.ajt.backend.domain.schedule.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.global.auth.AccessTokenService;
import com.ajt.backend.global.auth.AuthCookieService;
import com.ajt.backend.global.auth.CsrfTokenService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
@DisplayName("일정 API")
class ScheduleControllerTest {

    private static final String CSRF_TOKEN = "csrf-token";

    private final MockMvc mockMvc;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final ScheduleRepository scheduleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;

    @Autowired
    ScheduleControllerTest(
            MockMvc mockMvc,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            ScheduleRepository scheduleRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService
    ) {
        this.mockMvc = mockMvc;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.scheduleRepository = scheduleRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
    }

    @Test
    @DisplayName("POST /api/v1/schedules는 사원의 personal 일정을 approved로 생성한다")
    void createPersonalScheduleAsEmployee() throws Exception {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));

        mockMvc.perform(post("/api/v1/schedules")
                        .cookie(accessTokenCookie(employee))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "개인 일정",
                                  "content": "개인 일정 내용",
                                  "targetText": "본인",
                                  "location": "본사",
                                  "visibilityType": "personal",
                                  "departmentIds": [],
                                  "startAt": "2026-08-03T01:00:00Z",
                                  "endAt": "2026-08-03T03:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.visibilityType").value("personal"))
                .andExpect(jsonPath("$.ownerId").value(String.valueOf(employee.getId())));
    }

    @Test
    @DisplayName("POST /api/v1/schedules는 사원이 all 일정을 만들면 403을 반환한다")
    void createAllScheduleAsEmployeeRejected() throws Exception {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));

        mockMvc.perform(post("/api/v1/schedules")
                        .cookie(accessTokenCookie(employee))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "전체 공지",
                                  "visibilityType": "all",
                                  "departmentIds": [],
                                  "startAt": "2026-08-03T01:00:00Z",
                                  "endAt": "2026-08-03T03:00:00Z"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_REQUIRED"));
    }

    @Test
    @DisplayName("POST /api/v1/schedules는 종료가 시작보다 빠르면 400을 반환한다")
    void createScheduleRejectsInvalidPeriod() throws Exception {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));

        mockMvc.perform(post("/api/v1/schedules")
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "잘못된 일정",
                                  "visibilityType": "all",
                                  "departmentIds": [],
                                  "startAt": "2026-08-03T03:00:00Z",
                                  "endAt": "2026-08-03T01:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE"));
    }

    @Test
    @DisplayName("GET /api/v1/schedules/{id}는 본인 personal 일정을 반환한다")
    void getOwnPersonalSchedule() throws Exception {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                employee.getId(), "개인 일정", null, null, null,
                ScheduleVisibility.PERSONAL,
                Instant.parse("2026-08-03T01:00:00Z"), Instant.parse("2026-08-03T03:00:00Z")));

        mockMvc.perform(get("/api/v1/schedules/{scheduleId}", schedule.id())
                        .cookie(accessTokenCookie(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(String.valueOf(schedule.id())))
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.sourceDocument").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/schedules/{id}는 존재하지 않으면 404를 반환한다")
    void getMissingScheduleReturns404() throws Exception {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));

        mockMvc.perform(get("/api/v1/schedules/{scheduleId}", 999999L)
                        .cookie(accessTokenCookie(employee)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/schedules/{id}는 로그인하지 않으면 401을 반환한다")
    void getScheduleRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/schedules/{scheduleId}", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
    }

    @Test
    @DisplayName("GET /api/v1/schedules는 기간 내 조회 가능한 일정을 items로 반환한다")
    void listSchedulesInRange() throws Exception {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));
        scheduleRepository.save(Schedule.create(
                employee.getId(), "내 개인", null, null, null,
                ScheduleVisibility.PERSONAL,
                Instant.parse("2026-08-03T01:00:00Z"), Instant.parse("2026-08-03T03:00:00Z")));

        mockMvc.perform(get("/api/v1/schedules")
                        .cookie(accessTokenCookie(employee))
                        .param("startDate", "2026-08-01")
                        .param("endDate", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].title").value("내 개인"))
                .andExpect(jsonPath("$.items[0].status").value("approved"));
    }

    @Test
    @DisplayName("GET /api/v1/schedules는 잘못된 조회 기간에 400을 반환한다")
    void listRejectsInvalidRange() throws Exception {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));

        mockMvc.perform(get("/api/v1/schedules")
                        .cookie(accessTokenCookie(employee))
                        .param("startDate", "2026-08-31")
                        .param("endDate", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_RANGE"));
    }

    @Test
    @DisplayName("PATCH /api/v1/schedules/{id}는 관리자가 제목을 수정한다")
    void updateScheduleAsAdmin() throws Exception {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                admin.getId(), "기존", null, null, null, ScheduleVisibility.ALL,
                Instant.parse("2026-08-03T01:00:00Z"), Instant.parse("2026-08-03T03:00:00Z")));

        mockMvc.perform(patch("/api/v1/schedules/{scheduleId}", schedule.id())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "title": "수정된 회의" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("수정된 회의"));
    }

    @Test
    @DisplayName("POST /api/v1/schedules/{id}/approve는 관리자가 draft를 승인한다")
    void approveScheduleAsAdmin() throws Exception {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));
        Schedule draft = scheduleRepository.save(Schedule.draft(
                admin.getId(), "초안", null, null, null, ScheduleVisibility.ALL,
                Instant.parse("2026-08-03T01:00:00Z"), Instant.parse("2026-08-03T03:00:00Z")));

        mockMvc.perform(post("/api/v1/schedules/{scheduleId}/approve", draft.id())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));
    }

    @Test
    @DisplayName("DELETE /api/v1/schedules/{id}는 본인 personal 일정을 삭제한다")
    void deleteOwnPersonalSchedule() throws Exception {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                employee.getId(), "개인", null, null, null, ScheduleVisibility.PERSONAL,
                Instant.parse("2026-08-03T01:00:00Z"), Instant.parse("2026-08-03T03:00:00Z")));

        mockMvc.perform(delete("/api/v1/schedules/{scheduleId}", schedule.id())
                        .cookie(accessTokenCookie(employee))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /api/v1/schedules/{id}/source-file은 원본이 없으면 404다")
    void sourceFileMissingReturns404() throws Exception {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                admin.getId(), "수동 일정", null, null, null, ScheduleVisibility.ALL,
                Instant.parse("2026-08-03T01:00:00Z"), Instant.parse("2026-08-03T03:00:00Z")));

        mockMvc.perform(get("/api/v1/schedules/{scheduleId}/source-file", schedule.id())
                        .cookie(accessTokenCookie(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/schedules/{id}/source-file은 사원을 403으로 거절한다")
    void sourceFileByEmployeeRejected() throws Exception {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                employee.getId(), "개인", null, null, null, ScheduleVisibility.PERSONAL,
                Instant.parse("2026-08-03T01:00:00Z"), Instant.parse("2026-08-03T03:00:00Z")));

        mockMvc.perform(get("/api/v1/schedules/{scheduleId}/source-file", schedule.id())
                        .cookie(accessTokenCookie(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_REQUIRED"));
    }

    private Cookie accessTokenCookie(Member member) {
        return new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member));
    }

    private Cookie csrfCookie() {
        return new Cookie(CsrfTokenService.CSRF_COOKIE_NAME, CSRF_TOKEN);
    }

    private Member employee(Department department) {
        return Member.approvedEmployee(
                department, "employee@ajt.com", "홍길동",
                passwordEncoder.encode("password123!"), "AJT-2026-0001");
    }

    private Member admin(Department department) {
        return Member.approved(
                department, "admin@ajt.com", "관리자",
                passwordEncoder.encode("password123!"), "AJT-2026-9001", Role.ADMIN);
    }
}
