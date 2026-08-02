package com.ajt.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.global.auth.AccessTokenService;
import com.ajt.backend.global.auth.AuthCookieService;
import com.ajt.backend.global.auth.CsrfTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false",
        // signup-departments 목록 개수를 직접 단언하므로 시작 시 '전체' 자동 생성은 끈다(S15P11B106-146).
        "ajt.default-department.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {
    private final MockMvc mockMvc;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;


    @Autowired
    AuthControllerTest(
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
    @DisplayName("POST /api/v1/auth/signup 요청은 202와 승인 대기 회원 정보를 반환한다")
    void signupReturnsAccepted() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "employee@ajt.com",
                                  "password": "password123!",
                                  "name": "홍길동",
                                  "departmentId": "%s"
                                }
                                """.formatted(department.getId())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.email").value("employee@ajt.com"))
                .andExpect(jsonPath("$.role").value("employee"))
                .andExpect(jsonPath("$.departmentId").value(String.valueOf(department.getId())))
                .andExpect(jsonPath("$.employeeNo").doesNotExist())
                .andExpect(jsonPath("$.signupStatus").value("pending"))
                .andExpect(jsonPath("$.accountStatus").value("inactive"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login 요청은 accessToken을 HttpOnly 쿠키로 발급한다")
    void loginReturnsAccessTokenCookie() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        memberRepository.save(Member.approvedEmployee(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!"),
                "AJT-2026-0001"
        ));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "employee@ajt.com",
                                  "password": "password123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME)))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.tokenType").doesNotExist())
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.email").value("employee@ajt.com"))
                .andExpect(jsonPath("$.user.department.name").value("개발부"))
                // 신규: 로그인 응답 user에 isSuperAdmin(boolean)이 내려온다(사원이므로 false)
                .andExpect(jsonPath("$.user.isSuperAdmin").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/auth/csrf 요청은 XSRF-TOKEN 쿠키를 발급한다")
    void csrfReturnsReadableCookie() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(CsrfTokenService.CSRF_COOKIE_NAME)))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(jsonPath("$.message").value("CSRF 토큰이 발급되었습니다."));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout 요청은 인증 쿠키와 CSRF 토큰이 맞으면 인증 쿠키를 만료한다")
    void logoutExpiresAccessTokenCookie() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!"),
                "AJT-2026-0001"
        ));
        String csrfToken = "csrf-token";

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member)))
                        .cookie(new Cookie(CsrfTokenService.CSRF_COOKIE_NAME, csrfToken))
                        .header(CsrfTokenService.CSRF_HEADER_NAME, csrfToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.message").value("로그아웃되었습니다."));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout 요청은 CSRF 토큰이 없으면 403 오류를 반환한다")
    void logoutRejectsMissingCsrfToken() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!"),
                "AJT-2026-0001"
        ));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"))
                .andExpect(jsonPath("$.message").value("CSRF 토큰이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("PATCH /api/v1/me/password 요청은 현재 비밀번호가 맞으면 204로 비밀번호를 변경한다")
    void changeMyPasswordReturnsNoContent() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department, "employee@ajt.com", "홍길동",
                passwordEncoder.encode("password123!"), "AJT-2026-0001"));
        String csrfToken = "csrf-token";

        mockMvc.perform(patch("/api/v1/me/password")
                        .cookie(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member)))
                        .cookie(new Cookie(CsrfTokenService.CSRF_COOKIE_NAME, csrfToken))
                        .header(CsrfTokenService.CSRF_HEADER_NAME, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "password123!",
                                  "newPassword": "newPassword123!"
                                }
                                """))
                .andExpect(status().isNoContent());
        assertThat(passwordEncoder.matches("newPassword123!", member.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("PATCH /api/v1/me/password 요청은 인증 쿠키가 없으면 401을 반환한다")
    void changeMyPasswordRequiresLogin() throws Exception {
        mockMvc.perform(patch("/api/v1/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "password123!",
                                  "newPassword": "newPassword123!"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /api/v1/me/password 요청은 현재 비밀번호가 틀리면 400 INVALID_CURRENT_PASSWORD를 반환한다")
    void changeMyPasswordRejectsWrongCurrentPassword() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department, "employee@ajt.com", "홍길동",
                passwordEncoder.encode("password123!"), "AJT-2026-0001"));
        String csrfToken = "csrf-token";

        mockMvc.perform(patch("/api/v1/me/password")
                        .cookie(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member)))
                        .cookie(new Cookie(CsrfTokenService.CSRF_COOKIE_NAME, csrfToken))
                        .header(CsrfTokenService.CSRF_HEADER_NAME, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "wrongPassword!",
                                  "newPassword": "newPassword123!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    @DisplayName("PATCH /api/v1/me/password 요청은 새 비밀번호 형식이 정책에 맞지 않으면 400을 반환한다")
    void changeMyPasswordRejectsInvalidNewPasswordFormat() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department, "employee@ajt.com", "홍길동",
                passwordEncoder.encode("password123!"), "AJT-2026-0001"));
        String csrfToken = "csrf-token";

        mockMvc.perform(patch("/api/v1/me/password")
                        .cookie(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member)))
                        .cookie(new Cookie(CsrfTokenService.CSRF_COOKIE_NAME, csrfToken))
                        .header(CsrfTokenService.CSRF_HEADER_NAME, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "password123!",
                                  "newPassword": "short"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("PATCH /api/v1/me/password 요청은 CSRF 토큰이 없으면 403을 반환한다")
    void changeMyPasswordRejectsMissingCsrf() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department, "employee@ajt.com", "홍길동",
                passwordEncoder.encode("password123!"), "AJT-2026-0001"));

        mockMvc.perform(patch("/api/v1/me/password")
                        .cookie(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "password123!",
                                  "newPassword": "newPassword123!"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("GET /api/v1/signup-departments 요청은 로그인 없이 부서 목록을 반환한다")
    void signupDepartmentsReturnsItems() throws Exception {
        departmentRepository.save(new Department("개발부"));
        departmentRepository.save(new Department("인사부"));

        mockMvc.perform(get("/api/v1/signup-departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].departmentId").isNotEmpty())
                .andExpect(jsonPath("$.items[0].name").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/auth/password-reset-requests 요청은 계정 존재 여부와 관계없이 같은 메시지를 반환한다")
    void passwordResetRequestReturnsGenericMessage() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "unknown@ajt.com"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("입력한 이메일이 등록되어 있다면 비밀번호 재설정 안내를 전송했습니다."));
    }

    @Test
    @DisplayName("POST /api/v1/auth/password-resets 요청은 잘못된 인증번호면 400 오류를 반환한다")
    void passwordResetRejectsInvalidCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "unknown@ajt.com",
                                  "code": "000000",
                                  "newPassword": "newPassword123!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_RESET_CODE"))
                .andExpect(jsonPath("$.message").value("인증번호가 올바르지 않거나 만료되었습니다."));
    }
}
