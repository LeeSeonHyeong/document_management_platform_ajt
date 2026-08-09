import { useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { ChevronDown } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import Button from '@/components/ui/Button'
import DataTable from '@/components/ui/DataTable'
import EmptyState from '@/components/ui/EmptyState'
import Pagination from '@/components/ui/Pagination'
import SearchBar from '@/components/ui/SearchBar'
import { INQUIRY_PRIORITY, INQUIRY_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { cn } from '@/shared/lib/cn'
import { useAuth } from '@/hooks/useAuth'
import { fetchDepartments } from '@/api/departments'
import { fetchInquiries } from '../api'
import { INQUIRY_STATUS_FILTER, inquiryListTitle, toInquiryStatusParam } from '../statusFilter'

const PRIORITY_META = {
  [INQUIRY_PRIORITY.HIGH]: { label: '높음', className: 'bg-rose-50 text-rose-600' },
  [INQUIRY_PRIORITY.NORMAL]: { label: '보통', className: 'bg-amber-50 text-amber-600' },
  [INQUIRY_PRIORITY.LOW]: { label: '낮음', className: 'bg-slate-100 text-slate-500' },
}

const PRIORITY_ORDER = {
  [INQUIRY_PRIORITY.HIGH]: 3,
  [INQUIRY_PRIORITY.NORMAL]: 2,
  [INQUIRY_PRIORITY.LOW]: 1,
}

function PriorityBadge({ priority }) {
  const meta = PRIORITY_META[priority] ?? PRIORITY_META[INQUIRY_PRIORITY.NORMAL]
  return <span className={`shrink-0 rounded-lg px-3 py-1 text-xs font-semibold ${meta.className}`}>{meta.label}</span>
}

function StatusBadge({ status }) {
  const done = status === INQUIRY_STATUS.DONE
  return (
    <span className={`inline-flex items-center gap-1 rounded-lg px-3 py-1 text-xs font-semibold ${done ? 'bg-emerald-50 text-emerald-600' : 'bg-amber-50 text-amber-600'}`}>
      <span className="size-1.5 rounded-full bg-current" />
      {done ? '처리완료' : '미처리'}
    </span>
  )
}

// 상단 카드는 상태 필터 버튼을 겸한다(S15P11B106-226). 선택된 카드는 직원 관리 화면과 동일하게 강하게 강조한다.
function StatCard({ label, value, suffix = '건', tone, caption, badge, onClick, active }) {
  const colors = {
    primary: 'bg-primary-50 text-primary-600',
    amber: 'bg-amber-50 text-amber-600',
    green: 'bg-emerald-50 text-emerald-600',
  }[tone]
  // active면 아이콘 칩·badge를 솔리드 primary로 바꿔 진한 배경 위에서도 묻히지 않게 한다.
  const chipClass = active ? 'bg-primary-600 text-white' : colors
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'focus-ring w-full rounded-2xl border border-slate-200 bg-white p-5 text-left shadow-sm transition hover:border-primary-300 hover:shadow-md',
        active &&
          'scale-[1.03] border-primary-500 bg-primary-50 shadow-xl ring-2 ring-primary-500 ring-offset-2 ring-offset-white',
      )}
    >
      <div className="flex items-start justify-between">
        <span className={cn('flex size-9 items-center justify-center rounded-xl', chipClass)}>
          <span className="size-3 rounded bg-current" />
        </span>
        <span className={cn('rounded-full px-2.5 py-1 text-xs font-semibold', chipClass)}>{badge}</span>
      </div>
      <p className={cn('mt-3 text-sm text-slate-500', active && 'font-semibold text-primary-700')}>{label}</p>
      <p className={cn('mt-2 text-3xl font-bold text-slate-900', active && 'text-primary-700')}>{value}<span className="ml-1 text-sm font-medium text-slate-400">{suffix}</span></p>
      <p className="mt-3 text-xs text-slate-400">{caption}</p>
    </button>
  )
}

function relativeTime(value) {
  const diffMinutes = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 60000))
  if (diffMinutes < 60) return `${diffMinutes || 1}분 전`
  if (diffMinutes < 1440) return `${Math.floor(diffMinutes / 60)}시간 전`
  return `${Math.floor(diffMinutes / 1440)}일 전`
}

export default function InquiryManagementPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  const [page, setPage] = useState(1)
  const [searchInput, setSearchInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [sort, setSort] = useState('priority,desc')
  const [departmentId, setDepartmentId] = useState('')
  // 상단 카드로 고르는 상태 필터(S15P11B106-226): 'all' | 'pending' | 'done'. 기본 전체.
  const [statusFilter, setStatusFilter] = useState(INQUIRY_STATUS_FILTER.ALL)
  // 백엔드는 createdAt/updatedAt 정렬만 허용한다. 중요도 정렬은 현재 페이지에서 처리한다.
  const apiSort = sort.startsWith('priority') ? 'createdAt,desc' : sort
  // 상태 필터는 백엔드 status 파라미터(pending|done)를 재사용한다. 전체는 파라미터를 생략한다.
  const status = toInquiryStatusParam(statusFilter)
  const params = { page, size: 20, keyword: keyword || undefined, sort: apiSort, status }
  const query = useQuery({
    queryKey: qk.inquiries.list(params),
    queryFn: () => fetchInquiries(params),
  })

  // 카드를 누르면 해당 상태로 서버 필터링하고 첫 페이지부터 다시 본다.
  const handleStatusFilter = (value) => {
    setStatusFilter(value)
    setPage(1)
  }

  // 요구사항 FR-USR-006: 전체 관리자와 부서 관리자는 같은 ADMIN 역할이며 별도 플래그가 없다.
  // 따라서 "내가 어느 부서의 관리자로 지정됐는지"로 두 범위를 구분한다(Figma 3R vs 3-1R).
  const departmentsQuery = useQuery({
    queryKey: qk.departments.list,
    queryFn: fetchDepartments,
  })
  const departments = departmentsQuery.data ?? []
  // manager가 null인 부서와 userId가 없는 사용자가 String(undefined)로 서로 매칭되지 않도록 가드한다.
  const managedDepartment =
    (user?.userId &&
      departments.find(
        (department) => department.manager && String(department.manager.userId) === String(user.userId),
      )) ||
    null
  const isDepartmentAdmin = Boolean(managedDepartment)

  const inquiries = query.data?.items ?? []

  // 카드 카운트는 선택된 상태와 무관하게 항상 전체·미처리·완료를 보여줘야 하므로, 목록과 별개로
  // 상태별 totalCount만 size=1로 조회한다(EmployeeListPage와 같은 방식). 권한 범위는 백엔드가
  // 자동 적용하고, 검색어(keyword)는 목록과 맞춰 함께 반영한다.
  const countQueries = useQueries({
    queries: [undefined, INQUIRY_STATUS.PENDING, INQUIRY_STATUS.DONE].map((countStatus) => {
      const countParams = { page: 1, size: 1, keyword: keyword || undefined, status: countStatus }
      return {
        queryKey: qk.inquiries.list(countParams),
        queryFn: () => fetchInquiries(countParams),
      }
    }),
  })
  const totalCount = countQueries[0].data?.totalCount ?? 0
  const pendingCount = countQueries[1].data?.totalCount ?? 0
  const doneCount = countQueries[2].data?.totalCount ?? 0
  const completionRate = totalCount ? Math.round((doneCount / totalCount) * 100) : 0
  // 목록 제목 옆 배지에 쓸, 현재 선택된 카드의 카운트.
  const selectedCount =
    statusFilter === INQUIRY_STATUS_FILTER.PENDING
      ? pendingCount
      : statusFilter === INQUIRY_STATUS_FILTER.DONE
        ? doneCount
        : totalCount

  // 전체 관리자만 쓰는 부서별 필터. 문의 목록 API에 부서 파라미터가 없어 현재 페이지에서 걸러낸다.
  // 계약에 담당자 부서(assignee.department)만 있으므로 담당자 부서를 기준으로 한다.
  const departmentInquiries = departmentId
    ? inquiries.filter((item) => String(item.assignee?.department?.departmentId) === departmentId)
    : inquiries
  const visibleInquiries = sort.startsWith('priority')
    ? [...departmentInquiries].sort(
        (left, right) =>
          (PRIORITY_ORDER[right.priority] ?? 0) - (PRIORITY_ORDER[left.priority] ?? 0),
      )
    : departmentInquiries

  // width 를 주면 표가 table-fixed 로 그려진다 — 상태 카드를 바꿔도(전체 ↔ 미처리 ↔ 처리 완료)
  // 남은 행의 제목·담당자 길이에 따라 열이 밀리지 않는다.
  const columns = [
    {
      key: 'content',
      header: '문의 내용',
      width: '30%',
      render: (inquiry) => (
        // 제목이 길어도 한 줄로 줄인다(전체 값은 title).
        <div className="flex min-w-0 items-center gap-3">
          <PriorityBadge priority={inquiry.priority} />
          <p className="min-w-0 truncate font-semibold text-slate-800" title={inquiry.title}>{inquiry.title}</p>
        </div>
      ),
    },
    // 요청자는 keyword 검색 대상이므로 담당자와 같은 형식의 독립 열로 보여준다.
    { key: 'author', header: '요청자', width: '18%', render: (inquiry) => `${inquiry.author?.name} (${inquiry.author?.department?.name ?? '-'})` },
    { key: 'assignee', header: '담당자', width: '18%', render: (inquiry) => `${inquiry.assignee?.name} (${inquiry.assignee?.department?.name ?? '-'})` },
    { key: 'status', header: '상태', width: '12%', render: (inquiry) => <StatusBadge status={inquiry.status} /> },
    { key: 'createdAt', header: '접수 시간', width: '11%', render: (inquiry) => relativeTime(inquiry.createdAt) },
    {
      key: 'manage',
      header: '관리',
      width: '11%',
      render: (inquiry) => (
        <Button size="sm" variant="outline" onClick={() => navigate(`/admin/inquiries/${inquiry.inquiryId}`)}>상세</Button>
      ),
    },
  ]

  return (
    <div className="space-y-5">
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard
          label="전체 문의"
          value={totalCount}
          tone="primary"
          badge="주간"
          caption={isDepartmentAdmin ? '이번 주 내 담당' : '이번 주 접수 기준'}
          onClick={() => handleStatusFilter(INQUIRY_STATUS_FILTER.ALL)}
          active={statusFilter === INQUIRY_STATUS_FILTER.ALL}
        />
        <StatCard
          label="미처리"
          value={pendingCount}
          tone="amber"
          badge="확인 필요"
          caption="답변 대기 중"
          onClick={() => handleStatusFilter(INQUIRY_STATUS_FILTER.PENDING)}
          active={statusFilter === INQUIRY_STATUS_FILTER.PENDING}
        />
        <StatCard
          label="처리 완료"
          value={doneCount}
          tone="green"
          badge={`${completionRate}%`}
          caption="완료율"
          onClick={() => handleStatusFilter(INQUIRY_STATUS_FILTER.DONE)}
          active={statusFilter === INQUIRY_STATUS_FILTER.DONE}
        />
      </section>

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-4 px-5 py-4">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-bold">{inquiryListTitle(statusFilter)}</h2>
              <span className="rounded-full bg-primary-50 px-2.5 py-1 text-xs font-semibold text-primary-600">{selectedCount}</span>
            </div>
            <p className="mt-1 text-sm text-slate-400">
              {isDepartmentAdmin ? `내 담당 · ${managedDepartment.name} 범위` : '전체 부서 · 전체 관리자 범위'}
            </p>
          </div>
          <span className="rounded-lg border border-primary-200 bg-primary-50 px-3 py-2 text-xs font-semibold text-primary-600">
            ● {isDepartmentAdmin ? '부서 관리자' : '전체 관리자'}
          </span>
        </div>
        <div className="flex items-center gap-3 border-t border-slate-100 px-5 py-3">
          <SearchBar value={searchInput} onChange={setSearchInput} onSearch={(value) => { setKeyword(value); setPage(1) }} placeholder="문의 제목 또는 요청자로 검색" className="flex-1" />
          {isDepartmentAdmin ? null : (
            <div className="relative">
              <select
                value={departmentId}
                onChange={(event) => {
                  setDepartmentId(event.target.value)
                  setPage(1)
                }}
                className="focus-ring h-10 appearance-none rounded-lg border border-slate-200 bg-white pl-3 pr-9 text-sm text-slate-600"
              >
                <option value="">부서별</option>
                {departments.map((department) => (
                  <option key={department.departmentId} value={String(department.departmentId)}>
                    {department.name}
                  </option>
                ))}
              </select>
              <ChevronDown className="pointer-events-none absolute inset-y-0 right-3 my-auto size-4 text-slate-400" />
            </div>
          )}
          <div className="relative">
            <select value={sort} onChange={(event) => setSort(event.target.value)} className="focus-ring h-10 appearance-none rounded-lg border border-slate-200 bg-white pl-3 pr-9 text-sm text-slate-600">
              <option value="priority,desc">중요도별</option>
              <option value="createdAt,desc">최신순</option>
              <option value="createdAt,asc">오래된순</option>
            </select>
            <ChevronDown className="pointer-events-none absolute inset-y-0 right-3 my-auto size-4 text-slate-400" />
          </div>
        </div>
        <DataTable className="rounded-none border-0 shadow-none" columns={columns} rows={visibleInquiries} rowKey="inquiryId" loading={query.isLoading} emptyState={<EmptyState title="담당 문의가 없습니다." />} />
        <Pagination page={query.data?.page ?? page} totalPages={query.data?.totalPages ?? 1} onChange={setPage} className="border-t border-slate-100 py-4" />
      </section>
    </div>
  )
}
