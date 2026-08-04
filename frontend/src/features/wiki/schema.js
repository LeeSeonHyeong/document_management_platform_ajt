// 이 파일은 런타임 검증기가 아니라 docs/api/AJT-Backend-Public-API.postman_collection.json 기준
// 응답 형태를 코드 옆에 남겨두는 JSDoc 문서다. 실제 응답과 달라지면 이 파일을 먼저 고친다.

/**
 * 확정 — GET /wiki-spaces 200
 * @typedef {object} WikiSpace
 * @property {string} scopeKey
 * @property {'all'|'department'} visibilityType
 * @property {{departmentId: string, name: string}[]} departments
 * @property {string} displayName
 * @property {number} wikiCount
 */

/**
 * 확정 — GET /wiki-categories 200. AI가 관리하며 관리자는 조회만 한다(FR-WIKI-014).
 * @typedef {object} WikiCategory
 * @property {string} wikiCategoryId
 * @property {string} scopeKey
 * @property {string} name
 * @property {string} description
 */

/**
 * 확정 — GET /wikis 200 items
 * @typedef {object} WikiListItem
 * @property {string} wikiId
 * @property {string} title
 * @property {string} summary
 * @property {string} wikiCategoryId
 * @property {string} wikiCategoryName
 * @property {string} scopeKey
 * @property {string} updatedAt
 */

/**
 * 확정 — GET /wikis/:wikiId 200
 * @typedef {object} WikiDetail
 * @property {string} wikiId
 * @property {string} title
 * @property {string} contentMarkdown
 * @property {{wikiCategoryId: string, name: string}} category
 * @property {string} scopeKey
 * @property {{documentId: string, originalFileName: string, downloadUrl: string}[]} evidenceDocuments
 * @property {{wikiId: string, title: string}[]} relatedWikis
 * @property {string} updatedAt
 */

/**
 * 잠정 — GET /wikis/:wikiId/chat-messages 200. Postman에 예시 본문이 없다.
 * // TODO(BE): 실제 응답 확인 후 필드명 수정. "작업 상태" 필드명 미확정.
 * @typedef {object} WikiChatMessage
 * @property {string} messageId
 * @property {'admin'|'agent'} senderType
 * @property {string} content
 * @property {string} createdAt
 * @property {string|null} wikiId - 이 메시지가 관계된 Wiki. 관리자 메시지는 보낸 시점에 보던 Wiki,
 *   에이전트 메시지는 그 지시로 실제 변경된 Wiki. 하드 삭제되었으면 null.
 * @property {string|null} wikiTitle
 */

/**
 * 잠정 — POST /wikis/:wikiId/chat-messages 200. 필드명은 설명에만 있고 예시 본문은 없다.
 * // TODO(BE): 실제 응답 확인 후 필드명 수정
 * @typedef {object} WikiChatReply
 * @property {WikiChatMessage} adminMessage
 * @property {WikiChatMessage} agentMessage
 * @property {WikiDetail} updatedWiki
 */
