import { describe, it, expect } from 'vitest'
import { canUseWikiAgentChat, managedDepartmentScopeKey } from './agentChatAccess'

describe('Wiki AI 수정 대화 패널 노출 권한(S15P11B106-229 후속)', () => {
  it('최고관리자는 ALL Wiki에서도 패널을 표시한다', () => {
    expect(canUseWikiAgentChat({ isSuperAdmin: true, managedDepartmentId: null, scopeKey: 'ALL' })).toBe(true)
  })

  it('최고관리자는 상세 로딩 전(scopeKey 없음)에도 표시 가능하다', () => {
    expect(canUseWikiAgentChat({ isSuperAdmin: true, managedDepartmentId: null, scopeKey: undefined })).toBe(true)
  })

  it('부서관리자는 담당 부서 단일 scope(D2) Wiki에서 패널을 표시한다', () => {
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: 2, scopeKey: 'D2' })).toBe(true)
    // departmentId가 문자열이어도 동일하게 동작한다(/departments 응답이 문자열 ID일 수 있음).
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: '2', scopeKey: 'D2' })).toBe(true)
  })

  it('부서관리자는 ALL Wiki에서 패널을 숨긴다', () => {
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: 2, scopeKey: 'ALL' })).toBe(false)
  })

  it('부서관리자는 복수부서(D1-D2) Wiki에서 패널을 숨긴다', () => {
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: 2, scopeKey: 'D1-D2' })).toBe(false)
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: 2, scopeKey: 'D2-D3' })).toBe(false)
  })

  it('부서관리자는 타부서 단일 scope(D3) Wiki에서 패널을 숨긴다', () => {
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: 2, scopeKey: 'D3' })).toBe(false)
  })

  it('부서관리자도 상세 로딩 전(scopeKey 없음)에는 패널을 숨긴다', () => {
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: 2, scopeKey: undefined })).toBe(false)
  })

  it('담당 부서가 없는 관리자(managedDepartmentId 없음)는 패널을 숨긴다', () => {
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: null, scopeKey: 'D2' })).toBe(false)
  })

  it('사원(최고관리자 아님·담당 부서 없음)은 항상 숨긴다', () => {
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: null, scopeKey: 'ALL' })).toBe(false)
    expect(canUseWikiAgentChat({ isSuperAdmin: false, managedDepartmentId: null, scopeKey: 'D2' })).toBe(false)
  })

  it('managedDepartmentScopeKey는 담당 부서 ID를 D{id}로 바꾸고, 없으면 null이다', () => {
    expect(managedDepartmentScopeKey(2)).toBe('D2')
    expect(managedDepartmentScopeKey('7')).toBe('D7')
    expect(managedDepartmentScopeKey(null)).toBeNull()
    expect(managedDepartmentScopeKey(undefined)).toBeNull()
    expect(managedDepartmentScopeKey('')).toBeNull()
  })
})
