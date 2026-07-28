import { Link } from 'react-router-dom'

export default function ForbiddenPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-3 text-center">
      <h1 className="text-3xl font-bold">403</h1>
      <p className="text-gray-600">이 페이지에 접근할 권한이 없습니다.</p>
      <Link to="/" className="text-sm text-blue-600 hover:underline">
        대시보드로 돌아가기
      </Link>
    </div>
  )
}
