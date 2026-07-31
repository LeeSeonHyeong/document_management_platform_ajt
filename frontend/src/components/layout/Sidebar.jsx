import { NavLink, useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { cn } from '@/shared/lib/cn'
import { ROLES } from '@/shared/constants/enums'
import { useAuth } from '@/hooks/useAuth'
import { NAV_ITEMS } from './navConfig'
import ajtLogo from '@/assets/ajt-logo.png'

// 좌측 고정 사이드바. 역할에 따라 메뉴가 달라진다.
function NavItem({ item }) {
  const Icon = item.icon
  return (
    <NavLink
      to={item.to}
      end={item.end}
      className={({ isActive }) =>
        cn(
          'focus-ring flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
          isActive
            ? 'bg-white/20 text-white'
            : 'text-white/75 hover:bg-white/10 hover:text-white',
        )
      }
    >
      <Icon className="size-5 shrink-0" />
      {item.label}
    </NavLink>
  )
}

export default function Sidebar({ role }) {
  const navigate = useNavigate()
  const { logout } = useAuth()
  const items = NAV_ITEMS[role] ?? NAV_ITEMS[ROLES.EMPLOYEE]

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <aside className="flex h-full w-60 shrink-0 flex-col bg-gradient-to-b from-blue-600 via-indigo-600 to-violet-600">
      <div className="flex items-center gap-2 px-5 py-5">
        <span className="flex size-9 items-center justify-center rounded-xl bg-white">
          <img src={ajtLogo} alt="" className="size-8 object-contain" />
        </span>
        <div className="leading-tight">
          <p className="text-sm font-bold text-white">AUTO Janitor Tool</p>
          <p className="text-[11px] text-white/65">Enterprise AI Workspace</p>
        </div>
      </div>

      <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-2">
        {items.map((item) => (
          <NavItem key={item.to} item={item} />
        ))}
      </nav>

      <div className="px-3 pb-4">
        <button
          type="button"
          onClick={handleLogout}
          className="focus-ring flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-white/75 transition-colors hover:bg-white/10 hover:text-white"
        >
          <LogOut className="size-5 shrink-0" />
          로그아웃
        </button>
      </div>
    </aside>
  )
}
