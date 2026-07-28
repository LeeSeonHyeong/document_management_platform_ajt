import { Link, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { ROLES } from '@/constants/roles'

// 로그인 이후 공통 레이아웃. 상단 내비 + 역할별 메뉴 노출 + 로그아웃.
export default function AppLayout() {
  const { user, role, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-screen bg-gray-50 text-gray-900">
      <header className="border-b bg-white">
        <nav className="mx-auto flex max-w-5xl items-center gap-4 px-4 py-3">
          <Link to="/" className="font-semibold">
            AJT
          </Link>
          <Link to="/" className="text-sm text-gray-600 hover:text-gray-900">
            대시보드
          </Link>
          {role === ROLES.ADMIN && (
            <Link
              to="/admin"
              className="text-sm text-gray-600 hover:text-gray-900"
            >
              관리자
            </Link>
          )}
          <div className="ml-auto flex items-center gap-3 text-sm">
            <span className="text-gray-500">
              {user?.name} ({role})
            </span>
            <button
              type="button"
              onClick={handleLogout}
              className="rounded border px-3 py-1 hover:bg-gray-100"
            >
              로그아웃
            </button>
          </div>
        </nav>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
