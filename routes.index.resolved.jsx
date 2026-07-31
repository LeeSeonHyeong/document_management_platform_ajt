import { createBrowserRouter } from 'react-router-dom'
import { ROLES } from '@/constants/roles'
import AppShell from '@/components/layout/AppShell'
import AuthLayout from '@/components/layout/AuthLayout'
import ProtectedRoute from './guards/ProtectedRoute'
import RoleRoute from './guards/RoleRoute'
import GuestRoute from './guards/GuestRoute'
import LoginPage from '@/pages/auth/LoginPage'
import SignupPage from '@/pages/auth/SignupPage'
import PasswordFindPage from '@/pages/auth/PasswordFindPage'
import PasswordResetPage from '@/pages/auth/PasswordResetPage'
import HomePage from '@/pages/home/HomePage'
import AdminPage from '@/pages/AdminPage'
import AdminSchedulePage from '@/pages/admin/AdminSchedulePage'
import ForbiddenPage from '@/pages/ForbiddenPage'
import NotFoundPage from '@/pages/NotFoundPage'
import EmployeeListPage from '@/features/member/pages/EmployeeListPage'
import EmployeeDetailPage from '@/features/member/pages/EmployeeDetailPage'
import EmployeeEditPage from '@/features/member/pages/EmployeeEditPage'
import SignupRequestsPage from '@/features/member/pages/SignupRequestsPage'
import DepartmentManagementPage from '@/features/department/pages/DepartmentManagementPage'
import InquiryManagementPage from '@/features/inquiry/pages/InquiryManagementPage'
import InquiryDetailPage from '@/features/inquiry/pages/InquiryDetailPage'
import EmployeeInquiriesPage from '@/features/inquiry/pages/EmployeeInquiriesPage'
import CreateInquiryPage from '@/features/inquiry/pages/CreateInquiryPage'
import MyProfilePage from '@/features/me/pages/MyProfilePage'

// 라우트 레벨 접근 제어:
//  - GuestRoute:     비로그인 전용(로그인 화면)
//  - ProtectedRoute: 로그인 필요(모든 인증 사용자)
//  - RoleRoute:      특정 역할 필요(예: ADMIN 전용)
export const router = createBrowserRouter([
  {
    element: <GuestRoute />,
    children: [
      {
        element: <AuthLayout />,
        children: [
          { path: '/login', element: <LoginPage /> },
          { path: '/signup', element: <SignupPage /> },
          { path: '/password/find', element: <PasswordFindPage /> },
          { path: '/password/reset', element: <PasswordResetPage /> },
        ],
      },
    ],
  },
  {
    element: <ProtectedRoute />,
    children: [
      {
        element: <AppShell />,
        children: [
          { index: true, element: <HomePage /> },
          { path: 'me', element: <MyProfilePage /> },
          {
            element: <RoleRoute allowedRoles={[ROLES.EMPLOYEE]} />,
            children: [
              { path: 'inquiries', element: <EmployeeInquiriesPage /> },
              { path: 'inquiries/new', element: <CreateInquiryPage /> },
            ],
          },
          {
            element: <RoleRoute allowedRoles={[ROLES.ADMIN]} />,
            children: [
              { path: 'admin', element: <AdminPage /> },
              { path: 'admin/users', element: <EmployeeListPage /> },
              { path: 'admin/users/:userId', element: <EmployeeDetailPage /> },
              { path: 'admin/users/:userId/edit', element: <EmployeeEditPage /> },
              { path: 'admin/signup-requests', element: <SignupRequestsPage /> },
              { path: 'admin/departments', element: <DepartmentManagementPage /> },
              { path: 'admin/inquiries', element: <InquiryManagementPage /> },
              { path: 'admin/inquiries/:inquiryId', element: <InquiryDetailPage /> },
              { path: 'admin/schedules', element: <AdminSchedulePage /> },
            ],
          },
        ],
      },
    ],
  },
  { path: '/403', element: <ForbiddenPage /> },
  { path: '*', element: <NotFoundPage /> },
])
