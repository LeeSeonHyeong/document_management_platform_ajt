// 백엔드 도메인 ENUM과 공개 API JSON 표기(소문자)를 한곳에 모은다.
// 화면·검증·표시 로직은 문자열 리터럴을 직접 쓰지 말고 이 상수를 참조한다.

// member.role — 공개 API는 소문자로 내려온다.
export const ROLES = Object.freeze({
  ADMIN: 'admin',
  EMPLOYEE: 'employee',
})
export const ALL_ROLES = Object.values(ROLES)

// member.account_status
export const ACCOUNT_STATUS = Object.freeze({
  ACTIVE: 'active',
  INACTIVE: 'inactive',
})

// 회원가입 승인 흐름 상태(백엔드 signup_status). ERD엔 없지만 API가 사용하는 정답값이다.
export const SIGNUP_STATUS = Object.freeze({
  PENDING: 'pending',
  APPROVED: 'approved',
  REJECTED: 'rejected',
})

// schedule.visibility_type
export const SCHEDULE_VISIBILITY = Object.freeze({
  ALL: 'all',
  DEPARTMENT: 'department',
  PERSONAL: 'personal',
})

// schedule.status
export const SCHEDULE_STATUS = Object.freeze({
  DRAFT: 'draft',
  APPROVED: 'approved',
})

// ai_question.question_type — 내부 API는 mixed도 반환하므로 3값으로 둔다.
export const QUESTION_TYPE = Object.freeze({
  WIKI: 'wiki',
  SCHEDULE: 'schedule',
  MIXED: 'mixed',
})

// inquiry.priority
export const INQUIRY_PRIORITY = Object.freeze({
  HIGH: 'high',
  NORMAL: 'normal',
  LOW: 'low',
})

// inquiry.status
export const INQUIRY_STATUS = Object.freeze({
  PENDING: 'pending',
  DONE: 'done',
})

// document.status — 관리자 문서 처리 상태
export const DOCUMENT_STATUS = Object.freeze({
  UPLOADED: 'uploaded',
  PARSING: 'parsing',
  PROCESSING: 'processing',
  COMPLETED: 'completed',
  FAILED: 'failed',
})

// ai_job.status — AI 작업 상태
export const AI_JOB_STATUS = Object.freeze({
  WAITING: 'waiting',
  PROCESSING: 'processing',
  COMPLETED: 'completed',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
})

// wiki_scope.visibility_type
export const WIKI_VISIBILITY = Object.freeze({
  ALL: 'all',
  DEPARTMENT: 'department',
})

// 파일 업로드 제약(요구사항 NFR-FILE-001). 확장자는 소문자, 크기는 바이트.
export const MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024 // 파일당 20MB

export const FILE_ACCEPT = Object.freeze({
  // 위키 원본 문서
  WIKI_SOURCE: Object.freeze(['txt', 'md', 'pdf', 'docx']),
  // 일정 원본 문서 · 일정 첨부
  SCHEDULE: Object.freeze(['txt', 'md', 'docx', 'pdf', 'csv', 'xlsx']),
  // 문의 첨부(이미지, 최대 5개)
  INQUIRY_IMAGE: Object.freeze(['png', 'jpg', 'jpeg']),
})

// 확장자별 허용 MIME — 백엔드 DocumentUploadRequest·ScheduleSourceService의
// ALLOWED_MIME_TYPES와 같은 값이다. 확장자만 바꿔치기한 파일을 걸러내는 데 쓴다.
// 브라우저가 type을 못 채우는 경우가 있어(특히 .md), 값이 비어 있으면 검사하지 않는다.
export const FILE_MIME_TYPES = Object.freeze({
  txt: Object.freeze(['text/plain']),
  md: Object.freeze(['text/markdown', 'text/plain']),
  pdf: Object.freeze(['application/pdf']),
  docx: Object.freeze([
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  ]),
  csv: Object.freeze(['text/csv', 'text/plain', 'application/vnd.ms-excel']),
  xlsx: Object.freeze(['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet']),
})

// 백엔드 DocumentUploadRequest의 MAX_FILE_COUNT·MAX_TOTAL_SIZE와 같은 값.
export const MAX_UPLOAD_FILE_COUNT = 20
export const MAX_UPLOAD_TOTAL_SIZE_BYTES = 100 * 1024 * 1024

export const INQUIRY_ATTACHMENT_MAX_COUNT = 5

// 한글 라벨 — 화면에서 badge·select 표기에 사용한다.
export const ROLE_LABELS = Object.freeze({
  [ROLES.ADMIN]: '관리자',
  [ROLES.EMPLOYEE]: '사원',
})

export const ACCOUNT_STATUS_LABELS = Object.freeze({
  [ACCOUNT_STATUS.ACTIVE]: '활성',
  [ACCOUNT_STATUS.INACTIVE]: '비활성',
})

export const SIGNUP_STATUS_LABELS = Object.freeze({
  [SIGNUP_STATUS.PENDING]: '승인 대기',
  [SIGNUP_STATUS.APPROVED]: '승인 완료',
  [SIGNUP_STATUS.REJECTED]: '거부',
})

export const SCHEDULE_VISIBILITY_LABELS = Object.freeze({
  [SCHEDULE_VISIBILITY.ALL]: '전체',
  [SCHEDULE_VISIBILITY.DEPARTMENT]: '부서',
  [SCHEDULE_VISIBILITY.PERSONAL]: '개인',
})

export const INQUIRY_PRIORITY_LABELS = Object.freeze({
  [INQUIRY_PRIORITY.HIGH]: '높음',
  [INQUIRY_PRIORITY.NORMAL]: '보통',
  [INQUIRY_PRIORITY.LOW]: '낮음',
})

export const INQUIRY_STATUS_LABELS = Object.freeze({
  [INQUIRY_STATUS.PENDING]: '대기중',
  [INQUIRY_STATUS.DONE]: '답변완료',
})
