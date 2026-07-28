import { useCallback, useEffect, useMemo, useState } from 'react'
import { AuthContext } from './AuthContext'
import { login as loginRequest } from '@/api/auth'
import { UNAUTHORIZED_EVENT } from '@/api/client'
import {
  getToken,
  getStoredUser,
  setToken,
  setStoredUser,
  clearAuth,
} from '@/lib/authStorage'

export default function AuthProvider({ children }) {
  // 새로고침 시 localStorage에서 동기적으로 상태를 복원한다.
  const [user, setUser] = useState(() => getStoredUser())
  const [token, setTokenState] = useState(() => getToken())

  const logout = useCallback(() => {
    clearAuth()
    setTokenState(null)
    setUser(null)
  }, [])

  const login = useCallback(async ({ email, password }) => {
    const data = await loginRequest({ email, password })
    setToken(data.accessToken)
    setStoredUser(data.user)
    setTokenState(data.accessToken)
    setUser(data.user)
    return data.user
  }, [])

  // axios 인터셉터가 401을 감지하면 전역 이벤트로 알린다. 여기서 로그아웃 처리.
  useEffect(() => {
    const handleUnauthorized = () => {
      setTokenState(null)
      setUser(null)
    }
    window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
    return () =>
      window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  }, [])

  const value = useMemo(
    () => ({
      user,
      role: user?.role ?? null,
      isAuthenticated: Boolean(token),
      login,
      logout,
    }),
    [user, token, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
