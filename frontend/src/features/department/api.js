import apiClient from '@/api/client'
import { SIGNUP_STATUS } from '@/shared/constants/enums'

// 부서 목록 응답의 각 항목:
// departmentId=부서 ID, name=부서명, manager=지정 관리자({ userId, name }) 또는 null
export async function fetchDepartments() {
  const { data } = await apiClient.get('/departments')
  return data.items
}

// 인원 수와 관리자 선택 후보를 만들기 위해 직원 목록을 함께 조회합니다.
// 직원의 department.departmentId를 기준으로 부서별 인원을 집계합니다.
export async function fetchDepartmentMembers() {
  const { data } = await apiClient.get('/users', {
    params: { page: 1, size: 100, signupStatus: SIGNUP_STATUS.APPROVED },
  })
  return data.items
}

// 생성 요청: name=부서명, managerId=관리자 사용자 ID 또는 null
export async function createDepartment(request) {
  const { data } = await apiClient.post('/departments', request)
  return data
}

// 수정 요청에서 managerId를 null로 보내면 기존 관리자가 해제됩니다.
export async function updateDepartment(departmentId, request) {
  const { data } = await apiClient.patch(`/departments/${departmentId}`, request)
  return data
}

export async function deleteDepartment(departmentId) {
  await apiClient.delete(`/departments/${departmentId}`)
}
