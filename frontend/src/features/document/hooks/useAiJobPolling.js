import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import { fetchAiJob, cancelAiJob } from '../api'

const POLL_INTERVAL_MS = 2000
const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])

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
  })

  const cancelMutation = useMutation({
    mutationFn: () => cancelAiJob(jobId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.aiJobs.detail(jobId) })
    },
  })

  const job = query.data ?? null
  const isFinished = Boolean(job && TERMINAL_STATUSES.has(job.status))
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
