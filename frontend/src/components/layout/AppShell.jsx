import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { ROLES } from '@/shared/constants/enums'
import Sidebar from './Sidebar'
import TopBar from './TopBar'
import ChatAssistant from '@/features/chat/components/ChatAssistant'
import AiJobQueueProvider from '@/features/document/AiJobQueueProvider'

// 로그인 이후 공통 셸: 좌측 사이드바 + 상단바 + 메인 콘텐츠.
// 페이지별 플로팅 버튼(Fab)은 각 페이지가 직접 렌더한다(고정 위치).
//
// 본문은 폭 상한 없이 가용 폭을 전부 쓴다(각 페이지가 유동 비율로 배치). 사이드바를 접으면
// 그만큼 본문이 넓어져 박스들이 재배치된다.
//
// **작업 화면(위키)은 스크롤 처리만 다르다.** 위키는 열 세 개(문서 트리·본문·AI 편집)가
// 각자 스크롤하는 작업 화면이라 셸이 통째로 스크롤하면 안 된다. 그래서 위키는
// `overflow-hidden` 으로 두고 페이지가 자기 높이·스크롤을 직접 관리한다. 나머지 화면은
// 일반 문서 흐름이라 셸(main)이 세로로 스크롤한다.
const WORKSPACE_PATHS = ['/wiki']

export default function AppShell() {
  const { role } = useAuth()
  const { pathname } = useLocation()
  const workspace = WORKSPACE_PATHS.some(
    (path) => pathname === path || pathname.startsWith(`${path}/`),
  )

  // 모바일(md 미만) 사이드바 드로어 열림 상태. 페이지를 옮기면 닫는다(S15P11B106-234).
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  useEffect(() => {
    setMobileNavOpen(false)
  }, [pathname])

  // AI 작업 대기 목록은 셸이 들고 있는다 — 문서 관리를 벗어나도 올린 파일이 남아야 한다
  // (S15P11B106-230). 로그인 사용자에게만 필요하고 로그아웃하면 셸과 함께 사라진다.
  return (
    <AiJobQueueProvider>
      <div className="flex h-screen overflow-hidden bg-[#eef4ff]">
        <Sidebar
          role={role}
          collapsible
          mobileOpen={mobileNavOpen}
          onMobileClose={() => setMobileNavOpen(false)}
        />
        <div className="flex min-w-0 flex-1 flex-col">
          <TopBar onOpenNav={() => setMobileNavOpen(true)} />
          {workspace ? (
            <main className="min-h-0 flex-1 overflow-hidden px-4 pb-4">
              <Outlet />
            </main>
          ) : (
            <main className="flex-1 overflow-y-auto [scrollbar-gutter:stable]">
              <div className="px-6 py-6 xl:px-8">
                <Outlet />
              </div>
            </main>
          )}
        </div>
        {/* 위키 화면의 관리자에게는 띄우지 않는다 — 그 화면에는 이미 AI 문서 편집 입력창이
            우측 열에 있고, 이 플로팅 버튼이 그 전송 버튼을 정확히 덮는다(실측: 버튼
            1196~1252 × 636~692, 편집 열 928~1264). AI 입력 두 개가 겹치는 것 자체가
            혼란스럽기도 하다. 사원은 편집 열이 없으므로 그대로 띄운다. */}
        {!(workspace && role === ROLES.ADMIN) && <ChatAssistant />}
      </div>
    </AiJobQueueProvider>
  )
}
