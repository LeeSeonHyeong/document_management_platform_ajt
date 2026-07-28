import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import FullscreenLoader from '@/components/common/FullscreenLoader'

// 비로그인 전용(로그인 화면 등). 이미 로그인했다면 대시보드로 돌려보낸다.
// 부팅 중에는 로더를 보여줘 이미 로그인한 사용자가 로그인 화면을 깜빡이지 않게 한다.
export default function GuestRoute() {
  const { isAuthenticated, initializing } = useAuth()

  if (initializing) {
    return <FullscreenLoader />
  }
  if (isAuthenticated) {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
