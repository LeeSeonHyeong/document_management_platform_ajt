// MSW 목 데이터 골격. 백엔드 없이 화면을 개발/시연할 때 사용한다.
// 실제 API 계약과 응답 형태를 맞춰 두어, 백엔드 연동 시 핸들러만 제거하면 되도록 한다.
import {
  ROLES,
  ACCOUNT_STATUS,
  SIGNUP_STATUS,
  DOCUMENT_STATUS,
  AI_JOB_STATUS,
} from '@/shared/constants/enums'

export const departments = [
  { departmentId: '1', name: '개발부' },
  { departmentId: '2', name: '인사부' },
  { departmentId: '3', name: '기획부' },
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
]

// 목 로그인 계정(비밀번호는 검증만 통과시키는 데모용).
export const credentials = {
  'employee@ajt.com': { password: 'password123!', userId: '1' },
  'admin@ajt.com': { password: 'password123!', userId: '2' },
}

export function findUserById(userId) {
  return users.find((u) => u.userId === userId) ?? null
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
        order: 1,
        status: DOCUMENT_STATUS.COMPLETED,
        currentStage: 'wiki_applied',
        summary: '취업규칙을 근거로 휴가 규정 Wiki를 생성했습니다.',
        failureReason: null,
      },
      {
        documentId: '2',
        order: 2,
        status: DOCUMENT_STATUS.COMPLETED,
        currentStage: 'wiki_applied',
        summary: '연차 사용 안내를 휴가 규정 Wiki에 병합했습니다.',
        failureReason: null,
      },
    ],
    createdAt: '2026-07-20T08:59:00Z',
    startedAt: '2026-07-20T08:59:02Z',
    finishedAt: '2026-07-21T09:05:00Z',
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
        order: 1,
        status: DOCUMENT_STATUS.PROCESSING,
        currentStage: 'wiki_transform',
        summary: null,
        failureReason: null,
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
        order: 1,
        status: DOCUMENT_STATUS.PARSING,
        currentStage: 'parsing',
        summary: null,
        failureReason: null,
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
