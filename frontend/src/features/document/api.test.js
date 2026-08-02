import { beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient from '@/api/client'
import { fetchDocumentFile } from './api'

vi.mock('@/api/client', () => ({
  default: { get: vi.fn() },
}))

async function fetchFileName(contentDisposition) {
  apiClient.get.mockResolvedValue({
    data: new Blob(['document']),
    headers: contentDisposition ? { 'content-disposition': contentDisposition } : {},
  })

  const result = await fetchDocumentFile('15')
  return result.fileName
}

describe('fetchDocumentFile', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('filename보다 UTF-8 filename*을 우선한다', async () => {
    const disposition =
      'attachment; filename="15_ AI ____ __ __.pdf"; filename*=UTF-8\'\'15%EA%B8%B0%20AI%20%EC%8B%A4%EC%8A%B5%ED%8A%B9%EA%B0%95%20%EC%82%AC%EC%A0%84%20%EC%84%B8%ED%8C%85.pdf'

    await expect(fetchFileName(disposition)).resolves.toBe('15기 AI 실습특강 사전 세팅.pdf')
  })

  it('filename*이 없으면 일반 filename을 사용한다', async () => {
    await expect(fetchFileName('attachment; filename="rules.pdf"')).resolves.toBe('rules.pdf')
  })

  it('잘못된 filename* 인코딩은 null로 처리한다', async () => {
    await expect(fetchFileName("attachment; filename*=UTF-8''%E0%A4%A")).resolves.toBeNull()
  })

  it('Content-Disposition이 없으면 null을 반환한다', async () => {
    await expect(fetchFileName()).resolves.toBeNull()
  })
})
