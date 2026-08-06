import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Button, Badge, DataTable, ConfirmDialog, Spinner } from '@/components/ui'
import { useAiJobPolling } from '../hooks/useAiJobPolling'
import { useDocumentDetails } from '../hooks/useDocumentDetails'
import { useRetryDocument } from '../queries'
import AiJobDocumentSummaryModal from '../components/AiJobDocumentSummaryModal'
import { DOC_STATUS_TONE, DOC_STATUS_LABEL, STAGE_LABEL } from '../status'

// 결과가 확정된 문서 상태. 이때만 요약을 열 수 있다.
const SETTLED = new Set(['completed', 'failed', 'cancelled'])

// Figma 4-5R — AI 작업 처리 중. useAiJobPolling으로 2초 폴링하며 문서별 진행을 order 순으로 표시한다.
// 개별 문서 실패는 작업 전체 실패가 아니다(FR-AI-005) — 문서 단위로만 실패를 표시한다.
export default function AiJobProgressPage() {
  const { jobId } = useParams()
  const navigate = useNavigate()
  const [cancelOpen, setCancelOpen] = useState(false)
  // 끝난 문서의 요약·실패 사유를 여기서도 볼 수 있게 한다(S15P11B106-300). 예전에는 작업이
  // 전부 끝나 요약 화면으로 넘어갈 때까지 기다려야 했다 — 7건 중 1건이 실패했는데 왜 실패했는지
  // 확인할 방법이 없었다.
  const [selectedResult, setSelectedResult] = useState(null)
  const { job, documentResults, progress, isFinished, cancel } = useAiJobPolling(jobId)

  // 종료 상태(completed/failed/cancelled)에 도달하면 요약 화면으로 자동 이동한다.
  useEffect(() => {
    if (isFinished) navigate(`/admin/documents/jobs/${jobId}/summary`, { replace: true })
  }, [isFinished, jobId, navigate])

  const docById = useDocumentDetails(documentResults.map((r) => r.documentId))
  const retryMutation = useRetryDocument()

  // width 를 주면 표가 table-fixed 로 그려진다 — 처리 상태가 바뀌며 행이 갱신돼도 열이 흔들리지 않고,
  // 긴 파일명은 줄바꿈 대신 '…'로 줄어든다(전체 이름은 마우스를 올리면 보인다).
  const columns = [
    { key: 'order', header: '순서', width: '8%', render: (r) => r.order },
    { key: 'fileName', header: '파일명', width: '38%', render: (r) => docById[r.documentId]?.originalFileName ?? '…' },
    {
      key: 'stage',
      header: '현재 단계',
      width: '18%',
      render: (r) => (r.currentStage ? (STAGE_LABEL[r.currentStage] ?? r.currentStage) : '-'),
    },
    {
      key: 'status',
      header: '상태',
      width: '14%',
      render: (r) => <Badge tone={DOC_STATUS_TONE[r.status] ?? 'neutral'}>{DOC_STATUS_LABEL[r.status] ?? r.status}</Badge>,
    },
    {
      key: 'summary',
      header: '',
      width: '22%',
      align: 'right',
      // 아직 처리 중인 문서는 보여줄 결과가 없다. 끝난 것(완료·실패·취소)만 열 수 있다.
      render: (r) =>
        SETTLED.has(r.status) ? (
          <Button size="sm" variant="outline" onClick={() => setSelectedResult(r)}>
            요약 보기
          </Button>
        ) : null,
    },
  ].map((column, index) => ({ ...column, align: column.align ?? (index === 0 ? 'left' : 'center') }))

  if (!job) {
    return (
      <div className="flex justify-center py-16">
        <Spinner />
      </div>
    )
  }

  const percent = progress.total ? Math.round((progress.completed / progress.total) * 100) : 0

  return (
    <section className="space-y-4">
      {/*
        나갈 길이 없던 화면이다(S15P11B106-300). 작업이 끝나면 요약으로 자동 이동하지만,
        진행 중에는 사이드바 말고는 벗어날 방법이 없었다. 상위인 요약 목록으로 보낸다 —
        작업은 화면을 떠나도 서버에서 계속 돌고, 목록에서 진행 중 작업을 다시 열 수 있다.
      */}
      <Link
        to="/admin/documents/summaries"
        className="focus-ring inline-flex cursor-pointer items-center gap-1.5 rounded-md text-sm font-semibold text-slate-500 hover:text-primary-600"
      >
        <ArrowLeft className="size-3.5" />
        AI 작업 요약
      </Link>

      <div>
        <h1 className="text-2xl font-semibold">AI 작업 처리 중</h1>
        <p className="mt-1 text-sm text-slate-500">문서를 업로드 순서대로 1건씩 처리하고 있습니다. 이 화면은 자동으로 갱신됩니다.</p>
      </div>

      <div className="space-y-2 rounded-xl border border-slate-200 p-4">
        <div className="flex items-center justify-between text-sm">
          <span className="font-medium text-slate-700">
            완료 {progress.completed} / 전체 {progress.total}
            {progress.failed > 0 && <span className="ml-2 text-rose-600">실패 {progress.failed}</span>}
          </span>
          <span className="text-slate-400">{percent}%</span>
        </div>
        <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100">
          <div className="h-full rounded-full bg-primary-500 transition-all" style={{ width: `${percent}%` }} />
        </div>
      </div>

      <DataTable columns={columns} rows={documentResults} rowKey="documentId" headerAlign="center" />

      <div className="flex justify-end">
        <Button variant="outline" onClick={() => setCancelOpen(true)}>
          작업 중단
        </Button>
      </div>

      <ConfirmDialog
        open={cancelOpen}
        onClose={() => setCancelOpen(false)}
        onConfirm={() => {
          cancel()
          setCancelOpen(false)
        }}
        title="작업을 중단할까요?"
        confirmLabel="작업 중단"
        tone="danger"
      >
        <p className="text-sm text-slate-600">
          이미 처리 중인 문서는 즉시 멈추지 않고 완료된 뒤 중단됩니다. 아직 시작하지 않은 문서만 취소됩니다.
        </p>
      </ConfirmDialog>

      <AiJobDocumentSummaryModal
        open={Boolean(selectedResult)}
        onClose={() => setSelectedResult(null)}
        job={job}
        result={selectedResult}
        document={selectedResult ? docById[selectedResult.documentId] : undefined}
        onRetry={(documentId) =>
          retryMutation.mutate(documentId, { onSuccess: () => setSelectedResult(null) })
        }
        retrying={retryMutation.isPending}
      />
    </section>
  )
}
