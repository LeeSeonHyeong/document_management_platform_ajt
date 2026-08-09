import { describe, expect, it } from 'vitest'
import {
  actionTypeCountsLabel,
  affectedWikisFor,
  documentAction,
} from './aiJobResultPresentation'

describe('AI 작업 결과 표시 데이터', () => {
  it('기록이 없는 과거 문서를 추가로 보고 작업 유형별 건수를 집계한다', () => {
    const results = [
      {},
      { changeType: 'document_added' },
      { changeType: 'document_replaced' },
      { changeType: 'document_removed' },
    ]

    expect(actionTypeCountsLabel(results)).toBe('추가 2 · 교체 1 · 삭제 1')
    expect(documentAction()).toMatchObject({ label: '문서 추가' })
  })

  it('작업의 영향 Wiki 스냅샷을 현재 문서 연결보다 우선한다', () => {
    const result = {
      affectedWikis: [
        { wikiId: '21', title: '폐지된 규정', deleted: true },
        { wikiId: '22', title: '살아 있는 규정', deleted: false },
      ],
    }
    const document = { relatedWikis: [{ wikiId: '99', title: '현재 연결' }] }

    expect(affectedWikisFor(result, document)).toEqual(result.affectedWikis)
    expect(affectedWikisFor({ affectedWikis: [] }, document)).toEqual([])
  })

  it('영향 Wiki 필드가 없던 과거 작업만 현재 문서 연결을 보조값으로 쓴다', () => {
    expect(affectedWikisFor({}, {
      relatedWikis: [{ wikiId: '99', title: '현재 연결' }],
    })).toEqual([{ wikiId: '99', title: '현재 연결', deleted: false }])
  })
})
