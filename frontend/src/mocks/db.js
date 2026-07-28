// MSW 목 데이터 골격. 백엔드 없이 화면을 개발/시연할 때 사용한다.
// 실제 API 계약과 응답 형태를 맞춰 두어, 백엔드 연동 시 핸들러만 제거하면 되도록 한다.
import { ROLES, ACCOUNT_STATUS, SIGNUP_STATUS } from '@/shared/constants/enums'

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

// 목 로그인 계정(비밀번호는 검증만 통과시키는 데모용).
export const credentials = {
  'employee@ajt.com': { password: 'password123!', userId: '1' },
  'admin@ajt.com': { password: 'password123!', userId: '2' },
}

export function findUserById(userId) {
  return users.find((u) => u.userId === userId) ?? null
}
