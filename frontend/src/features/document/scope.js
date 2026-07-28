// 공개 범위 선택 → scopeKey 문자열.
// 서버가 최종 scopeKey를 계산하지만(업로드 응답으로 되돌려줌), 카테고리 목록을
// scopeKey별로 조회해야 해서 클라이언트도 같은 규칙으로 파생값을 만든다.
// 규칙(FR-DOC-002): 부서 ID 중복 제거 후 오름차순 정렬, "D1-D2" 형태. 전체 공개는 'ALL'.
export function buildScopeKey(visibilityType, departmentIds = []) {
  if (visibilityType === 'all') return 'ALL'
  const ids = [...new Set(departmentIds.map(String))].sort((a, b) => Number(a) - Number(b))
  return ids.length ? ids.map((id) => `D${id}`).join('-') : ''
}
