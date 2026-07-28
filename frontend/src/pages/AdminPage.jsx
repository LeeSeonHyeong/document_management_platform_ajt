export default function AdminPage() {
  return (
    <section className="space-y-2">
      <h1 className="text-2xl font-semibold">관리자</h1>
      <p className="text-gray-600">
        ADMIN 역할만 접근할 수 있는 화면입니다. 사원 계정으로는 403으로
        차단됩니다.
      </p>
    </section>
  )
}
