import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronRight, MessageSquarePlus, Paperclip, UserRound } from 'lucide-react'
import { useLocation, useNavigate } from 'react-router-dom'
import Button from '@/components/ui/Button'
import Card from '@/components/ui/Card'
import EmptyState from '@/components/ui/EmptyState'
import Spinner from '@/components/ui/Spinner'
import { INQUIRY_PRIORITY_LABELS, INQUIRY_STATUS, INQUIRY_STATUS_LABELS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { fetchInquiries, fetchInquiry } from '../api'
import { resolveSelectedInquiryId } from '../inquirySelection'

const PRIORITY_STYLE = {
  high: 'bg-rose-50 text-rose-500',
  normal: 'bg-amber-50 text-amber-600',
  low: 'bg-slate-100 text-slate-500',
}

const STATUS_STYLE = {
  [INQUIRY_STATUS.PENDING]: 'bg-amber-50 text-amber-600',
  [INQUIRY_STATUS.DONE]: 'bg-emerald-50 text-emerald-600',
}

function relativeTime(value) {
  const minutes = Math.max(1, Math.floor((Date.now() - new Date(value).getTime()) / 60000))
  if (minutes < 60) return `${minutes}분 전`
  if (minutes < 1440) return `${Math.floor(minutes / 60)}시간 전`
  if (minutes < 2880) return '어제'
  return `${Math.floor(minutes / 1440)}일 전`
}

function formatDateTime(value) {
  return value
    ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
    : '-'
}

// 담당자/작성자 "이름 · 부서" 표기. 부서가 없으면 이름만.
function personLabel(person) {
  if (!person?.name) return null
  return person.department?.name ? `${person.name} · ${person.department.name}` : person.name
}

function displayNo(inquiry) {
  return inquiry.displayId ?? `INQ-${inquiry.inquiryId}`
}

export default function EmployeeInquiriesPage() {
  const navigate = useNavigate()
  // 문의 작성 완료 후 넘어온 경우, 방금 작성한 문의를 선택 상태로 연다.
  const location = useLocation()
  const [filter, setFilter] = useState('all')
  const [selectedId, setSelectedId] = useState(location.state?.selectedInquiryId ?? null)
  const params = {
    page: 1,
    size: 20,
    status: ['pending', 'done'].includes(filter) ? filter : undefined,
    // 백엔드는 createdAt/updatedAt 정렬만 허용하므로 중요도는 응답 목록에서 정렬한다.
    sort: 'createdAt,desc',
  }
  const listQuery = useQuery({
    queryKey: qk.inquiries.list(params),
    queryFn: () => fetchInquiries(params),
  })
  const inquiries = useMemo(() => {
    const items = listQuery.data?.items ?? []
    if (filter !== 'priority') return items
    const priorityOrder = { high: 3, normal: 2, low: 1 }
    return [...items].sort(
      (left, right) =>
        (priorityOrder[right.priority] ?? 0) - (priorityOrder[left.priority] ?? 0),
    )
  }, [filter, listQuery.data?.items])

  useEffect(() => {
    const next = resolveSelectedInquiryId(inquiries, selectedId, listQuery.isFetching)
    if (next !== selectedId) setSelectedId(next)
  }, [inquiries, selectedId, listQuery.isFetching])

  const detailQuery = useQuery({
    queryKey: qk.inquiries.detail(selectedId),
    queryFn: () => fetchInquiry(selectedId),
    enabled: Boolean(selectedId),
  })
  const selected = detailQuery.data
  const countLabel = useMemo(
    () => `${listQuery.data?.totalCount ?? inquiries.length}건`,
    [inquiries.length, listQuery.data?.totalCount],
  )

  return (
    <div className="grid h-[calc(100vh-124px)] gap-6 lg:grid-cols-[2fr_3fr]">
      {/* 좌측 — 문의하기 버튼 + 필터 + 내 문의 목록(바) */}
      <div className="flex min-h-0 flex-col gap-4">
        <Button size="lg" fullWidth onClick={() => navigate('/inquiries/new')}>
          <MessageSquarePlus className="size-5" /> 문의하기
        </Button>
        <Card className="flex min-h-0 flex-1 flex-col p-5">
          <div className="flex items-center justify-between gap-3">
            <h2 className="font-bold">
              내 문의 <span className="ml-2 rounded-md bg-primary-50 px-2 py-1 text-xs text-primary-600">{countLabel}</span>
            </h2>
            <select
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              className="focus-ring h-9 rounded-lg border border-slate-200 bg-white px-2 text-xs text-slate-600"
            >
              <option value="all">전체</option>
              <option value="pending">미처리</option>
              <option value="done">처리 완료</option>
              <option value="priority">중요도</option>
              <option value="date">날짜순</option>
            </select>
          </div>
          <div className="mt-5 min-h-0 flex-1 space-y-3 overflow-y-auto pr-1">
            {listQuery.isLoading ? (
              <div className="flex justify-center py-10"><Spinner /></div>
            ) : inquiries.length === 0 ? (
              <EmptyState
                title="등록된 문의가 없습니다"
                description="문의하기 버튼을 눌러 새 문의를 남겨보세요."
              />
            ) : (
              inquiries.map((inquiry) => (
                <button
                  type="button"
                  key={inquiry.inquiryId}
                  onClick={() => setSelectedId(inquiry.inquiryId)}
                  className={`focus-ring w-full rounded-xl border p-4 text-left transition ${selectedId === inquiry.inquiryId ? 'border-primary-200 bg-primary-50/30' : 'border-slate-200 hover:bg-slate-50'}`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="flex items-center gap-1.5 text-xs">
                      <span className={`rounded-md px-2 py-0.5 font-semibold ${PRIORITY_STYLE[inquiry.priority]}`}>
                        {INQUIRY_PRIORITY_LABELS[inquiry.priority]}
                      </span>
                      <span className={`rounded-md px-2 py-0.5 font-semibold ${STATUS_STYLE[inquiry.status]}`}>
                        {INQUIRY_STATUS_LABELS[inquiry.status]}
                      </span>
                    </span>
                    <ChevronRight className="size-4 shrink-0 text-slate-300" />
                  </div>
                  <p className="mt-2 truncate text-sm font-bold text-slate-800">{inquiry.title}</p>
                  <div className="mt-2 flex items-center justify-between gap-2 text-xs text-slate-400">
                    <span className="truncate">{displayNo(inquiry)}</span>
                    <span className="shrink-0">{relativeTime(inquiry.createdAt)}</span>
                  </div>
                  {inquiry.assignee?.name && (
                    <p className="mt-1 truncate text-xs text-slate-400">담당 {inquiry.assignee.name}</p>
                  )}
                </button>
              ))
            )}
          </div>
        </Card>
      </div>

      {/* 우측 — 선택 문의 상세 */}
      <Card className="min-h-0 overflow-y-auto p-8">
        {detailQuery.isLoading ? (
          <div className="flex h-full items-center justify-center"><Spinner /></div>
        ) : selected ? (
          <>
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <div className="flex items-center gap-1.5">
                  <span className={`rounded-lg px-3 py-1 text-xs font-semibold ${PRIORITY_STYLE[selected.priority]}`}>
                    {INQUIRY_PRIORITY_LABELS[selected.priority]}
                  </span>
                  <span className={`rounded-lg px-3 py-1 text-xs font-semibold ${STATUS_STYLE[selected.status]}`}>
                    {INQUIRY_STATUS_LABELS[selected.status]}
                  </span>
                </div>
                <h2 className="mt-5 text-2xl font-bold text-slate-900">{selected.title}</h2>
              </div>
              <span className="shrink-0 text-sm text-slate-400">{relativeTime(selected.createdAt)}</span>
            </div>

            <dl className="mt-5 grid gap-x-6 gap-y-2 border-y border-slate-100 py-4 text-sm sm:grid-cols-2">
              <div className="flex gap-2">
                <dt className="shrink-0 text-slate-400">문의번호</dt>
                <dd className="font-medium text-slate-700">{displayNo(selected)}</dd>
              </div>
              <div className="flex gap-2">
                <dt className="shrink-0 text-slate-400">등록 일시</dt>
                <dd className="font-medium text-slate-700">{formatDateTime(selected.createdAt)}</dd>
              </div>
              {personLabel(selected.assignee) && (
                <div className="flex gap-2">
                  <dt className="shrink-0 text-slate-400">담당자</dt>
                  <dd className="truncate font-medium text-slate-700">{personLabel(selected.assignee)}</dd>
                </div>
              )}
            </dl>

            <p className="mt-6 whitespace-pre-line text-base leading-7 text-slate-700">{selected.content}</p>

            {selected.attachments?.length > 0 && (
              <div className="mt-8">
                <h3 className="flex items-center gap-1.5 text-sm font-semibold text-slate-500">
                  <Paperclip className="size-4" /> 첨부 이미지 {selected.attachments.length}
                </h3>
                <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {selected.attachments.map((attachment) => (
                    <a
                      key={attachment.attachmentId}
                      href={attachment.downloadUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="group overflow-hidden rounded-xl border border-slate-200 bg-slate-50"
                    >
                      {attachment.downloadUrl && (
                        <img src={attachment.downloadUrl} alt={attachment.fileName} className="h-36 w-full object-cover transition group-hover:scale-105" />
                      )}
                      <p className="truncate px-3 py-2 text-xs font-medium text-slate-600">{attachment.fileName}</p>
                    </a>
                  ))}
                </div>
              </div>
            )}

            {selected.answer?.content ? (
              <div className="mt-10 rounded-xl bg-slate-50 p-6">
                <div className="flex items-center justify-between gap-2">
                  <p className="flex items-center gap-2 font-semibold text-primary-600">
                    <span className="flex size-7 items-center justify-center rounded-full bg-primary-100"><UserRound className="size-4" /></span>
                    {selected.answer.adminName ?? selected.assignee?.name ?? '관리자'}
                  </p>
                  <span className="text-xs text-slate-400">
                    {formatDateTime(selected.answer.createdAt)}
                    {selected.answer.updatedAt && selected.answer.updatedAt !== selected.answer.createdAt && ' (수정됨)'}
                  </span>
                </div>
                <p className="mt-4 text-sm leading-6 text-slate-700">{selected.answer.content}</p>
              </div>
            ) : (
              <div className="mt-10 rounded-xl border border-dashed border-slate-200 bg-slate-50/60 px-6 py-5 text-sm text-slate-400">
                아직 답변이 등록되지 않았어요. 담당자가 확인한 뒤 답변을 남겨드릴게요.
              </div>
            )}
          </>
        ) : (
          <div className="flex h-full items-center justify-center">
            <EmptyState
              title="문의를 선택하세요"
              description="왼쪽 목록에서 문의를 선택하면 상세 내용을 볼 수 있습니다."
            />
          </div>
        )}
      </Card>
    </div>
  )
}
