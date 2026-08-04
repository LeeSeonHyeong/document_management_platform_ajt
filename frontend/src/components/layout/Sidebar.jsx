import { useEffect, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { LogOut, PanelLeftClose, PanelLeftOpen } from 'lucide-react'
import { cn } from '@/shared/lib/cn'
import { ROLES } from '@/shared/constants/enums'
import { useAuth } from '@/hooks/useAuth'
import { NAV_ITEMS } from './navConfig'
import ajtLogo from '@/assets/ajt-logo.png'

// 펼침/접힘 선택을 저장하는 공용 키. 지금은 위키 화면만 접을 수 있지만, 이후 전 화면
// 통일 때도 같은 키를 재사용한다.
const STORAGE_KEY = 'sidebar-expanded'

// 좌측 고정 사이드바. 역할에 따라 메뉴가 달라진다.
//
// **접기 가능(`collapsible`)** — 화면이 자기 열을 여러 개 요구하는 페이지(위키)에서 쓴다.
// 이 사이드바는 *모듈 전환기*(직원·부서·문의·문서·일정·위키)라서, 이미 그 모듈 안에
// 들어온 사람에게 6개 메뉴를 240px 펼쳐 둘 값이 낮다. 접으면 56px 만 쓰고 184px 를
// 본문에 돌려준다.
//
// 펼침/접힘은 로고 위 토글 버튼으로 직접 전환하고 그 선택을 유지한다(localStorage).
// 펼치면 폭이 실제로 늘어 본문(위키 3열)을 밀어낸다 — 사용자가 직접 펼친 것이므로
// 본문이 좁아지는 게 자연스럽다.
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

export default function Sidebar({ role, collapsible = false }) {
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

  // 접을 수 없는 화면은 항상 펼친 모습으로 둔다.
  const collapsed = collapsible && !expanded

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  const panel = (
    <>
      <div className={cn('flex items-center py-5', collapsed ? 'justify-center px-0' : 'gap-2 px-5')}>
        {collapsed ? (
          // 접힘: 로고를 hover 하면 펼치기 버튼으로 바뀐다(ChatGPT 방식).
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
            <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white">
              <img src={ajtLogo} alt="" className="size-8 object-contain" />
            </span>
            <div className="leading-tight whitespace-nowrap">
              <p className="text-sm font-bold text-white">AUTO Janitor Tool</p>
              <p className="text-[11px] text-white/65">Enterprise AI Workspace</p>
            </div>
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

      <nav className="flex-1 space-y-1 overflow-x-hidden overflow-y-auto px-3 py-2">
        {items.map((item) => (
          <NavItem key={item.to} item={item} collapsed={collapsed} />
        ))}
      </nav>

      <div className="px-3 pb-4">
        <button
          type="button"
          onClick={handleLogout}
          title={collapsed ? '로그아웃' : undefined}
          className={cn(
            'focus-ring flex w-full items-center rounded-lg text-sm font-medium text-white/75 transition-colors hover:bg-white/10 hover:text-white',
            collapsed ? 'justify-center px-0 py-2.5' : 'gap-3 px-3 py-2',
          )}
        >
          <LogOut className="size-5 shrink-0" />
          {!collapsed && <span className="whitespace-nowrap">로그아웃</span>}
        </button>
      </div>
    </>
  )

  return (
    <aside
      className={cn(
        'flex h-full shrink-0 flex-col bg-gradient-to-b from-blue-600 via-indigo-600 to-violet-600 transition-[width] duration-200',
        collapsed ? 'w-14' : 'w-60',
      )}
    >
      {panel}
    </aside>
  )
}
