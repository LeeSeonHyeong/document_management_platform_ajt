import apiClient from '@/api/client'

// GET /api/v1/wiki-spaces — 접근 가능한 독립 Wiki 공간만 내려온다.
export async function fetchWikiSpaces() {
  const { data } = await apiClient.get('/wiki-spaces')
  return data.items ?? []
}

// GET /api/v1/wiki-categories?scopeKey= — AI가 관리하는 카테고리(FR-WIKI-014). 조회만 가능.
export async function fetchWikiCategories(scopeKey) {
  const { data } = await apiClient.get('/wiki-categories', { params: { scopeKey } })
  return data.items ?? []
}

// GET /api/v1/wikis
export async function fetchWikis(filters = {}) {
  const { data } = await apiClient.get('/wikis', { params: filters })
  return data
}

// GET /api/v1/wikis/:wikiId
export async function fetchWiki(wikiId) {
  const { data } = await apiClient.get(`/wikis/${wikiId}`)
  return data
}

// GET /api/v1/wikis/:wikiId/chat-messages — 관리자 전용
export async function fetchWikiChatMessages(wikiId) {
  const { data } = await apiClient.get(`/wikis/${wikiId}/chat-messages`)
  return data.items ?? []
}

// POST /api/v1/wikis/:wikiId/chat-messages — 200 { adminMessage, agentMessage, updatedWiki }
export async function sendWikiChatMessage(wikiId, content) {
  const { data } = await apiClient.post(`/wikis/${wikiId}/chat-messages`, { content })
  return data
}

// GET /api/v1/wikis/:wikiId/file — blob. Content-Disposition에서 "{제목}.md" 파일명을 읽는다.
export async function fetchWikiFile(wikiId) {
  const response = await apiClient.get(`/wikis/${wikiId}/file`, { responseType: 'blob' })
  return { blob: response.data, fileName: parseContentDispositionFileName(response.headers) }
}

// 헤더엔 보통 둘 다 실린다: `filename="_(...)_.md"` (한글이 `_`로 뭉개진 옛 클라이언트용
// 대체본)과 `filename*=UTF-8''...` (RFC 5987 퍼센트 인코딩, 진짜 파일명). 순서상
// `filename=`이 먼저 나오므로 그냥 첫 매치를 쓰면 뭉개진 쪽을 집는다 — `filename*=`를
// 먼저 찾고, 없을 때만 일반 `filename=`로 내려간다.
function parseContentDispositionFileName(headers) {
  const disposition = headers?.['content-disposition'] ?? ''
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (extended) return decodeURIComponent(extended[1])
  const plain = /filename="?([^;"]+?)"?(?:;|$)/i.exec(disposition)
  return plain ? plain[1] : null
}
