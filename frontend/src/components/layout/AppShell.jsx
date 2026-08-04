import { Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { ROLES } from '@/shared/constants/enums'
import Sidebar from './Sidebar'
import TopBar from './TopBar'
import ChatAssistant from '@/features/chat/components/ChatAssistant'

// 로그인 이후 공통 셸: 좌측 사이드바 + 상단바 + 메인 콘텐츠.
// 페이지별 플로팅 버튼(Fab)은 각 페이지가 직접 렌더한다(고정 위치).
//
// **작업 화면(위키)은 예외로 폭을 전부 쓴다.** 대부분의 화면은 폭이 넓어도 읽기 좋게
// `max-w-7xl`(1280px)로 묶는 게 맞지만, 위키는 열 세 개(문서 트리·본문·AI 편집)가 폭을
// 나눠 쓰는 작업 화면이라 폭이 곧 기능이다. 1280px 로 묶으면 그보다 넓은 화면의 남는 폭을
// 전부 좌우 여백으로 버리면서 정작 본문은 300px 로 눌린다(실측).
// 같은 이유로 이 화면에서는 사이드바를 56px 로 접고, 스크롤도 페이지가 직접 관리한다 —
// 셸이 통째로 스크롤하면 열마다 따로 스크롤할 수 없다.
const WORKSPACE_PATHS = ['/wiki']

export default function AppShell() {
  const { role } = useAuth()
  const { pathname } = useLocation()
  const workspace = WORKSPACE_PATHS.some(
    (path) => pathname === path || pathname.startsWith(`${path}/`),
  )

  return (
    <div className="flex h-screen overflow-hidden bg-[#eef4ff]">
      <Sidebar role={role} collapsed={workspace} />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        {workspace ? (
          <main className="min-h-0 flex-1 overflow-hidden px-4 pb-4">
            <Outlet />
          </main>
        ) : (
          <main className="flex-1 overflow-y-auto [scrollbar-gutter:stable]">
            <div className="mx-auto max-w-7xl px-6 py-6">
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
  )
}
