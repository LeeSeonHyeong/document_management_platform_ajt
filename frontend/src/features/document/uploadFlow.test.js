import { describe, expect, it } from 'vitest'
import {
  buildWikiUploadPayload,
  validateWikiFile,
  validateWikiUpload,
  wikiUploadProgressPath,
} from './uploadFlow'

describe('Wiki 원본문서 업로드 흐름', () => {
  it('지원 형식과 파일당 20MB 제한을 검증한다', () => {
    expect(validateWikiFile({ name: 'policy.pdf', size: 1024 })).toBeNull()
    expect(validateWikiFile({ name: 'sheet.xlsx', size: 1024 })).toContain('지원하지 않는 형식')
    expect(validateWikiFile({ name: 'large.pdf', size: 20 * 1024 * 1024 + 1 })).toContain('20.0MB')
  })

  it('최대 20건과 총 100MB를 검증한다', () => {
    expect(validateWikiUpload([])).toContain('1개 이상')
    expect(
      validateWikiUpload(
        Array.from({ length: 21 }, (_, index) => ({
          name: `${index}.md`,
          size: 1,
        })),
      ),
    ).toContain('20건')
    expect(
      validateWikiUpload([
        { name: 'a.pdf', size: 60 * 1024 * 1024 },
        { name: 'b.pdf', size: 41 * 1024 * 1024 },
      ]),
    ).toContain('100.0MB')
  })

  it('실제 API mutation payload를 만든다', () => {
    const files = [{ name: 'policy.pdf', size: 1024 }]
    const onUploadProgress = () => {}

    expect(
      buildWikiUploadPayload(
        files,
        {
          documentCategoryId: '3',
          visibilityType: 'department',
          departmentIds: ['2', '1'],
        },
        onUploadProgress,
      ),
    ).toEqual({
      files,
      documentCategoryId: '3',
      visibilityType: 'department',
      departmentIds: ['2', '1'],
      onUploadProgress,
    })
  })

  it('서버 jobId로 실제 진행 라우트를 만든다', () => {
    expect(wikiUploadProgressPath({ jobId: '17' })).toBe('/admin/documents/jobs/17/progress')
    expect(() => wikiUploadProgressPath({})).toThrow('jobId')
  })
})
