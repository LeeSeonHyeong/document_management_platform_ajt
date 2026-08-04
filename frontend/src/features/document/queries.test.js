import { describe, expect, it } from 'vitest'
import { hasUnsettledWork } from './queries'

// 요약 목록의 폴링 중단 판정 (S15P11B106-244).
// 화면 배지는 작업 상태가 아니라 문서 결과 상태를 그리므로, 둘 다 종료돼야 멈춘다.
const job = (status, documentStatuses) => ({
  jobId: '1',
  status,
  documentResults: documentStatuses.map((s, i) => ({ documentId: String(i), status: s })),
})

describe('hasUnsettledWork', () => {
  it('작업이 진행 중이면 계속 읽는다', () => {
    expect(hasUnsettledWork([job('processing', ['processing'])])).toBe(true)
  })

  it('작업은 끝났어도 문서가 아직 처리 중이면 계속 읽는다', () => {
    // 이 틈에 폴링을 끊어서 「처리 중」이 그대로 굳는 것이 이 티켓의 증상이다.
    expect(hasUnsettledWork([job('completed', ['processing'])])).toBe(true)
  })

  it('작업과 문서가 모두 끝나면 멈춘다', () => {
    expect(hasUnsettledWork([job('completed', ['completed'])])).toBe(false)
  })

  it('실패·취소로 끝난 것도 종료로 본다 — 영원히 읽지 않는다', () => {
    expect(hasUnsettledWork([job('failed', ['failed'])])).toBe(false)
    expect(hasUnsettledWork([job('cancelled', ['cancelled'])])).toBe(false)
  })

  it('문서가 여럿이면 하나라도 안 끝났을 때 계속 읽는다', () => {
    expect(hasUnsettledWork([job('completed', ['completed', 'parsing'])])).toBe(true)
    expect(hasUnsettledWork([job('completed', ['completed', 'failed'])])).toBe(false)
  })

  it('목록이 비었거나 문서 결과가 없으면 멈춘다', () => {
    expect(hasUnsettledWork([])).toBe(false)
    expect(hasUnsettledWork([{ jobId: '1', status: 'completed' }])).toBe(false)
  })
})
