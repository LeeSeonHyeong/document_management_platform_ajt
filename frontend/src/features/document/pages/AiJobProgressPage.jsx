import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Badge, DataTable, ConfirmDialog, Spinner } from '@/components/ui'
import { useAiJobPolling } from '../hooks/useAiJobPolling'
import { useDocumentDetails } from '../hooks/useDocumentDetails'
import { DOC_STATUS_TONE, DOC_STATUS_LABEL, STAGE_LABEL } from '../status'

// Figma 4-5R — AI 작업 처리 중. useAiJobPolling으로 2초 폴링하며 문서별 진행을 order 순으로 표시한다.
// 개별 문서 실패는 작업 전체 실패가 아니다(FR-AI-005) — 문서 단위로만 실패를 표시한다.
export default function AiJobProgressPage() {
  const { jobId } = useParams()
  const navigate = useNavigate()
  const [cancelOpen, setCancelOpen] = useState(false)
  const { job, documentResults, progress, isFinished, cancel } = useAiJobPolling(jobId)

  // 종료 상태(completed/failed/cancelled)에 도달하면 요약 화면으로 자동 이동한다.
  useEffect(() => {
    if (isFinished) navigate(`/admin/documents/jobs/${jobId}/summary`, { replace: true })
  }, [isFinished, jobId, navigate])

  const docById = useDocumentDetails(documentResults.map((r) => r.documentId))

  const columns = [
    { key: 'order', header: '순서', render: (r) => r.order },
    { key: 'fileName', header: '파일명', render: (r) => docById[r.documentId]?.originalFileName ?? '…' },
    {
      key: 'stage',
      header: '현재 단계',
      render: (r) => (r.currentStage ? (STAGE_LABEL[r.currentStage] ?? r.currentStage) : '-'),
    },
    {
      key: 'status',
      header: '상태',
      render: (r) => <Badge tone={DOC_STATUS_TONE[r.status] ?? 'neutral'}>{DOC_STATUS_LABEL[r.status] ?? r.status}</Badge>,
    },
  ].map((column, index) => ({ ...column, align: index === 0 ? 'left' : 'center' }))

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
    </section>
  )
}
