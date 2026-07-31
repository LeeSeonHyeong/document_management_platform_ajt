import apiClient from '@/api/client'

// 직원 목록 조회에 사용하는 검색 조건입니다.
// 아래 이름은 DB 칼럼명이 아니라 백엔드 GET /users API의 쿼리 파라미터 이름입니다.
export async function fetchUsers(params = {}) {
  const { data } = await apiClient.get('/users', { params })
  return data
}

// 사용자 상세/수정 화면에서 특정 직원 한 명을 GET /users/{userId}로 직접 조회합니다.
export async function fetchUser(userId) {
  const { data } = await apiClient.get(`/users/${userId}`)
  return data
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
