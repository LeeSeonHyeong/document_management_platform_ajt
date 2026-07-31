package com.ajt.backend.domain.member;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
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
class MemberControllerTest {

    private static final String CSRF_TOKEN = "csrf-token";
    private final MockMvc mockMvc;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;


    @Autowired
    MemberControllerTest(
            MockMvc mockMvc,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService
    ) {
        this.mockMvc = mockMvc;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
    }

    @Test
    @DisplayName("GET /api/v1/me는 로그인한 사용자 정보를 반환한다")
    void meReturnsAuthenticatedMember() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        mockMvc.perform(get("/api/v1/me")
                        .cookie(accessTokenCookie(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("employee@ajt.com"))
                .andExpect(jsonPath("$.department.name").value("개발부"))
                .andExpect(jsonPath("$.signupStatus").value("approved"))
                .andExpect(jsonPath("$.accountStatus").value("active"))
                // 신규: /me 응답에 isSuperAdmin(boolean)이 내려온다(사원이므로 false)
                .andExpect(jsonPath("$.isSuperAdmin").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/me는 인증 쿠키가 없으면 401 INVALID_ACCESS_TOKEN을 반환한다")
    void meRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
    }

    @Test
    @DisplayName("GET /api/v1/users는 관리자에게 사용자 목록을 반환한다")
    void usersReturnsListForAdmin() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        memberRepository.save(approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        mockMvc.perform(get("/api/v1/users")
                        .param("signupStatus", "approved")
                        .param("keyword", "홍")
                        .cookie(accessTokenCookie(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].email").value("employee@ajt.com"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/users는 일반 사용자에게 403 ADMIN_PERMISSION_REQUIRED를 반환한다")
    void usersRejectsEmployee() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        mockMvc.perform(get("/api/v1/users")
                        .cookie(accessTokenCookie(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));
    }

    @Test
    @DisplayName("GET /api/v1/users는 부서관리자에게 403을 반환한다(최고관리자만 사용자 관리 가능)")
    void usersRejectsDepartmentManager() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);

        mockMvc.perform(get("/api/v1/users")
                        .cookie(accessTokenCookie(manager)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_REQUIRED"));
    }

    @Test
    @DisplayName("GET /api/v1/users/{userId}는 관리자에게 사용자 단건 상세를 반환한다")
    void userDetailReturnsForAdmin() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member employee = memberRepository.save(approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        mockMvc.perform(get("/api/v1/users/{userId}", employee.getId())
                        .cookie(accessTokenCookie(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(String.valueOf(employee.getId())))
                .andExpect(jsonPath("$.email").value("employee@ajt.com"))
                .andExpect(jsonPath("$.department.name").value("개발부"));
    }

    @Test
    @DisplayName("GET /api/v1/users/{userId}는 존재하지 않는 사용자에 404를 반환한다")
    void userDetailNotFound() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));

        mockMvc.perform(get("/api/v1/users/{userId}", Long.MAX_VALUE)
                        .cookie(accessTokenCookie(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/users/{userId}는 일반 사용자에게 403을 반환한다")
    void userDetailRejectsEmployee() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        mockMvc.perform(get("/api/v1/users/{userId}", employee.getId())
                        .cookie(accessTokenCookie(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_REQUIRED"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{userId}는 관리자가 자기 자신을 사원으로 강등하면 409를 반환한다")
    void updateUserRejectsSelfDemotion() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));

        mockMvc.perform(patch("/api/v1/users/{userId}", admin.getId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "employee"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_PRIVILEGE_REMOVAL_FORBIDDEN"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{userId}는 관리자가 사용자 정보를 수정한다")
    void updateUserChangesMember() throws Exception {
        Department beforeDepartment = departmentRepository.save(new Department("개발부"));
        Department afterDepartment = departmentRepository.save(new Department("인사부"));
        Member admin = memberRepository.save(approvedAdmin(beforeDepartment));
        Member employee = memberRepository.save(approvedEmployee(beforeDepartment, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        mockMvc.perform(patch("/api/v1/users/{userId}", employee.getId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "김관리",
                                  "role": "admin",
                                  "departmentId": "%s",
                                  "accountStatus": "inactive"
                                }
                                """.formatted(afterDepartment.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("김관리"))
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.department.name").value("인사부"))
                .andExpect(jsonPath("$.accountStatus").value("inactive"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{userId}는 잘못된 계정 상태를 한글 메시지로 거절한다")
    void updateUserRejectsInvalidAccountStatus() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member employee = memberRepository.save(approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        mockMvc.perform(patch("/api/v1/users/{userId}", employee.getId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountStatus": "sleep"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("계정 상태는 active 또는 inactive만 사용할 수 있습니다."));
    }

    @Test
    @DisplayName("GET /api/v1/signup-requests는 가입 신청 목록을 반환한다")
    void signupRequestsReturnsPendingMembers() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        memberRepository.save(Member.signup(department, "pending@ajt.com", "신청자", passwordEncoder.encode("password123!")));

        mockMvc.perform(get("/api/v1/signup-requests")
                        .cookie(accessTokenCookie(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].email").value("pending@ajt.com"))
                .andExpect(jsonPath("$.items[0].signupStatus").value("pending"));
    }

    @Test
    @DisplayName("POST /api/v1/signup-requests/{userId}/approve는 가입 신청을 승인한다")
    void approveSignupRequestApprovesMember() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member pending = memberRepository.save(Member.signup(
                department,
                "pending@ajt.com",
                "신청자",
                passwordEncoder.encode("password123!")
        ));

        mockMvc.perform(post("/api/v1/signup-requests/{userId}/approve", pending.getId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeNo").isNotEmpty())
                .andExpect(jsonPath("$.signupStatus").value("approved"))
                .andExpect(jsonPath("$.accountStatus").value("active"));
    }

    @Test
    @DisplayName("POST /api/v1/signup-requests/{userId}/reject는 가입 신청을 거부한다")
    void rejectSignupRequestRejectsMember() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member pending = memberRepository.save(Member.signup(
                department,
                "pending@ajt.com",
                "신청자",
                passwordEncoder.encode("password123!")
        ));

        mockMvc.perform(post("/api/v1/signup-requests/{userId}/reject", pending.getId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupStatus").value("rejected"))
                .andExpect(jsonPath("$.accountStatus").value("inactive"));
    }

    private Cookie accessTokenCookie(Member member) {
        return new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member));
    }

    private Cookie csrfCookie() {
        return new Cookie(CsrfTokenService.CSRF_COOKIE_NAME, CSRF_TOKEN);
    }

    private Member approvedAdmin(Department department) {
        return Member.approved(
                department,
                "admin@ajt.com",
                "관리자",
                passwordEncoder.encode("password123!"),
                "AJT-2026-9999",
                Role.ADMIN
        );
    }

    private Member approvedEmployee(Department department, String email, String name, String employeeNo) {
        return Member.approvedEmployee(
                department,
                email,
                name,
                passwordEncoder.encode("password123!"),
                employeeNo
        );
    }
}