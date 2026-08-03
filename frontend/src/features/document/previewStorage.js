// 요약 묶음(ajt-preview-ai-summaries-v1)은 S15P11B106-192 에서 걷어냈다.
// GET /api/v1/ai-jobs 가 생겨 실제 작업 이력과 문서별 변경 요약을 읽는다.
const PREVIEW_SOURCE_DOCUMENTS_KEY = 'ajt-preview-source-documents-v3'

export function readPreviewSourceDocuments() {
  try {
    const stored = sessionStorage.getItem(PREVIEW_SOURCE_DOCUMENTS_KEY)
    const documents = stored ? JSON.parse(stored) : []
    return documents.map((document) => ({ ...document, relatedWikis: [] }))
  } catch {
    return []
  }
}

export function addPreviewSourceDocuments(documents) {
  const current = readPreviewSourceDocuments()
  const next = [
    ...documents,
    ...current.filter(
      (stored) => !documents.some((document) => document.documentId === stored.documentId),
    ),
  ]
  sessionStorage.setItem(PREVIEW_SOURCE_DOCUMENTS_KEY, JSON.stringify(next))
  return next
}

export function updatePreviewDocument(documentId, changes) {
  const sources = readPreviewSourceDocuments()
  const nextSources = sources.map((document) =>
    document.documentId === documentId
      ? { ...document, ...changes, relatedWikis: [] }
      : document,
  )
  sessionStorage.setItem(PREVIEW_SOURCE_DOCUMENTS_KEY, JSON.stringify(nextSources))

  return nextSources.find((document) => document.documentId === documentId) ?? null
}

export function removePreviewDocument(documentId) {
  const sources = readPreviewSourceDocuments()
  const target = sources.find((document) => document.documentId === documentId)
  const nextSources = sources.filter((document) => document.documentId !== documentId)
  sessionStorage.setItem(PREVIEW_SOURCE_DOCUMENTS_KEY, JSON.stringify(nextSources))

  if (target?.downloadUrl?.startsWith('blob:')) URL.revokeObjectURL(target.downloadUrl)
}
