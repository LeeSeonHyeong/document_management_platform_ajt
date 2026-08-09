const TERMINAL_DOCUMENT_STATUSES = new Set(['completed', 'failed', 'cancelled'])

const STAGE_LABELS = {
  waiting: '대기 중',
  parsing: '파싱 중',
  wiki_pending: '위키 반영 준비 중',
  wiki_transform: '위키 반영 중',
  wiki_applied: '위키 반영 완료',
}

const DOCUMENT_STAGE_LABELS = ['원본 문서 분석', '위키 변경안 생성', '위키 반영']

export function documentStagesFor(result) {
  if (result?.status === 'waiting' || result?.status === 'uploaded') {
    return DOCUMENT_STAGE_LABELS.map((label) => ({ label, status: 'waiting' }))
  }
  const stage = result?.currentStage
  let activeIndex = 0
  if (stage === 'wiki_pending' || stage === 'wiki_transform') activeIndex = 1
  if (stage === 'wiki_applied' || result?.status === 'completed') activeIndex = 3

  return DOCUMENT_STAGE_LABELS.map((label, index) => ({
    label,
    status: index < activeIndex ? 'completed' : index === activeIndex ? 'processing' : 'waiting',
  }))
}

function stageLabelFor(result) {
  if (result.status === 'completed') return '완료'
  if (result.status === 'failed') return '실패'
  if (result.status === 'cancelled') return '취소됨'
  return STAGE_LABELS[result.currentStage] ?? (result.status === 'uploaded' ? '대기 중' : '처리 중')
}

function groupState(job, counts) {
  if (counts.pending > 0) return job.status === 'waiting' ? 'waiting' : 'processing'
  return counts.failed || counts.cancelled ? 'failed' : 'completed'
}

export function buildAiJobProgress(jobs = [], jobRefs = []) {
  const labelsByJobId = new Map(jobRefs.map((job) => [String(job.jobId), job.scopeLabel]))
  const groups = jobs.map((job) => {
    const results = job.documentResults ?? []
    const counts = results.reduce(
      (acc, result) => {
        if (result.status === 'completed') acc.completed += 1
        else if (result.status === 'failed') acc.failed += 1
        else if (result.status === 'cancelled') acc.cancelled += 1
        else acc.pending += 1
        return acc
      },
      { completed: 0, failed: 0, cancelled: 0, pending: 0 },
    )
    return {
      jobId: String(job.jobId),
      label: labelsByJobId.get(String(job.jobId)) ?? `작업 ${job.jobId}`,
      ...counts,
      total: results.length,
      state: groupState(job, counts),
    }
  })
  const currentJob = jobs.find((job) =>
    (job.documentResults ?? []).some((result) => !TERMINAL_DOCUMENT_STATUSES.has(result.status)),
  )
  const currentResult = currentJob?.documentResults?.find(
    (result) => !TERMINAL_DOCUMENT_STATUSES.has(result.status),
  )

  return {
    current: currentResult
      ? {
          ...currentResult,
          scopeLabel: labelsByJobId.get(String(currentJob.jobId)) ?? `작업 ${currentJob.jobId}`,
          stageLabel: stageLabelFor(currentResult),
        }
      : null,
    groups,
  }
}
