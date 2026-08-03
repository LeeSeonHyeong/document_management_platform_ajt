// MSW 목 데이터 골격. 백엔드 없이 화면을 개발/시연할 때 사용한다.
// 실제 API 계약과 응답 형태를 맞춰 두어, 백엔드 연동 시 핸들러만 제거하면 되도록 한다.
import {
  ROLES,
  ACCOUNT_STATUS,
  SIGNUP_STATUS,
  DOCUMENT_STATUS,
  AI_JOB_STATUS,
  INQUIRY_PRIORITY,
  INQUIRY_STATUS,
} from '@/shared/constants/enums'

export const departments = [
  { departmentId: '1', name: '개발팀', manager: { userId: '3', name: '김민수' }, memberCount: 8 },
  { departmentId: '2', name: '인사팀', manager: { userId: '6', name: '최서연' }, memberCount: 3 },
  { departmentId: '3', name: '기획팀', manager: { userId: '8', name: '한소희' }, memberCount: 2 },
  { departmentId: '4', name: '디자인팀', manager: null, memberCount: 4 },
  { departmentId: '5', name: '마케팅팀', manager: null, memberCount: 3 },
  { departmentId: '6', name: '영업팀', manager: null, memberCount: 2 },
  { departmentId: '7', name: '재무팀', manager: null, memberCount: 1 },
  { departmentId: '8', name: '고객지원팀', manager: null, memberCount: 1 },
]

export const users = [
  {
    userId: '1',
    email: 'employee@ajt.com',
    name: '홍길동',
    employeeNo: 'AJT-2026-0001',
    role: ROLES.EMPLOYEE,
    department: departments[0],
    signupStatus: SIGNUP_STATUS.APPROVED,
    accountStatus: ACCOUNT_STATUS.ACTIVE,
    createdAt: '2026-07-01T02:00:00Z',
    updatedAt: '2026-07-27T09:00:00Z',
  },
  {
    userId: '8',
    email: 'sohee.han@ajt.co.kr',
    name: '한소희',
    employeeNo: '2024-006',
    role: ROLES.ADMIN,
    department: departments[2],
    signupStatus: SIGNUP_STATUS.APPROVED,
    accountStatus: ACCOUNT_STATUS.ACTIVE,
    createdAt: '2024-05-21T01:00:00Z',
    updatedAt: '2026-07-14T03:00:00Z',
  },
  {
    userId: '2',
    email: 'admin@ajt.com',
    name: '김관리',
    employeeNo: 'AJT-2026-0002',
    role: ROLES.ADMIN,
    department: departments[1],
    signupStatus: SIGNUP_STATUS.APPROVED,
    accountStatus: ACCOUNT_STATUS.ACTIVE,
    createdAt: '2026-07-01T02:00:00Z',
    updatedAt: '2026-07-27T09:00:00Z',
  },
  {
    userId: '3',
    email: 'minsu.kim@ajt.co.kr',
    name: '김민수',
    employeeNo: '2024-001',
    role: ROLES.ADMIN,
    department: departments[0],
    signupStatus: SIGNUP_STATUS.APPROVED,
    accountStatus: ACCOUNT_STATUS.ACTIVE,
    createdAt: '2024-03-11T00:30:00Z',
    updatedAt: '2026-07-20T05:05:00Z',
  },
  {
    userId: '4',
    email: 'jieun.lee@ajt.co.kr',
    name: '이지은',
    employeeNo: '2024-002',
    role: ROLES.EMPLOYEE,
    department: { departmentId: '4', name: '디자인팀' },
    signupStatus: SIGNUP_STATUS.APPROVED,
    accountStatus: ACCOUNT_STATUS.ACTIVE,
    createdAt: '2024-04-01T01:00:00Z',
    updatedAt: '2026-07-18T03:00:00Z',
  },
  {
    userId: '5',
    email: 'junho.park@ajt.co.kr',
    name: '박준호',
    employeeNo: '2024-003',
    role: ROLES.EMPLOYEE,
    department: { departmentId: '5', name: '마케팅팀' },
    signupStatus: SIGNUP_STATUS.APPROVED,
    accountStatus: ACCOUNT_STATUS.ACTIVE,
    createdAt: '2024-04-11T01:00:00Z',
    updatedAt: '2026-07-17T03:00:00Z',
  },
  {
    userId: '6',
    email: 'seoyeon.choi@ajt.co.kr',
    name: '최서연',
    employeeNo: '2024-004',
    role: ROLES.ADMIN,
    department: departments[1],
    signupStatus: SIGNUP_STATUS.APPROVED,
    accountStatus: ACCOUNT_STATUS.ACTIVE,
    createdAt: '2024-05-11T01:00:00Z',
    updatedAt: '2026-07-16T03:00:00Z',
  },
  {
    userId: '7',
    email: 'sehun.oh@ajt.co.kr',
    name: '오세훈',
    employeeNo: '2024-009',
    role: ROLES.EMPLOYEE,
    department: { departmentId: '4', name: '디자인팀' },
    signupStatus: SIGNUP_STATUS.APPROVED,
    accountStatus: ACCOUNT_STATUS.INACTIVE,
    createdAt: '2024-06-11T01:00:00Z',
    updatedAt: '2026-07-15T03:00:00Z',
  },
]

// 가입 승인 화면용 임시 데이터입니다.
// 실제 연동 시 백엔드 SignupRequestSummaryResponse의 JSON 값으로 대체됩니다.
export const signupRequests = [
  ['101', '김하늘', 'haneul.kim@ajt.co.kr', departments[0], '2024-025', '2026-07-24T09:12:00+09:00'],
  ['102', '박서준', 'seojun.park@ajt.co.kr', { departmentId: '4', name: '디자인팀' }, '2024-026', '2026-07-24T08:40:00+09:00'],
  ['103', '이도윤', 'doyoon.lee@ajt.co.kr', { departmentId: '5', name: '마케팅팀' }, '2024-027', '2026-07-23T17:05:00+09:00'],
  ['104', '정예린', 'yerin.jung@ajt.co.kr', departments[1], '2024-028', '2026-07-23T15:22:00+09:00'],
  ['105', '최민재', 'minjae.choi@ajt.co.kr', { departmentId: '6', name: '영업팀' }, '2024-029', '2026-07-23T11:48:00+09:00'],
  ['106', '윤지우', 'jiwoo.yoon@ajt.co.kr', { departmentId: '7', name: '재무팀' }, '2024-030', '2026-07-22T16:30:00+09:00'],
].map(([userId, name, email, department, employeeNo, requestedAt]) => ({
  userId,
  name,
  email,
  department,
  employeeNo,
  signupStatus: SIGNUP_STATUS.PENDING,
  requestedAt,
}))

// 문의 관리 화면용 임시 데이터입니다.
// 실제 연동 시 아래 필드가 문의 목록·상세 API의 JSON 응답으로 대체됩니다.
export const inquiries = [
  {
    inquiryId: '4',
    displayId: 'INQ-2024-004',
    title: 'VPN 접속 문제',
    author: { userId: '11', name: '이하늘', department: departments[4] },
    assignee: { assigneeId: '3', name: '김민수', department: departments[0] },
    priority: INQUIRY_PRIORITY.HIGH,
    status: INQUIRY_STATUS.PENDING,
    createdAt: '2026-07-29T09:30:00+09:00',
    content: '외부에서 사내 VPN에 접속하려고 하면 인증 단계에서 연결이 종료됩니다.\n확인 부탁드립니다.',
    attachments: [],
    answer: null,
  },
  {
    inquiryId: '3',
    displayId: 'INQ-2024-003',
    title: '사내 메일 발송 오류',
    author: { userId: '12', name: '최지훈', department: departments[5] },
    assignee: { assigneeId: '3', name: '김민수', department: departments[0] },
    priority: INQUIRY_PRIORITY.HIGH,
    status: INQUIRY_STATUS.PENDING,
    createdAt: '2026-07-23T09:12:00+09:00',
    content: '안녕하세요. 오늘 오전부터 사내 메일 발송 시 오류 메시지가 나타나며 메일이 전송되지 않습니다.\n확인 후 조치 부탁드립니다.',
    attachments: [{ attachmentId: '31', name: '메일_오류_화면.png', sizeLabel: '218KB' }],
    answer: null,
  },
  {
    inquiryId: '2',
    displayId: 'INQ-2024-002',
    title: '장비 교체 요청',
    author: { userId: '4', name: '이지은', department: departments[3] },
    assignee: { assigneeId: '3', name: '김민수', department: departments[0] },
    priority: INQUIRY_PRIORITY.LOW,
    status: INQUIRY_STATUS.PENDING,
    createdAt: '2026-07-22T14:20:00+09:00',
    content: '업무용 모니터 화면이 반복해서 꺼집니다. 장비 점검 및 교체를 요청드립니다.',
    attachments: [],
    answer: null,
  },
  {
    inquiryId: '1',
    displayId: 'INQ-2024-001',
    title: '비밀번호 초기화 요청',
    author: { userId: '13', name: '정수민', department: departments[1] },
    assignee: { assigneeId: '3', name: '김민수', department: departments[0] },
    priority: INQUIRY_PRIORITY.NORMAL,
    status: INQUIRY_STATUS.DONE,
    createdAt: '2026-07-21T10:05:00+09:00',
    content: '업무 계정 비밀번호를 분실했습니다. 초기화를 요청드립니다.',
    attachments: [],
    answer: {
      content: '본인 확인 후 임시 비밀번호를 발급했습니다. 로그인 후 비밀번호를 변경해주세요.',
      answeredAt: '2026-07-21T10:30:00+09:00',
    },
  },
  {
    inquiryId: '103',
    displayId: 'INQ-2024-003',
    title: '장비 교체 요청',
    author: { userId: '1', name: '홍길동', department: departments[0] },
    assignee: { assigneeId: '8', name: '한소희', department: departments[2] },
    priority: INQUIRY_PRIORITY.LOW,
    status: INQUIRY_STATUS.PENDING,
    createdAt: '2026-07-28T14:10:00+09:00',
    content: '업무용 모니터 화면이 반복해서 꺼집니다. 점검 후 교체 가능 여부를 알려주세요.',
    attachments: [],
    answer: null,
  },
  {
    inquiryId: '102',
    displayId: 'INQ-2024-002',
    title: '연차 신청 관련 문의',
    author: { userId: '1', name: '홍길동', department: departments[0] },
    assignee: { assigneeId: '6', name: '최서연', department: departments[1] },
    priority: INQUIRY_PRIORITY.NORMAL,
    status: INQUIRY_STATUS.PENDING,
    createdAt: '2026-07-29T07:30:00+09:00',
    content: '다음 달 연차 신청 절차와 승인 기준이 궁금합니다.',
    attachments: [],
    answer: null,
  },
  {
    inquiryId: '101',
    displayId: 'INQ-2024-001',
    title: 'VPN 접속 문제',
    author: { userId: '1', name: '홍길동', department: departments[0] },
    assignee: { assigneeId: '3', name: '김민수', department: departments[0] },
    priority: INQUIRY_PRIORITY.HIGH,
    status: INQUIRY_STATUS.DONE,
    createdAt: '2026-07-29T09:30:00+09:00',
    content: '현재 VPN에 문제가 발생한 것 같습니다. 확인 가능할까요?',
    attachments: [],
    answer: {
      content: '정상화 되었습니다.',
      answeredAt: '2026-07-29T09:45:00+09:00',
    },
  },
]

// 목 로그인 계정(비밀번호는 검증만 통과시키는 데모용).
// 김민수(userId 3)는 개발팀 부서장이라 부서관리자(최고관리자 아님) UX 확인용 계정으로 둔다.
export const credentials = {
  'employee@ajt.com': { password: 'password123!', userId: '1' },
  'admin@ajt.com': { password: 'password123!', userId: '2' },
  'minsu.kim@ajt.co.kr': { password: 'password123!', userId: '3' },
}

export function findUserById(userId) {
  return users.find((u) => u.userId === userId) ?? null
}

// 최고관리자 여부: role=admin이면서 어떤 부서의 manager도 아닌 사용자(백엔드 SuperAdminChecker와 동일 기준).
export function isSuperAdmin(user) {
  if (!user || user.role !== ROLES.ADMIN) return false
  return !departments.some((department) => department.manager?.userId === user.userId)
}

// 부서 ID 목록 → "D1-D2" 형태의 scopeKey. 중복 제거 후 오름차순 정렬한다(FR-DOC-002 정책).
export function buildScopeKey(departmentIds) {
  const ids = [...new Set(departmentIds.map(String))].sort((a, b) => Number(a) - Number(b))
  return ids.map((id) => `D${id}`).join('-')
}

export function departmentsByIds(departmentIds) {
  return departments.filter((d) => departmentIds.includes(String(d.departmentId)))
}

export const documentCategories = [
  { documentCategoryId: '1', scopeKey: 'D1-D2', name: '사내 규정', description: '개발부와 인사부 공통 규정' },
  { documentCategoryId: '2', scopeKey: 'D1-D2', name: '복리후생', description: '휴가, 경조사 등 복리후생 안내' },
  { documentCategoryId: '3', scopeKey: 'D1', name: '개발 가이드', description: '개발부 사내 개발 표준' },
]

export const documents = [
  {
    documentId: '1',
    originalFileName: '취업규칙.pdf',
    mimeType: 'application/pdf',
    fileSize: 1048576,
    documentCategoryId: '1',
    scopeKey: 'D1-D2',
    visibilityType: 'department',
    departmentIds: ['1', '2'],
    status: DOCUMENT_STATUS.COMPLETED,
    failureReason: null,
    documentWikiRefs: ['101'],
    uploaderId: '2',
    uploadedAt: '2026-07-20T09:00:00Z',
  },
  {
    documentId: '2',
    originalFileName: '연차_사용_안내.docx',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    fileSize: 204800,
    documentCategoryId: '2',
    scopeKey: 'D1-D2',
    visibilityType: 'department',
    departmentIds: ['1', '2'],
    status: DOCUMENT_STATUS.COMPLETED,
    failureReason: null,
    documentWikiRefs: ['101'],
    uploaderId: '2',
    uploadedAt: '2026-07-21T09:00:00Z',
  },
  {
    documentId: '3',
    originalFileName: '개발팀_코딩_컨벤션.md',
    mimeType: 'text/markdown',
    fileSize: 40960,
    documentCategoryId: '3',
    scopeKey: 'D1',
    visibilityType: 'department',
    departmentIds: ['1'],
    status: DOCUMENT_STATUS.PROCESSING,
    failureReason: null,
    documentWikiRefs: [],
    uploaderId: '2',
    uploadedAt: '2026-07-27T09:00:00Z',
  },
  {
    documentId: '4',
    originalFileName: '복지포인트_사용_가이드.pdf',
    mimeType: 'application/pdf',
    fileSize: 890000,
    documentCategoryId: '2',
    scopeKey: 'D1-D2',
    visibilityType: 'department',
    departmentIds: ['1', '2'],
    status: DOCUMENT_STATUS.PARSING,
    failureReason: null,
    documentWikiRefs: [],
    uploaderId: '2',
    uploadedAt: '2026-07-27T09:00:05Z',
  },
  {
    documentId: '5',
    originalFileName: '스캔본_직인규정.pdf',
    mimeType: 'application/pdf',
    fileSize: 5242880,
    documentCategoryId: '1',
    scopeKey: 'D1-D2',
    visibilityType: 'department',
    departmentIds: ['1', '2'],
    status: DOCUMENT_STATUS.FAILED,
    failureReason: 'OCR로도 텍스트를 추출하지 못했습니다.',
    documentWikiRefs: [],
    uploaderId: '2',
    uploadedAt: '2026-07-19T09:00:00Z',
  },
  {
    documentId: '6',
    originalFileName: '전사_공지사항_모음.txt',
    mimeType: 'text/plain',
    fileSize: 12000,
    documentCategoryId: '1',
    scopeKey: 'ALL',
    visibilityType: 'all',
    departmentIds: [],
    // TODO: shared/constants/enums.js 의 DOCUMENT_STATUS 에 CANCELLED 가 없음(A 확인 필요).
    // erd.sql chk_document_status 와 REST 컨벤션 문서에는 'cancelled' 가 정의돼 있어 문자열을 직접 사용한다.
    status: 'cancelled',
    failureReason: null,
    documentWikiRefs: [],
    uploaderId: '2',
    uploadedAt: '2026-07-18T09:00:00Z',
  },
]

// ai_job.status: waiting, processing, completed, failed, cancelled — 문서와 별도로 관리한다(DR-005).
export const aiJobs = [
  {
    jobId: '1',
    scopeKey: 'D1-D2',
    requesterId: '2',
    documentIds: ['1', '2'],
    status: AI_JOB_STATUS.COMPLETED,
    documentResults: [
      {
        documentId: '1',
        originalFileName: '취업규칙.pdf',
        order: 1,
        status: DOCUMENT_STATUS.COMPLETED,
        currentStage: 'wiki_applied',
        summary: '취업규칙을 근거로 휴가 규정 Wiki를 생성했습니다.',
        failureReason: null,
        failureStage: null,
      },
      {
        documentId: '2',
        originalFileName: '연차_사용_안내.docx',
        order: 2,
        status: DOCUMENT_STATUS.COMPLETED,
        currentStage: 'wiki_applied',
        summary: '연차 사용 안내를 휴가 규정 Wiki에 병합했습니다.',
        failureReason: null,
        failureStage: null,
      },
    ],
    createdAt: '2026-07-20T08:59:00Z',
    startedAt: '2026-07-20T08:59:02Z',
    finishedAt: '2026-07-20T09:01:16Z',
    failureReason: null,
  },
  // 실패한 회차. 요약 목록에서 실패 사유·실패 단계와 재처리 버튼이 뜨는 경로다.
  {
    jobId: '4',
    scopeKey: 'D1',
    requesterId: '2',
    documentIds: ['5'],
    status: AI_JOB_STATUS.COMPLETED,
    documentResults: [
      {
        documentId: '5',
        originalFileName: '스캔본_직인규정.pdf',
        order: 1,
        status: DOCUMENT_STATUS.FAILED,
        // 파싱을 끝내고 Wiki 변환에서 죽었지만 currentStage 는 parsing 으로 온다.
        // 화면이 failureStage 를 봐야 하는 이유가 이 조합이다.
        currentStage: 'parsing',
        summary: null,
        failureReason: 'Wiki 변환이 시간 안에 끝나지 않았습니다.',
        failureStage: 'agent_timeout',
      },
    ],
    createdAt: '2026-07-22T02:10:00Z',
    startedAt: '2026-07-22T02:10:03Z',
    finishedAt: '2026-07-22T02:15:44Z',
    failureReason: null,
  },
  {
    jobId: '2',
    scopeKey: 'D1',
    requesterId: '2',
    documentIds: ['3'],
    status: AI_JOB_STATUS.PROCESSING,
    documentResults: [
      {
        documentId: '3',
        originalFileName: '개발팀_코딩_컨벤션.md',
        order: 1,
        status: DOCUMENT_STATUS.PROCESSING,
        currentStage: 'wiki_transform',
        summary: null,
        failureReason: null,
        failureStage: null,
      },
    ],
    createdAt: '2026-07-27T09:00:00Z',
    startedAt: '2026-07-27T09:00:02Z',
    finishedAt: null,
    failureReason: null,
  },
  {
    jobId: '3',
    scopeKey: 'D1-D2',
    requesterId: '2',
    documentIds: ['4'],
    status: AI_JOB_STATUS.WAITING,
    documentResults: [
      {
        documentId: '4',
        originalFileName: '복지포인트_사용_가이드.pdf',
        order: 1,
        status: DOCUMENT_STATUS.PARSING,
        currentStage: 'parsing',
        summary: null,
        failureReason: null,
        failureStage: null,
      },
    ],
    createdAt: '2026-07-27T09:00:05Z',
    startedAt: null,
    finishedAt: null,
    failureReason: null,
  },
]

let nextDocumentId = documents.length + 1
let nextJobId = aiJobs.length + 1
let nextCategoryId = documentCategories.length + 1

export function issueDocumentId() {
  return String(nextDocumentId++)
}

export function issueJobId() {
  return String(nextJobId++)
}

export function issueCategoryId() {
  return String(nextCategoryId++)
}

export function findDocumentById(documentId) {
  return documents.find((d) => d.documentId === documentId) ?? null
}

export function findDocumentCategoryById(categoryId) {
  return documentCategories.find((c) => c.documentCategoryId === categoryId) ?? null
}

export function findAiJobById(jobId) {
  return aiJobs.find((j) => j.jobId === jobId) ?? null
}

// ── Wiki ─────────────────────────────────────────────────────────
// AI가 관리하는 카테고리(FR-WIKI-014). 관리자는 조회만 하므로 여기서도 수정 핸들러를 만들지 않는다.
export const wikiCategories = [
  { wikiCategoryId: '9', scopeKey: 'D1-D2', name: '휴가 및 근태', description: 'AI가 분류한 휴가·근태 규정' },
  { wikiCategoryId: '10', scopeKey: 'D1-D2', name: '복리후생', description: 'AI가 분류한 복리후생 안내' },
  { wikiCategoryId: '11', scopeKey: 'D1', name: '개발 표준', description: 'AI가 분류한 개발팀 표준 문서' },
]

export const wikis = [
  {
    wikiId: '101',
    title: '휴가 규정',
    summary: '연차와 반차 사용 기준',
    contentMarkdown:
      '# 휴가 규정\n연차와 반차를 사용할 수 있습니다.\n\n복지 포인트는 [복지 포인트 안내](pages/102.md) 문서를 참고하세요.',
    wikiCategoryId: '9',
    scopeKey: 'D1-D2',
    evidenceDocumentIds: ['1', '2'],
    relatedWikiIds: ['102'],
    updatedAt: '2026-07-21T09:10:00Z',
  },
  {
    wikiId: '102',
    title: '복지 포인트 안내',
    summary: '분기별 복지 포인트 지급 및 사용 기준',
    contentMarkdown: '# 복지 포인트 안내\n분기별로 복지 포인트가 지급됩니다.',
    wikiCategoryId: '10',
    scopeKey: 'D1-D2',
    evidenceDocumentIds: ['4'],
    relatedWikiIds: ['101'],
    updatedAt: '2026-07-22T09:00:00Z',
  },
  {
    wikiId: '103',
    title: '코딩 컨벤션',
    summary: '개발팀 코드 스타일 가이드',
    contentMarkdown: '# 코딩 컨벤션\n들여쓰기는 2칸을 사용합니다.',
    wikiCategoryId: '11',
    scopeKey: 'D1',
    evidenceDocumentIds: ['3'],
    relatedWikiIds: [],
    updatedAt: '2026-07-27T09:20:00Z',
  },
]

// wikiId → 관리자·에이전트 대화 목록. FR-AI-004: 작업 ID가 아니라 Wiki ID에 연결.
export const wikiChatMessages = {
  101: [
    {
      messageId: '1',
      senderType: 'admin',
      content: '중복된 휴가 규정을 하나로 정리해줘.',
      createdAt: '2026-07-21T09:05:00Z',
    },
    {
      messageId: '2',
      senderType: 'agent',
      content: '중복된 연차 항목을 정리해 휴가 규정 Wiki에 반영했습니다.',
      createdAt: '2026-07-21T09:05:30Z',
    },
  ],
}

let nextWikiChatMessageId = 3

export function issueWikiChatMessageId() {
  return String(nextWikiChatMessageId++)
}

export function findWikiById(wikiId) {
  return wikis.find((w) => w.wikiId === wikiId) ?? null
}

export function findWikiCategoryById(wikiCategoryId) {
  return wikiCategories.find((c) => c.wikiCategoryId === wikiCategoryId) ?? null
}

// scopeKey별로 Wiki를 묶어 접근 가능한 독립 Wiki 공간 목록을 만든다.
export function buildWikiSpaces() {
  const scopeKeys = [...new Set(wikis.map((w) => w.scopeKey))]
  return scopeKeys.map((scopeKey) => {
    const departmentIds = scopeKey.split('-').map((part) => part.replace('D', ''))
    const scopeDepartments = departmentsByIds(departmentIds)
    return {
      scopeKey,
      visibilityType: 'department',
      departments: scopeDepartments,
      displayName: scopeDepartments.map((d) => d.name).join(' + '),
      wikiCount: wikis.filter((w) => w.scopeKey === scopeKey).length,
    }
  })
}

// 일정 목 데이터(오늘 기준 2026-07 전후). 멀티데이·레인 겹침을 확인할 수 있게 구성.
export const schedules = [
  {
    scheduleId: '1',
    title: '전사 워크숍',
    content: '2분기 전사 워크숍',
    location: '대강당',
    visibilityType: 'all',
    startAt: '2026-07-28T00:00:00Z',
    endAt: '2026-07-28T08:00:00Z',
    status: 'approved',
  },
  {
    scheduleId: '2',
    title: '개발부 스프린트',
    content: '스프린트 기간',
    location: '개발실',
    visibilityType: 'department',
    departmentIds: ['1'],
    startAt: '2026-07-29T00:00:00Z',
    endAt: '2026-08-03T09:00:00Z',
    status: 'approved',
  },
  {
    scheduleId: '3',
    title: '치과 예약',
    content: null,
    location: '강남치과',
    visibilityType: 'personal',
    startAt: '2026-07-28T05:00:00Z',
    endAt: '2026-07-28T06:00:00Z',
    status: 'approved',
  },
  {
    scheduleId: '4',
    title: '팀 점심',
    content: null,
    location: '회사 앞',
    visibilityType: 'personal',
    startAt: '2026-07-30T03:00:00Z',
    endAt: '2026-07-30T04:00:00Z',
    status: 'approved',
  },
  {
    scheduleId: '5',
    title: '월말 정산 마감',
    content: '7월 정산',
    location: null,
    visibilityType: 'all',
    startAt: '2026-07-31T00:00:00Z',
    endAt: '2026-07-31T09:00:00Z',
    status: 'approved',
  },
  {
    scheduleId: '6',
    title: '인사부 정기 교육',
    content: null,
    location: '교육장',
    visibilityType: 'department',
    departmentIds: ['2'],
    startAt: '2026-07-27T01:00:00Z',
    endAt: '2026-07-27T03:00:00Z',
    status: 'approved',
  },
  // AI 추출 초안(검수 대기). 관리자 일정 관리 화면에서 승인/거부 대상.
  {
    scheduleId: '7',
    title: '8월 전사 정기 점검',
    content: '8월 시스템 정기 점검 안내',
    targetText: '전사',
    location: '전 사업장',
    visibilityType: 'all',
    departmentIds: [],
    startAt: '2026-08-05T00:00:00Z',
    endAt: '2026-08-05T02:00:00Z',
    status: 'draft',
    sourceGroupKey: 'sg-2026-08-01',
  },
  {
    scheduleId: '8',
    title: '개발부 코드 리뷰 데이',
    content: '분기 코드 리뷰',
    targetText: '개발부',
    location: '개발실',
    visibilityType: 'department',
    departmentIds: ['1'],
    startAt: '2026-08-06T05:00:00Z',
    endAt: '2026-08-06T07:00:00Z',
    status: 'draft',
    sourceGroupKey: 'sg-2026-08-01',
  },
]
