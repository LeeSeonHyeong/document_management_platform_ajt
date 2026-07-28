import { useAuth } from '@/hooks/useAuth'

export default function DashboardPage() {
  const { user } = useAuth()
  return (
    <section className="space-y-2">
      <h1 className="text-2xl font-semibold">대시보드</h1>
      <p className="text-gray-600">
        {user?.name}님, 환영합니다. 로그인한 모든 사용자가 접근할 수 있는
        화면입니다.
      </p>
    </section>
  )
}
