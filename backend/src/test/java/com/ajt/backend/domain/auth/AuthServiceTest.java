package com.ajt.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.auth.dto.LoginRequest;
import com.ajt.backend.domain.auth.dto.LoginResponse;
import com.ajt.backend.domain.auth.dto.PasswordResetConfirmRequest;
import com.ajt.backend.domain.auth.dto.SignupRequest;
import com.ajt.backend.domain.auth.dto.SignupResponse;
import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.AccountStatus;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.SignupStatus;
import com.ajt.backend.global.auth.PasswordResetTokenService;
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
class AuthServiceTest {

    @Autowired
    AuthService authService;

    @Autowired
    DepartmentRepository departmentRepository;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    PasswordResetTokenService passwordResetTokenService;

    @Test
    @DisplayName("회원가입을 요청하면 사번 없이 승인 대기/비활성 상태로 저장된다")
    void signupCreatesPendingInactiveMember() {
        Department department = departmentRepository.save(new Department("개발부"));

        SignupResponse response = authService.signup(new SignupRequest(
                "EMPLOYEE@AJT.COM",
                "password123!",
                "홍길동",
                String.valueOf(department.getId())
        ));

        Member member = memberRepository.findByEmail("employee@ajt.com").orElseThrow();
        assertThat(response.signupStatus()).isEqualTo("pending");
        assertThat(response.accountStatus()).isEqualTo("inactive");
        assertThat(response.employeeNo()).isNull();
        assertThat(member.getSignupStatus()).isEqualTo(SignupStatus.PENDING);
        assertThat(member.getAccountStatus()).isEqualTo(AccountStatus.INACTIVE);
    }

    @Test
    @DisplayName("승인 대기 중인 이메일로 다시 회원가입하면 중복 가입 오류가 발생한다")
    void signupRejectsPendingDuplicateEmail() {
        Department department = departmentRepository.save(new Department("개발부"));
        authService.signup(new SignupRequest("employee@ajt.com", "password123!", "홍길동", String.valueOf(department.getId())));

        assertThatThrownBy(() -> authService.signup(
                new SignupRequest("employee@ajt.com", "password123!", "홍길동", String.valueOf(department.getId()))
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SIGNUP_ALREADY_PENDING);
    }

    @Test
    @DisplayName("거부된 이메일로 다시 회원가입하면 기존 회원 행이 승인 대기로 갱신된다")
    void signupResubmitsRejectedEmail() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.signup(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!")
        ));
        member.rejectSignup();

        SignupResponse response = authService.signup(new SignupRequest(
                "employee@ajt.com",
                "newPassword123!",
                "김싸피",
                String.valueOf(department.getId())
        ));

        assertThat(response.userId()).isEqualTo(String.valueOf(member.getId()));
        assertThat(member.getName()).isEqualTo("김싸피");
        assertThat(member.getSignupStatus()).isEqualTo(SignupStatus.PENDING);
        assertThat(member.getAccountStatus()).isEqualTo(AccountStatus.INACTIVE);
        assertThat(passwordEncoder.matches("newPassword123!", member.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("승인 완료 및 활성 상태 회원은 로그인하면 Bearer 토큰과 회원 정보를 받는다")
    void loginReturnsBearerTokenForApprovedActiveMember() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!"),
                "AJT-2026-0001"
        ));

        LoginResponse response = authService.login(new LoginRequest("employee@ajt.com", "password123!"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(response.user().userId()).isEqualTo(String.valueOf(member.getId()));
        assertThat(response.user().role()).isEqualTo("employee");
    }

    @Test
    @DisplayName("승인되지 않았거나 비활성 상태인 회원은 로그인할 수 없다")
    void loginRejectsNotApprovedMember() {
        Department department = departmentRepository.save(new Department("개발부"));
        memberRepository.save(Member.signup(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!")
        ));

        assertThatThrownBy(() -> authService.login(new LoginRequest("employee@ajt.com", "password123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("유효한 재설정 토큰으로 비밀번호를 변경하면 기존 토큰은 다시 사용할 수 없다")
    void resetPasswordChangesPasswordAndInvalidatesOldToken() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!"),
                "AJT-2026-0001"
        ));
        String token = passwordResetTokenService.createToken(member);

        authService.resetPassword(new PasswordResetConfirmRequest(token, "newPassword123!"));

        assertThat(passwordEncoder.matches("newPassword123!", member.getPasswordHash())).isTrue();
        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetConfirmRequest(token, "otherPassword123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_OR_EXPIRED_RESET_TOKEN);
    }

    @Test
    @DisplayName("올바르지 않은 재설정 토큰으로 비밀번호를 변경하면 토큰 오류가 발생한다")
    void resetPasswordRejectsInvalidToken() {
        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetConfirmRequest("wrong-token", "newPassword123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_OR_EXPIRED_RESET_TOKEN);
    }
}
