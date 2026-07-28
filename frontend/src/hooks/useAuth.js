import { useContext } from 'react'
import { AuthContext } from '@/context/AuthContext'

// 인증 컨텍스트 접근 훅. AuthProvider 밖에서 사용하면 즉시 오류로 알린다.
export function useAuth() {
  const ctx = useContext(AuthContext)
  if (ctx === null) {
    throw new Error('useAuth는 AuthProvider 내부에서만 사용할 수 있습니다.')
  }
  return ctx
}
