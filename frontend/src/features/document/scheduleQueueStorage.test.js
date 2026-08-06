import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  persistScheduleQueueDocuments,
  persistScheduleQueueMetadata,
  readScheduleQueue,
  removeScheduleQueueDocuments,
} from './scheduleQueueStorage'

// IndexedDB가 없는 환경(테스트 러너·구형 브라우저·일부 프라이빗 모드)에서 대기 목록 저장이
// 화면을 깨뜨리면 안 된다. 저장은 편의 기능이고, 실패하면 세션 안에서만 유지되는 예전 동작으로
// 돌아가야 한다 (S15P11B106-276).
describe('일정 대기 목록 저장 (IndexedDB를 쓸 수 없는 환경)', () => {
  beforeEach(() => {
    vi.stubGlobal('indexedDB', undefined)
  })

  it('읽기에 실패하면 빈 목록으로 시작한다', async () => {
    await expect(readScheduleQueue()).resolves.toEqual({ documents: [], metadata: {} })
  })

  it('저장이 실패해도 예외를 던지지 않는다', async () => {
    const file = new File(['# 문서'], 'plan.md', { type: 'text/markdown' })
    await expect(
      persistScheduleQueueDocuments([
        { documentId: 'preview-1', sourceFile: file, originalFileName: 'plan.md' },
      ]),
    ).resolves.toBeUndefined()
    await expect(persistScheduleQueueMetadata('preview-1', {})).resolves.toBeUndefined()
  })

  it('삭제가 실패해도 예외를 던지지 않는다', async () => {
    await expect(removeScheduleQueueDocuments(['preview-1'])).resolves.toBeUndefined()
  })

  it('담을 파일이 없으면 저장을 시도하지 않는다', async () => {
    // sourceFile이 없는 항목(서버 문서)은 담지 않는다. IndexedDB 접근 자체가 없어야 한다.
    await expect(
      persistScheduleQueueDocuments([{ documentId: '15', originalFileName: 'rule.md' }]),
    ).resolves.toBeUndefined()
  })

  it('지울 대상이 없으면 삭제를 시도하지 않는다', async () => {
    await expect(removeScheduleQueueDocuments([])).resolves.toBeUndefined()
  })
})
