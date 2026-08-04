import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronRight, MessageSquarePlus, UserRound } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import Button from '@/components/ui/Button'
import Card from '@/components/ui/Card'
import EmptyState from '@/components/ui/EmptyState'
import Spinner from '@/components/ui/Spinner'
import { INQUIRY_PRIORITY_LABELS, INQUIRY_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { fetchInquiries, fetchInquiry } from '../api'

const PRIORITY_STYLE = {
  high: 'bg-rose-50 text-rose-500',
  normal: 'bg-amber-50 text-amber-600',
  low: 'bg-slate-100 text-slate-500',
}

function relativeTime(value) {
  const minutes = Math.max(1, Math.floor((Date.now() - new Date(value).getTime()) / 60000))
  if (minutes < 60) return `${minutes}분 전`
  if (minutes < 1440) return `${Math.floor(minutes / 60)}시간 전`
  if (minutes < 2880) return '어제'
  return `${Math.floor(minutes / 1440)}일 전`
}

export default function EmployeeInquiriesPage() {
  const navigate = useNavigate()
  const [filter, setFilter] = useState('all')
  const [selectedId, setSelectedId] = useState(null)
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
    if (!inquiries.length) {
      setSelectedId(null)
      return
    }
    if (!inquiries.some((item) => item.inquiryId === selectedId)) {
      setSelectedId(inquiries[0].inquiryId)
    }
  }, [inquiries, selectedId])

  const detailQuery = useQuery({
    queryKey: qk.inquiries.detail(selectedId),
    queryFn: () => fetchInquiry(selectedId),
    enabled: Boolean(selectedId),
  })
  const selected = detailQuery.data
  const countLabel = useMemo(() => `${listQuery.data?.totalCount ?? inquiries.length}건`, [inquiries.length, listQuery.data?.totalCount])

  return (
    <div className="grid min-h-[calc(100vh-124px)] gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(17rem,22rem)]">
      <Card className="p-8">
        {detailQuery.isLoading ? (
          <div className="flex h-full items-center justify-center"><Spinner /></div>
        ) : selected ? (
          <>
            <div className="flex items-start justify-between gap-4">
              <div>
                <span className={`rounded-lg px-3 py-1 text-xs font-semibold ${PRIORITY_STYLE[selected.priority]}`}>
                  {INQUIRY_PRIORITY_LABELS[selected.priority]}
                </span>
                <h2 className="mt-5 text-2xl font-bold text-slate-900">{selected.title}</h2>
              </div>
              <span className="text-sm text-slate-400">{relativeTime(selected.createdAt)}</span>
            </div>
            <p className="mt-7 whitespace-pre-line text-base leading-7 text-slate-700">{selected.content}</p>
            {selected.attachments?.length > 0 && (
              <div className="mt-8">
                <h3 className="text-sm font-semibold text-slate-500">첨부 이미지</h3>
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
                        <img src={attachment.downloadUrl} alt={attachment.name} className="h-36 w-full object-cover transition group-hover:scale-105" />
                      )}
                      <p className="truncate px-3 py-2 text-xs font-medium text-slate-600">{attachment.name}</p>
                    </a>
                  ))}
                </div>
              </div>
            )}
            {selected.answer?.content && (
              <div className="mt-10 rounded-xl bg-slate-50 p-6">
                <p className="flex items-center gap-2 font-semibold text-primary-600">
                  <span className="flex size-7 items-center justify-center rounded-full bg-primary-100"><UserRound className="size-4" /></span>
                  {selected.assignee?.name ?? '관리자'}
                </p>
                <p className="mt-4 text-sm leading-6 text-slate-700">{selected.answer.content}</p>
              </div>
            )}
          </>
        ) : (
          <EmptyState title="등록된 문의가 없습니다." description="문의하기 버튼을 눌러 새 문의를 남겨보세요." />
        )}
      </Card>

      <div className="space-y-5">
        <Button size="lg" fullWidth onClick={() => navigate('/inquiries/new')}>
          <MessageSquarePlus className="size-5" /> 문의하기
        </Button>
        <Card className="min-h-[620px] p-5">
          <div className="flex items-center justify-between gap-3">
            <h2 className="font-bold">내 문의 <span className="ml-2 rounded-md bg-primary-50 px-2 py-1 text-xs text-primary-600">{countLabel}</span></h2>
            <select value={filter} onChange={(event) => setFilter(event.target.value)} className="focus-ring h-9 rounded-lg border border-slate-200 bg-white px-2 text-xs text-slate-600">
              <option value="all">전체</option>
              <option value="pending">미처리</option>
              <option value="done">처리 완료</option>
              <option value="priority">중요도</option>
              <option value="date">날짜순</option>
            </select>
          </div>
          <div className="mt-5 space-y-3">
            {listQuery.isLoading && <div className="flex justify-center py-10"><Spinner /></div>}
            {!listQuery.isLoading && inquiries.map((inquiry) => (
              <button
                type="button"
                key={inquiry.inquiryId}
                onClick={() => setSelectedId(inquiry.inquiryId)}
                className={`focus-ring w-full rounded-xl border p-4 text-left transition ${selectedId === inquiry.inquiryId ? 'border-primary-200 bg-primary-50/30' : 'border-slate-200 hover:bg-slate-50'}`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="flex items-center gap-2 text-xs">
                    <span className={`size-2 rounded-full ${inquiry.status === INQUIRY_STATUS.DONE ? 'bg-emerald-300' : 'bg-rose-500'}`} />
                    <span className={`rounded-md px-2 py-0.5 font-semibold ${PRIORITY_STYLE[inquiry.priority]}`}>{INQUIRY_PRIORITY_LABELS[inquiry.priority]}</span>
                  </span>
                  <ChevronRight className="size-4 text-slate-300" />
                </div>
                <p className="mt-2 truncate text-sm font-bold">{inquiry.title}</p>
                <div className="mt-3 flex justify-between text-xs text-slate-400">
                  <span>{inquiry.displayId ?? `INQ-${inquiry.inquiryId}`}</span>
                  <span>{relativeTime(inquiry.createdAt)}</span>
                </div>
              </button>
            ))}
          </div>
        </Card>
      </div>
    </div>
  )
}
