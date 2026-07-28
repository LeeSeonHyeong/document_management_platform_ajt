package com.ajt.backend.domain.department;

import com.ajt.backend.domain.department.dto.DepartmentCreateRequest;
import com.ajt.backend.domain.department.dto.DepartmentListResponse;
import com.ajt.backend.domain.department.dto.DepartmentResponse;
import com.ajt.backend.domain.department.dto.DepartmentUpdateRequest;
import com.ajt.backend.domain.member.AccountStatus;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.member.SignupStatus;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;

    public DepartmentService(
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository
    ) {
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
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
     * 직원이 한 명이라도 소속된 부서는 삭제하지 않고 409로 막습니다.
     */
    @Transactional
    public void deleteDepartment(AuthenticatedMember loginMember, Long departmentId) {
        requireAdmin(loginMember);
        Department department = findDepartment(departmentId);
        if (memberRepository.existsByDepartment_Id(department.getId())) {
            throw new BusinessException(ErrorCode.DEPARTMENT_IN_USE);
        }
        departmentRepository.delete(department);
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

