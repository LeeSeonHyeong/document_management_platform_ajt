package com.ajt.backend.domain.department;

import com.ajt.backend.domain.department.dto.DepartmentCreateRequest;
import com.ajt.backend.domain.department.dto.DepartmentListResponse;
import com.ajt.backend.domain.department.dto.DepartmentResponse;
import com.ajt.backend.domain.department.dto.DepartmentUpdateRequest;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.SuperAdminChecker;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final ScheduleRepository scheduleRepository;
    private final WikiScopeRepository wikiScopeRepository;
    private final SuperAdminChecker superAdminChecker;

    public DepartmentService(
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            ScheduleRepository scheduleRepository,
            WikiScopeRepository wikiScopeRepository,
            SuperAdminChecker superAdminChecker
    ) {
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.scheduleRepository = scheduleRepository;
        this.wikiScopeRepository = wikiScopeRepository;
        this.superAdminChecker = superAdminChecker;
    }

    /**
     * DEPT-01 부서 목록 조회 요구사항입니다.
     * 로그인한 사용자가 부서와 지정 관리자를 확인할 수 있습니다.
     * 수정(S15P11B106-183): 명목상 부서('최고관리자')는 실제 조직 부서가 아니므로 응답에서 제외한다.
     */
    @Transactional(readOnly = true)
    public DepartmentListResponse findDepartments() {
        List<Department> visibleDepartments = departmentRepository.findAllByOrderByNameAsc().stream()
                .filter(department -> !department.isNominal())
                .toList();
        return DepartmentListResponse.from(visibleDepartments);
    }

    /**
     * S15P11B106-69 데이터 정리입니다.
     * 부서장 자동 해제(S15P11B106-58) 도입 이전에 강등·비활성으로 자격을 잃은 채 남아 있는 부서장
     * 지정을 해제합니다. 자격을 유지한 부서장은 그대로 두므로 반복 실행해도 안전합니다(idempotent).
     * 해제한 건수를 반환합니다.
     */
    @Transactional
    public int releaseIneligibleDepartmentManagers() {
        List<Department> departments = departmentRepository.findByManagerIsNotNull();
        int cleared = 0;
        for (Department department : departments) {
            if (!department.getManager().isEligibleAsDepartmentManager()) {
                // 어느 부서의 어떤 지정을 해제했는지 추적할 수 있도록 부서·기존 부서장을 남긴다.
                log.warn(
                        "부서장 자격을 잃은 지정 해제: 부서='{}'(id={}), 기존 부서장 id={}",
                        department.getName(),
                        department.getId(),
                        department.getManager().getId()
                );
                department.clearManager();
                cleared++;
            }
        }
        return cleared;
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
            // 수정(S15P11B106-146): 기본 부서('미지정')은 이름을 실제로 바꾸는 요청을 거절한다(같은 이름 재전송은 허용).
            if (department.isDefault() && !Department.DEFAULT_NAME.equals(name)) {
                throw new BusinessException(ErrorCode.DEFAULT_DEPARTMENT_PROTECTED);
            }
            validateDuplicateName(name, department.getId());
            department.changeName(name);
        }
        if (request.managerIdPresent()) {
            if (request.managerId() == null) {
                // 해제는 기본 부서에도 허용한다 — 이 가드가 생기기 전에 붙은 관리자를 떼는 길이
                // 없으면 화면에서 되돌릴 수 없다 (S15P11B106-250).
                department.clearManager();
            } else {
                // 수정(S15P11B106-250): 기본 부서('미지정')는 부서 없는 사람을 담는 자리라 관리자를
                //   둘 대상이 아니다. S15P11B106-146이 이름·삭제만 막아 관리자 지정이 열려 있었다.
                if (department.isDefault()) {
                    throw new BusinessException(ErrorCode.DEFAULT_DEPARTMENT_PROTECTED);
                }
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
        // 수정(S15P11B106-146): 시스템 기본 부서('미지정')은 최고관리자라도 삭제할 수 없다(항상 존재 보장).
        if (department.isDefault()) {
            throw new BusinessException(ErrorCode.DEFAULT_DEPARTMENT_PROTECTED);
        }
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
        // 수정(S15P11B106-146): 최고관리자는 부서 관리자로 지정할 수 없다. 부서장으로 지정되면 최고관리자 자격을
        //   잃는 문제(가입 승인 메뉴 사라짐)를 애초에 막는다. 최고관리자는 설정 이메일로 고정 식별한다.
        if (superAdminChecker.isConfiguredSuperAdminEmail(manager.getEmail())) {
            throw new BusinessException(ErrorCode.DEPARTMENT_MANAGER_INVALID, "최고관리자는 부서 관리자로 지정할 수 없습니다.");
        }
        // 수정(S15P11B106-69): 부서장 자격 판정을 Member.isEligibleAsDepartmentManager로 일원화한다.
        //   (지정 검증과 자동 해제가 같은 자격 정의를 쓰도록 중복 제거.)
        if (!manager.isEligibleAsDepartmentManager()) {
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

