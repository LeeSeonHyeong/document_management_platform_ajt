import { useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { useAuth } from '@/hooks/useAuth'
import { ROLE_LABELS } from '@/shared/constants/enums'
import Avatar from '@/components/ui/Avatar'
import Breadcrumb from './Breadcrumb'

// 상단 헤더. 좌측 브레드크럼 + 우측 프로필/로그아웃.
export default function TopBar() {
  const { user, role, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <header className="flex h-16 shrink-0 items-center justify-between border-b border-slate-200 bg-white px-6">
      <Breadcrumb />
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2.5">
          <Avatar name={user?.name} size="sm" />
          <div className="text-right leading-tight">
            <p className="text-sm font-medium text-slate-800">{user?.name}</p>
            <p className="text-xs text-slate-400">
              {ROLE_LABELS[role] ?? role}
              {user?.department?.name ? ` · ${user.department.name}` : ''}
            </p>
          </div>
        </div>
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
