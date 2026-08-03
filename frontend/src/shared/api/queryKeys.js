// react-query queryKey 중앙 관리.
// 규칙: [도메인, 하위리소스, 파라미터] 배열. 목록은 필터 객체를 마지막에 둔다.
// 무효화(invalidate) 시 접두 배열로 범위를 지정한다. 예: queryClient.invalidateQueries({ queryKey: qk.schedules.all })
export const qk = {
  me: ['me'],

  users: {
    all: ['users'],
    list: (filters) => ['users', 'list', filters ?? {}],
    detail: (userId) => ['users', 'detail', String(userId)],
  },

  signupRequests: {
    all: ['signup-requests'],
    list: (filters) => ['signup-requests', 'list', filters ?? {}],
  },

  departments: {
    all: ['departments'],
    list: ['departments', 'list'],
    signupList: ['signup-departments'],
  },

  schedules: {
    all: ['schedules'],
    list: (range) => ['schedules', 'list', range ?? {}],
    detail: (scheduleId) => ['schedules', 'detail', String(scheduleId)],
  },

  documents: {
    all: ['documents'],
    list: (filters) => ['documents', 'list', filters ?? {}],
    detail: (documentId) => ['documents', 'detail', String(documentId)],
    file: (documentId) => ['documents', 'file', String(documentId)],
  },

  documentCategories: {
    all: ['document-categories'],
    list: (scopeKey) => ['document-categories', 'list', scopeKey ?? null],
  },

  aiJobs: {
    all: ['ai-jobs'],
    list: (filters) => ['ai-jobs', 'list', filters ?? {}],
    detail: (jobId) => ['ai-jobs', 'detail', String(jobId)],
  },

  wikis: {
    all: ['wikis'],
    spaces: ['wiki-spaces'],
    categories: (scopeKey) => ['wiki-categories', scopeKey],
    list: (filters) => ['wikis', 'list', filters ?? {}],
    detail: (wikiId) => ['wikis', 'detail', String(wikiId)],
    chat: (wikiId) => ['wikis', 'detail', String(wikiId), 'chat'],
  },

  inquiries: {
    all: ['inquiries'],
    list: (filters) => ['inquiries', 'list', filters ?? {}],
    detail: (inquiryId) => ['inquiries', 'detail', String(inquiryId)],
    assignees: ['inquiry-assignees'],
  },

  questions: {
    all: ['questions'],
    history: (filters) => ['questions', 'history', filters ?? {}],
  },
}
