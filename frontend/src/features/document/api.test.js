import { beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient from '@/api/client'
import { replaceDocumentFile, uploadDocuments } from './api'

vi.mock('@/api/client', () => ({
  default: {
    post: vi.fn(),
    put: vi.fn(),
  },
}))

describe('문서 파일 multipart 전송', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('원본문서 파일과 메타데이터를 multipart/form-data로 전송한다', async () => {
    const firstFile = new File(['first'], 'first.txt', { type: 'text/plain' })
    const secondFile = new File(['second'], 'second.md', { type: 'text/markdown' })
    apiClient.post.mockResolvedValue({ data: { jobId: '17' } })

    await uploadDocuments({
      files: [firstFile, secondFile],
      documentCategoryId: '3',
      visibilityType: 'department',
      departmentIds: ['2', '1'],
      onUploadProgress: vi.fn(),
    })

    const [path, formData, config] = apiClient.post.mock.calls[0]
    expect(path).toBe('/documents')
    expect(formData).toBeInstanceOf(FormData)
    expect(formData.getAll('files').map((file) => file.name)).toEqual(['first.txt', 'second.md'])
    expect(formData.get('documentCategoryId')).toBe('3')
    expect(formData.get('visibilityType')).toBe('department')
    expect(formData.get('departmentIds')).toBe('2,1')
    expect(config.headers).toEqual({ 'Content-Type': 'multipart/form-data' })
  })

  it('원본문서 교체 파일을 multipart/form-data로 전송한다', async () => {
    const file = new File(['replacement'], 'replacement.pdf', { type: 'application/pdf' })
    apiClient.put.mockResolvedValue({ data: { jobId: '18' } })

    await replaceDocumentFile('9', file)

    const [path, formData, config] = apiClient.put.mock.calls[0]
    expect(path).toBe('/documents/9/file')
    expect(formData.get('file').name).toBe('replacement.pdf')
    expect(config.headers).toEqual({ 'Content-Type': 'multipart/form-data' })
  })
})
