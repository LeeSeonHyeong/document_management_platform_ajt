import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import FullscreenLoader from '@/components/common/FullscreenLoader'

// 지정한 역할을 가진 사용자만 통과.
// 비로그인은 로그인으로, 권한 부족은 403 화면으로 보낸다.
// 사용 예: <Route element={<RoleRoute allowedRoles={[ROLES.ADMIN]} />}>
export default function RoleRoute({ allowedRoles = [] }) {
  const { isAuthenticated, role, initializing } = useAuth()

  if (initializing) {
    return <FullscreenLoader />
  }
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }
  if (!allowedRoles.includes(role)) {
    return <Navigate to="/403" replace />
  }
  return <Outlet />
}
