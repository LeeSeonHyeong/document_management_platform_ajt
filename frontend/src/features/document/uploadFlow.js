export const WIKI_UPLOAD_LIMIT = {
  extensions: ['txt', 'md', 'pdf', 'docx'],
  maxFileBytes: 20 * 1024 * 1024,
  maxCount: 20,
  maxTotalBytes: 100 * 1024 * 1024,
}

export function validateWikiFile(file) {
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!extension || !WIKI_UPLOAD_LIMIT.extensions.includes(extension)) {
    return `지원하지 않는 형식입니다 (${WIKI_UPLOAD_LIMIT.extensions.join(', ')}만 허용)`
  }
  if (file.size > WIKI_UPLOAD_LIMIT.maxFileBytes) {
    return '파일당 최대 20.0MB까지 가능합니다'
  }
  return null
}

export function validateWikiUpload(files) {
  if (files.length === 0) return '파일을 1개 이상 선택하세요'
  if (files.length > WIKI_UPLOAD_LIMIT.maxCount) {
    return `한 번에 최대 ${WIKI_UPLOAD_LIMIT.maxCount}건까지 업로드할 수 있습니다`
  }
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0)
  if (totalBytes > WIKI_UPLOAD_LIMIT.maxTotalBytes) {
    return '전체 용량은 최대 100.0MB까지 가능합니다'
  }
  return null
}

export function normalizeWikiFileSelection(fileList) {
  return Array.from(fileList ?? [])
}

export function createWikiUploadEntries(fileList, startSequence = 0) {
  const files = normalizeWikiFileSelection(fileList)
  return {
    entries: files.map((file, index) => ({
      id: startSequence + index + 1,
      file,
      error: validateWikiFile(file),
    })),
    nextSequence: startSequence + files.length,
  }
}

export function buildWikiUploadPayload(files, metadata, onUploadProgress) {
  return { files, ...metadata, onUploadProgress }
}

export function wikiUploadProgressPath(result) {
  if (!result?.jobId) throw new Error('업로드 응답에 jobId가 없습니다.')
  return `/admin/documents/jobs/${result.jobId}/progress`
}
