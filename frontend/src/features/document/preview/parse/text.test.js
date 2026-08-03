import { describe, expect, it } from 'vitest'
import { MAX_TEXT_CHARS, decodeText, readPreviewText } from './text'

describe('decodeText', () => {
  it('UTF-8 한글을 읽는다', async () => {
    const blob = new Blob(['전사 공지사항 모음'], { type: 'text/plain' })
    await expect(decodeText(blob)).resolves.toBe('전사 공지사항 모음')
  })

  // 메모장에서 저장한 CP949 txt 가 실제로 올라온다. UTF-8 로 읽으면 깨지므로 폴백이 필요하다.
  it('CP949(EUC-KR) 한글을 폴백으로 읽는다', async () => {
    // '한글' = C7 D1 B1 DB (CP949)
    const blob = new Blob([new Uint8Array([0xc7, 0xd1, 0xb1, 0xdb])], { type: 'text/plain' })
    await expect(decodeText(blob)).resolves.toBe('한글')
  })
})

describe('readPreviewText', () => {
  it('미리보기 길이 상한까지만 자른다', async () => {
    const blob = new Blob(['가'.repeat(MAX_TEXT_CHARS + 500)], { type: 'text/plain' })
    await expect(readPreviewText(blob)).resolves.toHaveLength(MAX_TEXT_CHARS)
  })
})
