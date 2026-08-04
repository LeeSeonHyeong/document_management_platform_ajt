import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'

vi.mock('../queries', () => ({
  useWiki: () => ({
    data: {
      wikiId: '1',
      scopeKey: 'team-a',
      title: '휴가 규정',
      category: { name: '인사' },
      contentMarkdown: '본문',
      updatedAt: '2026-08-04',
    },
    isLoading: false,
    isError: false,
  }),
  useWikis: () => ({ data: { items: [] } }),
}))

vi.mock('./WikiMarkdown', () => ({ default: () => <div>본문</div> }))
vi.mock('./WikiRelationGraph', () => ({ default: () => <div>관계 그래프</div> }))
vi.mock('./WikiSourcePreviewModal', () => ({ default: () => null }))

import WikiDetail from './WikiDetail'

describe('WikiDetail', () => {
  it('places the relation graph after the title summary and before the article', () => {
    const html = renderToStaticMarkup(<WikiDetail wikiId="1" />)

    expect(html.indexOf('휴가 규정에 관한 사내 기준')).toBeLessThan(html.indexOf('관계 그래프'))
    expect(html.indexOf('관계 그래프')).toBeLessThan(html.lastIndexOf('본문'))
  })
})
