import { useContext } from 'react'
import { AiJobQueueContext } from './AiJobQueueContext'

export function useAiJobQueue() {
  const context = useContext(AiJobQueueContext)
  if (context == null) {
    throw new Error('useAiJobQueue는 AiJobQueueProvider 안에서만 쓸 수 있습니다.')
  }
  return context
}
