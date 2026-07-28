import { Outlet } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import Sidebar from './Sidebar'
import TopBar from './TopBar'

// 로그인 이후 공통 셸: 좌측 사이드바 + 상단바 + 메인 콘텐츠.
// 페이지별 플로팅 버튼(Fab)은 각 페이지가 직접 렌더한다(고정 위치).
export default function AppShell() {
  const { role } = useAuth()

  return (
    <div className="flex h-screen overflow-hidden bg-slate-50">
      <Sidebar role={role} />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        <main className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-7xl px-6 py-6">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
