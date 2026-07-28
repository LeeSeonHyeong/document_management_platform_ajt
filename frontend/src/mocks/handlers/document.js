import { http, HttpResponse } from 'msw'
import {
  departmentsByIds,
  buildScopeKey,
  documents,
  documentCategories,
  aiJobs,
  findUserById,
  findDocumentById,
  findDocumentCategoryById,
  issueDocumentId,
  issueJobId,
  issueCategoryId,
} from '../db'

function errorBody(status, code, message, path, fieldErrors = []) {
  return { timestamp: new Date().toISOString(), status, error: code, code, message, path, fieldErrors }
}

function toListItem(doc) {
  const category = findDocumentCategoryById(doc.documentCategoryId)
  return {
    documentId: doc.documentId,
    originalFileName: doc.originalFileName,
    mimeType: doc.mimeType,
    fileSize: doc.fileSize,
    documentCategoryId: doc.documentCategoryId,
    documentCategoryName: category?.name ?? null,
    scopeKey: doc.scopeKey,
    visibilityType: doc.visibilityType,
    departments: departmentsByIds(doc.departmentIds),
    status: doc.status,
    failureReason: doc.failureReason,
    uploadedBy: (() => {
      const uploader = findUserById(doc.uploaderId)
      return uploader ? { userId: uploader.userId, name: uploader.name } : null
    })(),
    uploadedAt: doc.uploadedAt,
  }
}

function toDetail(doc) {
  // Wiki 도메인 목은 features/wiki 프롬프트(3/3)에서 채운다. 지금은 문서-Wiki 연결 개수만 흉내낸다.
  const relatedWikis = doc.documentWikiRefs.map((wikiId) => ({ wikiId, title: `Wiki ${wikiId}` }))
  return {
    ...toListItem(doc),
    relatedWikis,
    downloadUrl: `/api/v1/documents/${doc.documentId}/file`,
  }
}

export const documentHandlers = [
  // POST /documents — 업로드 묶음을 하나의 ai_job으로 생성
  http.post('/api/v1/documents', async ({ request }) => {
    const form = await request.formData()
    const files = form.getAll('files')
    const documentCategoryId = form.get('documentCategoryId')
    const visibilityType = form.get('visibilityType')
    const departmentIds = String(form.get('departmentIds') ?? '')
      .split(',')
      .filter(Boolean)

    if (files.length === 0 || !documentCategoryId || !visibilityType) {
      return HttpResponse.json(
        errorBody(400, 'INVALID_DOCUMENT_UPLOAD', '파일 형식, 개수 또는 용량 제한을 확인해주세요.', '/api/v1/documents'),
        { status: 400 },
      )
    }

    const scopeKey = visibilityType === 'all' ? 'ALL' : buildScopeKey(departmentIds)
    const createdAt = new Date().toISOString()
    const newDocs = files.map((file, index) => {
      const documentId = issueDocumentId()
      const doc = {
        documentId,
        originalFileName: file.name ?? `문서-${documentId}`,
        mimeType: file.type || 'application/octet-stream',
        fileSize: file.size ?? 0,
        documentCategoryId,
        scopeKey,
        visibilityType,
        departmentIds,
        status: 'uploaded',
        failureReason: null,
        documentWikiRefs: [],
        uploaderId: '2',
        uploadedAt: createdAt,
        _order: index + 1,
      }
      documents.push(doc)
      return doc
    })

    const jobId = issueJobId()
    aiJobs.push({
      jobId,
      scopeKey,
      requesterId: '2',
      documentIds: newDocs.map((d) => d.documentId),
      status: 'waiting',
      documentResults: newDocs.map((d) => ({
        documentId: d.documentId,
        order: d._order,
        status: 'uploaded',
        currentStage: null,
        summary: null,
        failureReason: null,
      })),
      createdAt,
      startedAt: null,
      finishedAt: null,
      failureReason: null,
    })

    return HttpResponse.json(
      { jobId, documentIds: newDocs.map((d) => d.documentId), scopeKey, status: 'waiting', createdAt },
      { status: 202 },
    )
  }),

  // GET /documents — TODO(BE): 실제 페이지네이션·필터 응답 확인 후 조정
  http.get('/api/v1/documents', ({ request }) => {
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? '1')
    const size = Number(url.searchParams.get('size') ?? '20')
    const scopeKey = url.searchParams.get('scopeKey')
    const categoryId = url.searchParams.get('categoryId')
    const status = url.searchParams.get('status')
    const keyword = url.searchParams.get('keyword')
    const fileType = url.searchParams.get('fileType')
    const departmentId = url.searchParams.get('departmentId')

    let filtered = documents
    if (scopeKey) filtered = filtered.filter((d) => d.scopeKey === scopeKey)
    if (categoryId) filtered = filtered.filter((d) => d.documentCategoryId === categoryId)
    if (status) filtered = filtered.filter((d) => d.status === status)
    if (fileType) filtered = filtered.filter((d) => d.originalFileName.toLowerCase().endsWith(`.${fileType}`))
    if (departmentId) filtered = filtered.filter((d) => d.departmentIds.includes(departmentId))
    if (keyword) filtered = filtered.filter((d) => d.originalFileName.includes(keyword))

    const totalCount = filtered.length
    const totalPages = Math.max(1, Math.ceil(totalCount / size))
    const items = filtered.slice((page - 1) * size, page * size).map(toListItem)

    return HttpResponse.json({ items, page, size, totalCount, totalPages })
  }),

  http.get('/api/v1/documents/:documentId', ({ params }) => {
    const doc = findDocumentById(params.documentId)
    if (!doc) {
      return HttpResponse.json(
        errorBody(404, 'DOCUMENT_NOT_FOUND', '문서가 없거나 접근할 수 없습니다.', `/api/v1/documents/${params.documentId}`),
        { status: 404 },
      )
    }
    return HttpResponse.json(toDetail(doc))
  }),

  // PATCH /documents/:documentId — 공개 범위·카테고리 변경. 202 + 재처리 jobId
  http.patch('/api/v1/documents/:documentId', async ({ request, params }) => {
    const doc = findDocumentById(params.documentId)
    if (!doc) {
      return HttpResponse.json(
        errorBody(404, 'DOCUMENT_NOT_FOUND', '문서가 없거나 접근할 수 없습니다.', `/api/v1/documents/${params.documentId}`),
        { status: 404 },
      )
    }
    const body = await request.json()
    if (body.documentCategoryId) doc.documentCategoryId = body.documentCategoryId
    if (body.visibilityType) doc.visibilityType = body.visibilityType
    if (body.departmentIds) {
      doc.departmentIds = body.departmentIds
      doc.scopeKey = doc.visibilityType === 'all' ? 'ALL' : buildScopeKey(body.departmentIds)
    }

    const jobId = issueJobId()
    aiJobs.push({
      jobId,
      scopeKey: doc.scopeKey,
      requesterId: '2',
      documentIds: [doc.documentId],
      status: 'waiting',
      documentResults: [
        { documentId: doc.documentId, order: 1, status: doc.status, currentStage: null, summary: null, failureReason: null },
      ],
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
      failureReason: null,
    })

    return HttpResponse.json({ jobId, ...toDetail(doc) }, { status: 202 })
  }),

  // GET /documents/:documentId/file — 목 파일 blob
  http.get('/api/v1/documents/:documentId/file', ({ params }) => {
    const doc = findDocumentById(params.documentId)
    if (!doc) {
      return HttpResponse.json(
        errorBody(404, 'DOCUMENT_NOT_FOUND', '문서가 없거나 접근할 수 없습니다.', `/api/v1/documents/${params.documentId}/file`),
        { status: 404 },
      )
    }
    return new HttpResponse(new Blob(['mock file content'], { type: doc.mimeType }), {
      headers: {
        'Content-Type': doc.mimeType,
        'Content-Disposition': `attachment; filename="${doc.originalFileName}"`,
      },
    })
  }),

  // PUT /documents/:documentId/file — 파일 교체. 202 + 재처리 jobId
  http.put('/api/v1/documents/:documentId/file', ({ params }) => {
    const doc = findDocumentById(params.documentId)
    if (!doc) {
      return HttpResponse.json(
        errorBody(404, 'DOCUMENT_NOT_FOUND', '문서가 없거나 접근할 수 없습니다.', `/api/v1/documents/${params.documentId}/file`),
        { status: 404 },
      )
    }
    doc.status = 'uploaded'
    const jobId = issueJobId()
    aiJobs.push({
      jobId,
      scopeKey: doc.scopeKey,
      requesterId: '2',
      documentIds: [doc.documentId],
      status: 'waiting',
      documentResults: [
        { documentId: doc.documentId, order: 1, status: 'uploaded', currentStage: null, summary: null, failureReason: null },
      ],
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
      failureReason: null,
    })
    return HttpResponse.json({ jobId, documentId: doc.documentId, status: doc.status }, { status: 202 })
  }),

  // DELETE /documents/:documentId — 202 + 재처리 jobId (204 아님, postman 계약 기준)
  http.delete('/api/v1/documents/:documentId', ({ params }) => {
    const index = documents.findIndex((d) => d.documentId === params.documentId)
    if (index === -1) {
      return HttpResponse.json(
        errorBody(404, 'DOCUMENT_NOT_FOUND', '문서가 없거나 접근할 수 없습니다.', `/api/v1/documents/${params.documentId}`),
        { status: 404 },
      )
    }
    const [doc] = documents.splice(index, 1)
    const jobId = issueJobId()
    aiJobs.push({
      jobId,
      scopeKey: doc.scopeKey,
      requesterId: '2',
      documentIds: [],
      status: 'completed',
      documentResults: [],
      createdAt: new Date().toISOString(),
      startedAt: new Date().toISOString(),
      finishedAt: new Date().toISOString(),
      failureReason: null,
    })
    return HttpResponse.json({ jobId, status: 'completed' }, { status: 202 })
  }),

  // POST /documents/:documentId/retry — failed/cancelled만 허용
  http.post('/api/v1/documents/:documentId/retry', ({ params }) => {
    const doc = findDocumentById(params.documentId)
    if (!doc) {
      return HttpResponse.json(
        errorBody(404, 'DOCUMENT_NOT_FOUND', '문서가 없거나 접근할 수 없습니다.', `/api/v1/documents/${params.documentId}/retry`),
        { status: 404 },
      )
    }
    if (doc.status !== 'failed' && doc.status !== 'cancelled') {
      return HttpResponse.json(
        errorBody(409, 'DOCUMENT_NOT_RETRYABLE', 'failed 또는 cancelled 상태만 재처리할 수 있습니다.', `/api/v1/documents/${params.documentId}/retry`),
        { status: 409 },
      )
    }
    doc.status = 'parsing'
    doc.failureReason = null
    const jobId = issueJobId()
    aiJobs.push({
      jobId,
      scopeKey: doc.scopeKey,
      requesterId: '2',
      documentIds: [doc.documentId],
      status: 'waiting',
      documentResults: [
        { documentId: doc.documentId, order: 1, status: 'parsing', currentStage: 'parsing', summary: null, failureReason: null },
      ],
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
      failureReason: null,
    })
    return HttpResponse.json({ jobId, documentId: doc.documentId, status: doc.status }, { status: 202 })
  }),

  // ── 원본문서 카테고리 ──────────────────────────────────────────
  http.get('/api/v1/document-categories', ({ request }) => {
    const scopeKey = new URL(request.url).searchParams.get('scopeKey')
    const items = scopeKey ? documentCategories.filter((c) => c.scopeKey === scopeKey) : documentCategories
    return HttpResponse.json({ items })
  }),

  http.post('/api/v1/document-categories', async ({ request }) => {
    const body = await request.json()
    const duplicate = documentCategories.some((c) => c.scopeKey === body.scopeKey && c.name === body.name)
    if (duplicate) {
      return HttpResponse.json(
        errorBody(409, 'DUPLICATE_CATEGORY_NAME', '같은 공간에 동일한 이름의 카테고리가 있습니다.', '/api/v1/document-categories'),
        { status: 409 },
      )
    }
    const category = {
      documentCategoryId: issueCategoryId(),
      scopeKey: body.scopeKey,
      name: body.name,
      description: body.description ?? '',
    }
    documentCategories.push(category)
    return HttpResponse.json(category, { status: 201 })
  }),

  http.patch('/api/v1/document-categories/:categoryId', async ({ request, params }) => {
    const category = findDocumentCategoryById(params.categoryId)
    if (!category) {
      return HttpResponse.json(
        errorBody(404, 'CATEGORY_NOT_FOUND', '존재하지 않는 카테고리입니다.', `/api/v1/document-categories/${params.categoryId}`),
        { status: 404 },
      )
    }
    const body = await request.json()
    if (body.name !== undefined) category.name = body.name
    if (body.description !== undefined) category.description = body.description
    return HttpResponse.json(category)
  }),

  http.delete('/api/v1/document-categories/:categoryId', ({ params }) => {
    const index = documentCategories.findIndex((c) => c.documentCategoryId === params.categoryId)
    if (index === -1) {
      return HttpResponse.json(
        errorBody(404, 'CATEGORY_NOT_FOUND', '존재하지 않는 카테고리입니다.', `/api/v1/document-categories/${params.categoryId}`),
        { status: 404 },
      )
    }
    const inUse = documents.some((d) => d.documentCategoryId === params.categoryId)
    if (inUse) {
      return HttpResponse.json(
        errorBody(409, 'CATEGORY_IN_USE', '문서에서 사용 중인 카테고리입니다.', `/api/v1/document-categories/${params.categoryId}`),
        { status: 409 },
      )
    }
    documentCategories.splice(index, 1)
    return new HttpResponse(null, { status: 204 })
  }),
]

// AI 작업(GET /ai-jobs/:jobId, POST .../cancel) 핸들러는 mocks/handlers/aiJob.js 로 옮겼다.
// 폴링할 때마다 진행 상태가 조금씩 전진하는 동작이 필요해서 문서 핸들러와 분리했다.
