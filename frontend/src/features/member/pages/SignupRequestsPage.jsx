import { useState } from 'react'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft } from 'lucide-react'
import { Link } from 'react-router-dom'
import Button from '@/components/ui/Button'
import DataTable from '@/components/ui/DataTable'
import EmptyState from '@/components/ui/EmptyState'
import Pagination from '@/components/ui/Pagination'
import { useToast } from '@/components/ui'
import { SIGNUP_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { approveSignupRequest, fetchSignupRequests, rejectSignupRequest } from '../api'
import { EmployeeAvatar, SignupBadge, StatCard } from '../components/MemberUi'

const STATUS_TABS = [
  { value: SIGNUP_STATUS.PENDING, label: '대기중' },
  { value: SIGNUP_STATUS.APPROVED, label: '승인 완료' },
  { value: SIGNUP_STATUS.REJECTED, label: '거부' },
]

const PAGE_SIZE = 20

function formatRequestedAt(value) {
  if (!value) return '-'
  const date = new Date(value)
  const pad = (number) => String(number).padStart(2, '0')
  return [
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
    `${pad(date.getHours())}:${pad(date.getMinutes())}`,
  ].join(' ')
}

export default function SignupRequestsPage() {
  const [status, setStatus] = useState(SIGNUP_STATUS.PENDING)
  const [page, setPage] = useState(1)
  const queryClient = useQueryClient()
  const toast = useToast()

  // 상태 탭을 바꾸면 목록이 달라지므로 첫 페이지부터 다시 본다.
  const handleStatus = (value) => {
    setStatus(value)
    setPage(1)
  }

  const params = { page, size: PAGE_SIZE, status }
  const query = useQuery({ queryKey: qk.signupRequests.list(params), queryFn: () => fetchSignupRequests(params) })
  // 상태별 totalCount를 각각 받아 상단 카드와 상태 탭의 숫자에 함께 사용합니다.
  const countQueries = useQueries({
    queries: STATUS_TABS.map((tab) => {
      const countParams = { page: 1, size: 1, status: tab.value }
      return {
        queryKey: qk.signupRequests.list(countParams),
        queryFn: () => fetchSignupRequests(countParams),
      }
    }),
  })
  const statusCounts = Object.fromEntries(
    STATUS_TABS.map((tab, index) => [
      tab.value,
      countQueries[index].data?.totalCount ?? 0,
    ]),
  )

  const action = useMutation({
    mutationFn: ({ userId, type }) => type === 'approve' ? approveSignupRequest(userId) : rejectSignupRequest(userId),
    onSuccess: async (_, variables) => {
      await queryClient.invalidateQueries({ queryKey: qk.signupRequests.all })
      await queryClient.invalidateQueries({ queryKey: qk.users.all })
      toast.success(variables.type === 'approve' ? '가입을 승인했습니다.' : '가입을 거부했습니다.')
    },
    onError: (error) => toast.error(error.message ?? '요청을 처리하지 못했습니다.'),
  })

  const items = query.data?.items ?? []
  const columns = [
    { key: 'name', header: '이름', render: (request) => <div className="flex items-center gap-3"><EmployeeAvatar employee={request} /><span className="font-semibold">{request.name}</span></div> },
    { key: 'email', header: '이메일' },
    { key: 'department', header: '신청 부서', render: (request) => request.department?.name ?? '-' },
    { key: 'employeeNo', header: '사번', render: (request) => request.employeeNo ?? '-' },
    { key: 'requestedAt', header: '신청일시', render: (request) => formatRequestedAt(request.requestedAt) },
    {
      key: 'manage',
      header: '관리',
      render: (request) => request.signupStatus === SIGNUP_STATUS.PENDING ? (
        <div className="flex gap-2">
          <Button size="sm" onClick={() => action.mutate({ userId: request.userId, type: 'approve' })}>승인</Button>
          <Button size="sm" variant="outline" onClick={() => action.mutate({ userId: request.userId, type: 'reject' })}>거부</Button>
        </div>
      ) : <SignupBadge status={request.signupStatus} />,
    },
  ]

  return (
    <div className="space-y-5">
      <Link to="/admin/users" className="inline-flex items-center gap-1 text-sm font-medium text-slate-500"><ChevronLeft className="size-4" /> 직원 관리</Link>
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard label="승인 대기" value={statusCounts[SIGNUP_STATUS.PENDING]} suffix="건" tone="amber" caption="확인이 필요한 요청" />
        <StatCard
          label="승인 완료"
          value={statusCounts[SIGNUP_STATUS.APPROVED]}
          suffix="건"
          tone="blue"
          caption="누적 승인 인원"
        />
        <StatCard
          label="거부"
          value={statusCounts[SIGNUP_STATUS.REJECTED]}
          suffix="건"
          tone="slate"
          caption="누적 반려 요청"
        />
      </section>
      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
          <div><h2 className="text-lg font-bold">가입 요청</h2><p className="text-sm text-slate-400">승인 시 즉시 계정이 활성화됩니다.</p></div>
          <div className="flex gap-2">
            {STATUS_TABS.map((tab) => (
              <Button
                key={tab.value}
                size="sm"
                variant={status === tab.value ? 'secondary' : 'outline'}
                onClick={() => handleStatus(tab.value)}
              >
                {tab.label} {statusCounts[tab.value]}
              </Button>
            ))}
          </div>
        </div>
        <DataTable className="rounded-none border-0 shadow-none" columns={columns} rows={items} rowKey="userId" loading={query.isLoading} emptyState={<EmptyState title="가입 요청이 없습니다." />} />
        {(query.data?.totalPages ?? 1) > 1 && (
          <div className="border-t border-slate-100 px-5 py-4">
            <Pagination page={query.data.page} totalPages={query.data.totalPages} onChange={setPage} />
          </div>
        )}
      </section>
    </div>
  )
}
