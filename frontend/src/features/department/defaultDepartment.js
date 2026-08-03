// 시스템 기본 부서는 항상 존재하며 이름 변경·삭제가 금지된다(S15P11B106-146).
// 백엔드 Department.DEFAULT_NAME과 값이 일치해야 한다.
//
// '전체'였다가 '미지정'으로 바꿨다(S15P11B106-204). 화면의 다른 두 '전체'와 충돌했다 —
// 일정 탭의 필터, 공개 범위의 '전체 공개'. 부서 하나가 그 둘과 같은 이름이라 '부서 공개 +
// 전체'를 전사 공개로 오해하기 쉬웠다. 이 부서는 공개 범위와 아무 관계가 없다.
export const DEFAULT_DEPARTMENT_NAME = '미지정'

/** 기본 부서 여부. */
export function isDefaultDepartment(department) {
  return department?.name === DEFAULT_DEPARTMENT_NAME
}

/** 기본 부서는 삭제할 수 없다(화면에서 삭제 버튼을 숨기는 기준). */
export function canDeleteDepartment(department) {
  return !isDefaultDepartment(department)
}
