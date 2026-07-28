import { Link } from 'react-router-dom'

export default function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-3 text-center">
      <h1 className="text-3xl font-bold">404</h1>
      <p className="text-gray-600">요청하신 페이지를 찾을 수 없습니다.</p>
      <Link to="/" className="text-sm text-blue-600 hover:underline">
        홈으로
      </Link>
    </div>
  )
}
