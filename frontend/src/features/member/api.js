import apiClient from '@/api/client'

// 직원 목록 조회에 사용하는 검색 조건입니다.
// 아래 이름은 DB 칼럼명이 아니라 백엔드 GET /users API의 쿼리 파라미터 이름입니다.
export async function fetchUsers(params = {}) {
  const { data } = await apiClient.get('/users', { params })
  return data
}

// 현재 백엔드 계약에는 GET /users/{userId}가 없습니다.
// 임시로 목록을 넉넉히 조회한 뒤 userId가 같은 직원을 찾습니다.
// 추후 단건 조회 API가 추가되면 이 함수 내부만 교체하면 됩니다.
export async function fetchUser(userId) {
  const data = await fetchUsers({ page: 1, size: 100 })
  return data.items.find((employee) => employee.userId === String(userId)) ?? null
}

// 직원 수정 요청 본문:
// name=이름, departmentId=부서 ID, role=역할, accountStatus=계정 활성 상태
export async function updateUser(userId, changes) {
  const { data } = await apiClient.patch(`/users/${userId}`, changes)
  return data
}

export async function fetchDepartments() {
  const { data } = await apiClient.get('/departments')
  return data.items
}

// 가입 신청 목록의 각 항목은 userId, name, email, department,
// signupStatus, requestedAt을 백엔드 JSON으로 전달받습니다.
export async function fetchSignupRequests(params = {}) {
  const { data } = await apiClient.get('/signup-requests', { params })
  return data
}

export async function approveSignupRequest(userId) {
  const { data } = await apiClient.post(`/signup-requests/${userId}/approve`)
  return data
}

export async function rejectSignupRequest(userId) {
  const { data } = await apiClient.post(`/signup-requests/${userId}/reject`)
  return data
}
