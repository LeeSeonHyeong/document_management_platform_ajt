import { NavLink } from 'react-router-dom'
import { Sparkles } from 'lucide-react'
import { cn } from '@/shared/lib/cn'
import { ROLES } from '@/shared/constants/enums'
import { NAV_ITEMS, ADMIN_FOOTER_ITEMS } from './navConfig'

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
            ? 'bg-primary-50 text-primary-700'
            : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
        )
      }
    >
      <Icon className="size-5 shrink-0" />
      {item.label}
    </NavLink>
  )
}

export default function Sidebar({ role }) {
  const items = NAV_ITEMS[role] ?? NAV_ITEMS[ROLES.EMPLOYEE]
  const isAdmin = role === ROLES.ADMIN

  return (
    <aside className="flex h-full w-60 shrink-0 flex-col border-r border-slate-200 bg-white">
      <div className="flex items-center gap-2 px-5 py-5">
        <span className="flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 text-white">
          <Sparkles className="size-5" />
        </span>
        <div className="leading-tight">
          <p className="text-sm font-bold text-slate-800">AJT</p>
          <p className="text-[11px] text-slate-400">Enterprise AI Workspace</p>
        </div>
      </div>

      <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-2">
        {items.map((item) => (
          <NavItem key={item.to} item={item} />
        ))}
      </nav>

      {isAdmin && (
        <div className="space-y-1 border-t border-slate-100 px-3 py-3">
          {ADMIN_FOOTER_ITEMS.map((item) => (
            <NavItem key={item.to} item={item} />
          ))}
        </div>
      )}
    </aside>
  )
}
