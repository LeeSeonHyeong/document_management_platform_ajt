package com.ajt.backend.domain.auth;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
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
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DepartmentRepository departmentRepository;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

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
    @DisplayName("POST /api/v1/auth/login 요청은 승인된 회원에게 접근 토큰을 반환한다")
    void loginReturnsToken() throws Exception {
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
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.email").value("employee@ajt.com"))
                .andExpect(jsonPath("$.user.department.name").value("개발부"));
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
    @DisplayName("POST /api/v1/auth/password-resets 요청은 잘못된 토큰이면 400 오류를 반환한다")
    void passwordResetRejectsInvalidToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "wrong-token",
                                  "newPassword": "newPassword123!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_RESET_TOKEN"))
                .andExpect(jsonPath("$.message").value("비밀번호 재설정 토큰이 올바르지 않거나 만료되었습니다."));
    }
}
