import { describe, expect, it } from 'vitest'
import { firstRelatedWiki } from './relatedWikiLink'

describe('요약 목록의 관련 위키 링크', () => {
  it('문서의 첫 관련 위키 제목과 상세 경로를 반환한다', () => {
    expect(firstRelatedWiki({ relatedWikis: [{ wikiId: '42', title: '개발 코딩 컨벤션' }] })).toEqual({
      wikiId: '42',
      title: '개발 코딩 컨벤션',
    })
  })
})
