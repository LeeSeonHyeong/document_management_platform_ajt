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

// 걷어내기가 한 번 실패한 문서. 두 번째 삭제 요청은 성공시켜 재시도 흐름을 볼 수 있게 한다.
const deleteFailedOnce = new Set()

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
    const doc = documents[index]
    const now = new Date().toISOString()

    // 걷어낼 근거가 없으면(Wiki에 반영된 적 없음) 즉시 지운다.
    // 실제 백엔드는 파싱 본문 유무로 가르지만 목 DB에는 그 필드가 없다 — 반영된 Wiki 유무가
    // 같은 것을 뜻한다(반영된 적 있으면 파싱 본문도 있었다).
    if (!(doc.documentWikiRefs ?? []).length) {
      documents.splice(index, 1)
      return HttpResponse.json(
        { deleted: true, reprocessRequired: false, jobId: null, scopeKey: doc.scopeKey, status: 'skipped' },
        { status: 202 },
      )
    }

    // S15P11B106-195: 걷어내기가 끝난 뒤에 지운다. 지금은 deleting 으로 두고 작업만 만든다.
    doc.status = 'deleting'
    const jobId = issueJobId()
    // 파일명에 '연차'가 들어간 문서는 걷어내기가 **처음 한 번만** 실패하도록 둔다.
    // 실패 화면(원본 유지 + 다시 삭제)과, 다시 삭제해서 복구되는 것까지 목으로 볼 수 있어야 한다.
    const fails = (doc.originalFileName ?? '').includes('연차') && !deleteFailedOnce.has(doc.documentId)
    if (fails) deleteFailedOnce.add(doc.documentId)
    if (fails) {
      doc.status = 'failed'
      doc.failureReason = 'Wiki 걷어내기가 시간 안에 끝나지 않았습니다.'
    } else {
      documents.splice(index, 1)
    }
    aiJobs.push({
      jobId,
      scopeKey: doc.scopeKey,
      requesterId: '2',
      documentIds: [doc.documentId],
      status: fails ? 'failed' : 'completed',
      documentResults: [{
        documentId: doc.documentId,
        order: 1,
        status: fails ? 'failed' : 'completed',
        currentStage: fails ? 'parsing' : 'wiki_applied',
        summary: fails ? null : `${doc.originalFileName}을(를) 근거로 쓴 위키 문단을 걷어냈습니다.`,
        failureReason: fails ? 'Wiki 걷어내기가 시간 안에 끝나지 않았습니다.' : null,
        failureStage: fails ? 'agent_timeout' : null,
      }],
      createdAt: now,
      startedAt: now,
      finishedAt: now,
      failureReason: fails ? 'Wiki 걷어내기가 시간 안에 끝나지 않았습니다.' : null,
    })
    return HttpResponse.json(
      { deleted: false, reprocessRequired: true, jobId, scopeKey: doc.scopeKey, status: 'deleting' },
      { status: 202 },
    )
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
  http.get('/api/v1/document-categories', () => {
    // 화면 검증용 목에서는 공개 부서 조합과 관계없이 모든 카테고리를 제공한다.
    // 실제 서비스에서는 백엔드의 scopeKey 정책에 따른 응답을 그대로 사용한다.
    return HttpResponse.json({ items: documentCategories })
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
