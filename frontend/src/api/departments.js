import apiClient from './client'

// GET /api/v1/departments — 부서 목록(관리자). 일정 공개 범위 선택 등에 사용.
export async function fetchDepartments() {
  const { data } = await apiClient.get('/departments')
  return data.items ?? []
}
