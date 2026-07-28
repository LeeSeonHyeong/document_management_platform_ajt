import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useQueries } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import { Button, Badge, Chip, DataTable, Spinner } from '@/components/ui'
import { useAiJob } from '../queries'
import { fetchDocument } from '../api'
import DocumentVisibilityDialog from '../components/DocumentVisibilityDialog'

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])

function VisibilityCell({ doc }) {
  if (!doc) return <span className="text-slate-300">…</span>
  if (doc.visibilityType === 'all') return <Badge tone="primary">전체</Badge>
  return (
    <div className="flex flex-wrap gap-1">
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
  const { data: job, isLoading } = useAiJob(jobId)

  // 상태가 맞지 않는 라우트로 진입하면 올바른 라우트로 보낸다(document-state.md 규칙).
  useEffect(() => {
    if (!job) return
    if (job.status === 'processing') navigate(`/admin/documents/jobs/${jobId}/progress`, { replace: true })
    else if (TERMINAL_STATUSES.has(job.status)) navigate(`/admin/documents/jobs/${jobId}/summary`, { replace: true })
  }, [job, jobId, navigate])

  const results = [...(job?.documentResults ?? [])].sort((a, b) => a.order - b.order)

  // 표시용 필드(파일명·카테고리·공개 범위)는 작업 응답에 없으므로 문서 상세로 채운다.
  const docQueries = useQueries({
    queries: results.map((r) => ({
      queryKey: qk.documents.detail(r.documentId),
      queryFn: () => fetchDocument(r.documentId),
      enabled: Boolean(r.documentId),
    })),
  })
  const docById = {}
  results.forEach((r, i) => {
    docById[r.documentId] = docQueries[i]?.data
  })

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
  ]

  function handleStart() {
    // 작업은 업로드 시점에 이미 생성(waiting)되어 있다. 시작 확인 다이얼로그(4-4R) 연결은
    // 다음 브랜치(S15P11B106-74 ai-job-run)에서 진행하며, 여기서는 진행 화면으로 이동만 한다.
    navigate(`/admin/documents/jobs/${jobId}/progress`)
  }

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

      <DataTable columns={columns} rows={results} rowKey="documentId" />

      <div className="flex items-center justify-between">
        <span className="text-sm text-slate-500">
          {allAssigned ? '모든 문서에 공개 범위가 지정되었습니다.' : '문서 정보를 불러오는 중입니다…'}
        </span>
        <Button variant="primary" onClick={handleStart} disabled={!allAssigned}>
          AI 작업 시작
        </Button>
      </div>

      <DocumentVisibilityDialog
        open={Boolean(editing)}
        document={editing}
        onClose={() => setEditing(null)}
        onSaved={() => setEditing(null)}
      />
    </section>
  )
}
