// 사용자 상세·수정 접근 정책입니다(S15P11B106-222).
//
// 사용자 목록은 부서관리자도 볼 수 있지만, 사용자 상세 조회와 수정은 최고관리자만 가능하다.
// 화면에서는 상세보기/수정 진입 버튼과 행 클릭 이동을 이 기준으로 숨기고, URL 직접 접근은
// 라우트 가드(SuperAdminRoute)가 막는다. 백엔드도 상세/수정 API를 최고관리자 전용으로 강제한다.
//
// 최고관리자 여부는 역할(role === 'admin')이 아니라 로그인/내 정보 응답의 isSuperAdmin으로
// 판단한다. 부서관리자도 role은 admin이라, role로 구분하면 상세/수정이 열려 버린다.

/**
 * 사용자 상세·수정 화면에 진입할 수 있는지(= 상세보기/수정 버튼을 노출할지) 판단한다.
 * isSuperAdmin이 정확히 true일 때만 허용한다(undefined·'admin' 같은 truthy 값은 불허).
 */
export function canManageUserAccounts(isSuperAdmin) {
  return isSuperAdmin === true
}
