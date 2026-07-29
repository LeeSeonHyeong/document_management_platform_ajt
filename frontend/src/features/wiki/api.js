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
