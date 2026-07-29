import { describe, it, expect } from 'vitest'
import { filterByDepartmentTab } from './adminFilters'

const events = [
  { id: '1', visibilityType: 'all', departmentIds: [] },
  { id: '2', visibilityType: 'department', departmentIds: ['1'] },
  { id: '3', visibilityType: 'department', departmentIds: ['2'] },
  { id: '4', visibilityType: 'department', departmentIds: ['1', '2'] },
]

describe('filterByDepartmentTab', () => {
  it('부서 미지정(전체 탭)이면 모두 통과', () => {
    expect(filterByDepartmentTab(events, null)).toHaveLength(4)
  })

  it('특정 부서 탭이면 전체 공개 + 해당 부서 대상만 통과', () => {
    const result = filterByDepartmentTab(events, '1').map((e) => e.id)
    expect(result).toEqual(['1', '2', '4']) // all + dept1 포함
  })

  it('부서2 탭', () => {
    const result = filterByDepartmentTab(events, '2').map((e) => e.id)
    expect(result).toEqual(['1', '3', '4'])
  })
})
