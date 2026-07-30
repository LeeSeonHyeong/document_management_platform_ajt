import { useLocation, useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { useAuth } from '@/hooks/useAuth'
import { ROLE_LABELS } from '@/shared/constants/enums'
import Avatar from '@/components/ui/Avatar'
import Breadcrumb from './Breadcrumb'
import { getPageEyebrow, getPageTitle } from './navConfig'

// 상단 헤더. 좌측 브레드크럼 + 우측 프로필/로그아웃.
export default function TopBar() {
  const { user, role, logout } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const pageEyebrow = getPageEyebrow(pathname)
  const titleOnly = pathname === '/'

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <header className="flex h-[76px] shrink-0 items-center justify-between border-b border-slate-200 bg-white px-6">
      <div>
        {titleOnly ? null : pageEyebrow ? (
          <p className="text-xs font-medium text-slate-400">
            <span className="text-primary-600">AJT</span>
            {pageEyebrow.slice(3)}
          </p>
        ) : (
          <Breadcrumb />
        )}
        <h1 className={`${titleOnly ? '' : 'mt-1'} text-2xl font-bold tracking-tight text-slate-900`}>
          {getPageTitle(pathname)}
        </h1>
      </div>
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={() => navigate('/me')}
          className="focus-ring flex items-center gap-2.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-left transition hover:border-primary-300 hover:bg-primary-50/40"
          aria-label="내 정보로 이동"
        >
          <Avatar name={user?.name} size="sm" />
          <div className="text-right leading-tight">
            <p className="text-sm font-medium text-slate-800">{user?.name}</p>
            <p className="text-xs text-slate-400">
              {ROLE_LABELS[role] ?? role}
              {user?.department?.name ? ` · ${user.department.name}` : ''}
            </p>
          </div>
        </button>
        <button
          type="button"
          onClick={handleLogout}
          className="focus-ring flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50"
        >
          <LogOut className="size-4" />
          로그아웃
        </button>
      </div>
    </header>
  )
}
