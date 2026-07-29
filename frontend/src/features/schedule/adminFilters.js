import { SCHEDULE_VISIBILITY } from '@/shared/constants/enums'

// 관리자 일정 화면의 부서 탭 필터.
// deptId가 없으면(전체 탭) 모두 통과. 특정 부서 탭이면 전체 공개 + 그 부서 대상 일정만 통과.
export function filterByDepartmentTab(events, deptId) {
  if (!deptId) return events
  return events.filter(
    (e) =>
      e.visibilityType === SCHEDULE_VISIBILITY.ALL ||
      (e.visibilityType === SCHEDULE_VISIBILITY.DEPARTMENT &&
        (e.departmentIds ?? []).includes(deptId)),
  )
}
