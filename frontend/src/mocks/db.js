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
