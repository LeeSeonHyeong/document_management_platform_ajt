import { useEffect } from 'react'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import { invalidateAfterAiJob, LIVE_QUERY_OPTIONS } from '../queries'
import { fetchAiJob, cancelAiJob } from '../api'

const POLL_INTERVAL_MS = 2000
const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])

// 폴링하던 작업이 끝나는 순간 문서·위키 캐시를 걷어낸다.
// 이게 없으면 「갱신된 위키 보기」로 넘어간 화면이 편집 전 위키를 그대로 보여준다 —
// 위키 화면은 작업을 지켜보지 않으므로 스스로 다시 읽을 계기가 없다.
// 인자는 끝난 작업(들)을 가리키는 문자열 키. 작업이 바뀌면 키도 바뀌어 다시 무효화된다.
function useInvalidateOnFinish(finishedKey) {
  const queryClient = useQueryClient()
  useEffect(() => {
    if (finishedKey) invalidateAfterAiJob(queryClient)
  }, [finishedKey, queryClient])
}

// jobId가 있는 동안 GET /ai-jobs/:jobId 를 2초 간격으로 폴링하고,
// 작업이 종료 상태(completed/failed/cancelled)에 도달하면 자동으로 멈춘다.
export function useAiJobPolling(jobId) {
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: qk.aiJobs.detail(jobId),
    queryFn: () => fetchAiJob(jobId),
    enabled: Boolean(jobId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && TERMINAL_STATUSES.has(status) ? false : POLL_INTERVAL_MS
    },
    // 탭을 떠나 있어도 계속 읽고, 돌아오면 즉시 맞춘다(queries.js 의 주석 참고).
    ...LIVE_QUERY_OPTIONS,
  })

  const cancelMutation = useMutation({
    mutationFn: () => cancelAiJob(jobId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.aiJobs.detail(jobId) })
    },
  })

  const job = query.data ?? null
  const isFinished = Boolean(job && TERMINAL_STATUSES.has(job.status))
  useInvalidateOnFinish(isFinished ? String(jobId) : null)
  const documentResults = [...(job?.documentResults ?? [])].sort((a, b) => a.order - b.order)

  const progress = documentResults.reduce(
    (acc, result) => {
      acc.total += 1
      if (result.status === 'completed') acc.completed += 1
      else if (result.status === 'failed') acc.failed += 1
      else if (result.status === 'cancelled') acc.cancelled += 1
      else acc.processing += 1
      return acc
    },
    { total: 0, completed: 0, failed: 0, cancelled: 0, processing: 0 },
  )

  return {
    job,
    isPolling: Boolean(jobId) && !isFinished,
    isFinished,
    documentResults,
    progress,
    cancel: cancelMutation.mutate,
  }
}

// 여러 job을 동시에 폴링하고 진행 상황을 하나로 합친다.
// 업로드가 공개 범위별로 여러 작업으로 쪼개져도 한 화면에서 전체 진행을 보여주기 위한 것이다.
// 모든 job이 종료 상태에 도달하면 isFinished가 true가 된다.
export function useAiJobsPolling(jobIds) {
  const ids = jobIds ?? []

  const results = useQueries({
    queries: ids.map((jobId) => ({
      queryKey: qk.aiJobs.detail(jobId),
      queryFn: () => fetchAiJob(jobId),
      refetchInterval: (query) => {
        const status = query.state.data?.status
        return status && TERMINAL_STATUSES.has(status) ? false : POLL_INTERVAL_MS
      },
    })),
  })

  const jobs = results.map((result) => result.data).filter(Boolean)
  const allLoaded = ids.length > 0 && jobs.length === ids.length
  const isFinished = allLoaded && jobs.every((job) => TERMINAL_STATUSES.has(job.status))
  useInvalidateOnFinish(isFinished ? ids.join(',') : null)

  const documentResults = jobs
    .flatMap((job) => job.documentResults ?? [])
    .sort((a, b) => a.order - b.order)

  const progress = documentResults.reduce(
    (acc, result) => {
      acc.total += 1
      if (result.status === 'completed') acc.completed += 1
      else if (result.status === 'failed') acc.failed += 1
      else if (result.status === 'cancelled') acc.cancelled += 1
      else acc.processing += 1
      return acc
    },
    { total: 0, completed: 0, failed: 0, cancelled: 0, processing: 0 },
  )

  return { isFinished, documentResults, progress }
}
