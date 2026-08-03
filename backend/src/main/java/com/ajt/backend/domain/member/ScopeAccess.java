package com.ajt.backend.domain.member;

/**
 * 로그인한 관리자의 접근 스코프입니다(S15P11B106-199).
 *
 * <ul>
 *   <li>최고관리자(super admin): 전체(ALL)와 모든 부서 범위에 접근할 수 있다(제한 없음).</li>
 *   <li>부서관리자(department manager): 본인이 manager로 지정된 부서({@link #managedDepartmentId()}) 범위만
 *       접근할 수 있고, 전체(ALL)·타부서·복수 부서 범위는 차단된다.</li>
 * </ul>
 *
 * <p>실제 스코프 표현(문서·Wiki의 {@code scope_key}, 일정의 부서 목록)은 도메인마다 다르므로, 이 값은
 * 부서 ID만 들고 있고 도메인별 판정은 각 서비스가 {@code managedDepartmentId}로 수행한다.
 */
public final class ScopeAccess {

    private final boolean superAdmin;
    private final Long managedDepartmentId;

    private ScopeAccess(boolean superAdmin, Long managedDepartmentId) {
        this.superAdmin = superAdmin;
        this.managedDepartmentId = managedDepartmentId;
    }

    /** 최고관리자: 전체·모든 부서 접근. */
    public static ScopeAccess superAdmin() {
        return new ScopeAccess(true, null);
    }

    /**
     * 부서관리자(또는 담당 부서가 없는 비-최고관리자 관리자): 담당 부서 범위만 접근.
     * 담당 부서가 없으면 {@code managedDepartmentId=null}이고 어떤 범위에도 접근할 수 없다.
     */
    public static ScopeAccess departmentManager(Long managedDepartmentId) {
        return new ScopeAccess(false, managedDepartmentId);
    }

    public boolean isSuperAdmin() {
        return superAdmin;
    }

    /** 담당 부서 범위로 제한되는 관리자인지 여부(최고관리자가 아님). */
    public boolean isRestricted() {
        return !superAdmin;
    }

    /** 담당 부서가 지정된 부서관리자인지 여부. */
    public boolean isDepartmentManager() {
        return !superAdmin && managedDepartmentId != null;
    }

    /** 담당 부서 ID입니다. 최고관리자이거나 담당 부서가 없으면 null입니다. */
    public Long managedDepartmentId() {
        return managedDepartmentId;
    }

    /**
     * 담당 부서의 단일 scope_key("D{id}")입니다. 최고관리자이거나 담당 부서가 없으면 null입니다.
     * 문서·Wiki의 부서 공개 scope_key 표기(정렬된 "D1-D2")에서 단일 부서는 항상 "D{id}"입니다.
     */
    public String managedScopeKey() {
        return managedDepartmentId == null ? null : "D" + managedDepartmentId;
    }

    /**
     * 문서·Wiki scope_key 접근 가능 여부입니다.
     * 최고관리자는 모든 scope, 부서관리자는 담당 부서 단일 scope("D{id}")만 접근할 수 있습니다.
     * 전체(ALL)·타부서·복수 부서 조합("D1-D2")은 모두 차단됩니다.
     */
    public boolean canAccessScopeKey(String scopeKey) {
        if (superAdmin) {
            return true;
        }
        String managed = managedScopeKey();
        return managed != null && managed.equals(scopeKey);
    }

    /**
     * 일정 등 "부서 ID 목록" 스코프의 관리(생성·수정·삭제·승인·조회) 가능 여부입니다.
     * 최고관리자는 모든 부서 목록, 부서관리자는 담당 부서 단독({@code [managedDepartmentId]})만 허용합니다.
     * 전체(ALL)·타부서·복수 부서 목록은 차단됩니다.
     */
    public boolean canManageDepartmentScope(java.util.List<Long> departmentIds) {
        if (superAdmin) {
            return true;
        }
        return managedDepartmentId != null
                && departmentIds != null
                && departmentIds.size() == 1
                && departmentIds.contains(managedDepartmentId);
    }
}
