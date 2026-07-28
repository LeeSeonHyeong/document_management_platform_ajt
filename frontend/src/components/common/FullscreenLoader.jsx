// 인증 부팅(GET /me) 등 앱 전역 초기화 동안 잠깐 보여주는 전체 화면 로더.
export default function FullscreenLoader({ label = '불러오는 중...' }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-50">
      <div className="flex flex-col items-center gap-3">
        <span
          className="size-8 animate-spin rounded-full border-2 border-slate-300 border-t-indigo-600"
          aria-hidden="true"
        />
        <p className="text-sm text-slate-500">{label}</p>
      </div>
    </div>
  )
}
