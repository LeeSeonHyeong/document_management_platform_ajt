import apiClient from './client'

// POST /api/v1/auth/login
// 응답: { accessToken, tokenType, expiresIn, user: { userId, email, name, role, department, ... } }
export async function login({ email, password }) {
  const { data } = await apiClient.post('/auth/login', { email, password })
  return data
}

// GET /api/v1/me — 토큰으로 현재 사용자 정보 재조회(새로고침 시 상태 복원 검증용)
export async function fetchMe() {
  const { data } = await apiClient.get('/me')
  return data
}
