const STORAGE_KEY = 'ajt:tracked-ai-jobs'
export const AI_JOB_TRACKED_EVENT = 'ajt:tracked-ai-jobs-changed'
const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])
const TERMINAL_DOCUMENT_STATUSES = new Set(['completed', 'failed', 'cancelled'])

function loadTrackedIds() {
  try {
    const stored = globalThis.sessionStorage?.getItem(STORAGE_KEY)
    const parsed = JSON.parse(stored ?? '[]')
    return new Set(Array.isArray(parsed) ? parsed.map(String) : [])
  } catch {
    return new Set()
  }
}

function saveTrackedIds(ids) {
  try {
    globalThis.sessionStorage?.setItem(STORAGE_KEY, JSON.stringify([...ids]))
  } catch {
    // 브라우저 저장소를 사용할 수 없어도 AI 작업 자체는 계속 진행한다.
  }
}

function notifyTrackedJobsChanged() {
  globalThis.dispatchEvent?.(new Event(AI_JOB_TRACKED_EVENT))
}

export function getTrackedAiJobIds() {
  return [...loadTrackedIds()]
}

export function trackAiJob(jobId) {
  if (jobId == null) return
  const ids = loadTrackedIds()
  ids.add(String(jobId))
  saveTrackedIds(ids)
  notifyTrackedJobsChanged()
}

export function trackAiJobsFromResponse(response) {
  response?.jobs?.forEach((job) => trackAiJob(job.jobId))
  trackAiJob(response?.jobId)
}

export function takeTerminalAiJobNotifications(jobs) {
  const ids = loadTrackedIds()
  const terminalJobs = (jobs ?? []).filter(
    (job) =>
      ids.has(String(job.jobId)) &&
      TERMINAL_STATUSES.has(job.status) &&
      (!job.documentResults?.length ||
        job.documentResults.every((result) => TERMINAL_DOCUMENT_STATUSES.has(result.status))),
  )
  terminalJobs.forEach((job) => ids.delete(String(job.jobId)))
  saveTrackedIds(ids)
  if (terminalJobs.length) notifyTrackedJobsChanged()
  return terminalJobs
}

export function aiJobNotificationFor(job) {
  const results = job.documentResults ?? []
  const completed = results.filter((result) => result.status === 'completed').length
  const failed = results.filter((result) => result.status === 'failed').length
  const cancelled = results.filter((result) => result.status === 'cancelled').length

  if (completed > 0 && failed + cancelled > 0) {
    const unsuccessful = [failed > 0 && `실패 ${failed}건`, cancelled > 0 && `취소 ${cancelled}건`]
      .filter(Boolean)
      .join(' · ')
    return {
      tone: 'error',
      title: 'AI 작업 일부 실패',
      description: `완료 ${completed}건 · ${unsuccessful}`,
    }
  }

  if (completed > 0) return { tone: 'success', title: `AI 작업이 완료되었습니다. (${completed}건)` }
  if (job.status === 'cancelled' || cancelled > 0) return { tone: 'error', title: 'AI 작업이 취소되었습니다.' }
  return {
    tone: 'error',
    title: 'AI 작업이 실패했습니다.',
    description: '작업 이력에서 사유를 확인해주세요.',
  }
}

export function clearTrackedAiJobsForTest() {
  try {
    globalThis.sessionStorage?.removeItem(STORAGE_KEY)
  } catch {
    // 테스트·브라우저 저장소가 막힌 경우에도 호출자는 계속 진행한다.
  }
}
