package com.ajt.backend.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.inquiry.Inquiry;
import com.ajt.backend.domain.inquiry.InquiryPriority;
import com.ajt.backend.domain.inquiry.InquiryRepository;
import com.ajt.backend.domain.member.dto.SignupApprovalResponse;
import com.ajt.backend.domain.member.dto.SignupRequestListResponse;
import com.ajt.backend.domain.member.dto.SignupRejectionResponse;
import com.ajt.backend.domain.member.dto.UserListResponse;
import com.ajt.backend.domain.member.dto.UserResponse;
import com.ajt.backend.domain.member.dto.UserUpdateRequest;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Instant;
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
    // 수정(S15P11B106-146): 최고관리자는 설정 이메일(ajt.super-admin.email, 기본값)로 고정 식별한다.
    private static final String SUPER_ADMIN_EMAIL = "superadmin@ajt.com";

    private final MemberService memberService;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final InquiryRepository inquiryRepository;
    private final PasswordEncoder passwordEncoder;


    @Autowired
    MemberServiceTest(
            MemberService memberService,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            InquiryRepository inquiryRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.memberService = memberService;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.inquiryRepository = inquiryRepository;
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
        // 신규 필드: 사원은 최고관리자가 아니다
        assertThat(response.isSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("내 정보 조회 응답의 isSuperAdmin은 설정된 최고관리자 이메일 계정이면 true다(S15P11B106-146)")
    void findMeReturnsSuperAdminTrueForConfiguredEmail() {
        Department department = departmentRepository.save(new Department("최고관리자"));
        Member admin = memberRepository.save(superAdmin(department));

        UserResponse response =
                memberService.findMe(new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN));

        assertThat(response.role()).isEqualTo("admin");
        // 소속 부서가 있어도 설정 이메일이면 최고관리자다.
        assertThat(response.isSuperAdmin()).isTrue();
    }

    @Test
    @DisplayName("내 정보 조회 응답의 isSuperAdmin은 설정 이메일이 아닌 일반 관리자면 false다(S15P11B106-146)")
    void findMeReturnsSuperAdminFalseForOrdinaryAdmin() {
        Department department = departmentRepository.save(new Department("개발부"));
        // approvedAdmin은 admin@ajt.com(설정 이메일 아님) → 부서장 지정 여부와 무관하게 최고관리자가 아니다.
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);

        UserResponse response =
                memberService.findMe(new AuthenticatedMember(manager.getId(), manager.getEmail(), Role.ADMIN));

        assertThat(response.isSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("최고관리자는 어느 부서의 manager로 지정돼도 isSuperAdmin=true를 유지한다(S15P11B106-146)")
    void superAdminStaysSuperAdminEvenWhenAssignedAsManager() {
        Department department = departmentRepository.save(new Department("최고관리자"));
        Member admin = memberRepository.save(superAdmin(department));
        // API에서는 차단되지만, 데이터가 어떤 경로로 manager로 지정되더라도 최고관리자 자격은 이메일로 유지돼야 한다.
        department.assignManager(admin);
        departmentRepository.save(department);

        UserResponse response =
                memberService.findMe(new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN));

        assertThat(response.isSuperAdmin()).isTrue();
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
        // role·accountStatus를 바꾸므로 액터는 최고관리자(설정 이메일)여야 한다(S15P11B106-146).
        Member admin = memberRepository.save(superAdmin(beforeDepartment));
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
        AuthenticatedMember actor = new AuthenticatedMember(Long.MAX_VALUE, SUPER_ADMIN_EMAIL, Role.ADMIN);
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
        AuthenticatedMember actor = new AuthenticatedMember(Long.MAX_VALUE, SUPER_ADMIN_EMAIL, Role.ADMIN);
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
        AuthenticatedMember actor = new AuthenticatedMember(Long.MAX_VALUE, SUPER_ADMIN_EMAIL, Role.ADMIN);
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
        AuthenticatedMember actor = new AuthenticatedMember(Long.MAX_VALUE, SUPER_ADMIN_EMAIL, Role.ADMIN);
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
        AuthenticatedMember actor = new AuthenticatedMember(Long.MAX_VALUE, SUPER_ADMIN_EMAIL, Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("employee");

        UserResponse response = memberService.updateUser(actor, admin.getId(), request);

        assertThat(response.role()).isEqualTo("employee");
    }

    @Test
    @DisplayName("가입 승인되지 않은(PENDING) 계정은 사용자 수정 API로 수정할 수 없다(409)")
    void updateUserRejectsNonApprovedTarget() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member pending = memberRepository.save(Member.signup(
                department, "pending@ajt.com", "신청자", passwordEncoder.encode("password123!")));
        AuthenticatedMember actor = new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN);
        // 승인 절차 우회 시도: PENDING 계정을 곧바로 활성 관리자로 바꾸려 함
        UserUpdateRequest request = UserUpdateRequest.of(null, "admin", null, "active");

        assertThatThrownBy(() -> memberService.updateUser(actor, pending.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_MODIFIABLE);
    }

    @Test
    @DisplayName("미처리(PENDING) 문의 담당자를 사원으로 강등하면 409로 거절한다(DR-027)")
    void updateUserRejectsDemotingPendingInquiryAssignee() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member author = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        savePendingInquiry(author, admin);
        AuthenticatedMember actor = new AuthenticatedMember(Long.MAX_VALUE, SUPER_ADMIN_EMAIL, Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("employee");

        assertThatThrownBy(() -> memberService.updateUser(actor, admin.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_ASSIGNEE_HAS_PENDING);
    }

    @Test
    @DisplayName("미처리(PENDING) 문의 담당자를 비활성화하면 409로 거절한다(DR-027)")
    void updateUserRejectsDeactivatingPendingInquiryAssignee() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member author = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        savePendingInquiry(author, admin);
        AuthenticatedMember actor = new AuthenticatedMember(Long.MAX_VALUE, SUPER_ADMIN_EMAIL, Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setAccountStatus("inactive");

        assertThatThrownBy(() -> memberService.updateUser(actor, admin.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_ASSIGNEE_HAS_PENDING);
    }

    @Test
    @DisplayName("처리 완료(DONE) 문의만 있는 담당자는 사원으로 강등할 수 있다")
    void updateUserAllowsDemotingAssigneeWithOnlyDoneInquiry() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member author = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        Inquiry inquiry = savePendingInquiry(author, admin);
        inquiry.markAnswered();
        inquiryRepository.save(inquiry);
        AuthenticatedMember actor = new AuthenticatedMember(Long.MAX_VALUE, SUPER_ADMIN_EMAIL, Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("employee");

        UserResponse response = memberService.updateUser(actor, admin.getId(), request);

        assertThat(response.role()).isEqualTo("employee");
    }

    @Test
    @DisplayName("가입 신청 승인은 사번을 발급하고 approved active 상태로 바꾼다")
    void approveSignupRequestActivatesMember() {
        Department department = departmentRepository.save(new Department("개발부"));
        // 가입 승인은 최고관리자 전용 → 설정 이메일 계정을 액터로 사용한다.
        Member admin = memberRepository.save(superAdmin(department));
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
    @DisplayName("가입 신청 목록은 승인 완료 항목에 발급된 사번을 담고, 대기 항목은 사번이 null이다")
    void signupRequestListIncludesEmployeeNo() {
        Department department = departmentRepository.save(new Department("개발부"));
        // 가입 신청 목록 조회는 최고관리자 전용 → 설정 이메일 계정을 액터로 사용한다.
        Member admin = memberRepository.save(superAdmin(department));
        AuthenticatedMember actor = new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN);
        Member pending = memberRepository.save(Member.signup(
                department, "pending@ajt.com", "신청자", passwordEncoder.encode("password123!")));
        memberService.approveSignupRequest(actor, pending.getId());

        // 승인 완료 탭: 발급된 사번이 응답에 포함된다
        SignupRequestListResponse approved = memberService.findSignupRequests(actor, 1, 20, "approved", "신청자");
        assertThat(approved.items()).hasSize(1);
        assertThat(approved.items().get(0).employeeNo()).startsWith("AJT-");

        // 대기 탭: 아직 사번이 없으므로 null
        memberRepository.save(Member.signup(
                department, "pending2@ajt.com", "대기자", passwordEncoder.encode("password123!")));
        SignupRequestListResponse pendingList = memberService.findSignupRequests(actor, 1, 20, "pending", "대기자");
        assertThat(pendingList.items()).hasSize(1);
        assertThat(pendingList.items().get(0).employeeNo()).isNull();
    }

    @Test
    @DisplayName("가입 신청 거절은 rejected inactive 상태로 바꾼다")
    void rejectSignupRequestInactivatesMember() {
        Department department = departmentRepository.save(new Department("개발부"));
        // 가입 거절은 최고관리자 전용 → 설정 이메일 계정을 액터로 사용한다.
        Member admin = memberRepository.save(superAdmin(department));
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

    @Test
    @DisplayName("회원 생성 시 createdAt과 updatedAt이 모두 채워지고 서로 같다")
    void memberCreationSetsCreatedAndUpdatedAtEqual() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member member = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        memberRepository.flush(); // @PrePersist 반영

        assertThat(member.getCreatedAt()).isNotNull();
        assertThat(member.getUpdatedAt()).isNotNull();
        assertThat(member.getUpdatedAt()).isEqualTo(member.getCreatedAt());
    }

    @Test
    @DisplayName("사용자 수정 시 updatedAt이 생성 시각 이후로 갱신된다")
    void updateRefreshesUpdatedAt() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member employee = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        memberRepository.flush();
        Instant createdUpdatedAt = employee.getUpdatedAt();
        AuthenticatedMember actor = new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setName("새이름");

        memberService.updateUser(actor, employee.getId(), request);
        memberRepository.flush(); // @PreUpdate/touch 반영

        assertThat(employee.getUpdatedAt()).isNotNull();
        assertThat(employee.getUpdatedAt()).isAfterOrEqualTo(createdUpdatedAt);
    }

    @Test
    @DisplayName("관리자는 사용자 단건 조회로 대상 사용자 상세를 받는다")
    void findUserReturnsDetail() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        Member employee = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));

        UserResponse response = memberService.findUser(
                new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN), employee.getId());

        assertThat(response.userId()).isEqualTo(String.valueOf(employee.getId()));
        assertThat(response.email()).isEqualTo("employee@ajt.com");
        assertThat(response.department().name()).isEqualTo("개발부");
    }

    @Test
    @DisplayName("일반 사용자는 사용자 단건 조회를 할 수 없다(403)")
    void findUserRequiresAdmin() {
        AuthenticatedMember employee = new AuthenticatedMember(1L, "employee@ajt.com", Role.EMPLOYEE);

        assertThatThrownBy(() -> memberService.findUser(employee, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("존재하지 않는 사용자 단건 조회는 404")
    void findUserNotFound() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));

        assertThatThrownBy(() -> memberService.findUser(
                new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN), Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자가 자기 자신을 사원으로 강등하려 하면 409")
    void updateUserRejectsSelfDemotion() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        AuthenticatedMember self = new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("employee");

        assertThatThrownBy(() -> memberService.updateUser(self, admin.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SELF_PRIVILEGE_REMOVAL_FORBIDDEN);
    }

    @Test
    @DisplayName("관리자가 자기 자신을 비활성화하려 하면 409")
    void updateUserRejectsSelfDeactivation() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        AuthenticatedMember self = new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setAccountStatus("inactive");

        assertThatThrownBy(() -> memberService.updateUser(self, admin.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SELF_PRIVILEGE_REMOVAL_FORBIDDEN);
    }

    @Test
    @DisplayName("관리자가 자기 자신의 이름 등 안전한 필드는 수정할 수 있다")
    void updateUserAllowsSelfSafeFieldEdit() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department));
        AuthenticatedMember self = new AuthenticatedMember(admin.getId(), admin.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setName("새관리자이름");

        UserResponse response = memberService.updateUser(self, admin.getId(), request);

        assertThat(response.name()).isEqualTo("새관리자이름");
        assertThat(response.role()).isEqualTo("admin");
        assertThat(response.accountStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("최고관리자(부서관리자가 아닌 ADMIN)는 사용자 목록을 조회할 수 있다")
    void superAdminCanListUsers() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member superAdmin = memberRepository.save(approvedAdmin(department));
        AuthenticatedMember actor = new AuthenticatedMember(superAdmin.getId(), superAdmin.getEmail(), Role.ADMIN);

        UserListResponse response = memberService.findUsers(actor, 1, 20, null, null, null, null, null, null);

        assertThat(response.totalCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("부서관리자도 사용자 목록을 조회할 수 있다(S15P11B106-104)")
    void departmentManagerCanListUsers() {
        AuthenticatedMember manager = savedDepartmentManagerActor();

        UserListResponse response = memberService.findUsers(manager, 1, 20, null, null, null, null, null, null);

        assertThat(response.totalCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("부서관리자는 사원 상세를 조회하고 사원 정보를 수정할 수 있다(S15P11B106-104)")
    void departmentManagerCanViewAndUpdateEmployee() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        Member employee = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        AuthenticatedMember managerActor = new AuthenticatedMember(manager.getId(), manager.getEmail(), Role.ADMIN);

        UserResponse detail = memberService.findUser(managerActor, employee.getId());
        assertThat(detail.email()).isEqualTo("employee@ajt.com");

        UserUpdateRequest request = new UserUpdateRequest();
        request.setName("새이름");
        UserResponse updated = memberService.updateUser(managerActor, employee.getId(), request);
        assertThat(updated.name()).isEqualTo("새이름");
    }

    @Test
    @DisplayName("부서관리자가 API 직접 호출로 사원 role을 admin으로 바꾸려 하면 403(S15P11B106-86)")
    void departmentManagerCannotChangeEmployeeRole() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        Member employee = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        AuthenticatedMember managerActor = new AuthenticatedMember(manager.getId(), manager.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("admin");

        assertThatThrownBy(() -> memberService.updateUser(managerActor, employee.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPARTMENT_MANAGER_CANNOT_MANAGE_ADMIN);
        // 실제로 승격되지 않았는지 확인
        assertThat(memberRepository.findById(employee.getId()).orElseThrow().getRole()).isEqualTo(Role.EMPLOYEE);
    }

    @Test
    @DisplayName("부서관리자가 API 직접 호출로 사원 accountStatus를 inactive로 바꾸려 하면 403(S15P11B106-86)")
    void departmentManagerCannotChangeEmployeeAccountStatus() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        Member employee = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        AuthenticatedMember managerActor = new AuthenticatedMember(manager.getId(), manager.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setAccountStatus("inactive");

        assertThatThrownBy(() -> memberService.updateUser(managerActor, employee.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPARTMENT_MANAGER_CANNOT_MANAGE_ADMIN);
        assertThat(memberRepository.findById(employee.getId()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("부서관리자가 role·accountStatus를 기존 값 그대로 보내며 이름/부서만 바꾸면 허용(S15P11B106-86)")
    void departmentManagerCanSendUnchangedRoleAndStatus() {
        Department department = departmentRepository.save(new Department("개발부"));
        Department other = departmentRepository.save(new Department("인사부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        Member employee = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        AuthenticatedMember managerActor = new AuthenticatedMember(manager.getId(), manager.getEmail(), Role.ADMIN);
        // 프론트가 기존 값을 그대로 재전송하는 상황: role=employee, accountStatus=active(현재와 동일) + 이름·부서 변경
        UserUpdateRequest request =
                UserUpdateRequest.of("새이름", "employee", String.valueOf(other.getId()), "active");

        UserResponse updated = memberService.updateUser(managerActor, employee.getId(), request);

        assertThat(updated.name()).isEqualTo("새이름");
        assertThat(updated.department().name()).isEqualTo("인사부");
        assertThat(updated.role()).isEqualTo("employee");
        assertThat(updated.accountStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("최고관리자는 사원의 role·accountStatus를 변경할 수 있다(S15P11B106-86)")
    void superAdminCanChangeEmployeeRoleAndStatus() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member superAdmin = memberRepository.save(superAdmin(department)); // 설정 이메일 → 최고관리자
        Member employee = memberRepository.save(
                approvedEmployee(department, "employee@ajt.com", "홍길동", "AJT-2026-0001"));
        AuthenticatedMember superActor = new AuthenticatedMember(superAdmin.getId(), superAdmin.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("admin");
        request.setAccountStatus("inactive");

        UserResponse updated = memberService.updateUser(superActor, employee.getId(), request);

        assertThat(updated.role()).isEqualTo("admin");
        assertThat(updated.accountStatus()).isEqualTo("inactive");
    }

    @Test
    @DisplayName("부서관리자는 다른 관리자 계정을 수정할 수 없다(403)")
    void departmentManagerCannotModifyAnotherAdmin() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        Member otherAdmin = memberRepository.save(Member.approved(
                department, "other-admin@ajt.com", "다른관리자",
                passwordEncoder.encode("password123!"), "AJT-2026-8888", Role.ADMIN));
        AuthenticatedMember managerActor = new AuthenticatedMember(manager.getId(), manager.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("employee");

        assertThatThrownBy(() -> memberService.updateUser(managerActor, otherAdmin.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPARTMENT_MANAGER_CANNOT_MANAGE_ADMIN);
    }

    @Test
    @DisplayName("부서관리자가 자기 자신을 사원으로 강등하려 하면 409")
    void departmentManagerCannotDemoteSelf() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        AuthenticatedMember managerActor = new AuthenticatedMember(manager.getId(), manager.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("employee");

        assertThatThrownBy(() -> memberService.updateUser(managerActor, manager.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SELF_PRIVILEGE_REMOVAL_FORBIDDEN);
    }

    @Test
    @DisplayName("최고관리자는 부서관리자를 사원으로 강등할 수 있고 부서장 지정도 해제된다(S15P11B106-104)")
    void superAdminCanDemoteDepartmentManager() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        Member superAdmin = memberRepository.save(Member.approved(
                department, SUPER_ADMIN_EMAIL, "최고관리자",
                passwordEncoder.encode("password123!"), "AJT-2026-7777", Role.ADMIN));
        AuthenticatedMember superActor = new AuthenticatedMember(superAdmin.getId(), superAdmin.getEmail(), Role.ADMIN);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("employee");

        UserResponse response = memberService.updateUser(superActor, manager.getId(), request);

        assertThat(response.role()).isEqualTo("employee");
        assertThat(departmentRepository.findById(department.getId()).orElseThrow().getManager()).isNull();
    }

    @Test
    @DisplayName("부서관리자는 가입 요청 목록·승인·거부를 할 수 없다(403)")
    void departmentManagerCannotAccessSignupRequests() {
        AuthenticatedMember manager = savedDepartmentManagerActor();

        assertThatThrownBy(() -> memberService.findSignupRequests(manager, 1, 20, "pending", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        assertThatThrownBy(() -> memberService.approveSignupRequest(manager, manager.memberId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        assertThatThrownBy(() -> memberService.rejectSignupRequest(manager, manager.memberId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("managerAssignable=true 후보 목록은 최고관리자를 제외하고 일반 관리자는 포함한다(S15P11B106-146)")
    void managerAssignableExcludesSuperAdmin() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member superAdmin = memberRepository.save(superAdmin(department));
        memberRepository.save(approvedAdmin(department)); // admin@ajt.com, 부서장 미지정 → 후보에 포함돼야 함
        AuthenticatedMember actor = new AuthenticatedMember(superAdmin.getId(), superAdmin.getEmail(), Role.ADMIN);

        UserListResponse response =
                memberService.findUsers(actor, 1, 100, null, null, null, true, null, null);

        assertThat(response.items()).extracting("email")
                .contains("admin@ajt.com")
                .doesNotContain(SUPER_ADMIN_EMAIL);
    }

    @Test
    @DisplayName("managerAssignable=true 후보 목록은 이미 다른 부서의 부서장인 관리자를 제외한다(S15P11B106-146)")
    void managerAssignableExcludesAlreadyAssignedManager() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member assignedManager = memberRepository.save(approvedAdmin(department));
        department.assignManager(assignedManager);
        departmentRepository.save(department);
        Member superAdmin = memberRepository.save(superAdmin(department));
        AuthenticatedMember actor = new AuthenticatedMember(superAdmin.getId(), superAdmin.getEmail(), Role.ADMIN);

        UserListResponse response =
                memberService.findUsers(actor, 1, 100, null, null, null, true, null, null);

        // 이미 부서장으로 지정된 admin@ajt.com은 후보에서 빠진다.
        assertThat(response.items()).extracting("email").doesNotContain("admin@ajt.com");
    }

    @Test
    @DisplayName("일반 사원은 사용자 관리 API를 사용할 수 없다(403)")
    void employeeCannotAccessUserManagement() {
        AuthenticatedMember employee = new AuthenticatedMember(1L, "employee@ajt.com", Role.EMPLOYEE);

        assertThatThrownBy(() -> memberService.findUsers(employee, 1, 20, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    // 부서관리자(어느 부서의 manager_id로 지정된 ADMIN)를 만들어 그 사용자로 동작하는 actor를 반환한다.
    private AuthenticatedMember savedDepartmentManagerActor() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member manager = memberRepository.save(approvedAdmin(department));
        department.assignManager(manager);
        departmentRepository.save(department);
        return new AuthenticatedMember(manager.getId(), manager.getEmail(), Role.ADMIN);
    }

    // 수정(S15P11B106-146): 설정 이메일과 일치하는 최고관리자 계정. 부서 소속이 있어도 최고관리자다.
    private Member superAdmin(Department department) {
        return Member.approved(
                department,
                SUPER_ADMIN_EMAIL,
                "최고관리자",
                passwordEncoder.encode("password123!"),
                "AJT-2026-9000",
                Role.ADMIN
        );
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

    private Inquiry savePendingInquiry(Member author, Member assignee) {
        return inquiryRepository.save(
                Inquiry.create(author, assignee, "문의 제목", "문의 내용", InquiryPriority.NORMAL));
    }
}
