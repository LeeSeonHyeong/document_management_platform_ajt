package com.ajt.backend.domain.department;

import com.ajt.backend.domain.department.dto.DepartmentCreateRequest;
import com.ajt.backend.domain.department.dto.DepartmentListResponse;
import com.ajt.backend.domain.department.dto.DepartmentResponse;
import com.ajt.backend.domain.department.dto.DepartmentUpdateRequest;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.AccountStatus;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.member.SignupStatus;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final ScheduleRepository scheduleRepository;
    private final WikiScopeRepository wikiScopeRepository;

    public DepartmentService(
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            ScheduleRepository scheduleRepository,
            WikiScopeRepository wikiScopeRepository
    ) {
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.scheduleRepository = scheduleRepository;
        this.wikiScopeRepository = wikiScopeRepository;
    }

    /**
     * DEPT-01 부서 목록 조회 요구사항입니다.
     * 로그인한 사용자가 전체 부서와 지정 관리자를 확인할 수 있습니다.
     */
    @Transactional(readOnly = true)
    public DepartmentListResponse findDepartments() {
        return DepartmentListResponse.from(departmentRepository.findAllByOrderByNameAsc());
    }

    /**
     * DEPT-02 부서 생성 요구사항입니다.
     * 관리자는 중복되지 않는 이름으로 부서를 만들고, 선택적으로 관리자를 지정할 수 있습니다.
     */
    @Transactional
    public DepartmentResponse createDepartment(AuthenticatedMember loginMember, DepartmentCreateRequest request) {
        requireAdmin(loginMember);
        String name = normalizeName(request.name());
        validateDuplicateName(name);

        Department department = new Department(name);
        if (request.managerId() != null) {
            department.assignManager(findAssignableManager(request.managerId(), null));
        }
        return DepartmentResponse.from(departmentRepository.save(department));
    }

    /**
     * DEPT-03 부서 수정 요구사항입니다.
     * name은 값이 있을 때만 바꾸고, managerId는 null을 보내면 관리자 지정을 해제합니다.
     */
    @Transactional
    public DepartmentResponse updateDepartment(
            AuthenticatedMember loginMember,
            Long departmentId,
            DepartmentUpdateRequest request
    ) {
        requireAdmin(loginMember);
        Department department = findDepartment(departmentId);

        if (request.name() != null) {
            String name = normalizeName(request.name());
            validateDuplicateName(name, department.getId());
            department.changeName(name);
        }
        if (request.managerIdPresent()) {
            if (request.managerId() == null) {
                department.clearManager();
            } else {
                department.assignManager(findAssignableManager(request.managerId(), department.getId()));
            }
        }
        return DepartmentResponse.from(department);
    }

    /**
     * DEPT-04 부서 삭제 요구사항입니다.
     * 직원이 소속되거나 다른 데이터가 참조하는 부서는 삭제하지 않고 409로 막습니다. (FR-USR-008)
     */
    @Transactional
    public void deleteDepartment(AuthenticatedMember loginMember, Long departmentId) {
        requireAdmin(loginMember);
        Department department = findDepartment(departmentId);
        if (memberRepository.existsByDepartment_Id(department.getId())) {
            throw new BusinessException(ErrorCode.DEPARTMENT_IN_USE);
        }
        if (isReferencedByDepartment(department.getId())) {
            throw new BusinessException(ErrorCode.DEPARTMENT_IN_USE);
        }
        departmentRepository.delete(department);
    }

    /**
     * 부서 공개 일정과 부서 공개 위키 범위가 이 부서를 참조하는지 확인합니다.
     * 일정은 schedule_department 조인 테이블의 FK가 삭제를 막지만, FK 위반은 계약이 요구하는
     * 409 DEPARTMENT_IN_USE가 아니라 500으로 나가므로 삭제 전에 미리 확인한다.
     * 위키 범위는 wiki_scope.department_refs JSON으로 저장되어 FK를 걸 수 없으므로
     * (backend-spring-convention.md 3절) 부서 공개 범위만 읽어 메모리에서 판정한다.
     * 부서 공개가 아닌 행의 참조 목록은 항상 비어 있다.
     */
    private boolean isReferencedByDepartment(long departmentId) {
        if (scheduleRepository.existsByDepartments_DepartmentId(departmentId)) {
            return true;
        }
        return wikiScopeRepository
                .findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT)
                .stream()
                .anyMatch(scope -> scope.departmentRefs().contains(departmentId));
    }

    private Department findDepartment(Long departmentId) {
        return departmentRepository.findById(departmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPARTMENT_RESOURCE_NOT_FOUND));
    }

    private void requireAdmin(AuthenticatedMember loginMember) {
        if (loginMember == null || !loginMember.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "부서명을 입력해주세요.");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 50) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "부서명은 50자 이하로 입력해주세요.");
        }
        return trimmedName;
    }

    private void validateDuplicateName(String name) {
        if (departmentRepository.existsByName(name)) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NAME_DUPLICATED);
        }
    }

    private void validateDuplicateName(String name, Long departmentId) {
        if (departmentRepository.existsByNameAndIdNot(name, departmentId)) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NAME_DUPLICATED);
        }
    }

    private Member findAssignableManager(String managerId, Long currentDepartmentId) {
        Long id = parseId(managerId, "관리자 ID는 숫자 문자열이어야 합니다.");
        Member manager = memberRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPARTMENT_MANAGER_INVALID));
        if (manager.getRole() != Role.ADMIN
                || manager.getSignupStatus() != SignupStatus.APPROVED
                || manager.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.DEPARTMENT_MANAGER_INVALID);
        }
        boolean alreadyAssigned = currentDepartmentId == null
                ? departmentRepository.existsByManager_Id(manager.getId())
                : departmentRepository.existsByManager_IdAndIdNot(manager.getId(), currentDepartmentId);
        if (alreadyAssigned) {
            throw new BusinessException(ErrorCode.DEPARTMENT_MANAGER_ALREADY_ASSIGNED);
        }
        return manager;
    }

    private Long parseId(String value, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, errorMessage);
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, errorMessage);
        }
    }
}

