import apiClient from '@/api/client'

// ── Wiki 원본문서 ────────────────────────────────────────────────

// POST /api/v1/documents (multipart) — 202 { jobId, documentIds, scopeKey, status, createdAt }
// onUploadProgress: axios 진행률 콜백(선택). 진행 표시가 필요한 업로드 모달에서 넘긴다.
export async function uploadDocuments({ files, documentCategoryId, visibilityType, departmentIds, onUploadProgress }) {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  form.append('documentCategoryId', documentCategoryId)
  form.append('visibilityType', visibilityType)
  // 업로드 요청은 배열이 아니라 콤마로 이어붙인 문자열이다(PATCH의 departmentIds 배열과 다름).
  if (departmentIds?.length) form.append('departmentIds', departmentIds.join(','))
  const { data } = await apiClient.post('/documents', form, { onUploadProgress })
  return data
}

// GET /api/v1/documents — items: 문서 ID, 파일명, scopeKey, 카테고리, 상태, 업로드자, 업로드 시각
export async function fetchDocuments(filters = {}) {
  const { data } = await apiClient.get('/documents', { params: filters })
  return data
}

// GET /api/v1/documents/:documentId — 메타데이터 + relatedWikis(반영 완료된 연결 Wiki만)
export async function fetchDocument(documentId) {
  const { data } = await apiClient.get(`/documents/${documentId}`)
  return data
}

// PATCH /api/v1/documents/:documentId — 202, 재처리 jobId + 수정된 문서 정보를 함께 반환
export async function updateDocument(documentId, { documentCategoryId, visibilityType, departmentIds }) {
  const { data } = await apiClient.patch(`/documents/${documentId}`, {
    documentCategoryId,
    visibilityType,
    departmentIds,
  })
  return data
}

// GET /api/v1/documents/:documentId/file — blob. Content-Disposition에서 원본 파일명을 읽는다.
export async function fetchDocumentFile(documentId) {
  const response = await apiClient.get(`/documents/${documentId}/file`, { responseType: 'blob' })
  return { blob: response.data, fileName: parseContentDispositionFileName(response.headers) }
}

function parseContentDispositionFileName(headers) {
  const disposition = headers?.['content-disposition'] ?? ''
  const match = /filename\*?=(?:UTF-8''|")?([^;"]+)"?/i.exec(disposition)
  return match ? decodeURIComponent(match[1]) : null
}

// PUT /api/v1/documents/:documentId/file (multipart, 필드명 file) — 202 { jobId, documentId, status }
export async function replaceDocumentFile(documentId, file) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await apiClient.put(`/documents/${documentId}/file`, form)
  return data
}

// DELETE /api/v1/documents/:documentId — 202, 재처리 jobId를 반환한다(즉시 204가 아님).
export async function deleteDocument(documentId) {
  const { data } = await apiClient.delete(`/documents/${documentId}`)
  return data
}

// POST /api/v1/documents/:documentId/retry — 202 { jobId, documentId, status }. failed/cancelled만 가능.
export async function retryDocument(documentId) {
  const { data } = await apiClient.post(`/documents/${documentId}/retry`)
  return data
}

// ── 원본문서 카테고리 ────────────────────────────────────────────

// GET /api/v1/document-categories?scopeKey=
export async function fetchDocumentCategories(scopeKey) {
  const { data } = await apiClient.get('/document-categories', { params: { scopeKey } })
  return data.items ?? []
}

// POST /api/v1/document-categories
export async function createDocumentCategory({ scopeKey, name, description }) {
  const { data } = await apiClient.post('/document-categories', { scopeKey, name, description })
  return data
}

// PATCH /api/v1/document-categories/:categoryId — scopeKey는 보내지 않는다.
export async function updateDocumentCategory(categoryId, { name, description }) {
  const { data } = await apiClient.patch(`/document-categories/${categoryId}`, { name, description })
  return data
}

// DELETE /api/v1/document-categories/:categoryId — 204
export async function deleteDocumentCategory(categoryId) {
  await apiClient.delete(`/document-categories/${categoryId}`)
}

// ── AI 작업 ──────────────────────────────────────────────────────

// GET /api/v1/ai-jobs/:jobId
export async function fetchAiJob(jobId) {
  const { data } = await apiClient.get(`/ai-jobs/${jobId}`)
  return data
}

// POST /api/v1/ai-jobs/:jobId/cancel — 202 { jobId, status: 'cancelled' }
export async function cancelAiJob(jobId) {
  const { data } = await apiClient.post(`/ai-jobs/${jobId}/cancel`)
  return data
}
