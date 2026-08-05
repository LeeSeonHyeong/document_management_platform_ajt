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
  const { data } = await apiClient.post('/documents', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress,
  })
  return data
}

// POST /api/v1/schedule-sources (multipart)
// 일정 원본은 문서 원본과 API 계약이 달라 파일별로 업로드한다.
export async function uploadScheduleSource({ file, visibilityType, departmentIds, onUploadProgress }) {
  const form = new FormData()
  form.append('file', file)
  if (visibilityType) form.append('visibilityType', visibilityType)
  if (departmentIds?.length) form.append('departmentIds', departmentIds.join(','))
  const { data } = await apiClient.post('/schedule-sources', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress,
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
export async function replaceDocumentFile(documentId, file, { onUploadProgress } = {}) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await apiClient.put(`/documents/${documentId}/file`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress,
  })
  return data
}

// DELETE /api/v1/documents/:documentId — 202
//   { deleted, reprocessRequired, jobId, scopeKey, status }
//   deleted=true(실패는 에러 응답). reprocessRequired=true면 Wiki 재처리 작업이 생성되고 jobId·status=waiting,
//   재처리할 내용이 없으면 reprocessRequired=false·jobId=null·status=skipped(정상). jobId=null을 실패로 보지 않는다.
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

// GET /api/v1/ai-jobs — 작업 이력 목록(최신순). 요약 목록 화면이 회차별로 묶어 보여준다.
// 항목 모양은 단건 조회와 같다.
export async function fetchAiJobs({ page = 1, size = 20 } = {}) {
  const { data } = await apiClient.get('/ai-jobs', { params: { page, size } })
  return data
}

// GET /api/v1/ai-jobs/:jobId
export async function fetchAiJob(jobId) {
  const { data } = await apiClient.get(`/ai-jobs/${jobId}`)
  return data
}

// POST /api/v1/ai-jobs/:jobId/start — 202 { jobId, status: 'processing' }
// 업로드는 작업을 waiting으로만 만든다. 이 호출이 있어야 파싱·Wiki 변환이 시작된다.
export async function startAiJob(jobId) {
  const { data } = await apiClient.post(`/ai-jobs/${jobId}/start`)
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
