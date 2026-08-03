import { Link } from 'react-router-dom'
import { FileText, RotateCcw } from 'lucide-react'
import { Badge, Button, Modal } from '@/components/ui'
import { DOC_STATUS_LABEL, DOC_STATUS_TONE, FAILURE_STAGE_LABEL } from '../status'

const RETRYABLE = new Set(['failed', 'cancelled'])

function formatDateTime(iso) {
  if (!iso) return null
  return new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// 작업의 시작~종료 간격. 백엔드는 두 시각만 주고 소요 시간은 주지 않는다.
function formatDuration(startedAt, finishedAt) {
  if (!startedAt || !finishedAt) return null
  const seconds = Math.round((new Date(finishedAt) - new Date(startedAt)) / 1000)
  if (!Number.isFinite(seconds) || seconds < 0) return null
  if (seconds < 60) return `${seconds}초`
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return rest === 0 ? `${minutes}분` : `${minutes}분 ${rest}초`
}

/**
 * 요약 목록에서 문서 한 건의 "무엇이 바뀌었나"를 보여준다(S15P11B106-192).
 *
 * 요약은 작업 회차마다 따로 남으므로 job과 documentResult를 함께 받는다 — 같은 문서를
 * 재처리하면 회차별로 다른 요약이 붙는다.
 */
export default function AiJobDocumentSummaryModal({ open, onClose, job, result, document, onRetry, retrying }) {
  if (!result) return null

  const duration = formatDuration(job?.startedAt, job?.finishedAt)
  const workedAt = formatDateTime(job?.createdAt)
  const relatedWikis = document?.relatedWikis ?? []

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      footer={
        <>
          {RETRYABLE.has(result.status) && onRetry && (
            <Button variant="outline" loading={retrying} onClick={() => onRetry(result.documentId)}>
              <RotateCcw className="size-4" />
              재처리
            </Button>
          )}
          <Button variant="outline" onClick={onClose}>닫기</Button>
        </>
      }
    >
      <div className="flex items-start justify-between gap-3 border-b border-slate-100 pb-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-500">
            <FileText className="size-4" />
          </span>
          <div className="min-w-0">
            <p className="truncate font-semibold text-slate-800">
              {document?.originalFileName ?? `문서 ${result.documentId}`}
            </p>
            <p className="mt-0.5 text-xs text-slate-400">
              {workedAt ? `${workedAt} 작업` : '작업 시각 미상'}
              {duration && <span className="mx-1.5 text-slate-300">·</span>}
              {duration && `소요 ${duration}`}
            </p>
          </div>
        </div>
        <Badge tone={DOC_STATUS_TONE[result.status] ?? 'neutral'}>
          {DOC_STATUS_LABEL[result.status] ?? result.status}
        </Badge>
      </div>

      {result.status === 'completed' ? (
        <div className="pt-4">
          <p className="text-sm text-slate-500">변경 요약</p>
          <p className="mt-1.5 text-sm leading-7 text-slate-700">
            {result.summary ?? '이 작업에 기록된 요약이 없습니다.'}
          </p>
        </div>
      ) : (
        <div className="pt-4">
          <p className="text-sm text-slate-500">실패 사유</p>
          <p className="mt-1.5 text-sm leading-7 text-rose-600">
            {result.failureReason ?? '기록된 실패 사유가 없습니다.'}
          </p>
          {/* currentStage 를 쓰지 않는다 — 문서 상태에서 역산한 값이라 어디서 죽었든
              parsing 으로 온다. 실패 지점을 아는 것은 failureStage 뿐이고, 그마저
              없으면 아는 척하지 않고 줄을 지운다. */}
          {result.failureStage && (
            <p className="mt-2 text-xs text-slate-400">
              실패 단계 · {FAILURE_STAGE_LABEL[result.failureStage] ?? result.failureStage}
            </p>
          )}
        </div>
      )}

      {relatedWikis.length > 0 && (
        <div className="pt-4">
          <p className="text-sm text-slate-500">반영된 위키</p>
          <ul className="mt-1.5 space-y-1">
            {relatedWikis.map((wiki) => (
              <li key={wiki.wikiId ?? wiki.title}>
                <Link
                  to={`/wiki/${wiki.wikiId}`}
                  className="text-sm text-primary-600 underline-offset-2 hover:underline"
                >
                  {wiki.title}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Modal>
  )
}
