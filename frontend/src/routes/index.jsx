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
import AdminPage from '@/pages/AdminPage'
import AdminSchedulePage from '@/pages/admin/AdminSchedulePage'
import ForbiddenPage from '@/pages/ForbiddenPage'
import NotFoundPage from '@/pages/NotFoundPage'
import DocumentListPage from '@/features/document/pages/DocumentListPage'
import SourceDocumentListPage from '@/features/document/pages/SourceDocumentListPage'
import SourceDocumentDetailPage from '@/features/document/pages/SourceDocumentDetailPage'
import AiJobQueuePage from '@/features/document/pages/AiJobQueuePage'
import AiJobProgressPage from '@/features/document/pages/AiJobProgressPage'
import AiJobSummaryPage from '@/features/document/pages/AiJobSummaryPage'
import AiJobSummaryListPage from '@/features/document/pages/AiJobSummaryListPage'
import DocumentCategoryPage from '@/features/document/pages/DocumentCategoryPage'
import WikiPage from '@/features/wiki/pages/WikiPage'
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
import RoleHomePage from './RoleHomePage'

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
          { index: true, element: <RoleHomePage /> },
          // Wiki 열람은 관리자·사원 공통(6R/S2). ADMIN 전용 블록 밖에 둔다.
          { path: 'wiki', element: <WikiPage /> },
          { path: 'wiki/:wikiId', element: <WikiPage /> },
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
              { path: 'admin/documents', element: <DocumentListPage /> },
              { path: 'admin/documents/source', element: <SourceDocumentListPage /> },
              { path: 'admin/documents/source/:documentId', element: <SourceDocumentDetailPage /> },
              { path: 'admin/documents/jobs/:jobId', element: <AiJobQueuePage /> },
              { path: 'admin/documents/jobs/:jobId/progress', element: <AiJobProgressPage /> },
              { path: 'admin/documents/jobs/:jobId/summary', element: <AiJobSummaryPage /> },
              { path: 'admin/documents/summaries', element: <AiJobSummaryListPage /> },
              { path: 'admin/documents/categories', element: <DocumentCategoryPage /> },
            ],
          },
        ],
      },
    ],
  },
  { path: '/403', element: <ForbiddenPage /> },
  { path: '*', element: <NotFoundPage /> },
])
