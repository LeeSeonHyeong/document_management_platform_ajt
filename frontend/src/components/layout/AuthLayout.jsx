import { Outlet } from 'react-router-dom'
import { Sparkles } from 'lucide-react'

// 인증 화면(로그인/회원가입/비밀번호) 공통 레이아웃.
// 좌측 브랜드 패널(보라 그라데이션) + 우측 폼 영역.
export default function AuthLayout() {
  return (
    <div className="flex min-h-screen bg-white">
      <div className="relative hidden w-1/2 flex-col justify-between overflow-hidden bg-gradient-to-br from-primary-600 to-primary-800 p-12 text-white lg:flex">
        <div className="flex items-center gap-2">
          <span className="flex size-10 items-center justify-center rounded-xl bg-white/15">
            <Sparkles className="size-6" />
          </span>
          <span className="text-lg font-bold">AJT</span>
        </div>
        <div className="space-y-4">
          <h1 className="text-3xl font-bold leading-snug">
            사내 지식과 일정을
            <br />
            AI로 한곳에서
          </h1>
          <p className="text-sm text-white/70">
            Enterprise AI Workspace · 몇 분이면 팀에 합류합니다.
          </p>
        </div>
        <p className="text-xs text-white/50">© 2026 AUTO Janitor Tool</p>
      </div>

      <div className="flex w-full items-center justify-center px-6 py-12 lg:w-1/2">
        <div className="w-full max-w-sm">
          <Outlet />
        </div>
      </div>
    </div>
  )
}
