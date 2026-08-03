import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FileText, Sparkles } from 'lucide-react'
import { Badge, Button, EmptyState, Spinner } from '@/components/ui'
import { useAiJobs, useRetryDocument } from '../queries'
import { useDocumentDetails } from '../hooks/useDocumentDetails'
import DocumentSectionTabs from '../components/DocumentSectionTabs'
import AiJobDocumentSummaryModal from '../components/AiJobDocumentSummaryModal'
import { DOC_STATUS_LABEL, DOC_STATUS_TONE } from '../status'

// 종료된 작업만 요약이 있다. 진행 중인 작업은 진행 화면이 따로 있다.
const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])

function formatDateTime(iso) {
  if (!iso) return '최근 작업'
  return new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatFileSize(bytes) {
  if (!bytes && bytes !== 0) return '-'
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function formatDuration(startedAt, finishedAt) {
  if (!startedAt || !finishedAt) return null
  const seconds = Math.round((new Date(finishedAt) - new Date(startedAt)) / 1000)
  if (!Number.isFinite(seconds) || seconds < 0) return null
  if (seconds < 60) return `${seconds}초`
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return rest === 0 ? `${minutes}분` : `${minutes}분 ${rest}초`
}

export default function AiJobSummaryListPage() {
  const { data, isLoading } = useAiJobs({ page: 1, size: 20 })
  const [selected, setSelected] = useState(null)

  const jobs = (data?.items ?? []).filter((job) => TERMINAL_STATUSES.has(job.status))
  // 문서 상세는 한 번에 모아 요청한다. AI 작업 응답에는 파일명·공개 범위가 없다.
  const documentIds = [
    ...new Set(jobs.flatMap((job) => job.documentResults.map((result) => result.documentId))),
  ]
  const docById = useDocumentDetails(documentIds)
  const totalDocuments = jobs.reduce((sum, job) => sum + job.documentResults.length, 0)
  const allApplied =
    totalDocuments > 0 &&
    jobs.every((job) => job.documentResults.every((result) => result.status === 'completed'))

  return (
    <section className="space-y-5">
      <DocumentSectionTabs />

      <div className="flex items-center justify-between">
        <p className="text-sm text-slate-500">
          총 <strong className="text-slate-800">{jobs.length}회 작업</strong>
          <span className="mx-2 text-slate-300">•</span>
          문서 {totalDocuments}개
        </p>
        {allApplied && <Badge tone="success">모두 위키 반영 완료</Badge>}
      </div>

      {isLoading ? (
        <div className="flex justify-center py-20">
          <Spinner />
        </div>
      ) : jobs.length === 0 ? (
        <div className="rounded-2xl border border-slate-200 bg-white">
          <EmptyState title="완료된 AI 작업이 없습니다." description="문서를 업로드하고 AI 작업을 실행해보세요." />
        </div>
      ) : (
        <div className="space-y-4">
          {jobs.map((job) => (
            <JobCard key={job.jobId} job={job} docById={docById} onOpenSummary={setSelected} />
          ))}
        </div>
      )}

      <SummaryModal selected={selected} docById={docById} onClose={() => setSelected(null)} />
    </section>
  )
}

function SummaryModal({ selected, docById, onClose }) {
  const navigate = useNavigate()
  const retryMutation = useRetryDocument()

  function handleRetry(documentId) {
    retryMutation.mutate(documentId, {
      // 재처리는 새 작업을 만든다. 그 작업의 진행 화면으로 옮겨간다.
      onSuccess: (data) => {
        onClose()
        if (data?.jobId) navigate(`/admin/documents/jobs/${data.jobId}/progress`)
      },
    })
  }

  return (
    <AiJobDocumentSummaryModal
      open={Boolean(selected)}
      onClose={onClose}
      job={selected?.job}
      result={selected?.result}
      document={selected ? docById[selected.result.documentId] : undefined}
      onRetry={handleRetry}
      retrying={retryMutation.isPending}
    />
  )
}

function JobCard({ job, docById, onOpenSummary }) {
  const duration = formatDuration(job.startedAt, job.finishedAt)

  return (
    <article className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
        <div className="flex items-center gap-3">
          <span className="flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-violet-600 text-white">
            <Sparkles className="size-5" />
          </span>
          <div className="flex items-center gap-2">
            <h2 className="font-bold text-slate-800">{formatDateTime(job.createdAt)} 작업</h2>
            <span className="rounded-full bg-primary-50 px-2 py-0.5 text-xs font-semibold text-primary-600">
              문서 {job.documentResults.length}개
            </span>
          </div>
        </div>
        {duration && <span className="text-xs text-slate-400">소요 {duration}</span>}
      </div>

      <div className="grid grid-cols-[minmax(0,2fr)_1fr_1fr_minmax(0,1.4fr)_110px_100px] gap-3 bg-slate-50 px-5 py-3 text-xs font-semibold text-slate-500">
        <span className="text-center">파일명</span>
        <span className="text-center">공개 부서</span>
        <span className="text-center">카테고리</span>
        <span className="text-center">생성된 위키 문서</span>
        <span className="text-center">상태</span>
        <span className="text-center">관리</span>
      </div>

      <ul className="divide-y divide-slate-100">
        {job.documentResults.map((result) => {
          const document = docById[result.documentId]
          return (
            <li
              key={result.documentId}
              className="grid grid-cols-[minmax(0,2fr)_1fr_1fr_minmax(0,1.4fr)_110px_100px] items-center gap-3 px-5 py-3 text-sm"
            >
              <div className="flex min-w-0 items-center gap-3">
                <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-500">
                  <FileText className="size-4" />
                </span>
                <div className="min-w-0">
                  <p className="truncate font-semibold text-slate-800">
                    {document?.originalFileName ?? `문서 ${result.documentId}`}
                  </p>
                  <p className="text-xs text-slate-400">{formatFileSize(document?.fileSize)}</p>
                </div>
              </div>
              <span className="truncate text-center text-slate-500">
                {document?.visibilityType === 'all'
                  ? '전체 공개'
                  : (document?.departments ?? []).map((department) => department.name).join(', ') || '-'}
              </span>
              <span className="truncate text-center text-slate-500">
                {document?.documentCategoryName ?? '미분류'}
              </span>
              <span className="truncate text-center font-semibold text-primary-600">
                {document?.relatedWikis?.[0]?.title ?? '-'}
              </span>
              <div className="flex justify-center">
                <Badge tone={DOC_STATUS_TONE[result.status] ?? 'neutral'}>
                  {DOC_STATUS_LABEL[result.status] ?? result.status}
                </Badge>
              </div>
              <div className="flex justify-center">
                <Button size="sm" variant="outline" onClick={() => onOpenSummary({ job, result })}>
                  요약 보기
                </Button>
              </div>
            </li>
          )
        })}
      </ul>
    </article>
  )
}
