package com.ajt.backend.domain.department;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.department.dto.DepartmentCreateRequest;
import com.ajt.backend.domain.department.dto.DepartmentListResponse;
import com.ajt.backend.domain.department.dto.DepartmentResponse;
import com.ajt.backend.domain.department.dto.DepartmentUpdateRequest;
import com.ajt.backend.domain.document.DefaultDocumentCategoryEnsurer;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false",
        // 부서 개수를 직접 단언하고 '전체' 부서를 테스트에서 직접 만들므로 시작 시 자동 생성은 끈다(S15P11B106-146).
        "ajt.default-department.enabled=false"
})
@Transactional
class DepartmentServiceTest {
    private final DepartmentService departmentService;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final ScheduleRepository scheduleRepository;
    private final WikiScopeRepository wikiScopeRepository;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final PasswordEncoder passwordEncoder;


    @Autowired
    DepartmentServiceTest(
            DepartmentService departmentService,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            ScheduleRepository scheduleRepository,
            WikiScopeRepository wikiScopeRepository,
            DocumentCategoryRepository documentCategoryRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.departmentService = departmentService;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.scheduleRepository = scheduleRepository;
        this.wikiScopeRepository = wikiScopeRepository;
        this.documentCategoryRepository = documentCategoryRepository;
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
    @DisplayName("부서 목록 조회는 명목상 부서('최고관리자')를 제외하고 기본 부서('미지정')은 노출한다(S15P11B106-183)")
    void findDepartmentsExcludesNominalButKeepsDefault() {
        departmentRepository.save(new Department(Department.NOMINAL_DEPARTMENT_NAME));
        departmentRepository.save(new Department(Department.DEFAULT_NAME));
        departmentRepository.save(new Department("개발부"));

        DepartmentListResponse response = departmentService.findDepartments();

        // 명목상 부서만 빠지고 '전체'·'개발부'는 그대로 노출된다.
        assertThat(response.items()).extracting(item -> item.name())
                .containsExactlyInAnyOrder(Department.DEFAULT_NAME, "개발부")
                .doesNotContain(Department.NOMINAL_DEPARTMENT_NAME);
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
    @DisplayName("부서 생성 시 부서 문서 공간에 기본 카테고리가 채워진다(S15P11B106-257)")
    void createDepartmentSeedsDefaultDocumentCategories() {
        Department baseDepartment = departmentRepository.save(new Department("기본부"));
        Member admin = memberRepository.save(approvedAdmin(baseDepartment, "admin@ajt.com", "AJT-2026-9001"));

        DepartmentResponse response = departmentService.createDepartment(
                authenticated(admin),
                new DepartmentCreateRequest("플랫폼개발부", null)
        );

        String scopeKey = "D" + response.departmentId();
        // 카테고리 목록 조회(CAT-01)에 필요한 부서 문서 공간과 기본 카테고리가 함께 생긴다.
        assertThat(wikiScopeRepository.existsById(scopeKey)).isTrue();
        assertThat(documentCategoryRepository.findAllByScopeKeyOrderByNameAsc(scopeKey))
                .extracting(DocumentCategory::name)
                .containsExactlyInAnyOrderElementsOf(DefaultDocumentCategoryEnsurer.DEFAULT_CATEGORY_NAMES);
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
    @DisplayName("부서 생성 시 최고관리자를 관리자로 지정하면 거절한다(S15P11B106-146)")
    void createDepartmentRejectsSuperAdminAsManager() {
        Department baseDepartment = departmentRepository.save(new Department("기본부"));
        Member actorAdmin = memberRepository.save(approvedAdmin(baseDepartment, "admin@ajt.com", "AJT-2026-9001"));
        // 설정 이메일(ajt.super-admin.email 기본값) 계정 → 최고관리자
        Member superAdmin = memberRepository.save(approvedAdmin(baseDepartment, "superadmin@ajt.com", "AJT-2026-9000"));

        assertThatThrownBy(() -> departmentService.createDepartment(
                authenticated(actorAdmin),
                new DepartmentCreateRequest("플랫폼개발부", String.valueOf(superAdmin.getId()))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPARTMENT_MANAGER_INVALID);
    }

    @Test
    @DisplayName("부서 수정 시 최고관리자를 관리자로 지정하면 거절한다(S15P11B106-146)")
    void updateDepartmentRejectsSuperAdminAsManager() {
        Department department = departmentRepository.save(new Department("개발부"));
        Member actorAdmin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "AJT-2026-9001"));
        Member superAdmin = memberRepository.save(approvedAdmin(department, "superadmin@ajt.com", "AJT-2026-9000"));
        DepartmentUpdateRequest request = new DepartmentUpdateRequest();
        request.setManagerId(String.valueOf(superAdmin.getId()));

        assertThatThrownBy(() -> departmentService.updateDepartment(
                authenticated(actorAdmin), department.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPARTMENT_MANAGER_INVALID);
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
    @DisplayName("기본 부서('미지정')은 최고관리자라도 삭제할 수 없다(S15P11B106-146)")
    void deleteDefaultDepartmentRejected() {
        Department base = departmentRepository.save(new Department("기본부"));
        Department defaultDept = departmentRepository.save(new Department(Department.DEFAULT_NAME));
        Member admin = memberRepository.save(approvedAdmin(base, "admin@ajt.com", "AJT-2026-9001"));

        assertThatThrownBy(() -> departmentService.deleteDepartment(authenticated(admin), defaultDept.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEFAULT_DEPARTMENT_PROTECTED);
        assertThat(departmentRepository.findById(defaultDept.getId())).isPresent();
    }

    @Test
    @DisplayName("기본 부서('미지정')은 이름을 변경할 수 없다(S15P11B106-146)")
    void renameDefaultDepartmentRejected() {
        Department defaultDept = departmentRepository.save(new Department(Department.DEFAULT_NAME));
        Member admin = memberRepository.save(approvedAdmin(defaultDept, "admin@ajt.com", "AJT-2026-9001"));
        DepartmentUpdateRequest request = new DepartmentUpdateRequest();
        request.setName("전사");

        assertThatThrownBy(() -> departmentService.updateDepartment(
                authenticated(admin), defaultDept.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEFAULT_DEPARTMENT_PROTECTED);
        assertThat(departmentRepository.findById(defaultDept.getId()).orElseThrow().getName())
                .isEqualTo(Department.DEFAULT_NAME);
    }

    @Test
    @DisplayName("다른 부서를 '전체' 이름으로 변경하면 중복으로 거절한다(S15P11B106-146)")
    void renameOtherDepartmentToDefaultNameRejected() {
        departmentRepository.save(new Department(Department.DEFAULT_NAME));
        Department other = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(other, "admin@ajt.com", "AJT-2026-9001"));
        DepartmentUpdateRequest request = new DepartmentUpdateRequest();
        request.setName(Department.DEFAULT_NAME);

        assertThatThrownBy(() -> departmentService.updateDepartment(
                authenticated(admin), other.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPARTMENT_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("기본 부서('미지정')에는 관리자를 지정할 수 없다(S15P11B106-250)")
    void defaultDepartmentRejectsManagerAssignment() {
        // S15P11B106-146이 이름·삭제만 막아 관리자 지정이 열려 있었다. '미지정'은 부서 없는
        // 사람을 담는 자리라 관리자를 둘 대상이 아니다.
        Department defaultDept = departmentRepository.save(new Department(Department.DEFAULT_NAME));
        Member admin = memberRepository.save(approvedAdmin(defaultDept, "admin@ajt.com", "AJT-2026-9001"));
        DepartmentUpdateRequest request = new DepartmentUpdateRequest();
        request.setManagerId(String.valueOf(admin.getId()));

        assertThatThrownBy(() -> departmentService.updateDepartment(
                authenticated(admin), defaultDept.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEFAULT_DEPARTMENT_PROTECTED);
    }

    @Test
    @DisplayName("기본 부서('미지정')의 관리자 해제는 허용한다 — 되돌릴 길을 남긴다(S15P11B106-250)")
    void defaultDepartmentAllowsManagerClear() {
        // 이 가드가 생기기 전에 붙은 관리자를 뗄 방법이 없으면 화면에서 되돌릴 수 없다.
        Department defaultDept = departmentRepository.save(new Department(Department.DEFAULT_NAME));
        Member admin = memberRepository.save(approvedAdmin(defaultDept, "admin@ajt.com", "AJT-2026-9001"));
        defaultDept.assignManager(admin);
        departmentRepository.save(defaultDept);
        DepartmentUpdateRequest request = new DepartmentUpdateRequest();
        request.setManagerId(null);

        DepartmentResponse response = departmentService.updateDepartment(
                authenticated(admin), defaultDept.getId(), request);

        assertThat(response.manager()).isNull();
        assertThat(response.name()).isEqualTo(Department.DEFAULT_NAME);
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

    @Test
    @DisplayName("소속 회원이 없으면 일정의 부서 공개 연결을 제거하고 부서를 삭제한다")
    void deleteDepartmentRemovesScheduleReference() {
        Department adminDepartment = departmentRepository.save(new Department("인사부"));
        Department referenced = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(adminDepartment, "admin@ajt.com", "AJT-2026-9001"));
        Schedule schedule = Schedule.create(
                admin.getId(),
                "부서 워크샵",
                null,
                null,
                null,
                ScheduleVisibility.DEPARTMENT,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T09:00:00Z")
        );
        schedule.replaceDepartments(List.of(referenced.getId()));
        scheduleRepository.save(schedule);

        departmentService.deleteDepartment(authenticated(admin), referenced.getId());

        assertThat(departmentRepository.findById(referenced.getId())).isEmpty();
        assertThat(scheduleRepository.findById(schedule.id()).orElseThrow().departmentIds()).isEmpty();
    }

    @Test
    @DisplayName("단독 Wiki 범위와 기본 카테고리를 함께 삭제한 뒤 부서를 삭제한다")
    void deleteDepartmentRemovesExclusiveWikiScope() {
        Department adminDepartment = departmentRepository.save(new Department("인사부"));
        Department referenced = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(adminDepartment, "admin@ajt.com", "AJT-2026-9001"));
        WikiScope scope = wikiScopeRepository.save(WikiScope.department(List.of(referenced.getId())));
        documentCategoryRepository.save(DocumentCategory.create(scope.scopeKey(), "업무 가이드", null));

        departmentService.deleteDepartment(authenticated(admin), referenced.getId());

        assertThat(departmentRepository.findById(referenced.getId())).isEmpty();
        assertThat(wikiScopeRepository.findById(scope.scopeKey())).isEmpty();
        assertThat(documentCategoryRepository.findAllByScopeKeyOrderByNameAsc(scope.scopeKey())).isEmpty();
    }

    @Test
    @DisplayName("공유 Wiki 범위는 유지하고 삭제한 부서의 참조만 제거한다")
    void deleteDepartmentKeepsSharedWikiScope() {
        Department adminDepartment = departmentRepository.save(new Department("인사부"));
        Department deleted = departmentRepository.save(new Department("개발부"));
        Department retained = departmentRepository.save(new Department("기획부"));
        Member admin = memberRepository.save(approvedAdmin(adminDepartment, "admin@ajt.com", "AJT-2026-9001"));
        WikiScope shared = wikiScopeRepository.save(WikiScope.department(List.of(deleted.getId(), retained.getId())));

        departmentService.deleteDepartment(authenticated(admin), deleted.getId());

        assertThat(departmentRepository.findById(deleted.getId())).isEmpty();
        assertThat(wikiScopeRepository.findById(shared.scopeKey()).orElseThrow().departmentRefs())
                .containsExactly(retained.getId());
    }

    @Test
    @DisplayName("아무 데이터도 참조하지 않는 부서는 삭제된다")
    void deleteDepartmentRemovesUnreferencedDepartment() {
        Department adminDepartment = departmentRepository.save(new Department("인사부"));
        Department unreferenced = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(approvedAdmin(adminDepartment, "admin@ajt.com", "AJT-2026-9001"));
        Schedule otherSchedule = Schedule.create(
                admin.getId(),
                "다른 부서 워크샵",
                null,
                null,
                null,
                ScheduleVisibility.DEPARTMENT,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T09:00:00Z")
        );
        otherSchedule.replaceDepartments(List.of(adminDepartment.getId()));
        scheduleRepository.save(otherSchedule);

        departmentService.deleteDepartment(authenticated(admin), unreferenced.getId());

        assertThat(departmentRepository.findById(unreferenced.getId())).isEmpty();
    }

    @Test
    @DisplayName("정합성 정리는 자격을 잃은 기존 부서장 지정을 해제한다")
    void releaseIneligibleDepartmentManagersClearsDemotedManager() {
        Department department = departmentRepository.save(new Department("기획부"));
        Member manager = memberRepository.save(approvedAdmin(department, "manager@ajt.com", "AJT-2026-9101"));
        department.assignManager(manager);
        departmentRepository.save(department);
        // 자동 해제 도입 이전 상태를 재현: 부서장이 사원으로 강등됐지만 부서장 지정이 남아 있음(담당 부서 null 전달)
        manager.updateByAdmin(null, null, Role.EMPLOYEE, null, null);

        int cleared = departmentService.releaseIneligibleDepartmentManagers();

        assertThat(cleared).isEqualTo(1);
        assertThat(departmentRepository.findById(department.getId()).orElseThrow().getManager()).isNull();
    }

    @Test
    @DisplayName("정합성 정리는 자격을 유지한 부서장 지정은 그대로 둔다")
    void releaseIneligibleDepartmentManagersKeepsEligibleManager() {
        Department department = departmentRepository.save(new Department("기획부"));
        Member manager = memberRepository.save(approvedAdmin(department, "manager@ajt.com", "AJT-2026-9101"));
        department.assignManager(manager);
        departmentRepository.save(department);

        int cleared = departmentService.releaseIneligibleDepartmentManagers();

        assertThat(cleared).isEqualTo(0);
        assertThat(departmentRepository.findById(department.getId()).orElseThrow().getManager().getId())
                .isEqualTo(manager.getId());
    }

    @Test
    @DisplayName("updateByAdmin에 담당하지 않는 부서를 넘겨도 그 부서의 부서장은 해제되지 않는다")
    void updateByAdminDoesNotClearUnmanagedDepartment() {
        Department mine = departmentRepository.save(new Department("기획부"));
        Department other = departmentRepository.save(new Department("개발부"));
        Member me = memberRepository.save(approvedAdmin(mine, "me@ajt.com", "AJT-2026-9201"));
        Member otherManager = memberRepository.save(approvedAdmin(other, "other@ajt.com", "AJT-2026-9202"));
        other.assignManager(otherManager);
        departmentRepository.save(other);
        // me를 강등하면서, 실수로 me가 담당하지 않는 other 부서를 넘긴다
        me.updateByAdmin(null, null, Role.EMPLOYEE, null, other);

        // 자기-부서 가드: other의 부서장은 me가 아니라 otherManager이므로 해제되지 않아야 한다
        assertThat(departmentRepository.findById(other.getId()).orElseThrow().getManager().getId())
                .isEqualTo(otherManager.getId());
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
