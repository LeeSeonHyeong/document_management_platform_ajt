import { useEffect } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Button, Badge, Spinner } from '@/components/ui'
import { ArrowLeft, RotateCcw } from 'lucide-react'
import { useAiJob, useRetryDocument } from '../queries'
import { useDocumentDetails } from '../hooks/useDocumentDetails'
import { DOC_STATUS_TONE, DOC_STATUS_LABEL } from '../status'
import SummaryMarkdown from '../components/SummaryMarkdown'

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])
const RETRYABLE = new Set(['failed', 'cancelled'])

// Figma 4-6R — AI 작업 요약. 종료된 작업의 문서별 결과(요약/실패 사유)를 보여주고,
// 실패·취소 문서는 재처리한다. 요구사항상 변환 전 승인 절차가 없으므로 승인/검수 UI는 두지 않는다.
export default function AiJobSummaryPage() {
  const { jobId } = useParams()
  const navigate = useNavigate()
  const { data: job, isLoading } = useAiJob(jobId)
  const retryMutation = useRetryDocument()

  // 아직 종료되지 않은 작업이면 진행 화면으로 돌려보낸다(document-state.md 규칙).
  useEffect(() => {
    if (job && !TERMINAL_STATUSES.has(job.status)) {
      navigate(`/admin/documents/jobs/${jobId}/progress`, { replace: true })
    }
  }, [job, jobId, navigate])

  const results = [...(job?.documentResults ?? [])].sort((a, b) => a.order - b.order)
  const docById = useDocumentDetails(results.map((r) => r.documentId))

  function handleRetry(documentId) {
    retryMutation.mutate(documentId, {
      // 재처리는 새 작업을 생성하므로 그 작업의 진행 화면으로 이동한다.
      onSuccess: (data) => {
        if (data?.jobId) navigate(`/admin/documents/jobs/${data.jobId}/progress`)
      },
    })
  }

  if (isLoading || !job) {
    return (
      <div className="flex justify-center py-16">
        <Spinner />
      </div>
    )
  }

  const completed = results.filter((r) => r.status === 'completed').length
  const failed = results.filter((r) => r.status === 'failed').length
  const cancelled = results.filter((r) => r.status === 'cancelled').length

  return (
    <section className="space-y-4">
      {/* 진행 화면과 마찬가지로 나갈 길이 없었다(S15P11B106-300). */}
      <Link
        to="/admin/documents/summaries"
        className="focus-ring inline-flex cursor-pointer items-center gap-1.5 rounded-md text-sm font-semibold text-slate-500 hover:text-primary-600"
      >
        <ArrowLeft className="size-3.5" />
        AI 작업 요약
      </Link>

      <div>
        <h1 className="text-2xl font-semibold">AI 작업 요약</h1>
        <p className="mt-1 text-sm text-slate-500">
          완료 {completed}
          {failed > 0 && ` · 실패 ${failed}`}
          {cancelled > 0 && ` · 취소 ${cancelled}`} / 전체 {results.length}건
        </p>
      </div>

      <ul className="space-y-3">
        {results.map((r) => {
          const doc = docById[r.documentId]
          return (
            <li key={r.documentId} className="rounded-xl border border-slate-200 p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="font-medium text-slate-800">
                    <span className="mr-2 text-slate-400">{r.order}.</span>
                    {doc?.originalFileName ?? `문서 ${r.documentId}`}
                  </p>
                  {/* 요약은 표·목록이 섞인 마크다운이다. 원문 그대로 두면 표 기호가 그대로 보인다. */}
                  {r.summary && <div className="mt-1"><SummaryMarkdown markdown={r.summary} /></div>}
                  {r.status === 'failed' && r.failureReason && (
                    <p className="mt-1 text-sm text-rose-600">실패 사유: {r.failureReason}</p>
                  )}

                  {/* 이 문서를 근거로 생성·수정된 Wiki (있을 때만). Wiki 상세 라우트는 브랜치 8에서 확정된다. */}
                  {doc?.relatedWikis?.length > 0 && (
                    <div className="mt-2 flex flex-wrap gap-2">
                      {doc.relatedWikis.map((w) => (
                        <Link
                          key={w.wikiId}
                          to={`/wiki/${w.wikiId}`}
                          className="text-sm text-primary-600 underline-offset-2 hover:underline"
                        >
                          {w.title}
                        </Link>
                      ))}
                    </div>
                  )}
                </div>

                <div className="flex shrink-0 items-center gap-2">
                  <Badge tone={DOC_STATUS_TONE[r.status] ?? 'neutral'}>
                    {DOC_STATUS_LABEL[r.status] ?? r.status}
                  </Badge>
                  {RETRYABLE.has(r.status) && (
                    <Button
                      size="sm"
                      variant="outline"
                      loading={retryMutation.isPending && retryMutation.variables === r.documentId}
                      onClick={() => handleRetry(r.documentId)}
                    >
                      <RotateCcw className="size-4" />
                      재처리
                    </Button>
                  )}
                </div>
              </div>
            </li>
          )
        })}
      </ul>

      <div className="flex justify-end">
        <Link to="/admin/documents">
          <Button variant="ghost">문서 관리로 돌아가기</Button>
        </Link>
      </div>
    </section>
  )
}
