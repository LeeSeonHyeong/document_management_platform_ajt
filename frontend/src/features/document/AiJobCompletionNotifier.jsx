import { useEffect, useState } from 'react'
import { useToast } from '@/components/ui'
import { useAiJobsPolling } from './hooks/useAiJobPolling'
import { AI_JOB_TRACKED_EVENT, aiJobNotificationFor, getTrackedAiJobIds, takeTerminalAiJobNotifications } from './aiJobNotifications'

export default function AiJobCompletionNotifier() {
  const toast = useToast()
  const [jobIds, setJobIds] = useState(getTrackedAiJobIds)
  const { jobs } = useAiJobsPolling(jobIds)

  useEffect(() => {
    const refreshTrackedJobs = () => setJobIds(getTrackedAiJobIds())
    globalThis.addEventListener?.(AI_JOB_TRACKED_EVENT, refreshTrackedJobs)
    return () => globalThis.removeEventListener?.(AI_JOB_TRACKED_EVENT, refreshTrackedJobs)
  }, [])

  useEffect(() => {
    takeTerminalAiJobNotifications(jobs).forEach((job) => {
      const notification = aiJobNotificationFor(job)
      toast[notification.tone](notification.title, notification.description)
    })
  }, [jobs, toast])

  return null
}
