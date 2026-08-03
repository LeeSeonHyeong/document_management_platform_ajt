import { describe, expect, it } from 'vitest'
import { PREVIEW_KIND, fileExtension, previewKindOf } from './kind'

describe('previewKindOf', () => {
  it('확장자로 렌더러를 고른다', () => {
    expect(previewKindOf('취업규칙.pdf')).toBe(PREVIEW_KIND.PDF)
    expect(previewKindOf('연차_사용_안내.docx')).toBe(PREVIEW_KIND.DOCX)
    expect(previewKindOf('개발팀_코딩_컨벤션.md')).toBe(PREVIEW_KIND.MARKDOWN)
    expect(previewKindOf('전사_공지사항_모음.txt')).toBe(PREVIEW_KIND.TEXT)
    expect(previewKindOf('2026_상반기_일정.csv')).toBe(PREVIEW_KIND.SHEET)
    expect(previewKindOf('2026_상반기_일정.xlsx')).toBe(PREVIEW_KIND.SHEET)
  })

  it('대소문자를 가리지 않는다', () => {
    expect(previewKindOf('REPORT.PDF')).toBe(PREVIEW_KIND.PDF)
  })

  // 업로드한 OS·브라우저에 따라 서버 mimeType 이 octet-stream 으로 오는 경우가 있어
  // 확장자를 1차 기준으로 삼는다.
  it('mimeType 이 부정확해도 확장자를 우선한다', () => {
    expect(previewKindOf('취업규칙.pdf', 'application/octet-stream')).toBe(PREVIEW_KIND.PDF)
  })

  it('확장자가 없으면 mimeType 으로 판단한다', () => {
    expect(previewKindOf('무제', 'application/pdf')).toBe(PREVIEW_KIND.PDF)
    expect(previewKindOf(undefined, 'text/markdown')).toBe(PREVIEW_KIND.MARKDOWN)
    expect(previewKindOf('무제', 'text/plain; charset=UTF-8')).toBe(PREVIEW_KIND.TEXT)
  })

  it('둘 다 모르면 미지원으로 떨어진다', () => {
    expect(previewKindOf('도장.hwp')).toBe(PREVIEW_KIND.UNSUPPORTED)
    expect(previewKindOf('스캔.png', 'image/png')).toBe(PREVIEW_KIND.UNSUPPORTED)
    expect(previewKindOf(undefined, undefined)).toBe(PREVIEW_KIND.UNSUPPORTED)
  })
})

describe('fileExtension', () => {
  it('마지막 확장자만 소문자로 돌려준다', () => {
    expect(fileExtension('a.b.DOCX')).toBe('docx')
    expect(fileExtension('확장자없음')).toBe('')
    expect(fileExtension(undefined)).toBe('')
  })
})
