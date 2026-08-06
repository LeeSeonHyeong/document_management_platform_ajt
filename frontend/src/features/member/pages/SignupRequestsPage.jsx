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
const SYSTEM_DEPARTMENT_NAME = '최고관리자'

function isSuperAdminAccount(member) {
  return member?.isSuperAdmin === true || member?.department?.name?.trim() === SYSTEM_DEPARTMENT_NAME
}

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
      const countParams = {
        page: 1,
        size: tab.value === SIGNUP_STATUS.APPROVED ? 100 : 1,
        status: tab.value,
      }
      return {
        queryKey: qk.signupRequests.list(countParams),
        queryFn: () => fetchSignupRequests(countParams),
      }
    }),
  })
  const statusCounts = Object.fromEntries(
    STATUS_TABS.map((tab, index) => [
      tab.value,
      tab.value === SIGNUP_STATUS.APPROVED
        ? (countQueries[index].data?.items ?? []).filter(
            (member) => !isSuperAdminAccount(member),
          ).length
        : countQueries[index].data?.totalCount ?? 0,
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

  const items = (query.data?.items ?? []).filter(
    (member) => status !== SIGNUP_STATUS.APPROVED || !isSuperAdminAccount(member),
  )
  const totalPages = status === SIGNUP_STATUS.APPROVED
    ? Math.max(1, Math.ceil(statusCounts[SIGNUP_STATUS.APPROVED] / PAGE_SIZE))
    : query.data?.totalPages ?? 1
  // width 를 주면 표가 table-fixed 로 그려진다 — 상태 카드를 바꿔도(대기 ↔ 승인 완료) 열이 밀리지 않는다.
  // 특히 '관리' 열은 대기일 때 버튼 2개, 그 외에는 배지 1개라 너비 차이가 가장 크다.
  const columns = [
    { key: 'name', header: '이름', width: '20%', render: (request) => <div className="flex min-w-0 items-center gap-3"><EmployeeAvatar employee={request} /><span className="truncate font-semibold" title={request.name}>{request.name}</span></div> },
    { key: 'email', header: '이메일', width: '22%' },
    { key: 'department', header: '신청 부서', width: '14%', render: (request) => request.department?.name ?? '-' },
    { key: 'employeeNo', header: '사번', width: '12%', render: (request) => request.employeeNo ?? '-' },
    { key: 'requestedAt', header: '신청일시', width: '16%', render: (request) => formatRequestedAt(request.requestedAt) },
    {
      key: 'manage',
      header: '관리',
      width: '16%',
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
      {/* 통계 카드가 곧 상태 필터다. 클릭하면 해당 상태의 목록을 본다(직원 관리 페이지와 동일한 패턴). */}
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard
          label="승인 대기"
          value={statusCounts[SIGNUP_STATUS.PENDING]}
          suffix="건"
          tone="amber"
          caption="확인이 필요한 요청"
          onClick={() => handleStatus(SIGNUP_STATUS.PENDING)}
          active={status === SIGNUP_STATUS.PENDING}
        />
        <StatCard
          label="승인 완료"
          value={statusCounts[SIGNUP_STATUS.APPROVED]}
          suffix="건"
          tone="blue"
          caption="누적 승인 인원"
          onClick={() => handleStatus(SIGNUP_STATUS.APPROVED)}
          active={status === SIGNUP_STATUS.APPROVED}
        />
        <StatCard
          label="거부"
          value={statusCounts[SIGNUP_STATUS.REJECTED]}
          suffix="건"
          tone="slate"
          caption="누적 반려 요청"
          onClick={() => handleStatus(SIGNUP_STATUS.REJECTED)}
          active={status === SIGNUP_STATUS.REJECTED}
        />
      </section>
      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex flex-wrap items-center gap-3 px-5 py-4">
          <div><h2 className="text-lg font-bold">가입 요청</h2><p className="text-sm text-slate-400">승인 시 즉시 계정이 활성화됩니다.</p></div>
        </div>
        <DataTable className="rounded-none border-0 shadow-none" columns={columns} rows={items} rowKey="userId" loading={query.isLoading} emptyState={<EmptyState title="가입 요청이 없습니다." />} />
        {query.data && totalPages > 1 && (
          <div className="border-t border-slate-100 px-5 py-4">
            <Pagination page={query.data.page} totalPages={totalPages} onChange={setPage} />
          </div>
        )}
      </section>
    </div>
  )
}
