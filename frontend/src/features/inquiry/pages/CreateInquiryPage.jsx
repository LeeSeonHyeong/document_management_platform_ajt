import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ChevronDown, ChevronLeft, Paperclip, Search, Trash2 } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'
import Modal from '@/components/ui/Modal'
import Textarea from '@/components/ui/Textarea'
import { useToast } from '@/components/ui'
import { FILE_ACCEPT, INQUIRY_ATTACHMENT_MAX_COUNT, MAX_FILE_SIZE_BYTES } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { createInquiry, fetchInquiryAssignees } from '../api'

const MAX_TOTAL_SIZE = 100 * 1024 * 1024

function FilePreview({ file }) {
  const [previewUrl, setPreviewUrl] = useState('')

  useEffect(() => {
    const url = URL.createObjectURL(file)
    setPreviewUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [file])

  return previewUrl
    ? <img src={previewUrl} alt={file.name} className="size-12 rounded-lg object-cover" />
    : <span className="size-12 rounded-lg bg-slate-100" />
}

export default function CreateInquiryPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const fileInputRef = useRef(null)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [priority, setPriority] = useState('normal')
  const [assigneeId, setAssigneeId] = useState('')
  const [assigneeOpen, setAssigneeOpen] = useState(false)
  const [assigneeKeyword, setAssigneeKeyword] = useState('')
  const [attachments, setAttachments] = useState([])
  const [isDragging, setIsDragging] = useState(false)
  const [createdInquiry, setCreatedInquiry] = useState(null)
  const assigneesQuery = useQuery({
    queryKey: qk.inquiries.assignees,
    queryFn: () => fetchInquiryAssignees(),
  })
  const allAssignees = useMemo(() => assigneesQuery.data?.items ?? [], [assigneesQuery.data?.items])
  const assignees = useMemo(
    () => allAssignees.filter((item) => item.name.includes(assigneeKeyword.trim())),
    [allAssignees, assigneeKeyword],
  )
  const selectedAssignee = useMemo(
    () => allAssignees.find((item) => item.assigneeId === assigneeId),
    [allAssignees, assigneeId],
  )

  const mutation = useMutation({
    mutationFn: () => createInquiry({ assigneeId, title: title.trim(), content: content.trim(), priority, attachments }),
    onSuccess: async (inquiry) => {
      await queryClient.invalidateQueries({ queryKey: qk.inquiries.all })
      setCreatedInquiry(inquiry)
    },
    onError: (error) => toast.error(error.message ?? '문의를 접수하지 못했습니다.'),
  })

  const addFiles = (fileList) => {
    const files = Array.from(fileList)
    const allowed = new Set(FILE_ACCEPT.INQUIRY_IMAGE)
    if (attachments.length + files.length > INQUIRY_ATTACHMENT_MAX_COUNT) {
      toast.error('첨부 이미지는 최대 5개까지 등록할 수 있습니다.')
      return
    }
    if (files.some((file) => !allowed.has(file.name.split('.').pop()?.toLowerCase()) || file.size > MAX_FILE_SIZE_BYTES)) {
      toast.error('PNG, JPG, JPEG 파일만 가능하며 파일당 최대 크기는 20MB입니다.')
      return
    }
    if ([...attachments, ...files].reduce((sum, file) => sum + file.size, 0) > MAX_TOTAL_SIZE) {
      toast.error('첨부 파일의 총 크기는 100MB를 넘을 수 없습니다.')
      return
    }
    setAttachments((current) => [...current, ...files])
  }

  const canSubmit = title.trim() && content.trim() && assigneeId

  return (
    <div className="space-y-5">
      <Link to="/inquiries" className="inline-flex items-center gap-1 text-sm font-medium text-slate-500"><ChevronLeft className="size-4" /> 문의사항</Link>
      <section className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
        <h2 className="text-xl font-bold">어떤 도움이 필요하신가요?</h2>
        <p className="mt-1 text-sm text-slate-500">문의 내용을 남겨주시면 담당자가 확인 후 빠르게 답변드릴게요.</p>
        <div className="mt-8 grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(17rem,22rem)]">
          <div className="space-y-5">
            <Input label="제목" value={title} onChange={(event) => setTitle(event.target.value)} placeholder="문의 제목을 입력하세요" maxLength={100} />
            <Textarea label="상세 내용" value={content} onChange={(event) => setContent(event.target.value)} placeholder="문의 내용을 자세히 작성해 주세요..." rows={6} maxLength={2000} />
            <div>
              <p className="mb-2 text-sm font-medium text-slate-700">첨부 파일</p>
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                onDragOver={(event) => { event.preventDefault(); setIsDragging(true) }}
                onDragLeave={(event) => { event.preventDefault(); setIsDragging(false) }}
                onDrop={(event) => { event.preventDefault(); setIsDragging(false); addFiles(event.dataTransfer.files) }}
                className={`focus-ring w-full rounded-xl border border-dashed px-5 py-5 text-sm transition-colors ${isDragging ? 'border-primary-400 bg-primary-50 text-primary-600' : 'border-slate-200 bg-slate-50 text-slate-500'}`}
              >
                <Paperclip className="pointer-events-none mx-auto mb-2 size-5" />
                <span className="pointer-events-none">파일을 끌어다 놓거나 클릭하여 첨부</span>
                <span className="pointer-events-none mt-1 block text-xs text-slate-400">PNG, JPG, JPEG / 최대 5개 / 파일당 20MB / 총 100MB</span>
              </button>
              <input ref={fileInputRef} type="file" accept=".png,.jpg,.jpeg" multiple hidden onChange={(event) => { addFiles(event.target.files); event.target.value = '' }} />
              {attachments.map((file, index) => (
                <div key={`${file.name}-${file.lastModified}`} className="mt-2 flex items-center gap-3 rounded-lg border border-slate-200 px-3 py-2 text-sm">
                  <FilePreview file={file} />
                  <span className="min-w-0 flex-1 truncate">{file.name}</span>
                  <button type="button" onClick={() => setAttachments((current) => current.filter((_, itemIndex) => itemIndex !== index))} aria-label={`${file.name} 삭제`}><Trash2 className="size-4 text-slate-400" /></button>
                </div>
              ))}
            </div>
          </div>

          <aside className="rounded-2xl border border-slate-200 bg-slate-50 p-5">
            <p className="text-sm font-bold">문의 담당자</p>
            <div className="relative mt-2">
              <button type="button" onClick={() => setAssigneeOpen((open) => !open)} className="focus-ring flex h-11 w-full items-center justify-between rounded-lg border border-slate-300 bg-white px-3 text-sm">
                <span>{selectedAssignee ? `${selectedAssignee.name} · ${selectedAssignee.department?.name}` : '담당자를 선택하세요'}</span>
                <ChevronDown className={`size-4 transition ${assigneeOpen ? 'rotate-180' : ''}`} />
              </button>
              {assigneeOpen && (
                <div className="absolute z-20 mt-2 w-full rounded-xl border border-slate-200 bg-white p-2 shadow-xl">
                  <div className="relative">
                    <Search className="absolute left-3 top-2.5 size-4 text-slate-400" />
                    <input value={assigneeKeyword} onChange={(event) => setAssigneeKeyword(event.target.value)} placeholder="담당자 검색..." className="focus-ring h-9 w-full rounded-lg border border-slate-200 pl-9 pr-3 text-sm" />
                  </div>
                  <div className="mt-2 max-h-48 space-y-1 overflow-auto">
                    {assignees.map((assignee) => (
                      <button type="button" key={assignee.assigneeId} onClick={() => { setAssigneeId(assignee.assigneeId); setAssigneeOpen(false) }} className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-sm ${assigneeId === assignee.assigneeId ? 'bg-primary-50 font-semibold text-primary-700' : 'hover:bg-slate-50'}`}>
                        <span>{assignee.name} · {assignee.department?.name}</span>
                        {assigneeId === assignee.assigneeId && <Check className="size-4" />}
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>

            <p className="mt-5 text-sm font-bold">우선순위</p>
            <div className="mt-2 flex gap-2">
              {[['high', '높음'], ['normal', '보통'], ['low', '낮음']].map(([value, label]) => (
                <button type="button" key={value} onClick={() => setPriority(value)} className={`focus-ring flex-1 rounded-lg px-2 py-2 text-sm ${priority === value ? 'border border-primary-500 bg-white font-semibold text-primary-600' : 'text-slate-500'}`}>{label}</button>
              ))}
            </div>
            <div className="mt-6 rounded-xl bg-primary-50 p-4 text-xs leading-6 text-slate-600">
              <p className="font-bold text-primary-600">작성 안내</p>
              <p>• 문제 발생 시점과 환경을 적어주세요.</p>
              <p>• 화면 캡처를 첨부하면 더 빨라요.</p>
              <p>• 긴급 문의는 우선순위를 높여주세요.</p>
            </div>
          </aside>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="ghost" onClick={() => navigate('/inquiries')}>취소</Button>
          <Button disabled={!canSubmit} loading={mutation.isPending} onClick={() => mutation.mutate()}>제출하기</Button>
        </div>
      </section>

      <Modal open={Boolean(createdInquiry)} onClose={() => navigate('/inquiries', { state: { selectedInquiryId: createdInquiry?.inquiryId } })} closeOnOverlay={false} size="md">
        <div className="py-4 text-center">
          <span className="mx-auto flex size-16 items-center justify-center rounded-full bg-emerald-50 text-emerald-500"><Check className="size-8" /></span>
          <h2 className="mt-5 text-xl font-bold">문의가 접수됐어요</h2>
          <p className="mt-3 text-sm text-slate-500">담당자가 내용을 확인한 뒤 알림으로 답변을 알려드릴게요.</p>
          <p className="mt-5 rounded-xl bg-slate-50 px-4 py-4 text-sm font-semibold">접수번호 {createdInquiry?.displayId ?? `INQ-${createdInquiry?.inquiryId}`} · 예상 답변 1영업일 이내</p>
          <Button className="mt-4" fullWidth onClick={() => navigate('/inquiries', { state: { selectedInquiryId: createdInquiry?.inquiryId } })}>내 문의 확인하기</Button>
        </div>
      </Modal>
    </div>
  )
}
