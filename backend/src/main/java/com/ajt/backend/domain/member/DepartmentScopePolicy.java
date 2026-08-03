package com.ajt.backend.domain.member;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 관리자의 접근 스코프를 판정하는 정책 컴포넌트입니다(S15P11B106-199).
 *
 * <p>문서·Wiki·일정 도메인이 "최고관리자냐 부서관리자냐, 담당 부서가 어디냐"를 동일한 기준으로 판단하도록
 * 판정을 한곳으로 모은다.
 *
 * <ul>
 *   <li>최고관리자 = 기존 {@link SuperAdminChecker} 기준(설정 이메일). 전체·모든 부서 접근.</li>
 *   <li>부서관리자 = 최고관리자가 아니면서 어느 부서의 manager로 지정된 ADMIN. 담당 부서 범위만 접근.</li>
 * </ul>
 *
 * <p>{@code manager_id}는 UNIQUE라 담당 부서는 최대 1개다({@link DepartmentRepository#findByManager_Id}).
 * 담당 부서가 없는 비-최고관리자 관리자는 어떤 범위에도 접근할 수 없다(안전 기본값).
 */
@Component
public class DepartmentScopePolicy {

    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;
    private final SuperAdminChecker superAdminChecker;

    public DepartmentScopePolicy(
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository,
            SuperAdminChecker superAdminChecker
    ) {
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
        this.superAdminChecker = superAdminChecker;
    }

    /**
     * 회원 ID로 최신 DB 상태를 기준으로 접근 스코프를 판정합니다.
     * 최고관리자면 전체 접근, 아니면 담당 부서(있으면) 범위로 제한합니다.
     */
    @Transactional(readOnly = true)
    public ScopeAccess resolve(long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (superAdminChecker.isSuperAdmin(member)) {
            return ScopeAccess.superAdmin();
        }
        Long managedDepartmentId = departmentRepository.findByManager_Id(memberId)
                .map(Department::getId)
                .orElse(null);
        return ScopeAccess.departmentManager(managedDepartmentId);
    }
}
