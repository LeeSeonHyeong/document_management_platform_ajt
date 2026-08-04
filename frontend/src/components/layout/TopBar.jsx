import { useLocation, useNavigate } from 'react-router-dom'
import { Menu } from 'lucide-react'
import { useAuth } from '@/hooks/useAuth'
import { ROLE_LABELS } from '@/shared/constants/enums'
import Avatar from '@/components/ui/Avatar'
import Breadcrumb from './Breadcrumb'
import { getPageEyebrow, getPageTitle } from './navConfig'

// 상단 헤더. (모바일) 메뉴 버튼 + 좌측 브레드크럼 + 우측 프로필.
export default function TopBar({ onOpenNav }) {
  const { user, role } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const pageEyebrow = getPageEyebrow(pathname)
  const titleOnly = pathname === '/'

  return (
    <header className="flex h-[68px] shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-4 sm:h-[76px] sm:px-6">
      {/* 모바일(md 미만) 사이드바 열기. 데스크톱은 좌측 고정 사이드바가 있어 숨긴다. */}
      {onOpenNav && (
        <button
          type="button"
          onClick={onOpenNav}
          aria-label="메뉴 열기"
          className="focus-ring flex size-9 shrink-0 items-center justify-center rounded-lg text-slate-600 transition-colors hover:bg-slate-100 md:hidden"
        >
          <Menu className="size-5" />
        </button>
      )}
      <div className="min-w-0">
        {titleOnly ? null : pageEyebrow ? (
          <p className="truncate text-xs font-medium text-slate-400">
            <span className="text-primary-600">AJT</span>
            {pageEyebrow.slice(3)}
          </p>
        ) : (
          <Breadcrumb />
        )}
        <h1 className={`${titleOnly ? '' : 'mt-1'} truncate text-xl font-bold tracking-tight text-slate-900 sm:text-2xl`}>
          {getPageTitle(pathname)}
        </h1>
      </div>
      <div className="ml-auto flex items-center">
        <button
          type="button"
          onClick={() => navigate('/me')}
          className="focus-ring flex items-center gap-2.5 rounded-xl border border-slate-200 bg-white px-2 py-1.5 text-left transition hover:border-primary-300 hover:bg-primary-50/40 sm:px-3 sm:py-2"
          aria-label="내 정보로 이동"
        >
          <Avatar name={user?.name} size="sm" />
          {/* 좁은 화면에서는 이름/역할 텍스트를 숨겨 헤더가 넘치지 않게 한다(아바타는 유지). */}
          <div className="hidden text-right leading-tight sm:block">
            <p className="max-w-[12rem] truncate text-sm font-medium text-slate-800">{user?.name}</p>
            <p className="max-w-[12rem] truncate text-xs text-slate-400">
              {ROLE_LABELS[role] ?? role}
              {user?.department?.name ? ` · ${user.department.name}` : ''}
            </p>
          </div>
        </button>
      </div>
    </header>
  )
}
