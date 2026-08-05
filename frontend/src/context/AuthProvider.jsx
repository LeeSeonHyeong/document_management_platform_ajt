import { useCallback, useEffect, useMemo, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { AuthContext } from './AuthContext'
import {
  login as loginRequest,
  logout as logoutRequest,
  fetchMe,
} from '@/api/auth'
import { UNAUTHORIZED_EVENT } from '@/api/client'
import { getStoredUser, setStoredUser, clearStoredUser } from '@/lib/authStorage'

// 쿠키 기반 인증.
//  - 부팅 시 GET /me로 실제 로그인 여부를 확정한다(initializing 동안 가드는 대기).
//  - 캐시된 user는 첫 화면 깜빡임을 줄이기 위한 낙관적 값일 뿐, /me 결과로 재조정된다.
export default function AuthProvider({ children }) {
  const queryClient = useQueryClient()
  const [user, setUser] = useState(() => getStoredUser())
  const [initializing, setInitializing] = useState(true)

  const applyUser = useCallback((nextUser) => {
    setUser(nextUser)
    setStoredUser(nextUser)
  }, [])

  const clearUser = useCallback(() => {
    setUser(null)
    clearStoredUser()
  }, [])

  // 부팅 시 1회 /me 호출로 세션 복원. 401이면 비로그인으로 확정(리다이렉트 이벤트는 억제).
  useEffect(() => {
    let active = true
    fetchMe({ silent: true })
      .then((me) => {
        if (active) applyUser(me)
      })
      .catch(() => {
        if (active) clearUser()
      })
      .finally(() => {
        if (active) setInitializing(false)
      })
    return () => {
      active = false
    }
  }, [applyUser, clearUser])

  const login = useCallback(
    async ({ email, password }) => {
      const data = await loginRequest({ email, password })
      // 다른 계정으로 바로 갈아탈 때 이전 사용자의 캐시가 새 사용자에게 노출되지 않도록 초기화한다.
      queryClient.clear()
      applyUser(data.user)
      return data.user
    },
    [applyUser, queryClient],
  )

  const logout = useCallback(async () => {
    try {
      await logoutRequest()
    } finally {
      clearUser()
      // 로그아웃 후 남은 서버 상태 캐시를 제거해 다음 로그인 사용자에게 흔적이 남지 않게 한다.
      queryClient.clear()
    }
  }, [clearUser, queryClient])

  // axios 인터셉터가 401을 감지하면 전역 이벤트로 알린다. 여기서 로그아웃 상태로 전환.
  useEffect(() => {
    const handleUnauthorized = () => clearUser()
    window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  }, [clearUser])

  const value = useMemo(
    () => ({
      user,
      role: user?.role ?? null,
      // 최고관리자 여부(S15P11B106-83). 사용자 관리는 role=admin 전체가 가능하지만,
      // 가입 신청 조회/승인/거절은 이 값이 true인 최고관리자만 가능하다.
      isSuperAdmin: Boolean(user?.isSuperAdmin),
      isAuthenticated: Boolean(user),
      initializing,
      login,
      logout,
    }),
    [user, initializing, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
