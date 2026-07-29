import { useQuery } from '@tanstack/react-query'
import { ChevronLeft } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import Button from '@/components/ui/Button'
import Card from '@/components/ui/Card'
import EmptyState from '@/components/ui/EmptyState'
import Spinner from '@/components/ui/Spinner'
import { qk } from '@/shared/api/queryKeys'
import { fetchUser } from '../api'
import { AccountBadge, EmployeeAvatar, RoleBadge } from '../components/MemberUi'

function formatDate(value) {
  return value ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '-'
}

export default function EmployeeDetailPage() {
  const { userId } = useParams()
  const navigate = useNavigate()
  const query = useQuery({ queryKey: qk.users.detail(userId), queryFn: () => fetchUser(userId) })

  if (query.isLoading) return <div className="flex justify-center py-24"><Spinner /></div>
  if (!query.data) return <EmptyState title="직원 정보를 찾을 수 없습니다." />

  const employee = query.data
  return (
    <div className="space-y-5">
      <Link to="/admin/users" className="inline-flex items-center gap-1 text-sm font-medium text-slate-500 hover:text-slate-800">
        <ChevronLeft className="size-4" /> 직원 관리
      </Link>

      <div className="grid gap-5 lg:grid-cols-[2fr_1fr]">
        <Card className="p-6">
          <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-100 pb-5">
            <div className="flex items-center gap-4">
              <EmployeeAvatar employee={employee} large />
              <div>
                <div className="flex items-center gap-2">
                  <h2 className="text-2xl font-bold">{employee.name}</h2>
                  <RoleBadge role={employee.role} />
                  <AccountBadge status={employee.accountStatus} />
                </div>
                <p className="mt-1 text-sm text-slate-400">
                  {employee.department?.name} · 사번 {employee.employeeNo} · {employee.email}
                </p>
              </div>
            </div>
            <Button onClick={() => navigate(`/admin/users/${userId}/edit`)}>정보 수정</Button>
          </div>
          <dl className="mt-5 grid gap-y-4 text-sm sm:grid-cols-[140px_1fr]">
            <dt className="text-slate-400">사용자명</dt><dd className="font-semibold">{employee.name}</dd>
            <dt className="text-slate-400">사번</dt><dd className="font-semibold">{employee.employeeNo}</dd>
            <dt className="text-slate-400">부서</dt><dd className="font-semibold">{employee.department?.name}</dd>
            <dt className="text-slate-400">이메일 (로그인 ID)</dt><dd className="font-semibold">{employee.email}</dd>
            <dt className="text-slate-400">사용자 역할</dt><dd className="font-semibold"><RoleBadge role={employee.role} /></dd>
          </dl>
        </Card>
        <Card className="h-fit p-6">
          <h2 className="border-b border-slate-100 pb-4 text-lg font-bold">계정 정보</h2>
          <dl className="mt-4 space-y-4 text-sm">
            <div className="flex justify-between"><dt className="text-slate-400">계정 상태</dt><dd><AccountBadge status={employee.accountStatus} /></dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">생성일시</dt><dd className="font-semibold">{formatDate(employee.createdAt)}</dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">수정일시</dt><dd className="font-semibold">{formatDate(employee.updatedAt)}</dd></div>
          </dl>
          <p className="mt-5 rounded-lg bg-primary-50 px-4 py-3 text-xs text-primary-700">계정 정보 변경은 관리자만 가능합니다.</p>
        </Card>
      </div>
    </div>
  )
}
