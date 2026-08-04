import { useEffect, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { LogOut, PanelLeftClose, PanelLeftOpen, X } from 'lucide-react'
import { cn } from '@/shared/lib/cn'
import { ROLES } from '@/shared/constants/enums'
import { useAuth } from '@/hooks/useAuth'
import { NAV_ITEMS } from './navConfig'
import ajtLogo from '@/assets/ajt-logo.png'

// 펼침/접힘 선택을 저장하는 공용 키.
const STORAGE_KEY = 'sidebar-expanded'

// 좌측 사이드바. 역할에 따라 메뉴가 달라진다.
//
// **반응형(S15P11B106-234).**
//   - `md` 이상: 좌측 고정 열. 접기 가능(`collapsible`)이면 사용자가 토글해 아이콘 레일(56px)↔펼침(240px).
//   - `md` 미만: 고정 열을 숨기고, 상단바 햄버거로 여는 오프캔버스 드로어로 표시한다(본문을 밀지 않음).
//     드로어는 항상 펼친 모습이며 바깥/Esc/메뉴 선택 시 닫힌다.
function NavItem({ item, collapsed, onNavigate }) {
  const Icon = item.icon
  const { pathname } = useLocation()
  const isRelatedPath = item.activePaths?.some(
    (path) => pathname === path || pathname.startsWith(`${path}/`),
  )
  return (
    <NavLink
      to={item.to}
      end={item.end}
      onClick={onNavigate}
      title={collapsed ? item.label : undefined}
      className={({ isActive }) =>
        cn(
          'focus-ring flex items-center rounded-lg text-sm font-medium transition-colors',
          collapsed ? 'justify-center px-0 py-2.5' : 'gap-3 px-3 py-2',
          isActive || isRelatedPath
            ? 'bg-white/20 text-white'
            : 'text-white/75 hover:bg-white/10 hover:text-white',
        )
      }
    >
      <Icon className="size-5 shrink-0" />
      {!collapsed && <span className="truncate">{item.label}</span>}
    </NavLink>
  )
}

export default function Sidebar({ role, collapsible = false, mobileOpen = false, onMobileClose }) {
  const navigate = useNavigate()
  const { logout } = useAuth()
  const items = NAV_ITEMS[role] ?? NAV_ITEMS[ROLES.EMPLOYEE]

  // 저장된 값이 없으면 접힘으로 시작한다.
  const [expanded, setExpanded] = useState(
    () => globalThis.localStorage?.getItem(STORAGE_KEY) === 'true',
  )
  useEffect(() => {
    globalThis.localStorage?.setItem(STORAGE_KEY, String(expanded))
  }, [expanded])

  // 모바일 드로어는 Esc로도 닫는다.
  useEffect(() => {
    if (!mobileOpen) return
    const onKey = (event) => {
      if (event.key === 'Escape') onMobileClose?.()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [mobileOpen, onMobileClose])

  // 접을 수 없는(=드로어) 상황은 항상 펼친 모습으로 둔다.
  const collapsed = collapsible && !expanded

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  const brand = (
    <>
      <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white">
        <img src={ajtLogo} alt="" className="size-8 object-contain" />
      </span>
      <div className="leading-tight whitespace-nowrap">
        <p className="text-sm font-bold text-white">AUTO Janitor Tool</p>
        <p className="text-[11px] text-white/65">Enterprise AI Workspace</p>
      </div>
    </>
  )

  // 데스크톱 헤더: 접힘이면 로고 hover로 펼치기, 펼침이면 접기 토글.
  const desktopHeader = (
    <div className={cn('flex items-center py-5', collapsed ? 'justify-center px-0' : 'gap-2 px-5')}>
      {collapsed ? (
        <div className="group/logo relative flex size-9 items-center justify-center">
          <span className="flex size-9 items-center justify-center rounded-xl bg-white transition-opacity group-hover/logo:opacity-0">
            <img src={ajtLogo} alt="" className="size-8 object-contain" />
          </span>
          <button
            type="button"
            onClick={() => setExpanded(true)}
            aria-label="사이드바 펼치기"
            title="사이드바 펼치기"
            className="focus-ring absolute inset-0 flex items-center justify-center rounded-xl bg-white/15 text-white opacity-0 transition-opacity group-hover/logo:opacity-100"
          >
            <PanelLeftOpen className="size-5" />
          </button>
        </div>
      ) : (
        <>
          {brand}
          {collapsible && (
            <button
              type="button"
              onClick={() => setExpanded(false)}
              aria-label="사이드바 접기"
              title="사이드바 접기"
              className="focus-ring ml-auto flex size-8 shrink-0 items-center justify-center rounded-lg text-white/75 transition-colors hover:bg-white/10 hover:text-white"
            >
              <PanelLeftClose className="size-5" />
            </button>
          )}
        </>
      )}
    </div>
  )

  // 모바일 드로어 헤더: 브랜드 + 닫기.
  const mobileHeader = (
    <div className="flex items-center gap-2 px-5 py-5">
      {brand}
      <button
        type="button"
        onClick={onMobileClose}
        aria-label="메뉴 닫기"
        className="focus-ring ml-auto flex size-8 shrink-0 items-center justify-center rounded-lg text-white/75 transition-colors hover:bg-white/10 hover:text-white"
      >
        <X className="size-5" />
      </button>
    </div>
  )

  // 메뉴 + 로그아웃(공통 하단). isCollapsed=true면 아이콘만, onNavigate는 드로어에서 선택 시 닫기용.
  const body = (isCollapsed, onNavigate) => (
    <>
      <nav className="flex-1 space-y-1 overflow-x-hidden overflow-y-auto px-3 py-2">
        {items.map((item) => (
          <NavItem key={item.to} item={item} collapsed={isCollapsed} onNavigate={onNavigate} />
        ))}
      </nav>
      <div className="px-3 pb-4">
        <button
          type="button"
          onClick={handleLogout}
          title={isCollapsed ? '로그아웃' : undefined}
          className={cn(
            'focus-ring flex w-full items-center rounded-lg text-sm font-medium text-white/75 transition-colors hover:bg-white/10 hover:text-white',
            isCollapsed ? 'justify-center px-0 py-2.5' : 'gap-3 px-3 py-2',
          )}
        >
          <LogOut className="size-5 shrink-0" />
          {!isCollapsed && <span className="whitespace-nowrap">로그아웃</span>}
        </button>
      </div>
    </>
  )

  const surface = 'bg-gradient-to-b from-blue-600 via-indigo-600 to-violet-600'

  return (
    <>
      {/* 데스크톱 고정 사이드바(md 이상) */}
      <aside
        className={cn(
          'hidden h-full shrink-0 flex-col transition-[width] duration-200 md:flex',
          surface,
          collapsed ? 'w-14' : 'w-60',
        )}
      >
        {desktopHeader}
        {body(collapsed, undefined)}
      </aside>

      {/* 모바일 오프캔버스 드로어(md 미만) */}
      {mobileOpen && (
        <div className="fixed inset-0 z-50 md:hidden">
          <button
            type="button"
            aria-label="메뉴 닫기"
            onClick={onMobileClose}
            className="absolute inset-0 bg-slate-900/40"
          />
          <aside className={cn('absolute inset-y-0 left-0 flex w-60 max-w-[85vw] flex-col shadow-xl', surface)}>
            {mobileHeader}
            {body(false, onMobileClose)}
          </aside>
        </div>
      )}
    </>
  )
}
