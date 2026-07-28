import { createBrowserRouter } from 'react-router-dom'
import { ROLES } from '@/constants/roles'
import AppLayout from '@/components/layout/AppLayout'
import ProtectedRoute from './guards/ProtectedRoute'
import RoleRoute from './guards/RoleRoute'
import GuestRoute from './guards/GuestRoute'
import LoginPage from '@/pages/LoginPage'
import DashboardPage from '@/pages/DashboardPage'
import AdminPage from '@/pages/AdminPage'
import ForbiddenPage from '@/pages/ForbiddenPage'
import NotFoundPage from '@/pages/NotFoundPage'

// 라우트 레벨 접근 제어:
//  - GuestRoute:     비로그인 전용(로그인 화면)
//  - ProtectedRoute: 로그인 필요(모든 인증 사용자)
//  - RoleRoute:      특정 역할 필요(예: ADMIN 전용)
export const router = createBrowserRouter([
  {
    element: <GuestRoute />,
    children: [{ path: '/login', element: <LoginPage /> }],
  },
  {
    element: <ProtectedRoute />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <DashboardPage /> },
          {
            element: <RoleRoute allowedRoles={[ROLES.ADMIN]} />,
            children: [{ path: 'admin', element: <AdminPage /> }],
          },
        ],
      },
    ],
  },
  { path: '/403', element: <ForbiddenPage /> },
  { path: '*', element: <NotFoundPage /> },
])
