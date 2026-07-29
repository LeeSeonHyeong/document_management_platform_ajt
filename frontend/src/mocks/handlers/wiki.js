import { http, HttpResponse } from 'msw'
import {
  wikis,
  wikiCategories,
  wikiChatMessages,
  findWikiById,
  findWikiCategoryById,
  findDocumentById,
  buildWikiSpaces,
  issueWikiChatMessageId,
} from '../db'

function errorBody(status, code, message, path, fieldErrors = []) {
  return { timestamp: new Date().toISOString(), status, error: code, code, message, path, fieldErrors }
}

function toListItem(wiki) {
  const category = findWikiCategoryById(wiki.wikiCategoryId)
  return {
    wikiId: wiki.wikiId,
    title: wiki.title,
    summary: wiki.summary,
    wikiCategoryId: wiki.wikiCategoryId,
    wikiCategoryName: category?.name ?? null,
    scopeKey: wiki.scopeKey,
    updatedAt: wiki.updatedAt,
  }
}

function toDetail(wiki) {
  const category = findWikiCategoryById(wiki.wikiCategoryId)
  const evidenceDocuments = wiki.evidenceDocumentIds
    .map((documentId) => {
      const doc = findDocumentById(documentId)
      if (!doc) return null
      return { documentId: doc.documentId, originalFileName: doc.originalFileName, downloadUrl: `/api/v1/documents/${doc.documentId}/file` }
    })
    .filter(Boolean)
  const relatedWikis = wiki.relatedWikiIds
    .map((wikiId) => {
      const related = findWikiById(wikiId)
      return related ? { wikiId: related.wikiId, title: related.title } : null
    })
    .filter(Boolean)

  return {
    wikiId: wiki.wikiId,
    title: wiki.title,
    contentMarkdown: wiki.contentMarkdown,
    category: category ? { wikiCategoryId: category.wikiCategoryId, name: category.name } : null,
    scopeKey: wiki.scopeKey,
    evidenceDocuments,
    relatedWikis,
    updatedAt: wiki.updatedAt,
  }
}

export const wikiHandlers = [
  http.get('/api/v1/wiki-spaces', () => HttpResponse.json({ items: buildWikiSpaces() })),

  http.get('/api/v1/wiki-categories', ({ request }) => {
    const scopeKey = new URL(request.url).searchParams.get('scopeKey')
    if (!scopeKey) {
      return HttpResponse.json(
        errorBody(400, 'INVALID_SCOPE_KEY', 'scopeKey 형식이 올바르지 않습니다.', '/api/v1/wiki-categories'),
        { status: 400 },
      )
    }
    const items = wikiCategories.filter((c) => c.scopeKey === scopeKey)
    return HttpResponse.json({ items })
  }),

  http.get('/api/v1/wikis', ({ request }) => {
    const url = new URL(request.url)
    const scopeKey = url.searchParams.get('scopeKey')
    const wikiCategoryId = url.searchParams.get('wikiCategoryId')
    const keyword = url.searchParams.get('keyword')
    const page = Number(url.searchParams.get('page') ?? '1')
    const size = Number(url.searchParams.get('size') ?? '20')

    let filtered = wikis
    if (scopeKey) filtered = filtered.filter((w) => w.scopeKey === scopeKey)
    if (wikiCategoryId) filtered = filtered.filter((w) => w.wikiCategoryId === wikiCategoryId)
    if (keyword) {
      filtered = filtered.filter(
        (w) => w.title.includes(keyword) || w.contentMarkdown.includes(keyword),
      )
    }

    const totalCount = filtered.length
    const totalPages = Math.max(1, Math.ceil(totalCount / size))
    const items = filtered.slice((page - 1) * size, page * size).map(toListItem)

    return HttpResponse.json({ items, page, size, totalCount, totalPages })
  }),

  http.get('/api/v1/wikis/:wikiId', ({ params }) => {
    const wiki = findWikiById(params.wikiId)
    if (!wiki) {
      return HttpResponse.json(
        errorBody(404, 'WIKI_NOT_FOUND', 'Wiki가 없거나 접근할 수 없습니다.', `/api/v1/wikis/${params.wikiId}`),
        { status: 404 },
      )
    }
    return HttpResponse.json(toDetail(wiki))
  }),

  // TODO(BE): 실제 응답 확인 후 필드명 수정("작업 상태" 필드 미확정이라 우선 생략)
  http.get('/api/v1/wikis/:wikiId/chat-messages', ({ params }) => {
    const wiki = findWikiById(params.wikiId)
    if (!wiki) {
      return HttpResponse.json(
        errorBody(404, 'WIKI_NOT_FOUND', '존재하지 않는 Wiki입니다.', `/api/v1/wikis/${params.wikiId}/chat-messages`),
        { status: 404 },
      )
    }
    return HttpResponse.json({ items: wikiChatMessages[wiki.wikiId] ?? [] })
  }),

  http.post('/api/v1/wikis/:wikiId/chat-messages', async ({ request, params }) => {
    const wiki = findWikiById(params.wikiId)
    if (!wiki) {
      return HttpResponse.json(
        errorBody(404, 'WIKI_NOT_FOUND', '존재하지 않는 Wiki입니다.', `/api/v1/wikis/${params.wikiId}/chat-messages`),
        { status: 404 },
      )
    }
    const { content } = await request.json()
    if (!content) {
      return HttpResponse.json(
        errorBody(400, 'EMPTY_CHAT_CONTENT', '내용이 비어 있습니다.', `/api/v1/wikis/${params.wikiId}/chat-messages`),
        { status: 400 },
      )
    }

    const now = new Date().toISOString()
    const adminMessage = { messageId: issueWikiChatMessageId(), senderType: 'admin', content, createdAt: now }
    const agentMessage = {
      messageId: issueWikiChatMessageId(),
      senderType: 'agent',
      content: '요청하신 내용을 반영해 Wiki를 수정했습니다.',
      createdAt: now,
    }
    if (!wikiChatMessages[wiki.wikiId]) wikiChatMessages[wiki.wikiId] = []
    wikiChatMessages[wiki.wikiId].push(adminMessage, agentMessage)

    wiki.updatedAt = now
    return HttpResponse.json({ adminMessage, agentMessage, updatedWiki: toDetail(wiki) })
  }),
]
