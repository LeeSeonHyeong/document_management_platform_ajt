import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import FullscreenLoader from '@/components/common/FullscreenLoader'

// 최고관리자(super-admin) 전용 라우트. 부서관리자·사원은 403으로 보낸다.
// 최고관리자 여부는 role이 아니라 로그인/내 정보 응답의 isSuperAdmin으로 판단한다(S15P11B106-83).
// 가입 신청 조회/승인/거절 화면(/admin/signup-requests)에 사용한다.
export default function SuperAdminRoute() {
  const { isAuthenticated, isSuperAdmin, initializing } = useAuth()

  if (initializing) {
    return <FullscreenLoader />
  }
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }
  if (!isSuperAdmin) {
    return <Navigate to="/403" replace />
  }
  return <Outlet />
}
