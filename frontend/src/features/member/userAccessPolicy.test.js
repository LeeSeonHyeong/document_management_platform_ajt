import { describe, it, expect } from 'vitest'
import { canManageUserAccounts } from './userAccessPolicy'

describe('사용자 상세·수정 접근 정책(S15P11B106-222)', () => {
  it('최고관리자(isSuperAdmin=true)는 상세·수정 진입이 허용된다', () => {
    expect(canManageUserAccounts(true)).toBe(true)
  })

  it('부서관리자(isSuperAdmin=false)는 상세·수정 진입이 막힌다 — 목록만 볼 수 있다', () => {
    expect(canManageUserAccounts(false)).toBe(false)
  })

  it('isSuperAdmin이 없으면(로딩 전/미인증) 막는다', () => {
    expect(canManageUserAccounts(undefined)).toBe(false)
    expect(canManageUserAccounts(null)).toBe(false)
  })

  it("role이 admin이어도 isSuperAdmin이 아니면 막는다 — role이 아닌 isSuperAdmin으로 판단한다", () => {
    // 부서관리자도 role은 admin이라, role 기반으로 열면 안 된다.
    expect(canManageUserAccounts('admin')).toBe(false)
    expect(canManageUserAccounts(1)).toBe(false)
  })
})
