import { Navigate } from 'react-router-dom'
import { ROLES } from '@/constants/roles'
import { useAuth } from '@/hooks/useAuth'
import HomePage from '@/pages/home/HomePage'

// 기본 경로(/)는 역할에 따라 다르게 처리한다.
// 관리자는 사원용 홈을 거치지 않고 직원 관리로, 사원은 기존 홈으로 진입한다.
export default function RoleHomePage() {
  const { role } = useAuth()
  return role === ROLES.ADMIN
    ? <Navigate to="/admin/users" replace />
    : <HomePage />
}
