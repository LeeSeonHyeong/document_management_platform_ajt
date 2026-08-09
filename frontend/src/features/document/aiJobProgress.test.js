import { describe, expect, it } from 'vitest'
import { buildAiJobProgress, documentStagesFor } from './aiJobProgress'

describe('묶음 AI 작업 진행 표시', () => {
  it('위키 변경안 생성 중인 문서의 실제 처리 단계를 표시한다', () => {
    expect(documentStagesFor({ status: 'processing', currentStage: 'wiki_transform' })).toEqual([
      { label: '원본 문서 분석', status: 'completed' },
      { label: '위키 변경안 생성', status: 'processing' },
      { label: '위키 반영', status: 'waiting' },
    ])
  })

  it('현재 처리 문서와 범위별 완료 수를 만든다', () => {
    const progress = buildAiJobProgress(
      [
        {
          jobId: '1',
          status: 'completed',
          documentResults: [{ documentId: '11', originalFileName: '인사 규정.pdf', status: 'completed' }],
        },
        {
          jobId: '2',
          status: 'processing',
          documentResults: [
            { documentId: '21', originalFileName: '개발 규칙.pdf', status: 'processing', currentStage: 'wiki_transform' },
            { documentId: '22', originalFileName: 'API 규약.pdf', status: 'uploaded' },
          ],
        },
      ],
      [
        { jobId: '1', scopeLabel: '인사부' },
        { jobId: '2', scopeLabel: '개발부' },
      ],
    )

    expect(progress.current).toMatchObject({
      originalFileName: '개발 규칙.pdf',
      stageLabel: '위키 반영 중',
      scopeLabel: '개발부',
    })
    expect(progress.groups).toEqual([
      { jobId: '1', label: '인사부', completed: 1, failed: 0, cancelled: 0, pending: 0, total: 1, state: 'completed' },
      { jobId: '2', label: '개발부', completed: 0, failed: 0, cancelled: 0, pending: 2, total: 2, state: 'processing' },
    ])
  })
})
