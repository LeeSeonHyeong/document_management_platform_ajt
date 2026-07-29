import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import Button from '@/components/ui/Button'
import DataTable from '@/components/ui/DataTable'
import EmptyState from '@/components/ui/EmptyState'
import SearchBar from '@/components/ui/SearchBar'
import { ACCOUNT_STATUS, ROLES, SIGNUP_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { fetchSignupRequests, fetchUsers } from '../api'
import { AccountBadge, EmployeeAvatar, RoleBadge, StatCard } from '../components/MemberUi'

const FILTERS = [
  { value: 'all', label: '전체' },
  { value: ROLES.ADMIN, label: '관리자' },
  { value: ROLES.EMPLOYEE, label: '사원' },
  { value: ACCOUNT_STATUS.INACTIVE, label: '비활성' },
]

export default function EmployeeListPage() {
  const navigate = useNavigate()
  const [searchInput, setSearchInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [filter, setFilter] = useState('all')

  // query.data는 백엔드 JSON 응답입니다.
  // items=직원 배열, totalCount=전체 직원 수, page/totalPages=페이지 정보입니다.
  const params = {
    page: 1,
    size: 100,
    keyword: keyword || undefined,
    role: filter === ROLES.ADMIN || filter === ROLES.EMPLOYEE ? filter : undefined,
    status: filter === ACCOUNT_STATUS.INACTIVE ? filter : undefined,
  }
  const query = useQuery({
    queryKey: qk.users.list(params),
    queryFn: () => fetchUsers(params),
  })
  // 가입 승인 대기 인원은 직원 목록이 아닌 GET /signup-requests 응답에서 받습니다.
  // totalCount가 현재 승인 처리를 기다리는 계정 수입니다.
  const pendingSignupQuery = useQuery({
    queryKey: qk.signupRequests.list({ status: SIGNUP_STATUS.PENDING, page: 1, size: 1 }),
    queryFn: () =>
      fetchSignupRequests({
        status: SIGNUP_STATUS.PENDING,
        page: 1,
        size: 1,
      }),
  })

  const employees = query.data?.items ?? []
  const counts = useMemo(() => {
    const source = query.data?.items ?? []
    return {
      total: query.data?.totalCount ?? source.length,
      admins: source.filter((employee) => employee.role === ROLES.ADMIN).length,
      employees: source.filter((employee) => employee.role === ROLES.EMPLOYEE).length,
    }
  }, [query.data])

  const columns = [
    {
      key: 'name',
      header: '이름',
      render: (employee) => (
        <div className="flex items-center gap-3">
          <EmployeeAvatar employee={employee} />
          <span className="font-semibold text-slate-800">{employee.name}</span>
        </div>
      ),
    },
    { key: 'employeeNo', header: '사번' },
    { key: 'email', header: '이메일' },
    { key: 'department', header: '부서', render: (employee) => employee.department?.name ?? '-' },
    { key: 'role', header: '역할', render: (employee) => <RoleBadge role={employee.role} /> },
    { key: 'accountStatus', header: '상태', render: (employee) => <AccountBadge status={employee.accountStatus} /> },
    {
      key: 'manage',
      header: '관리',
      render: (employee) => (
        <Button
          size="sm"
          variant="outline"
          onClick={(event) => {
            event.stopPropagation()
            navigate(`/admin/users/${employee.userId}`)
          }}
        >
          상세보기
        </Button>
      ),
    },
  ]

  return (
    <div className="space-y-5">
      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="전체 직원" value={counts.total} caption="현재 등록 계정" />
        <StatCard label="관리자" value={counts.admins} tone="blue" caption="관리 권한 보유" />
        <StatCard label="사원" value={counts.employees} tone="slate" caption="일반 계정" />
        <StatCard
          label="가입 승인 대기"
          value={pendingSignupQuery.data?.totalCount ?? 0}
          suffix="건"
          tone="amber"
          caption="처리 대기 중"
        />
      </section>

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex flex-wrap items-end justify-between gap-4 px-5 py-5">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-bold text-slate-900">직원 현황</h2>
              <span className="rounded-full bg-primary-50 px-2.5 py-1 text-xs font-semibold text-primary-600">
                {counts.total}명
              </span>
            </div>
            <p className="mt-1 text-sm text-slate-400">계정 권한과 재직 상태를 한 곳에서 관리합니다.</p>
          </div>
          <Button onClick={() => navigate('/admin/signup-requests')}>가입 승인</Button>
        </div>
        <div className="flex flex-wrap items-center gap-3 border-t border-slate-100 px-5 py-3">
          <SearchBar
            value={searchInput}
            onChange={setSearchInput}
            onSearch={setKeyword}
            placeholder="이름 또는 부서로 검색"
            className="min-w-64 flex-1"
          />
          <div className="flex gap-2">
            {FILTERS.map((item) => (
              <Button
                key={item.value}
                size="sm"
                variant={filter === item.value ? 'secondary' : 'outline'}
                onClick={() => setFilter(item.value)}
              >
                {item.label}
              </Button>
            ))}
          </div>
        </div>
        <DataTable
          className="rounded-none border-0 shadow-none"
          columns={columns}
          rows={employees}
          rowKey="userId"
          loading={query.isLoading}
          onRowClick={(employee) => navigate(`/admin/users/${employee.userId}`)}
          emptyState={<EmptyState title="조건에 맞는 직원이 없습니다." />}
        />
      </section>
    </div>
  )
}
