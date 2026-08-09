import { describe, expect, it } from 'vitest'
import { createAiJobPollingQuery } from './useAiJobPolling'

describe('AI 작업 진행 폴링 쿼리', () => {
  it('일부 성공·일부 실패 작업도 탭을 떠난 동안 최신 종료 상태를 받는다', () => {
    const query = createAiJobPollingQuery('42')

    expect(query.refetchIntervalInBackground).toBe(true)
    expect(query.refetchOnWindowFocus).toBe(true)
    expect(query.staleTime).toBe(0)
    expect(query.refetchInterval({ state: { data: { status: 'completed' } } })).toBe(false)
    expect(query.refetchInterval({ state: { data: { status: 'processing' } } })).toBe(2000)
  })
})
