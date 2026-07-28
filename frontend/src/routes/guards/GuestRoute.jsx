import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'

// 비로그인 전용(로그인 화면 등). 이미 로그인했다면 대시보드로 돌려보낸다.
export default function GuestRoute() {
  const { isAuthenticated } = useAuth()

  if (isAuthenticated) {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
