// 시스템 기본 부서('전체')는 항상 존재하며 이름 변경·삭제가 금지된다(S15P11B106-146).
// 백엔드 Department.DEFAULT_NAME과 값이 일치해야 한다.
export const DEFAULT_DEPARTMENT_NAME = '전체'

/** 기본 부서('전체') 여부. */
export function isDefaultDepartment(department) {
  return department?.name === DEFAULT_DEPARTMENT_NAME
}

/** 기본 부서는 삭제할 수 없다(화면에서 삭제 버튼을 숨기는 기준). */
export function canDeleteDepartment(department) {
  return !isDefaultDepartment(department)
}
