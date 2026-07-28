import apiClient from '@/api/client'

// GET /api/v1/departments — 로그인 사용자용 부서 목록.
// (회원가입 화면의 공개용 /signup-departments 와 구분한다.)
export async function fetchDepartments() {
  const { data } = await apiClient.get('/departments')
  return data.items ?? []
}
