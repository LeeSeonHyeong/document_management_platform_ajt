import { beforeEach, describe, expect, it } from 'vitest'
import { aiJobNotificationFor, clearTrackedAiJobsForTest, trackAiJobsFromResponse, takeTerminalAiJobNotifications, trackAiJob } from './aiJobNotifications'

function stubSessionStorage() {
  const values = new Map()
  globalThis.sessionStorage = { getItem: (key) => (values.has(key) ? values.get(key) : null), setItem: (key, value) => values.set(key, String(value)), removeItem: (key) => values.delete(key) }
}

describe('AI 작업 종료 알림 레지스트리', () => {
  beforeEach(() => { stubSessionStorage(); clearTrackedAiJobsForTest() })
  it('현재 세션에 등록한 완료 작업을 한 번만 반환한다', () => {
    trackAiJob('17'); const jobs = [{ jobId: '17', status: 'completed' }]
    expect(takeTerminalAiJobNotifications(jobs)).toEqual(jobs); expect(takeTerminalAiJobNotifications(jobs)).toEqual([])
  })
  it('현재 세션에 등록하지 않은 작업 종료는 반환하지 않는다', () => {
    expect(takeTerminalAiJobNotifications([{ jobId: '18', status: 'failed' }])).toEqual([])
  })
  it('작업이 끝나도 문서 결과가 모두 끝날 때까지 알리지 않는다', () => {
    trackAiJob('19')
    const stillProcessing = { jobId: '19', status: 'completed', documentResults: [{ status: 'completed' }, { status: 'processing' }] }
    const finished = { ...stillProcessing, documentResults: [{ status: 'completed' }, { status: 'completed' }] }
    expect(takeTerminalAiJobNotifications([stillProcessing])).toEqual([]); expect(takeTerminalAiJobNotifications([finished])).toEqual([finished])
  })
  it('AI 작업 응답의 작업 ID를 등록한다', () => {
    trackAiJobsFromResponse({ jobs: [{ jobId: '20' }, { jobId: '21' }] }); trackAiJobsFromResponse({ jobId: '22' })
    expect(takeTerminalAiJobNotifications([{ jobId: '20', status: 'completed' }, { jobId: '21', status: 'failed' }, { jobId: '22', status: 'cancelled' }])).toHaveLength(3)
  })
  it('완료 문서와 실패 문서가 섞이면 일부 실패 알림을 만든다', () => {
    expect(aiJobNotificationFor({ status: 'completed', documentResults: [{ status: 'completed' }, { status: 'completed' }, { status: 'failed' }] })).toEqual({ tone: 'error', title: 'AI 작업 일부 실패', description: '완료 2건 · 실패 1건' })
  })
  it('모든 문서가 완료된 경우에만 완료 알림을 만든다', () => {
    expect(aiJobNotificationFor({ status: 'completed', documentResults: [{ status: 'completed' }, { status: 'completed' }] })).toEqual({ tone: 'success', title: 'AI 작업이 완료되었습니다. (2건)' })
  })
})
