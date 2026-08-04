import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { cn } from '@/shared/lib/cn'
import { ROLES } from '@/shared/constants/enums'
import { useAuth } from '@/hooks/useAuth'
import { NAV_ITEMS } from './navConfig'
import ajtLogo from '@/assets/ajt-logo.png'

// 좌측 고정 사이드바. 역할에 따라 메뉴가 달라진다.
//
// **접힌 모드(`collapsed`)** — 화면이 자기 열을 여러 개 요구하는 페이지(위키)에서 쓴다.
// 이 사이드바는 *모듈 전환기*(직원·부서·문의·문서·일정·위키)라서, 이미 그 모듈 안에
// 들어온 사람에게 6개 메뉴를 240px 펼쳐 둘 값이 낮다. 접으면 56px 만 쓰고 184px 를
// 본문에 돌려준다.
//
// 접힌 상태에서도 다른 모듈로 갈 수 있어야 하므로 hover·키보드 포커스에서 라벨이 펼쳐진다.
// 펼침은 **레이아웃을 밀지 않는다** — 폭을 늘리면 옆 열이 같이 줄어들어 본문이 흔들린다.
// 그래서 겉껍데기는 56px 로 고정하고 안쪽 패널만 절대 위치로 덮는다.
function NavItem({ item, collapsed }) {
  const Icon = item.icon
  const { pathname } = useLocation()
  const isRelatedPath = item.activePaths?.some(
    (path) => pathname === path || pathname.startsWith(`${path}/`),
  )
  return (
    <NavLink
      to={item.to}
      end={item.end}
      title={collapsed ? item.label : undefined}
      className={({ isActive }) =>
        cn(
          'focus-ring flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
          isActive || isRelatedPath
            ? 'bg-white/20 text-white'
            : 'text-white/75 hover:bg-white/10 hover:text-white',
        )
      }
    >
      <Icon className="size-5 shrink-0" />
      <span
        className={cn(
          'truncate transition-opacity',
          collapsed && 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100',
        )}
      >
        {item.label}
      </span>
    </NavLink>
  )
}

export default function Sidebar({ role, collapsed = false }) {
  const navigate = useNavigate()
  const { logout } = useAuth()
  const items = NAV_ITEMS[role] ?? NAV_ITEMS[ROLES.EMPLOYEE]

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  const panel = (
    <>
      <div className={cn('flex items-center gap-2 py-5', collapsed ? 'px-3.5' : 'px-5')}>
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white">
          <img src={ajtLogo} alt="" className="size-8 object-contain" />
        </span>
        <div
          className={cn(
            'leading-tight whitespace-nowrap transition-opacity',
            collapsed && 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100',
          )}
        >
          <p className="text-sm font-bold text-white">AUTO Janitor Tool</p>
          <p className="text-[11px] text-white/65">Enterprise AI Workspace</p>
        </div>
      </div>

      <nav className="flex-1 space-y-1 overflow-y-auto overflow-x-hidden px-3 py-2">
        {items.map((item) => (
          <NavItem key={item.to} item={item} collapsed={collapsed} />
        ))}
      </nav>

      <div className="px-3 pb-4">
        <button
          type="button"
          onClick={handleLogout}
          title={collapsed ? '로그아웃' : undefined}
          className="focus-ring flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-white/75 transition-colors hover:bg-white/10 hover:text-white"
        >
          <LogOut className="size-5 shrink-0" />
          <span
            className={cn(
              'whitespace-nowrap transition-opacity',
              collapsed && 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100',
            )}
          >
            로그아웃
          </span>
        </button>
      </div>
    </>
  )

  if (!collapsed) {
    return (
      <aside className="flex h-full w-60 shrink-0 flex-col bg-gradient-to-b from-blue-600 via-indigo-600 to-violet-600">
        {panel}
      </aside>
    )
  }

  return (
    <div className="group relative h-full w-14 shrink-0">
      <aside className="absolute inset-y-0 left-0 z-30 flex w-14 flex-col overflow-hidden bg-gradient-to-b from-blue-600 via-indigo-600 to-violet-600 transition-[width] duration-200 group-hover:w-60 group-hover:shadow-xl group-focus-within:w-60 group-focus-within:shadow-xl">
        {panel}
      </aside>
    </div>
  )
}
