import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import Button from '@/components/ui/Button'
import DataTable from '@/components/ui/DataTable'
import EmptyState from '@/components/ui/EmptyState'
import Pagination from '@/components/ui/Pagination'
import SearchBar from '@/components/ui/SearchBar'
import { INQUIRY_PRIORITY, INQUIRY_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { fetchInquiries } from '../api'

const PRIORITY_META = {
  [INQUIRY_PRIORITY.HIGH]: { label: '높음', className: 'bg-rose-50 text-rose-600' },
  [INQUIRY_PRIORITY.NORMAL]: { label: '보통', className: 'bg-amber-50 text-amber-600' },
  [INQUIRY_PRIORITY.LOW]: { label: '낮음', className: 'bg-slate-100 text-slate-500' },
}

function PriorityBadge({ priority }) {
  const meta = PRIORITY_META[priority] ?? PRIORITY_META[INQUIRY_PRIORITY.NORMAL]
  return <span className={`rounded-lg px-3 py-1 text-xs font-semibold ${meta.className}`}>{meta.label}</span>
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

function StatCard({ label, value, suffix = '건', tone, caption, badge }) {
  const colors = {
    primary: 'bg-primary-50 text-primary-600',
    amber: 'bg-amber-50 text-amber-600',
    green: 'bg-emerald-50 text-emerald-600',
  }[tone]
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-start justify-between">
        <span className={`flex size-9 items-center justify-center rounded-xl ${colors}`}>
          <span className="size-3 rounded bg-current" />
        </span>
        <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${colors}`}>{badge}</span>
      </div>
      <p className="mt-3 text-sm text-slate-500">{label}</p>
      <p className="mt-2 text-3xl font-bold text-slate-900">{value}<span className="ml-1 text-sm font-medium text-slate-400">{suffix}</span></p>
      <p className="mt-3 text-xs text-slate-400">{caption}</p>
    </div>
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
  const [page, setPage] = useState(1)
  const [searchInput, setSearchInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [sort, setSort] = useState('priority,desc')
  const params = { page, size: 20, keyword: keyword || undefined, sort }
  const query = useQuery({
    queryKey: qk.inquiries.list(params),
    queryFn: () => fetchInquiries(params),
  })

  const inquiries = query.data?.items ?? []
  const pendingCount = inquiries.filter((item) => item.status === INQUIRY_STATUS.PENDING).length
  const doneCount = inquiries.filter((item) => item.status === INQUIRY_STATUS.DONE).length
  const totalCount = query.data?.totalCount ?? inquiries.length
  const completionRate = totalCount ? Math.round((doneCount / totalCount) * 100) : 0

  const columns = [
    {
      key: 'content',
      header: '문의 내용',
      render: (inquiry) => (
        <div className="flex items-center gap-3">
          <PriorityBadge priority={inquiry.priority} />
          <div>
            <p className="font-semibold text-slate-800">{inquiry.title}</p>
            <p className="mt-0.5 text-xs text-slate-400">{inquiry.author?.name} · {inquiry.author?.department?.name ?? '-'}</p>
          </div>
        </div>
      ),
    },
    { key: 'assignee', header: '담당자', render: (inquiry) => `${inquiry.assignee?.name} (${inquiry.assignee?.department?.name ?? '-'})` },
    { key: 'status', header: '상태', render: (inquiry) => <StatusBadge status={inquiry.status} /> },
    { key: 'createdAt', header: '접수 시간', render: (inquiry) => relativeTime(inquiry.createdAt) },
    {
      key: 'manage',
      header: '관리',
      render: (inquiry) => (
        <Button size="sm" variant="outline" onClick={() => navigate(`/admin/inquiries/${inquiry.inquiryId}`)}>상세</Button>
      ),
    },
  ]

  return (
    <div className="space-y-5">
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard label="전체 문의" value={totalCount} tone="primary" badge="주간" caption="이번 주 내 담당" />
        <StatCard label="미처리" value={pendingCount} tone="amber" badge="확인 필요" caption="답변 대기 중" />
        <StatCard label="처리 완료" value={doneCount} tone="green" badge={`${completionRate}%`} caption="완료율" />
      </section>

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-4 px-5 py-4">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-bold">대기 중인 문의</h2>
              <span className="rounded-full bg-amber-50 px-2.5 py-1 text-xs font-semibold text-amber-600">{pendingCount}</span>
            </div>
            <p className="mt-1 text-sm text-slate-400">내 담당 · 개발팀 범위</p>
          </div>
          <span className="rounded-lg border border-primary-200 bg-primary-50 px-3 py-2 text-xs font-semibold text-primary-600">● 부서 관리자</span>
        </div>
        <div className="flex items-center gap-3 border-t border-slate-100 px-5 py-3">
          <SearchBar value={searchInput} onChange={setSearchInput} onSearch={(value) => { setKeyword(value); setPage(1) }} placeholder="문의 제목 또는 요청자로 검색" className="flex-1" />
          <select value={sort} onChange={(event) => setSort(event.target.value)} className="focus-ring h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm text-slate-600">
            <option value="priority,desc">중요도별</option>
            <option value="createdAt,desc">최신순</option>
            <option value="createdAt,asc">오래된순</option>
          </select>
        </div>
        <DataTable className="rounded-none border-0 shadow-none" columns={columns} rows={inquiries} rowKey="inquiryId" loading={query.isLoading} emptyState={<EmptyState title="담당 문의가 없습니다." />} />
        <Pagination page={query.data?.page ?? page} totalPages={query.data?.totalPages ?? 1} onChange={setPage} className="border-t border-slate-100 py-4" />
      </section>
    </div>
  )
}
