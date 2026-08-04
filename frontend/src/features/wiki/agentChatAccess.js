// Wiki AI 수정 대화(WikiAgentChat) 패널 노출 권한입니다(S15P11B106-229 후속).
//
// Wiki '조회'는 넓다(전체공개 ALL + 본인 소속 부서 포함). 하지만 AI 수정 대화/AI 수정은 '관리' 작업이라
// 좁게 유지한다. 백엔드는 부서관리자의 담당 부서 scope 밖 Wiki 수정 대화를 WIKI_NOT_FOUND로 막으므로,
// 프론트도 권한 있는 Wiki에서만 패널을 보여줘 헛된 호출과 오류 노출을 막는다.
//
//   - 최고관리자: 모든 Wiki에서 표시.
//   - 부서관리자: 본인이 관리하는 담당 부서 '단일' scope(`D{managedDepartmentId}`)와 정확히 일치하는
//     Wiki에서만 표시. 전체공개(ALL)·타부서·복수부서(예: `D1-D2`)는 조회만 가능하고 수정 대화는 숨긴다.
//   - 사원: 항상 숨김.
//
// role=admin만으로 판단하지 않는다(부서관리자도 role=admin이므로). 최고관리자 여부는 isSuperAdmin,
// 담당 부서는 /departments의 manager.userId와 로그인 userId 비교로 구한다.

/** 담당 부서 ID를 단일 부서 공개 scopeKey(`D{id}`)로 바꾼다. 담당이 없으면 null. */
export function managedDepartmentScopeKey(managedDepartmentId) {
  if (managedDepartmentId === null || managedDepartmentId === undefined || managedDepartmentId === '') {
    return null
  }
  return `D${managedDepartmentId}`
}

/**
 * 현재 Wiki에서 AI 수정 대화 패널을 보여줄 수 있는지 판단한다.
 * - isSuperAdmin=true면 scopeKey와 무관하게 표시(최고관리자는 모든 Wiki 관리 가능).
 * - 그 외에는 담당 부서 단일 scope와 scopeKey가 정확히 일치할 때만 표시.
 *   scopeKey가 아직 없으면(상세 로딩 전) 숨긴다.
 */
export function canUseWikiAgentChat({ isSuperAdmin, managedDepartmentId, scopeKey }) {
  if (isSuperAdmin === true) {
    return true
  }
  const managedKey = managedDepartmentScopeKey(managedDepartmentId)
  return managedKey !== null && scopeKey === managedKey
}
