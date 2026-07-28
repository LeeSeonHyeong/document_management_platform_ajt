import apiClient, { ensureCsrfToken } from './client'

// POST /api/v1/auth/login
// 성공 시 accessToken은 HttpOnly 쿠키로 내려오고, 본문은 { expiresIn, user }만 담는다.
export async function login({ email, password }) {
  const { data } = await apiClient.post('/auth/login', { email, password })
  // 로그인 직후 이후 변경 요청을 대비해 CSRF 토큰을 미리 확보한다.
  await ensureCsrfToken()
  return data
}

// POST /api/v1/auth/logout — 인증 쿠키 만료(CSRF 헤더 필요, 인터셉터가 자동 첨부)
export async function logout() {
  await apiClient.post('/auth/logout')
}

// GET /api/v1/me — 현재 로그인 사용자. 부팅 시 상태 복원에 사용.
// 비로그인(401)을 정상 흐름으로 처리하려면 skipAuthRedirect를 켜서 전역 이벤트를 막는다.
export async function fetchMe({ silent = false } = {}) {
  const { data } = await apiClient.get('/me', { skipAuthRedirect: silent })
  return data
}

// POST /api/v1/auth/signup — 202, pending 상태로 가입 신청
export async function signup({ name, email, departmentId, password }) {
  const { data } = await apiClient.post('/auth/signup', {
    name,
    email,
    departmentId,
    password,
  })
  return data
}

// GET /api/v1/signup-departments — 비로그인 회원가입용 부서 목록
export async function fetchSignupDepartments() {
  const { data } = await apiClient.get('/signup-departments')
  return data.items ?? []
}

// POST /api/v1/auth/password-reset-requests — 재설정 메일 요청(항상 동일 응답)
export async function requestPasswordReset({ email }) {
  const { data } = await apiClient.post('/auth/password-reset-requests', { email })
  return data
}

// POST /api/v1/auth/password-resets — 토큰으로 새 비밀번호 설정(204)
export async function confirmPasswordReset({ token, newPassword }) {
  await apiClient.post('/auth/password-resets', { token, newPassword })
}
