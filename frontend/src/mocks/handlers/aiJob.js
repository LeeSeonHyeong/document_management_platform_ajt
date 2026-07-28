import { http, HttpResponse } from 'msw'
import { findAiJobById, findDocumentById } from '../db'

function errorBody(status, code, message, path, fieldErrors = []) {
  return { timestamp: new Date().toISOString(), status, error: code, code, message, path, fieldErrors }
}

const TERMINAL_JOB_STATUSES = new Set(['completed', 'failed', 'cancelled'])
const TERMINAL_DOC_STATUSES = new Set(['completed', 'failed', 'cancelled'])
const STAGE_SEQUENCE = ['parsing', 'wiki_transform', 'wiki_applied']

// 폴링 요청을 받을 때마다 작업을 한 단계씩 진행시켜 최종 completed 까지 도달하게 하는 목 동작.
// 문서는 documentResults 배열 순서(=업로드 순서)대로 한 건씩만 진행한다(FR-AI-003).
function advanceJob(job) {
  if (job.status === 'waiting') {
    job.status = 'processing'
    job.startedAt = new Date().toISOString()
    return
  }
  if (job.status !== 'processing') return

  const current = job.documentResults.find((r) => !TERMINAL_DOC_STATUSES.has(r.status))
  if (!current) {
    job.status = 'completed'
    job.finishedAt = new Date().toISOString()
    return
  }

  const stageIndex = STAGE_SEQUENCE.indexOf(current.currentStage)
  const nextStageIndex = stageIndex + 1

  if (nextStageIndex >= STAGE_SEQUENCE.length) {
    current.status = 'completed'
    current.summary = `${current.documentId}번 문서를 근거로 Wiki를 반영했습니다.`
    const doc = findDocumentById(current.documentId)
    if (doc) doc.status = 'completed'

    const remaining = job.documentResults.some((r) => !TERMINAL_DOC_STATUSES.has(r.status))
    if (!remaining) {
      job.status = 'completed'
      job.finishedAt = new Date().toISOString()
    }
    return
  }

  current.currentStage = STAGE_SEQUENCE[nextStageIndex]
  current.status = nextStageIndex === 0 ? 'parsing' : 'processing'
  const doc = findDocumentById(current.documentId)
  if (doc) doc.status = current.status
}

export const aiJobHandlers = [
  http.get('/api/v1/ai-jobs/:jobId', ({ params }) => {
    const job = findAiJobById(params.jobId)
    if (!job) {
      return HttpResponse.json(
        errorBody(404, 'AI_JOB_NOT_FOUND', 'AI 작업을 찾을 수 없습니다.', `/api/v1/ai-jobs/${params.jobId}`),
        { status: 404 },
      )
    }
    if (!TERMINAL_JOB_STATUSES.has(job.status)) advanceJob(job)
    return HttpResponse.json(job)
  }),

  http.post('/api/v1/ai-jobs/:jobId/cancel', ({ params }) => {
    const job = findAiJobById(params.jobId)
    if (!job) {
      return HttpResponse.json(
        errorBody(404, 'AI_JOB_NOT_FOUND', '존재하지 않는 작업입니다.', `/api/v1/ai-jobs/${params.jobId}/cancel`),
        { status: 404 },
      )
    }
    if (TERMINAL_JOB_STATUSES.has(job.status)) {
      return HttpResponse.json(
        errorBody(409, 'AI_JOB_NOT_CANCELLABLE', '이미 종료되었거나 중단할 수 없는 작업입니다.', `/api/v1/ai-jobs/${params.jobId}/cancel`),
        { status: 409 },
      )
    }
    job.status = 'cancelled'
    job.finishedAt = new Date().toISOString()
    // 처리 중이던 문서는 완료된 것으로 간주하고, 아직 시작하지 않은 나머지만 cancelled로 전환한다(FR-AI-007).
    job.documentResults
      .filter((r) => !TERMINAL_DOC_STATUSES.has(r.status) && r.currentStage === null)
      .forEach((r) => {
        r.status = 'cancelled'
        const doc = findDocumentById(r.documentId)
        if (doc) doc.status = 'cancelled'
      })
    return HttpResponse.json({ jobId: job.jobId, status: job.status }, { status: 202 })
  }),
]
