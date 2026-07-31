const PREVIEW_SOURCE_DOCUMENTS_KEY = 'ajt-preview-source-documents-v3'
const PREVIEW_SUMMARIES_KEY = 'ajt-preview-ai-summaries-v1'

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

export function readPreviewSummaries() {
  try {
    const stored = sessionStorage.getItem(PREVIEW_SUMMARIES_KEY)
    return stored ? JSON.parse(stored) : []
  } catch {
    return []
  }
}

export function addPreviewSummary(documents) {
  const current = readPreviewSummaries()
  const createdAt = new Date().toISOString()
  const summary = {
    summaryId: `preview-summary-${Date.now()}`,
    createdAt,
    previewOnly: true,
    documents: documents.map((document) => ({
      ...document,
      status: 'completed',
      relatedWikis: document.relatedWikis?.length
        ? document.relatedWikis
        : [{ title: `${document.documentCategoryName} 위키` }],
    })),
  }
  const next = [summary, ...current]
  sessionStorage.setItem(PREVIEW_SUMMARIES_KEY, JSON.stringify(next))
  return summary
}

export function updatePreviewDocument(documentId, changes) {
  const sources = readPreviewSourceDocuments()
  const nextSources = sources.map((document) =>
    document.documentId === documentId
      ? { ...document, ...changes, relatedWikis: [] }
      : document,
  )
  sessionStorage.setItem(PREVIEW_SOURCE_DOCUMENTS_KEY, JSON.stringify(nextSources))

  const summaries = readPreviewSummaries()
  const nextSummaries = summaries.map((summary) => ({
    ...summary,
    documents: summary.documents.map((document) =>
      document.documentId === documentId
        ? { ...document, ...changes }
        : document,
    ),
  }))
  sessionStorage.setItem(PREVIEW_SUMMARIES_KEY, JSON.stringify(nextSummaries))

  return nextSources.find((document) => document.documentId === documentId) ?? null
}
