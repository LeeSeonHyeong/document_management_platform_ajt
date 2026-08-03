// 원본 문서 미리보기 렌더러 선택 기준.
// 파일명 확장자를 1차 기준으로 쓴다. 서버 mimeType 은 업로드한 브라우저·OS 에 따라
// application/octet-stream 으로 오는 경우가 있어 확장자를 못 믿을 때만 보조로 쓴다.
export const PREVIEW_KIND = Object.freeze({
  PDF: 'pdf',
  DOCX: 'docx',
  SHEET: 'sheet',
  MARKDOWN: 'markdown',
  TEXT: 'text',
  UNSUPPORTED: 'unsupported',
})

const BY_EXTENSION = Object.freeze({
  pdf: PREVIEW_KIND.PDF,
  docx: PREVIEW_KIND.DOCX,
  csv: PREVIEW_KIND.SHEET,
  xlsx: PREVIEW_KIND.SHEET,
  xls: PREVIEW_KIND.SHEET,
  md: PREVIEW_KIND.MARKDOWN,
  markdown: PREVIEW_KIND.MARKDOWN,
  txt: PREVIEW_KIND.TEXT,
})

const BY_MIME = Object.freeze({
  'application/pdf': PREVIEW_KIND.PDF,
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': PREVIEW_KIND.DOCX,
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': PREVIEW_KIND.SHEET,
  'application/vnd.ms-excel': PREVIEW_KIND.SHEET,
  'text/csv': PREVIEW_KIND.SHEET,
  'text/markdown': PREVIEW_KIND.MARKDOWN,
  'text/plain': PREVIEW_KIND.TEXT,
})

export function fileExtension(fileName) {
  if (!fileName?.includes('.')) return ''
  return fileName.split('.').pop().toLowerCase()
}

export function previewKindOf(fileName, mimeType) {
  const byExtension = BY_EXTENSION[fileExtension(fileName)]
  if (byExtension) return byExtension

  const byMime = BY_MIME[(mimeType ?? '').split(';')[0].trim().toLowerCase()]
  if (byMime) return byMime

  return PREVIEW_KIND.UNSUPPORTED
}
