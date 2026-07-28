// MSW 목 데이터 골격. 백엔드 없이 화면을 개발/시연할 때 사용한다.
// 실제 API 계약과 응답 형태를 맞춰 두어, 백엔드 연동 시 핸들러만 제거하면 되도록 한다.
import { ROLES, ACCOUNT_STATUS, SIGNUP_STATUS } from '@/shared/constants/enums'

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
    startAt: '2026-07-27T01:00:00Z',
    endAt: '2026-07-27T03:00:00Z',
    status: 'approved',
  },
]
