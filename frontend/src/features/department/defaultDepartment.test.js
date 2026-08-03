import { describe, it, expect } from 'vitest'
import {
  DEFAULT_DEPARTMENT_NAME,
  isDefaultDepartment,
  canDeleteDepartment,
} from './defaultDepartment'

describe('기본 부서 판별(S15P11B106-146)', () => {
  // '전체'였다가 '미지정'으로 바꿨다(S15P11B106-204) — 화면의 다른 두 '전체'와 충돌했다.
  it("기본 부서명은 '미지정'이다", () => {
    expect(DEFAULT_DEPARTMENT_NAME).toBe('미지정')
  })

  it("예전 이름 '전체'는 더 이상 기본 부서가 아니다 — 개명은 백엔드 기동이 처리한다", () => {
    expect(isDefaultDepartment({ departmentId: '1', name: '전체' })).toBe(false)
  })

  it("이름이 '미지정'인 부서는 기본 부서이고 삭제 버튼을 노출하지 않는다", () => {
    const dept = { departmentId: '1', name: '미지정' }
    expect(isDefaultDepartment(dept)).toBe(true)
    expect(canDeleteDepartment(dept)).toBe(false)
  })

  it('일반 부서는 기본 부서가 아니며 삭제할 수 있다', () => {
    const dept = { departmentId: '2', name: '개발부' }
    expect(isDefaultDepartment(dept)).toBe(false)
    expect(canDeleteDepartment(dept)).toBe(true)
  })

  it('null/undefined는 기본 부서가 아니다', () => {
    expect(isDefaultDepartment(null)).toBe(false)
    expect(isDefaultDepartment(undefined)).toBe(false)
  })
})
