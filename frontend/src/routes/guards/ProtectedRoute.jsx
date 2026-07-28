import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import FullscreenLoader from '@/components/common/FullscreenLoader'

// 로그인한 사용자만 통과. 비로그인 시 로그인 화면으로 보내고 원래 목적지를 state로 넘긴다.
// 부팅 중(GET /me 확인 전)에는 성급히 리다이렉트하지 않고 로더를 보여준다.
export default function ProtectedRoute() {
  const { isAuthenticated, initializing } = useAuth()
  const location = useLocation()

  if (initializing) {
    return <FullscreenLoader />
  }
  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <Outlet />
}
