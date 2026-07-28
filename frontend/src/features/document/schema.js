// 이 파일은 런타임 검증기가 아니라 docs/api/AJT-Backend-Public-API.postman_collection.json 기준
// 응답 형태를 코드 옆에 남겨두는 JSDoc 문서다. 실제 응답과 달라지면 이 파일을 먼저 고친다.

/**
 * 확정 — POST /documents 202, PUT .../file 202, POST .../retry 202, POST /ai-jobs/:jobId/cancel 202
 * @typedef {object} JobRef
 * @property {string} jobId
 * @property {string} [documentId]
 * @property {string[]} [documentIds]
 * @property {string} [scopeKey]
 * @property {string} status - waiting | processing | completed | failed | cancelled
 * @property {string} [createdAt]
 */

/**
 * 확정 — GET /ai-jobs/:jobId 200
 * @typedef {object} AiJob
 * @property {string} jobId
 * @property {'waiting'|'processing'|'completed'|'failed'|'cancelled'} status
 * @property {AiJobDocumentResult[]} documentResults
 * @property {string} createdAt
 * @property {string|null} startedAt
 * @property {string|null} finishedAt
 * @property {string|null} failureReason
 */

/**
 * @typedef {object} AiJobDocumentResult
 * @property {string} documentId
 * @property {number} order
 * @property {'uploaded'|'parsing'|'processing'|'completed'|'failed'|'cancelled'} status
 * @property {string|null} currentStage
 * @property {string|null} summary
 * @property {string|null} failureReason
 */

/**
 * 잠정 — GET /documents 200. Postman에 설명만 있고 예시 본문은 없다.
 * // TODO(BE): 실제 응답 확인 후 필드명 수정
 * @typedef {object} DocumentListItem
 * @property {string} documentId
 * @property {string} originalFileName
 * @property {string} mimeType
 * @property {number} fileSize
 * @property {string} documentCategoryId
 * @property {string} documentCategoryName
 * @property {string} scopeKey
 * @property {'all'|'department'} visibilityType
 * @property {{departmentId: string, name: string}[]} departments
 * @property {'uploaded'|'parsing'|'processing'|'completed'|'failed'|'cancelled'} status
 * @property {string|null} failureReason
 * @property {{userId: string, name: string}} uploadedBy
 * @property {string} uploadedAt
 */

/**
 * 잠정 — GET /documents/:documentId 200. 목록 필드 + 아래 항목이 Postman 설명에 명시됨(예시 본문 없음).
 * // TODO(BE): 실제 응답 확인 후 필드명 수정
 * @typedef {DocumentListItem & object} DocumentDetail
 * @property {{wikiId: string, title: string}[]} relatedWikis - 반영 완료·권한 있는 Wiki만 (FR-DOC-013)
 * @property {string} downloadUrl - 권한 검증이 적용된 다운로드 URL
 */

/**
 * 확정 — GET /document-categories 200
 * @typedef {object} DocumentCategory
 * @property {string} documentCategoryId
 * @property {string} scopeKey
 * @property {string} name
 * @property {string} description
 */
