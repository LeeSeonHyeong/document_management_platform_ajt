import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ChevronRight, FileText, Loader2, Sparkles } from 'lucide-react'
import { Badge, Button, EmptyState, Spinner } from '@/components/ui'
import { useAiJobs, useRetryDocument } from '../queries'
import { useDocumentDetails } from '../hooks/useDocumentDetails'
import DocumentSectionTabs from '../components/DocumentSectionTabs'
import AiJobDocumentSummaryModal from '../components/AiJobDocumentSummaryModal'
import AffectedWikiDisplay from '../components/AffectedWikiDisplay'
import { DOC_STATUS_LABEL, DOC_STATUS_TONE } from '../status'
import {
  actionTypeCountsLabel,
  affectedWikisFor,
  documentAction,
} from '../aiJobResultPresentation'

// 종료된 작업만 요약이 있다. 진행 중인 작업은 위쪽에 따로 묶어 진행 화면으로 보낸다.
const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])
// 문서 결과의 종료 상태. 작업이 취소돼도 그때 처리 중이던 문서는 여기 도달할 때까지 계속 돈다.
const TERMINAL_DOCUMENT_STATUSES = new Set(['completed', 'failed', 'cancelled'])

const JOB_STATUS_LABEL = {
  waiting: '대기 중',
  processing: '처리 중',
}

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
  // 진행 중인 작업이 있으면 그것도 보여줘야 하므로 짧게 폴링한다. 끝나면 멈춘다.
  const { data, isLoading } = useAiJobs({ page: 1, size: 20 })
  const [selected, setSelected] = useState(null)

  const allJobs = data?.items ?? []
  const runningJobs = allJobs.filter((job) => !TERMINAL_STATUSES.has(job.status))
  const jobs = allJobs.filter((job) => TERMINAL_STATUSES.has(job.status))
  // 문서 상세는 한 번에 모아 요청한다. AI 작업 응답에는 파일명·공개 범위가 없다.
  const documentIds = [
    ...new Set(allJobs.flatMap((job) => job.documentResults.map((result) => result.documentId))),
  ]
  const docById = useDocumentDetails(documentIds)
  const totalDocuments = jobs.reduce((sum, job) => sum + job.documentResults.length, 0)
  const allApplied =
    totalDocuments > 0 &&
    jobs.every((job) => job.documentResults.every((result) => result.status === 'completed'))

  return (
    <section className="space-y-5">
      <DocumentSectionTabs />

      {/* 진행 중인 작업은 요약이 아직 없다. 그래도 여기 있어야 관리자가 찾아갈 수 있다
          — 예전에는 작업이 끝날 때까지 화면 어디에도 없었다 (S15P11B106-200). */}
      {runningJobs.length > 0 && <RunningJobs jobs={runningJobs} docById={docById} />}

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

/** 아직 끝나지 않은 작업. 누르면 진행 화면으로 간다 — 그 화면이 문서별 단계를 2초마다 갱신한다. */
function RunningJobs({ jobs, docById }) {
  return (
    <div className="overflow-hidden rounded-2xl border border-primary-200 bg-primary-50/40">
      <div className="flex items-center gap-2 border-b border-primary-100 px-5 py-3">
        <Loader2 className="size-4 animate-spin text-primary-600" />
        <h2 className="text-sm font-bold text-primary-700">진행 중인 AI 작업 {jobs.length}건</h2>
      </div>
      <ul className="divide-y divide-primary-100">
        {jobs.map((job) => {
          const names = job.documentResults
            .map((result) => docById[result.documentId]?.originalFileName)
            .filter(Boolean)
          const done = job.documentResults.filter((r) => r.status === 'completed').length
          return (
            <li key={job.jobId}>
              <Link
                to={`/admin/documents/jobs/${job.jobId}/progress`}
                className="focus-ring flex items-center gap-3 px-5 py-3 text-sm hover:bg-white/70"
              >
                <span className="min-w-0 flex-1 truncate font-semibold text-slate-800">
                  {names[0] ?? `문서 ${job.documentResults.length}개`}
                  {names.length > 1 && ` 외 ${names.length - 1}개`}
                </span>
                <span className="shrink-0 text-xs text-slate-500">
                  {done}/{job.documentResults.length} 완료
                </span>
                <Badge tone="info">{JOB_STATUS_LABEL[job.status] ?? job.status}</Badge>
                <ChevronRight className="size-4 shrink-0 text-slate-400" />
              </Link>
            </li>
          )
        })}
      </ul>
    </div>
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
  // 중단은 「아직 시작하지 않은 문서」만 취소한다. 이미 시작한 문서는 끝까지 처리되고 Wiki에도
  // 반영된다(AiJob.cancel). 그동안 이 카드는 「취소됨」인데 행은 「처리 중」이라 무슨 일인지
  // 알 수 없었다 — 지금 무엇이 도는 중인지 말로 적는다.
  const finishingCount = job.status === 'cancelled'
    ? job.documentResults.filter((result) => !TERMINAL_DOCUMENT_STATUSES.has(result.status)).length
    : 0

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
            <span className="text-xs font-semibold text-slate-500">
              {actionTypeCountsLabel(job.documentResults)}
            </span>
          </div>
        </div>
        {duration && <span className="text-xs text-slate-400">소요 {duration}</span>}
      </div>

      {finishingCount > 0 && (
        <p className="border-b border-amber-100 bg-amber-50 px-5 py-2.5 text-xs text-amber-700">
          중단을 요청했습니다. 이미 시작한 문서 {finishingCount}건은 마무리된 뒤 멈춥니다 — 그 문서는
          Wiki까지 반영됩니다.
        </p>
      )}

      <div className="grid grid-cols-[minmax(0,2fr)_1fr_1fr_minmax(0,1.4fr)_110px_100px] gap-3 bg-slate-50 px-5 py-3 text-xs font-semibold text-slate-500">
        <span className="text-center">파일명</span>
        <span className="text-center">공개 부서</span>
        <span className="text-center">카테고리</span>
        <span className="text-center">생성·변경된 위키 문서</span>
        <span className="text-center">상태</span>
        <span className="text-center">관리</span>
      </div>

      <ul className="divide-y divide-slate-100">
        {job.documentResults.map((result) => {
          const document = docById[result.documentId]
          // 문서가 하드 삭제되면 상세 조회가 404다. 파일명은 작업 결과의 스냅샷이 들고
          // 있으므로(S15P11B106-202) 나머지 칸만 「삭제된 문서」로 읽히게 한다.
          const deleted = !document
          const fileName = result.originalFileName ?? document?.originalFileName
          const affectedWikis = affectedWikisFor(result, document)
          const action = documentAction(result.changeType)
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
                    {fileName ?? `문서 ${result.documentId}`}
                  </p>
                  <Badge tone={action.tone} className="mt-1">{action.label}</Badge>
                  <p className="text-xs text-slate-400">
                    {deleted ? '삭제된 문서' : formatFileSize(document?.fileSize)}
                  </p>
                </div>
              </div>
              <span className="truncate text-center text-slate-500">
                {deleted
                  ? '-'
                  : document.visibilityType === 'all'
                    ? '전체 공개'
                    : (document.departments ?? []).map((department) => department.name).join(', ') || '-'}
              </span>
              <span className="truncate text-center text-slate-500">
                {deleted ? '-' : (document.documentCategoryName ?? '미분류')}
              </span>
              <AffectedWikiDisplay wikis={affectedWikis} />
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
