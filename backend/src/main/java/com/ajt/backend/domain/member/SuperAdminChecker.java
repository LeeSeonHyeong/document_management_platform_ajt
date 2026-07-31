package com.ajt.backend.domain.member;

import com.ajt.backend.domain.department.DepartmentRepository;
import org.springframework.stereotype.Component;

/**
 * 최고관리자(super-admin) 판별을 한곳에서 관리하는 정책 컴포넌트입니다(S15P11B106-83).
 *
 * <p>이 프로젝트는 별도 SUPER_ADMIN role을 두지 않는다. 부서관리자도 {@code role=ADMIN}이며, 부서관리자 여부는
 * role이 아니라 {@code department.manager_id}에 해당 회원이 지정돼 있는지로 판단한다. 따라서 정의는 다음과 같다.
 * <ul>
 *   <li>최고관리자 = {@code role=ADMIN} 이면서 어떤 부서의 manager로도 지정되지 않은 사용자</li>
 *   <li>부서관리자 = {@code role=ADMIN} 이면서 하나 이상의 부서 manager로 지정된 사용자 (최고관리자 아님)</li>
 *   <li>일반 사원 = {@code role=EMPLOYEE} (최고관리자 아님)</li>
 * </ul>
 *
 * <p>사용자 관리·가입 승인/거절 권한 검사(MemberService.requireSuperAdmin)와 로그인·내 정보 응답의
 * {@code isSuperAdmin} 필드가 <b>동일한 기준</b>을 쓰도록 판별을 여기로 단일화한다.
 */
@Component
public class SuperAdminChecker {

    private final DepartmentRepository departmentRepository;

    public SuperAdminChecker(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    public boolean isSuperAdmin(Member member) {
        return isSuperAdmin(member.getId(), member.getRole() == Role.ADMIN);
    }

    /**
     * @param memberId 대상 회원 ID
     * @param isAdmin  대상이 {@code role=ADMIN}인지 여부
     * @return ADMIN이면서 어느 부서의 부서장으로도 지정되지 않았으면 true
     */
    public boolean isSuperAdmin(Long memberId, boolean isAdmin) {
        return isAdmin && !departmentRepository.existsByManager_Id(memberId);
    }
}
