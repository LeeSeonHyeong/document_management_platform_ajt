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
  // 공유 apiClient의 기본 헤더가 application/json이라, 명시하지 않으면 axios가 FormData를
  // JSON으로 직렬화해 파일이 소실된다. multipart를 지정해 boundary 자동 생성 경로를 탄다.
  const { data } = await apiClient.post('/documents', form, {
    onUploadProgress,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

// GET /api/v1/documents — items: 문서 ID, 파일명, scopeKey, 카테고리, 상태, 업로드자, 업로드 시각
export async function fetchDocuments(filters = {}) {
  const { data } = await apiClient.get('/documents', { params: filters })
  return {
    ...data,
    items: (data.items ?? []).map(normalizeDocument),
  }
}

// GET /api/v1/documents/:documentId — 메타데이터 + relatedWikis(반영 완료된 연결 Wiki만)
export async function fetchDocument(documentId) {
  const { data } = await apiClient.get(`/documents/${documentId}`)
  return normalizeDocument(data)
}

// PATCH /api/v1/documents/:documentId — 202, 재처리 jobId + 수정된 문서 정보를 함께 반환
export async function updateDocument(documentId, { documentCategoryId, visibilityType, departmentIds }) {
  const { data } = await apiClient.patch(`/documents/${documentId}`, {
    documentCategoryId,
    visibilityType,
    departmentIds,
  })
  return normalizeDocument(data)
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
  // uploadDocuments와 동일: multipart 명시로 FormData의 JSON 직렬화(파일 소실) 방지.
  const { data } = await apiClient.put(`/documents/${documentId}/file`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
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

// 계약(docs/api generate-postman-collections.mjs): 카테고리 목록·생성·수정 응답의 ID 필드는 `categoryId`.
// 컴포넌트는 문서 객체와 동일하게 `documentCategoryId`로 읽으므로, 여기서 별칭을 붙여 정규화한다.
function normalizeCategory(category) {
  if (!category) return category
  return { ...category, documentCategoryId: category.documentCategoryId ?? category.categoryId }
}

// GET /api/v1/document-categories?scopeKey=
export async function fetchDocumentCategories(scopeKey) {
  const { data } = await apiClient.get('/document-categories', { params: { scopeKey } })
  return (data.items ?? []).map(normalizeCategory)
}

// POST /api/v1/document-categories
export async function createDocumentCategory({ scopeKey, name, description }) {
  const { data } = await apiClient.post('/document-categories', { scopeKey, name, description })
  return normalizeCategory(data)
}

// PATCH /api/v1/document-categories/:categoryId — scopeKey는 보내지 않는다.
export async function updateDocumentCategory(categoryId, { name, description }) {
  const { data } = await apiClient.patch(`/document-categories/${categoryId}`, { name, description })
  return normalizeCategory(data)
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

function normalizeDocument(document) {
  const category = document.category ?? null
  return {
    ...document,
    documentCategoryId:
      document.documentCategoryId ??
      category?.documentCategoryId ??
      category?.categoryId ??
      null,
    documentCategoryName:
      document.documentCategoryName ??
      category?.name ??
      null,
    uploadedAt: document.uploadedAt ?? document.createdAt ?? null,
  }
}