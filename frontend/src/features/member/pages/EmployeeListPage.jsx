import { useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import Button from '@/components/ui/Button'
import DataTable from '@/components/ui/DataTable'
import EmptyState from '@/components/ui/EmptyState'
import Pagination from '@/components/ui/Pagination'
import SearchBar from '@/components/ui/SearchBar'
import { ACCOUNT_STATUS, ROLES, SIGNUP_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { useAuth } from '@/hooks/useAuth'
import { fetchSignupRequests, fetchUsers } from '../api'
import { AccountBadge, EmployeeAvatar, RoleBadge, StatCard } from '../components/MemberUi'

const FILTERS = [
  { value: 'all', label: '전체' },
  { value: ROLES.ADMIN, label: '관리자' },
  { value: ROLES.EMPLOYEE, label: '사원' },
  { value: ACCOUNT_STATUS.INACTIVE, label: '비활성' },
]

const PAGE_SIZE = 20

export default function EmployeeListPage() {
  const navigate = useNavigate()
  // 사용자 목록 조회는 모든 admin이 가능하지만, 가입 승인 관련 기능은 최고관리자 전용이다(S15P11B106-104).
  const { isSuperAdmin } = useAuth()
  const [searchInput, setSearchInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [filter, setFilter] = useState('all')
  const [page, setPage] = useState(1)

  // 검색어·필터를 바꾸면 목록 조건이 달라지므로 첫 페이지부터 다시 본다.
  const handleSearch = (value) => {
    setKeyword(value)
    setPage(1)
  }
  const handleFilter = (value) => {
    setFilter(value)
    setPage(1)
  }

  // query.data는 백엔드 JSON 응답입니다.
  // items=직원 배열, totalCount=전체 직원 수, page/totalPages=페이지 정보입니다.
  // 직원 목록은 가입 승인(approved)된 계정만 대상으로 합니다.
  const params = {
    page,
    size: PAGE_SIZE,
    signupStatus: SIGNUP_STATUS.APPROVED,
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
  // 가입 신청 API는 최고관리자 전용(부서관리자는 403)이므로 최고관리자일 때만 조회한다.
  const pendingSignupQuery = useQuery({
    queryKey: qk.signupRequests.list({ status: SIGNUP_STATUS.PENDING, page: 1, size: 1 }),
    queryFn: () =>
      fetchSignupRequests({
        status: SIGNUP_STATUS.PENDING,
        page: 1,
        size: 1,
      }),
    enabled: isSuperAdmin,
  })

  const employees = query.data?.items ?? []

  // 상단 카드의 관리자/사원 수는 현재 페이지가 아니라 전체 승인 계정 기준이어야 하므로
  // role별로 size=1 조회해 totalCount만 받아온다(가입 대기 카드와 같은 방식).
  const roleCountQueries = useQueries({
    queries: [ROLES.ADMIN, ROLES.EMPLOYEE].map((role) => {
      const countParams = { page: 1, size: 1, signupStatus: SIGNUP_STATUS.APPROVED, role }
      return {
        queryKey: qk.users.list(countParams),
        queryFn: () => fetchUsers(countParams),
      }
    }),
  })
  const adminCount = roleCountQueries[0].data?.totalCount ?? 0
  const employeeCount = roleCountQueries[1].data?.totalCount ?? 0
  const totalCount = adminCount + employeeCount

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
      {/* 가입 승인 대기 카드는 최고관리자만 볼 수 있어 그에 맞춰 열 수를 조정한다. */}
      <section className={`grid gap-4 sm:grid-cols-2 ${isSuperAdmin ? 'lg:grid-cols-4' : 'lg:grid-cols-3'}`}>
        <StatCard label="전체 직원" value={totalCount} caption="현재 등록 계정" />
        <StatCard label="관리자" value={adminCount} tone="blue" caption="관리 권한 보유" />
        <StatCard label="사원" value={employeeCount} tone="slate" caption="일반 계정" />
        {isSuperAdmin && (
          <StatCard
            label="가입 승인 대기"
            value={pendingSignupQuery.data?.totalCount ?? 0}
            suffix="건"
            tone="amber"
            caption="처리 대기 중"
          />
        )}
      </section>

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex flex-wrap items-end justify-between gap-4 px-5 py-5">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-bold text-slate-900">직원 현황</h2>
              <span className="rounded-full bg-primary-50 px-2.5 py-1 text-xs font-semibold text-primary-600">
                {totalCount}명
              </span>
            </div>
            <p className="mt-1 text-sm text-slate-400">계정 권한과 재직 상태를 한 곳에서 관리합니다.</p>
          </div>
          {isSuperAdmin && (
            <Button onClick={() => navigate('/admin/signup-requests')}>가입 승인</Button>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-3 border-t border-slate-100 px-5 py-3">
          <SearchBar
            value={searchInput}
            onChange={setSearchInput}
            onSearch={handleSearch}
            placeholder="이름 또는 부서로 검색"
            className="min-w-64 flex-1"
          />
          <div className="flex gap-2">
            {FILTERS.map((item) => (
              <Button
                key={item.value}
                size="sm"
                variant={filter === item.value ? 'secondary' : 'outline'}
                onClick={() => handleFilter(item.value)}
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
        {(query.data?.totalPages ?? 1) > 1 && (
          <div className="border-t border-slate-100 px-5 py-4">
            <Pagination page={query.data.page} totalPages={query.data.totalPages} onChange={setPage} />
          </div>
        )}
      </section>
    </div>
  )
}
