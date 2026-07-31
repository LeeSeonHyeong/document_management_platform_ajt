import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Badge, Chip, DataTable, Spinner } from '@/components/ui'
import { useAiJob, useStartAiJob } from '../queries'
import { useDocumentDetails } from '../hooks/useDocumentDetails'
import DocumentVisibilityDialog from '../components/DocumentVisibilityDialog'
import AiJobStartDialog from '../components/AiJobStartDialog'

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])

function VisibilityCell({ doc }) {
  if (!doc) return <span className="text-slate-300">…</span>
  if (doc.visibilityType === 'all') return <Badge tone="primary">전체</Badge>
  return (
    <div className="flex flex-wrap justify-center gap-1">
      {(doc.departments ?? []).map((dept) => (
        <Chip key={dept.departmentId}>{dept.name}</Chip>
      ))}
    </div>
  )
}

// Figma 4-2R / 4-3R — AI 작업 대기. 업로드로 생성된 작업(jobId)의 문서 묶음을 order 순으로 보여주고,
// 행별로 공개 범위를 다시 지정한 뒤 작업을 시작한다. (4-3R은 모든 문서 지정이 끝난 4-2R의 로컬 상태다.)
export default function AiJobQueuePage() {
  const { jobId } = useParams()
  const navigate = useNavigate()
  const [editing, setEditing] = useState(null) // 공개 범위를 수정할 document 상세
  const [startOpen, setStartOpen] = useState(false)
  const { data: job, isLoading } = useAiJob(jobId)
  const startAiJob = useStartAiJob()

  // 이미 종료된 작업으로 대기 라우트에 진입하면 요약으로 보낸다(document-state.md 규칙).
  // 처리 중(processing)에는 튕기지 않는다 — 다른 관리자가 먼저 시작했거나 이 화면의 시작 요청이
  // 반영된 직후일 수 있어서다. 진행 화면 이동은 "AI 작업 시작" 버튼이 담당한다.
  useEffect(() => {
    if (job && TERMINAL_STATUSES.has(job.status)) {
      navigate(`/admin/documents/jobs/${jobId}/summary`, { replace: true })
    }
  }, [job, jobId, navigate])

  const results = [...(job?.documentResults ?? [])].sort((a, b) => a.order - b.order)

  // 표시용 필드(파일명·카테고리·공개 범위)는 작업 응답에 없으므로 문서 상세로 채운다.
  const docById = useDocumentDetails(results.map((r) => r.documentId))

  const allAssigned = results.length > 0 && results.every((r) => Boolean(docById[r.documentId]?.visibilityType))

  const columns = [
    { key: 'order', header: '순서', render: (r) => r.order },
    { key: 'fileName', header: '파일명', render: (r) => docById[r.documentId]?.originalFileName ?? '…' },
    { key: 'category', header: '카테고리', render: (r) => docById[r.documentId]?.documentCategoryName ?? '-' },
    { key: 'visibility', header: '공개 범위', render: (r) => <VisibilityCell doc={docById[r.documentId]} /> },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (r) => (
        <Button
          size="sm"
          variant="outline"
          disabled={!docById[r.documentId]}
          onClick={() => setEditing(docById[r.documentId])}
        >
          공개 범위 변경
        </Button>
      ),
    },
  ].map((column, index) => ({ ...column, align: index === 0 ? 'left' : 'center' }))

  if (isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner />
      </div>
    )
  }

  return (
    <section className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">AI 작업 대기</h1>
        <p className="mt-1 text-sm text-slate-500">
          업로드된 문서는 순서대로 1건씩 처리됩니다. 시작 전에 각 문서의 공개 범위를 확인·수정할 수 있습니다.
        </p>
      </div>

      <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
        같은 공개 범위(scopeKey)의 문서들이 하나의 AI 작업으로 처리됩니다. 특정 문서의 공개 범위를 바꾸면 작업 묶음이 나뉠 수 있습니다.
      </p>

      <DataTable columns={columns} rows={results} rowKey="documentId" headerAlign="center" />

      <div className="flex items-center justify-between">
        <span className="text-sm text-slate-500">
          {allAssigned ? '모든 문서에 공개 범위가 지정되었습니다.' : '문서 정보를 불러오는 중입니다…'}
        </span>
        <Button variant="primary" onClick={() => setStartOpen(true)} disabled={!allAssigned}>
          AI 작업 시작
        </Button>
      </div>

      <DocumentVisibilityDialog
        open={Boolean(editing)}
        document={editing}
        onClose={() => setEditing(null)}
        onSaved={() => setEditing(null)}
      />

      <AiJobStartDialog
        open={startOpen}
        documents={results.map((r) => docById[r.documentId]).filter(Boolean)}
        onClose={() => setStartOpen(false)}
        pending={startAiJob.isPending}
        onConfirm={() => {
          // 업로드는 작업을 waiting으로만 만든다. 이 호출이 파싱·Wiki 변환을 시작한다.
          startAiJob.mutate(jobId, {
            onSuccess: () => {
              setStartOpen(false)
              navigate(`/admin/documents/jobs/${jobId}/progress`)
            },
          })
        }}
      />
    </section>
  )
}
