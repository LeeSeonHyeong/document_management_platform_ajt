import { describe, expect, it } from 'vitest'
import {
  createWikiUploadEntries,
  normalizeWikiFileSelection,
} from './uploadFlow'

describe('문서 카드 파일 선택 연결', () => {
  it('파일 선택과 드롭에서 받은 FileList 형태를 같은 파일 배열로 정규화한다', () => {
    const firstFile = new File(['first'], 'first.txt', { type: 'text/plain' })
    const secondFile = new File(['second'], 'second.md', { type: 'text/markdown' })
    const fileListLike = {
      0: firstFile,
      1: secondFile,
      length: 2,
    }

    expect(normalizeWikiFileSelection(fileListLike)).toEqual([firstFile, secondFile])
    expect(normalizeWikiFileSelection(null)).toEqual([])
  })

  it('카드에서 전달된 파일을 모달의 검증된 초기 파일 목록으로 만든다', () => {
    const validFile = new File(['valid'], 'guide.pdf', { type: 'application/pdf' })
    const invalidFile = new File(['invalid'], 'sheet.xlsx')

    expect(createWikiUploadEntries([validFile, invalidFile], 4)).toEqual({
      entries: [
        { id: 5, file: validFile, error: null },
        {
          id: 6,
          file: invalidFile,
          error: '지원하지 않는 형식입니다 (txt, md, pdf, docx만 허용)',
        },
      ],
      nextSequence: 6,
    })
  })
})
