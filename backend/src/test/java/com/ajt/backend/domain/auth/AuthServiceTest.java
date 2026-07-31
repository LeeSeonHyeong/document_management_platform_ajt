package com.ajt.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.auth.dto.ChangePasswordRequest;
import com.ajt.backend.domain.auth.dto.LoginRequest;
import com.ajt.backend.domain.auth.dto.LoginResult;
import com.ajt.backend.domain.auth.dto.AuthMessageResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.domain.auth.dto.PasswordResetConfirmRequest;
import com.ajt.backend.domain.auth.dto.PasswordResetVerifyRequest;
import com.ajt.backend.domain.auth.dto.SignupRequest;
import com.ajt.backend.domain.auth.dto.SignupResponse;
import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.AccountStatus;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.member.SignupStatus;
import com.ajt.backend.global.auth.PasswordResetCodeStore;
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
    private final AuthService authService;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetCodeStore passwordResetCodeStore;


    @Autowired
    AuthServiceTest(
            AuthService authService,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            PasswordResetCodeStore passwordResetCodeStore
    ) {
        this.authService = authService;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetCodeStore = passwordResetCodeStore;
    }

    @Test
    @DisplayName("회원가입을 요청하면 사번 없이 승인 대기 비활성 상태로 저장된다")
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
    @DisplayName("거절된 이메일로 다시 회원가입하면 기존 회원 행이 승인 대기로 갱신된다")
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
    @DisplayName("승인 완료 및 활성 상태 회원이 로그인하면 쿠키 발급용 accessToken과 회원 정보를 받는다")
    void loginReturnsTokenResultForApprovedActiveMember() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!"),
                "AJT-2026-0001"
        ));

        LoginResult response = authService.login(new LoginRequest("employee@ajt.com", "password123!"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(response.user().userId()).isEqualTo(String.valueOf(member.getId()));
        assertThat(response.user().role()).isEqualTo("employee");
        // 기존 응답 필드가 그대로 유지되는지 + 신규 필드 기본값 확인(사원은 최고관리자 아님)
        assertThat(response.user().email()).isEqualTo("employee@ajt.com");
        assertThat(response.user().isSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("최고관리자(부서장으로 지정되지 않은 ADMIN) 로그인 응답은 isSuperAdmin=true")
    void loginReturnsSuperAdminTrueForNonManagerAdmin() {
        Department department = departmentRepository.save(new Department("개발부"));
        memberRepository.save(Member.approved(
                department, "admin@ajt.com", "관리자",
                passwordEncoder.encode("password123!"), "AJT-2026-9001", Role.ADMIN));

        LoginResult response = authService.login(new LoginRequest("admin@ajt.com", "password123!"));

        assertThat(response.user().role()).isEqualTo("admin");
        assertThat(response.user().isSuperAdmin()).isTrue();
    }

    @Test
    @DisplayName("부서관리자(부서장으로 지정된 ADMIN) 로그인 응답은 isSuperAdmin=false")
    void loginReturnsSuperAdminFalseForDepartmentManager() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(Member.approved(
                department, "manager@ajt.com", "부서장",
                passwordEncoder.encode("password123!"), "AJT-2026-9002", Role.ADMIN));
        department.assignManager(manager);
        departmentRepository.save(department);

        LoginResult response = authService.login(new LoginRequest("manager@ajt.com", "password123!"));

        assertThat(response.user().role()).isEqualTo("admin");
        assertThat(response.user().isSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("현재 비밀번호가 맞으면 새 비밀번호로 변경되고 새 비밀번호로 인코딩되어 저장된다")
    void changeMyPasswordSucceedsWithCorrectCurrentPassword() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department, "employee@ajt.com", "홍길동",
                passwordEncoder.encode("password123!"), "AJT-2026-0001"));

        authService.changeMyPassword(
                new AuthenticatedMember(member.getId(), member.getEmail(), Role.EMPLOYEE),
                new ChangePasswordRequest("password123!", "newPassword123!"));

        assertThat(passwordEncoder.matches("newPassword123!", member.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("password123!", member.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 비밀번호를 변경하지 않고 400으로 거절한다")
    void changeMyPasswordRejectsWrongCurrentPassword() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department, "employee@ajt.com", "홍길동",
                passwordEncoder.encode("password123!"), "AJT-2026-0001"));

        assertThatThrownBy(() -> authService.changeMyPassword(
                new AuthenticatedMember(member.getId(), member.getEmail(), Role.EMPLOYEE),
                new ChangePasswordRequest("wrongPassword!", "newPassword123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD);
        assertThat(passwordEncoder.matches("password123!", member.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("새 비밀번호가 현재 비밀번호와 같으면 400으로 거절한다")
    void changeMyPasswordRejectsSameAsCurrent() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department, "employee@ajt.com", "홍길동",
                passwordEncoder.encode("password123!"), "AJT-2026-0001"));

        assertThatThrownBy(() -> authService.changeMyPassword(
                new AuthenticatedMember(member.getId(), member.getEmail(), Role.EMPLOYEE),
                new ChangePasswordRequest("password123!", "password123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT);
    }

    @Test
    @DisplayName("비밀번호 변경은 인증 주체 본인 계정에만 적용되고 다른 회원 계정은 바뀌지 않는다")
    void changeMyPasswordAppliesOnlyToAuthenticatedMember() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member me = memberRepository.save(Member.approvedEmployee(
                department, "me@ajt.com", "본인",
                passwordEncoder.encode("password123!"), "AJT-2026-0001"));
        Member other = memberRepository.save(Member.approvedEmployee(
                department, "other@ajt.com", "타인",
                passwordEncoder.encode("password123!"), "AJT-2026-0002"));

        authService.changeMyPassword(
                new AuthenticatedMember(me.getId(), me.getEmail(), Role.EMPLOYEE),
                new ChangePasswordRequest("password123!", "newPassword123!"));

        assertThat(passwordEncoder.matches("newPassword123!", me.getPasswordHash())).isTrue();
        // 대상 userId를 받지 않으므로 다른 회원 비밀번호는 구조적으로 변경될 수 없다
        assertThat(passwordEncoder.matches("password123!", other.getPasswordHash())).isTrue();
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
    @DisplayName("유효한 인증번호로 비밀번호를 변경하면 인증번호는 다시 사용할 수 없다")
    void resetPasswordChangesPasswordAndConsumesCode() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(Member.approvedEmployee(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!"),
                "AJT-2026-0001"
        ));
        passwordResetCodeStore.save("employee@ajt.com", "123456");

        authService.resetPassword(new PasswordResetConfirmRequest("employee@ajt.com", "123456", "newPassword123!"));

        assertThat(passwordEncoder.matches("newPassword123!", member.getPasswordHash())).isTrue();
        assertThatThrownBy(() -> authService.resetPassword(
                new PasswordResetConfirmRequest("employee@ajt.com", "123456", "otherPassword123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_OR_EXPIRED_RESET_CODE);
    }

    @Test
    @DisplayName("올바르지 않은 인증번호로 비밀번호를 변경하면 인증번호 오류가 발생한다")
    void resetPasswordRejectsInvalidCode() {
        assertThatThrownBy(() -> authService.resetPassword(
                new PasswordResetConfirmRequest("employee@ajt.com", "000000", "newPassword123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_OR_EXPIRED_RESET_CODE);
    }

    @Test
    @DisplayName("유효한 인증번호를 확인하면 확인 메시지를 반환한다")
    void verifyResetCodeReturnsMessageForValidCode() {
        passwordResetCodeStore.save("employee@ajt.com", "123456");

        AuthMessageResponse response = authService.verifyResetCode(
                new PasswordResetVerifyRequest("employee@ajt.com", "123456"));

        assertThat(response.message()).isEqualTo("인증번호가 확인되었습니다.");
    }

    @Test
    @DisplayName("올바르지 않은 인증번호 확인은 인증번호 오류가 발생한다")
    void verifyResetCodeRejectsInvalidCode() {
        assertThatThrownBy(() -> authService.verifyResetCode(
                new PasswordResetVerifyRequest("employee@ajt.com", "000000")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_OR_EXPIRED_RESET_CODE);
    }
}
