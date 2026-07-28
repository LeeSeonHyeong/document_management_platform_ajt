package com.ajt.backend.domain.department;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.department.dto.DepartmentCreateRequest;
import com.ajt.backend.domain.department.dto.DepartmentListResponse;
import com.ajt.backend.domain.department.dto.DepartmentResponse;
import com.ajt.backend.domain.department.dto.DepartmentUpdateRequest;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
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
class DepartmentServiceTest {
    private final DepartmentService departmentService;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;


    @Autowired
    DepartmentServiceTest(
            DepartmentService departmentService,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.departmentService = departmentService;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Test
    @DisplayName("부서 목록 조회는 이름순으로 부서와 지정 관리자를 반환한다")
    void findDepartmentsReturnsDepartmentsWithManager() {
        Department development = departmentRepository.save(new Department("개발부"));
        Department sales = departmentRepository.save(new Department("영업부"));
        Member admin = memberRepository.save(approvedAdmin(development, "admin@ajt.com", "AJT-2026-9001"));
        sales.assignManager(admin);

        DepartmentListResponse response = departmentService.findDepartments();

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).name()).isEqualTo("개발부");
        assertThat(response.items().get(1).manager().name()).isEqualTo("관리자");
    }

    @Test
    @DisplayName("부서 생성은 관리자만 수행할 수 있고 선택한 관리자를 함께 지정한다")
    void createDepartmentAssignsManager() {
        Department baseDepartment = departmentRepository.save(new Department("기본부"));
        Member admin = memberRepository.save(approvedAdmin(baseDepartment, "admin@ajt.com", "AJT-2026-9001"));

        DepartmentResponse response = departmentService.createDepartment(
                authenticated(admin),
                new DepartmentCreateRequest(" 플랫폼개발부 ", String.valueOf(admin.getId()))
        );

        assertThat(response.name()).isEqualTo("플랫폼개발부");
        assertThat(response.manager().userId()).isEqualTo(String.valueOf(admin.getId()));
    }

    @Test
    @DisplayName("부서 생성은 중복된 부서명을 거절한다")
    void createDepartmentRejectsDuplicateName() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "AJT-2026-9001"));

        assertThatThrownBy(() -> departmentService.createDepartment(
                authenticated(admin),
                new DepartmentCreateRequest("개발부", null)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPARTMENT_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("부서 수정은 managerId가 null이면 지정 관리자를 해제한다")
    void updateDepartmentClearsManager() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "AJT-2026-9001"));
        department.assignManager(admin);
        DepartmentUpdateRequest request = new DepartmentUpdateRequest();
        request.setManagerId(null);

        DepartmentResponse response = departmentService.updateDepartment(authenticated(admin), department.getId(), request);

        assertThat(response.manager()).isNull();
    }

    @Test
    @DisplayName("부서 수정은 이미 다른 부서를 담당 중인 관리자를 거절한다")
    void updateDepartmentRejectsAlreadyAssignedManager() {
        Department baseDepartment = departmentRepository.save(new Department("기본부"));
        Department firstDepartment = departmentRepository.save(new Department("개발부"));
        Department secondDepartment = departmentRepository.save(new Department("인사부"));
        Member admin = memberRepository.save(approvedAdmin(baseDepartment, "admin@ajt.com", "AJT-2026-9001"));
        firstDepartment.assignManager(admin);
        DepartmentUpdateRequest request = new DepartmentUpdateRequest();
        request.setManagerId(String.valueOf(admin.getId()));

        assertThatThrownBy(() -> departmentService.updateDepartment(authenticated(admin), secondDepartment.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPARTMENT_MANAGER_ALREADY_ASSIGNED);
    }

    @Test
    @DisplayName("소속 회원이 있는 부서는 삭제할 수 없다")
    void deleteDepartmentRejectsDepartmentInUse() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "AJT-2026-9001"));

        assertThatThrownBy(() -> departmentService.deleteDepartment(authenticated(admin), department.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPARTMENT_IN_USE);
    }

    private AuthenticatedMember authenticated(Member member) {
        return new AuthenticatedMember(member.getId(), member.getEmail(), member.getRole());
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
}
