import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, FileImage, Info } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import Button from '@/components/ui/Button'
import Card from '@/components/ui/Card'
import ConfirmDialog from '@/components/ui/ConfirmDialog'
import EmptyState from '@/components/ui/EmptyState'
import Spinner from '@/components/ui/Spinner'
import { useToast } from '@/components/ui'
import { INQUIRY_PRIORITY_LABELS, INQUIRY_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { fetchInquiry, saveInquiryAnswer } from '../api'

function formatDate(value) {
  if (!value) return '-'
  const date = new Date(value)
  const pad = (number) => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export default function InquiryDetailPage() {
  const { inquiryId } = useParams()
  const queryClient = useQueryClient()
  const toast = useToast()
  const [answer, setAnswer] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const query = useQuery({
    queryKey: qk.inquiries.detail(inquiryId),
    queryFn: () => fetchInquiry(inquiryId),
  })

  useEffect(() => {
    if (query.data?.answer?.content) setAnswer(query.data.answer.content)
  }, [query.data])

  const mutation = useMutation({
    mutationFn: () => saveInquiryAnswer(inquiryId, answer.trim()),
    onSuccess: async () => {
      setConfirmOpen(false)
      await queryClient.invalidateQueries({ queryKey: qk.inquiries.all })
      toast.success('답변이 등록되었습니다.')
    },
    onError: (error) => {
      setConfirmOpen(false)
      toast.error(error.message ?? '답변을 등록하지 못했습니다.')
    },
  })

  if (query.isLoading) return <div className="flex justify-center py-24"><Spinner /></div>
  if (!query.data) return <EmptyState title="문의를 찾을 수 없습니다." />
  const inquiry = query.data
  const pending = inquiry.status === INQUIRY_STATUS.PENDING

  return (
    <div className="space-y-5">
      <Link to="/admin/inquiries" className="inline-flex items-center gap-1 text-sm font-medium text-slate-500"><ChevronLeft className="size-4" /> 문의 관리</Link>
      <div className="grid gap-5 lg:grid-cols-[2fr_1fr]">
        <div className="space-y-5">
          <Card className="p-6">
            <div className="flex items-center justify-between">
              <div className="flex gap-2">
                <span className="rounded-lg bg-rose-50 px-3 py-1 text-xs font-semibold text-rose-600">{INQUIRY_PRIORITY_LABELS[inquiry.priority]}</span>
                <span className={`rounded-lg px-3 py-1 text-xs font-semibold ${pending ? 'bg-amber-50 text-amber-600' : 'bg-emerald-50 text-emerald-600'}`}>● {pending ? '미처리' : '처리완료'}</span>
              </div>
              <span className="rounded-lg bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-500">{inquiry.displayId ?? `INQ-${inquiry.inquiryId}`}</span>
            </div>
            <h2 className="mt-4 text-2xl font-bold">{inquiry.title}</h2>
            <p className="mt-3 border-b border-slate-100 pb-4 text-sm text-slate-400">{inquiry.author?.name} · {inquiry.author?.department?.name}　·　접수 {formatDate(inquiry.createdAt)}</p>
            <h3 className="mt-4 text-sm font-semibold text-slate-400">문의 내용</h3>
            <div className="mt-3 whitespace-pre-line rounded-xl bg-slate-50 p-5 text-sm leading-7 text-slate-600">{inquiry.content}</div>
            <h3 className="mt-4 text-sm font-semibold text-slate-400">첨부 파일</h3>
            <div className="mt-3 flex flex-wrap gap-2">
              {(inquiry.attachments ?? []).length ? inquiry.attachments.map((file) => (
                <a key={file.attachmentId ?? file.name} href={file.downloadUrl} target="_blank" rel="noreferrer" className="overflow-hidden rounded-xl border border-slate-200">
                  {file.downloadUrl ? (
                    <img src={file.downloadUrl} alt={file.name} className="h-32 w-48 object-cover" />
                  ) : (
                    <span className="flex h-20 w-48 items-center justify-center bg-slate-50 text-primary-500"><FileImage className="size-6" /></span>
                  )}
                  <span className="flex items-center justify-between gap-3 px-3 py-2">
                    <span className="max-w-32 truncate text-xs font-semibold">{file.name}</span>
                    <span className="text-xs text-slate-400">{file.sizeLabel ?? ''}</span>
                  </span>
                </a>
              )) : <span className="text-sm text-slate-400">첨부 파일 없음</span>}
            </div>
          </Card>

          <Card className="p-6">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-bold">답변 작성</h2>
              <span className="rounded-lg bg-slate-100 px-3 py-1 text-xs text-slate-400">{answer.length} / 2000자</span>
            </div>
            <textarea value={answer} onChange={(event) => setAnswer(event.target.value.slice(0, 2000))} rows={7} placeholder="요청자에게 전달할 답변을 입력해주세요." className="focus-ring mt-4 w-full resize-none rounded-xl border border-primary-300 bg-slate-50 p-4 text-sm leading-6" />
            <p className="mt-3 flex items-center gap-2 rounded-lg bg-primary-50 px-4 py-3 text-xs text-slate-500"><Info className="size-4 text-primary-500" /> 등록하면 요청자에게 알림 메일이 발송되고 상태가 처리완료로 변경됩니다.</p>
            <div className="mt-4 flex justify-end gap-2">
              <Button variant="outline" onClick={() => setAnswer('')}>취소</Button>
              <Button disabled={!answer.trim()} onClick={() => setConfirmOpen(true)}>{pending ? '답변 등록' : '답변 수정'}</Button>
            </div>
          </Card>
        </div>

        <Card className="h-fit p-6">
          <h2 className="border-b border-slate-100 pb-4 text-lg font-bold">문의 정보</h2>
          <dl className="mt-4 space-y-4 text-sm">
            <div className="flex justify-between"><dt className="text-slate-400">중요도</dt><dd className="font-semibold">{INQUIRY_PRIORITY_LABELS[inquiry.priority]}</dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">상태</dt><dd className="font-semibold">{pending ? '미처리' : '처리완료'}</dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">요청자</dt><dd className="font-semibold">{inquiry.author?.name}</dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">요청자 부서</dt><dd className="font-semibold">{inquiry.author?.department?.name}</dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">접수 시간</dt><dd className="font-semibold">{formatDate(inquiry.createdAt)}</dd></div>
          </dl>
          <div className="mt-5 border-t border-slate-100 pt-4">
            <p className="mb-2 text-xs text-slate-400">담당자</p>
            <div className="flex items-center gap-3 rounded-xl border border-slate-200 px-3 py-3">
              <span className="flex size-8 items-center justify-center rounded-full bg-primary-600 text-sm font-bold text-white">{inquiry.assignee?.name?.slice(0, 1)}</span>
              <span className="font-semibold">{inquiry.assignee?.name} ({inquiry.assignee?.department?.name})</span>
            </div>
            <p className="mt-3 text-xs text-slate-400">최신 API 계약에서는 등록 후 담당자를 변경할 수 없습니다.</p>
          </div>
        </Card>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={() => mutation.mutate()}
        title="답변을 등록할까요?"
        description={`등록하면 요청자(${inquiry.author?.name} · ${inquiry.author?.department?.name})에게 작성한 답변과 함께 알림 메일이 발송되고, 문의 상태가 자동으로 처리완료로 변경됩니다.`}
        confirmLabel="등록하기"
        loading={mutation.isPending}
      />
    </div>
  )
}
