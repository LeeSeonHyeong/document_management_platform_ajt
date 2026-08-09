import { beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient from '@/api/client'
import { fetchDocumentFile } from './api'

vi.mock('@/api/client', () => ({ default: { get: vi.fn() } }))

describe('fetchDocumentFile', () => {
  beforeEach(() => vi.clearAllMocks())
  it('UTF-8 filename*을 우선해 한글 원본 파일명을 보존한다', async () => {
    apiClient.get.mockResolvedValue({ data: new Blob(['document']), headers: { 'content-disposition': 'attachment; filename="15_ AI ____ __ __.pdf"; filename*=UTF-8\'\'15%EA%B8%B0%20AI%20%EC%8B%A4%EC%8A%B5%ED%8A%B9%EA%B0%95%20%EC%82%AC%EC%A0%84%20%EC%84%B8%ED%8C%85.pdf' } })
    await expect(fetchDocumentFile('15')).resolves.toMatchObject({ fileName: '15기 AI 실습특강 사전 세팅.pdf' })
  })
})
