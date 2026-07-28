package com.ajt.backend.domain.department;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AccessTokenService;
import com.ajt.backend.global.auth.AuthCookieService;
import com.ajt.backend.global.auth.CsrfTokenService;
import jakarta.servlet.http.Cookie;
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
class DepartmentControllerTest {

    private static final String CSRF_TOKEN = "csrf-token";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DepartmentRepository departmentRepository;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AccessTokenService accessTokenService;

    @Test
    @DisplayName("GET /api/v1/departments는 로그인 사용자에게 부서 목록을 반환한다")
    void findDepartmentsReturnsList() throws Exception {
        Department development = departmentRepository.save(new Department("개발부"));
        Department sales = departmentRepository.save(new Department("영업부"));
        Member admin = memberRepository.save(approvedAdmin(development, "admin@ajt.com", "AJT-2026-9001"));
        sales.assignManager(admin);

        mockMvc.perform(get("/api/v1/departments")
                        .cookie(accessTokenCookie(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].name").value("개발부"))
                .andExpect(jsonPath("$.items[1].manager.userId").value(String.valueOf(admin.getId())));
    }

    @Test
    @DisplayName("GET /api/v1/departments는 로그인하지 않으면 401을 반환한다")
    void findDepartmentsRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/departments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
    }

    @Test
    @DisplayName("POST /api/v1/departments는 관리자가 부서를 생성한다")
    void createDepartmentCreatesDepartment() throws Exception {
        Department baseDepartment = departmentRepository.save(new Department("기본부"));
        Member admin = memberRepository.save(approvedAdmin(baseDepartment, "admin@ajt.com", "AJT-2026-9001"));

        mockMvc.perform(post("/api/v1/departments")
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "플랫폼개발부",
                                  "managerId": "%s"
                                }
                                """.formatted(admin.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("플랫폼개발부"))
                .andExpect(jsonPath("$.manager.name").value("관리자"));
    }

    @Test
    @DisplayName("POST /api/v1/departments는 일반 사용자를 403으로 거절한다")
    void createDepartmentRejectsEmployee() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(approvedEmployee(department, "employee@ajt.com", "AJT-2026-0001"));

        mockMvc.perform(post("/api/v1/departments")
                        .cookie(accessTokenCookie(employee))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "인사부"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_REQUIRED"));
    }

    @Test
    @DisplayName("PATCH /api/v1/departments/{departmentId}는 부서명과 지정 관리자를 수정한다")
    void updateDepartmentChangesNameAndManager() throws Exception {
        Department baseDepartment = departmentRepository.save(new Department("기본부"));
        Department targetDepartment = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(baseDepartment, "admin@ajt.com", "AJT-2026-9001"));

        mockMvc.perform(patch("/api/v1/departments/{departmentId}", targetDepartment.getId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "플랫폼개발부",
                                  "managerId": "%s"
                                }
                                """.formatted(admin.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("플랫폼개발부"))
                .andExpect(jsonPath("$.manager.userId").value(String.valueOf(admin.getId())));
    }

    @Test
    @DisplayName("PATCH /api/v1/departments/{departmentId}는 managerId null로 지정 관리자를 해제한다")
    void updateDepartmentClearsManager() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "AJT-2026-9001"));
        department.assignManager(admin);

        mockMvc.perform(patch("/api/v1/departments/{departmentId}", department.getId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "managerId": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manager").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /api/v1/departments/{departmentId}는 사용 중인 부서를 409로 거절한다")
    void deleteDepartmentRejectsDepartmentInUse() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "AJT-2026-9001"));

        mockMvc.perform(delete("/api/v1/departments/{departmentId}", department.getId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTMENT_IN_USE"));
    }

    @Test
    @DisplayName("DELETE /api/v1/departments/{departmentId}는 사용하지 않는 부서를 삭제한다")
    void deleteDepartmentDeletesUnusedDepartment() throws Exception {
        Department baseDepartment = departmentRepository.save(new Department("기본부"));
        Department targetDepartment = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(baseDepartment, "admin@ajt.com", "AJT-2026-9001"));

        mockMvc.perform(delete("/api/v1/departments/{departmentId}", targetDepartment.getId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isNoContent());
    }

    private Cookie accessTokenCookie(Member member) {
        return new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member));
    }

    private Cookie csrfCookie() {
        return new Cookie(CsrfTokenService.CSRF_COOKIE_NAME, CSRF_TOKEN);
    }

    private Member approvedAdmin(Department department, String email, String employeeNo) {
        return Member.approved(
                department,
                email,
                "관리자",
                passwordEncoder.encode("password123!"),
                employeeNo,
                Role.ADMIN
        );
    }

    private Member approvedEmployee(Department department, String email, String employeeNo) {
        return Member.approvedEmployee(
                department,
                email,
                "홍길동",
                passwordEncoder.encode("password123!"),
                employeeNo
        );
    }
}
