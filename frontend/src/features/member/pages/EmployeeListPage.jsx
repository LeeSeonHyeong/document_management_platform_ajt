import { useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import Button from '@/components/ui/Button'
import DataTable from '@/components/ui/DataTable'
import EmptyState from '@/components/ui/EmptyState'
import Pagination from '@/components/ui/Pagination'
import SearchBar from '@/components/ui/SearchBar'
import Select from '@/components/ui/Select'
import { ACCOUNT_STATUS, ROLES, SIGNUP_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { useAuth } from '@/hooks/useAuth'
import { fetchDepartments, fetchSignupRequests, fetchUsers } from '../api'
import { canManageUserAccounts } from '../userAccessPolicy'
import { AccountBadge, EmployeeAvatar, RoleBadge, StatCard } from '../components/MemberUi'

const PAGE_SIZE = 20
const SYSTEM_DEPARTMENT_NAME = '최고관리자'

// 상단 통계 카드가 곧 목록 필터다. 각 값은 fetchUsers의 role·status 조건에 매핑된다.
const CARD_FILTERS = {
  all: 'all',
  admin: ROLES.ADMIN,
  employee: ROLES.EMPLOYEE,
  inactive: ACCOUNT_STATUS.INACTIVE,
}

function isSuperAdminAccount(member) {
  return member?.isSuperAdmin === true ||
    member?.department?.name?.trim() === SYSTEM_DEPARTMENT_NAME ||
    member?.name?.trim() === SYSTEM_DEPARTMENT_NAME
}

export default function EmployeeListPage() {
  const navigate = useNavigate()
  // 사용자 목록 조회는 모든 admin이 가능하지만, 가입 승인 관련 기능은 최고관리자 전용이다(S15P11B106-104).
  const { isSuperAdmin } = useAuth()
  // 상세·수정 진입(상세보기 버튼·행 클릭)도 최고관리자 전용이다(S15P11B106-222).
  const canManageUsers = canManageUserAccounts(isSuperAdmin)
  const [searchInput, setSearchInput] = useState('')
  const [keyword, setKeyword] = useState('')
  // 상단 카드로 선택하는 역할·상태 필터('all'|admin|employee|inactive).
  const [filter, setFilter] = useState('all')
  // 부서 필터(단일 선택, null이면 전체 부서).
  const [departmentId, setDepartmentId] = useState(null)
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
  // 부서 필터는 드롭다운에서 하나만 선택한다. 빈 값('전체 부서')이면 필터를 해제한다.
  const handleDepartment = (value) => {
    setDepartmentId(value || null)
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
    departmentId: departmentId || undefined,
  }
  const query = useQuery({
    queryKey: qk.users.list(params),
    queryFn: () => fetchUsers(params),
  })
  // 가입 승인 대기 인원은 직원 목록이 아닌 GET /signup-requests 응답에서 받습니다.
  // totalCount가 현재 승인 처리를 기다리는 계정 수이며, '가입 승인' 버튼의 배지로 노출합니다.
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
  const pendingCount = pendingSignupQuery.data?.totalCount ?? 0

  // 부서 토글 버튼 목록. 시스템 부서(최고관리자)는 필터 대상에서 제외한다.
  const departmentsQuery = useQuery({
    queryKey: qk.departments.list,
    queryFn: fetchDepartments,
  })
  const departments = (departmentsQuery.data ?? []).filter(
    (department) => department?.name?.trim() !== SYSTEM_DEPARTMENT_NAME,
  )

  const employees = (query.data?.items ?? []).filter((employee) => !isSuperAdminAccount(employee))

  // 상단 카드의 카운트는 현재 페이지가 아니라 전체 승인 계정 기준이어야 하므로
  // 조건별로 size=1 조회해 totalCount만 받아온다(관리자만 최고관리자 제외를 위해 목록을 받아 센다).
  const countQueries = useQueries({
    queries: [
      { page: 1, size: 100, signupStatus: SIGNUP_STATUS.APPROVED, role: ROLES.ADMIN },
      { page: 1, size: 1, signupStatus: SIGNUP_STATUS.APPROVED, role: ROLES.EMPLOYEE },
      { page: 1, size: 1, signupStatus: SIGNUP_STATUS.APPROVED, status: ACCOUNT_STATUS.INACTIVE },
    ].map((countParams) => ({
      queryKey: qk.users.list(countParams),
      queryFn: () => fetchUsers(countParams),
    })),
  })
  const adminCount = (countQueries[0].data?.items ?? []).filter(
    (member) => !isSuperAdminAccount(member),
  ).length
  const employeeCount = countQueries[1].data?.totalCount ?? 0
  const inactiveCount = countQueries[2].data?.totalCount ?? 0
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
    // 상세보기(상세·수정 화면 진입)는 최고관리자 전용이다(S15P11B106-222).
    // 부서관리자는 목록만 볼 수 있으므로 '관리' 열 자체를 숨긴다.
    ...(canManageUsers
      ? [
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
      : []),
  ]

  return (
    <div className="space-y-5">
      {/* 통계 카드는 클릭하면 해당 조건으로 목록을 필터링한다(카드 = 필터). */}
      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="전체 직원"
          value={totalCount}
          caption="현재 등록 계정"
          onClick={() => handleFilter(CARD_FILTERS.all)}
          active={filter === CARD_FILTERS.all}
        />
        <StatCard
          label="관리자"
          value={adminCount}
          tone="blue"
          caption="관리 권한 보유"
          onClick={() => handleFilter(CARD_FILTERS.admin)}
          active={filter === CARD_FILTERS.admin}
        />
        <StatCard
          label="사원"
          value={employeeCount}
          tone="amber"
          caption="일반 계정"
          onClick={() => handleFilter(CARD_FILTERS.employee)}
          active={filter === CARD_FILTERS.employee}
        />
        <StatCard
          label="비활성"
          value={inactiveCount}
          tone="slate"
          caption="비활성 계정"
          onClick={() => handleFilter(CARD_FILTERS.inactive)}
          active={filter === CARD_FILTERS.inactive}
        />
      </section>

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex flex-wrap items-end justify-between gap-4 px-5 py-4">
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
            <Button onClick={() => navigate('/admin/signup-requests')}>
              가입 승인
              {pendingCount > 0 && (
                <span className="ml-2 inline-flex min-w-5 items-center justify-center rounded-full bg-white/20 px-1.5 text-xs font-semibold">
                  {pendingCount}
                </span>
              )}
            </Button>
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
          {/* 부서별 필터: 하나의 드롭다운에서 선택한다. '전체 부서'는 필터 해제. */}
          <Select
            value={departmentId ?? ''}
            onChange={(event) => handleDepartment(event.target.value)}
            className="min-w-44"
            aria-label="부서 필터"
          >
            <option value="">전체 부서</option>
            {departments.map((department) => (
              <option key={department.departmentId} value={department.departmentId}>
                {department.name}
              </option>
            ))}
          </Select>
        </div>
        <DataTable
          className="rounded-none border-0 shadow-none"
          columns={columns}
          rows={employees}
          rowKey="userId"
          loading={query.isLoading}
          // 행 클릭 상세 진입도 최고관리자만. 부서관리자는 목록 조회만 가능(S15P11B106-222).
          onRowClick={
            canManageUsers ? (employee) => navigate(`/admin/users/${employee.userId}`) : undefined
          }
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
