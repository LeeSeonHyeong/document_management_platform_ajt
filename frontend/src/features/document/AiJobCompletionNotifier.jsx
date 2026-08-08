import { useEffect } from 'react'
import { useToast } from '@/components/ui'
import { useAiJobs } from './queries'
import { aiJobNotificationFor, takeTerminalAiJobNotifications } from './aiJobNotifications'

export default function AiJobCompletionNotifier() {
  const toast = useToast()
  const { data } = useAiJobs({ page: 1, size: 100 })

  useEffect(() => {
    takeTerminalAiJobNotifications(data?.items).forEach((job) => {
      const notification = aiJobNotificationFor(job)
      toast[notification.tone](notification.title, notification.description)
    })
  }, [data?.items, toast])

  return null
}
