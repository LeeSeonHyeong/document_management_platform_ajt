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

/**
 * 공개 범위 대상으로 고를 수 있는 부서만 남긴다(S15P11B106-208).
 *
 * 기본 부서는 소속을 아직 정하지 않은 사람이 들어가는 자리이지 문서·일정·Wiki를 공개할
 * 대상이 아니다. 공개 범위를 고르는 화면에 뜨면 '전체 공개'와 같은 층으로 읽힌다.
 * 부서 관리·회원가입·직원 소속 변경에는 그대로 보여야 하므로, 거르는 책임은 공개 범위
 * 화면들이 함께 쓰는 useDepartments 한 곳에만 둔다.
 */
export function excludeDefaultDepartment(departments) {
  return (departments ?? []).filter((department) => !isDefaultDepartment(department))
}
