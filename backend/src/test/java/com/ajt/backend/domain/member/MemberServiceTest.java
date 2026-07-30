package com.ajt.backend.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.dto.SignupApprovalResponse;
import com.ajt.backend.domain.member.dto.SignupRejectionResponse;
import com.ajt.backend.domain.member.dto.UserListResponse;
import com.ajt.backend.domain.member.dto.UserResponse;
import com.ajt.backend.domain.member.dto.UserUpdateRequest;
import com.ajt.backend.global.auth.AuthenticatedMember;
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
class MemberServiceTest {
    private final MemberService memberService;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;


    @Autowired
    MemberServiceTest(
            MemberService memberService,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.memberService = memberService;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Test
    @DisplayName("내 정보 조회는 토큰의 회원 ID로 최신 회원 정보를 반환한다")
    void findMeReturnsCurrentMember() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        UserResponse response = memberService.findMe(new AuthenticatedMember(member.getId(), member.getEmail(), Role.EMPLOYEE));

        assertThat(response.userId()).isEqualTo(String.valueOf(member.getId()));
        assertThat(response.email()).isEqualTo("employee@ajt.com");
        assertThat(response.department().name()).isEqualTo("개발부");
        assertThat(response.signupStatus()).isEqualTo("approved");
        assertThat(response.accountStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("관리자 사용자 목록 조회는 필터와 검색어에 맞는 사용자만 반환한다")
    void findUsersFiltersByStatusRoleAndKeyword() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        memberRepository.save(approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        memberRepository.save(Member.signup(department, "pending@ajt.com", "신청자", passwordEncoder.encode("password123!")));

        UserListResponse response = memberService.findUsers(
                new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN),
                1,
                20,
                "active",
                "approved",
                "employee",
                false,
                "홍",
                "createdAt,desc"
        );

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.items().getFirst().email()).isEqualTo("employee@ajt.com");
    }

    @Test
    @DisplayName("관리자가 아니면 사용자 목록을 조회할 수 없다")
    void findUsersRequiresAdmin() {
        AuthenticatedMember employee = new AuthenticatedMember(1L, "employee@ajt.com", Role.EMPLOYEE);

        assertThatThrownBy(() -> memberService.findUsers(employee, 1, 20, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("사용자 수정은 전달된 필드만 변경한다")
    void updateUserChangesOnlyRequestedFields() {
        Department beforeDepartment = departmentRepository.save(new Department("개발부"));
        Department afterDepartment = departmentRepository.save(new Department("인사부"));
        Member admin = memberRepository.save(approvedAdmin(beforeDepartment));
        Member employee = memberRepository.save(approvedEmployee(beforeDepartment, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        UserResponse response = memberService.updateUser(
                new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN),
                employee.getId(),
                UserUpdateRequest.of("김싸피", "admin", String.valueOf(afterDepartment.getId()), "inactive")
        );

        assertThat(response.name()).isEqualTo("김싸피");
        assertThat(response.role()).isEqualTo("admin");
        assertThat(response.department().name()).isEqualTo("인사부");
        assertThat(response.accountStatus()).isEqualTo("inactive");
    }

    @Test
    @DisplayName("사용자 수정에서 필수 필드에 명시적 null을 보내면 400")
    void updateUserRejectsExplicitNull() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member employee = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        AuthenticatedMember adminMember = new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN);
        // role 키가 전달됐지만 값이 null(명시적 null) → 필수 필드라 400
        UserUpdateRequest request =
                UserUpdateRequest.of("김싸피", null, String.valueOf(department.getId()), "active");

        assertThatThrownBy(() -> memberService.updateUser(adminMember, employee.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("부서장인 회원을 사원으로 강등하면 그 부서의 부서장 지정이 자동 해제된다")
    void updateUserClearsDepartmentManagerOnDemotion() {
        Department department = departmentRepository.save(new Department("기획부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        AuthenticatedMember actor = new AuthenticatedMember(1L, "actor@ajt.com", Role.ADMIN);
        // role만 전달(부분 수정): 사원으로 강등
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("employee");

        UserResponse response = memberService.updateUser(actor, manager.getId(), request);

        assertThat(response.role()).isEqualTo("employee");
        assertThat(departmentRepository.findById(department.getId()).orElseThrow().getManager()).isNull();
    }

    @Test
    @DisplayName("부서장인 회원을 비활성화하면 그 부서의 부서장 지정이 자동 해제된다")
    void updateUserClearsDepartmentManagerOnDeactivation() {
        Department department = departmentRepository.save(new Department("기획부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        AuthenticatedMember actor = new AuthenticatedMember(1L, "actor@ajt.com", Role.ADMIN);
        // accountStatus만 전달(부분 수정): 비활성화. 역할은 여전히 admin이지만 활성 자격을 잃음
        UserUpdateRequest request = new UserUpdateRequest();
        request.setAccountStatus("inactive");

        UserResponse response = memberService.updateUser(actor, manager.getId(), request);

        assertThat(response.accountStatus()).isEqualTo("inactive");
        assertThat(departmentRepository.findById(department.getId()).orElseThrow().getManager()).isNull();
    }

    @Test
    @DisplayName("부서장 자격을 유지하면 이름만 수정해도 부서장 지정은 유지된다")
    void updateUserKeepsDepartmentManagerOnNameChange() {
        Department department = departmentRepository.save(new Department("기획부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        AuthenticatedMember actor = new AuthenticatedMember(1L, "actor@ajt.com", Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setName("새이름");

        UserResponse response = memberService.updateUser(actor, manager.getId(), request);

        assertThat(response.name()).isEqualTo("새이름");
        assertThat(response.role()).isEqualTo("admin");
        assertThat(response.accountStatus()).isEqualTo("active");
        // 자격을 유지하므로 부서장 지정은 그대로여야 한다
        assertThat(departmentRepository.findById(department.getId()).orElseThrow().getManager().getId())
                .isEqualTo(manager.getId());
    }

    @Test
    @DisplayName("부서장 자격을 유지하면 부서장인 회원을 다른 부서로 이동할 수 있다")
    void updateUserAllowsMovingDepartmentManagerToAnotherDepartment() {
        // 부서장 지정은 담당 부서 소속 여부를 검사하지 않으므로, 부서 이동은 지정 자격을 깨지 않는다.
        Department department = departmentRepository.save(new Department("기획부"));
        Department otherDepartment = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        AuthenticatedMember actor = new AuthenticatedMember(1L, "actor@ajt.com", Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setDepartmentId(String.valueOf(otherDepartment.getId()));

        UserResponse response = memberService.updateUser(actor, manager.getId(), request);

        assertThat(response.department().name()).isEqualTo("개발부");
        // 부서 이동은 부서장 자격을 깨지 않으므로 기획부 부서장 지정은 유지된다(자동 해제 대상 아님)
        assertThat(departmentRepository.findById(department.getId()).orElseThrow().getManager().getId())
                .isEqualTo(manager.getId());
    }

    @Test
    @DisplayName("부서장이 아닌 관리자는 사원으로 강등할 수 있다")
    void updateUserAllowsDemotingNonManagerAdmin() {
        Department department = departmentRepository.save(new Department("기획부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        AuthenticatedMember actor = new AuthenticatedMember(1L, "actor@ajt.com", Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("employee");

        UserResponse response = memberService.updateUser(actor, admin.getId(), request);

        assertThat(response.role()).isEqualTo("employee");
    }

    @Test
    @DisplayName("가입 신청 승인은 사번을 발급하고 approved active 상태로 바꾼다")
    void approveSignupRequestActivatesMember() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member pending = memberRepository.save(Member.signup(
                department,
                "pending@ajt.com",
                "신청자",
                passwordEncoder.encode("password123!")
        ));

        SignupApprovalResponse response = memberService.approveSignupRequest(
                new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN),
                pending.getId()
        );

        assertThat(response.employeeNo()).startsWith("AJT-");
        assertThat(response.signupStatus()).isEqualTo("approved");
        assertThat(response.accountStatus()).isEqualTo("active");
        assertThat(pending.getSignupStatus()).isEqualTo(SignupStatus.APPROVED);
        assertThat(pending.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("가입 신청 거절은 rejected inactive 상태로 바꾼다")
    void rejectSignupRequestInactivatesMember() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member pending = memberRepository.save(Member.signup(
                department,
                "pending@ajt.com",
                "신청자",
                passwordEncoder.encode("password123!")
        ));

        SignupRejectionResponse response = memberService.rejectSignupRequest(
                new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN),
                pending.getId()
        );

        assertThat(response.signupStatus()).isEqualTo("rejected");
        assertThat(response.accountStatus()).isEqualTo("inactive");
        assertThat(pending.getSignupStatus()).isEqualTo(SignupStatus.REJECTED);
        assertThat(pending.getAccountStatus()).isEqualTo(AccountStatus.INACTIVE);
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
