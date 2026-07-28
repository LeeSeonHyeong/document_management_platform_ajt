import { http, HttpResponse } from 'msw'
import { departments, users, credentials, findUserById } from './db'
import { documentHandlers } from './handlers/document'
import { aiJobHandlers } from './handlers/aiJob'

// 목 세션(데모용). HttpOnly 쿠키를 흉내 내는 대신 메모리 플래그로 로그인 상태를 유지한다.
let currentUserId = null

function errorBody(status, code, message, path, fieldErrors = []) {
  return {
    timestamp: new Date().toISOString(),
    status,
    error: code,
    code,
    message,
    path,
    fieldErrors,
  }
}

// 공개 API 계약에 맞춘 최소 핸들러 골격. 도메인 확장 시 여기에 추가한다.
export const handlers = [
  http.get('/api/v1/auth/csrf', () =>
    HttpResponse.json({ message: 'CSRF 토큰이 발급되었습니다.' }),
  ),

  http.post('/api/v1/auth/login', async ({ request }) => {
    const { email, password } = await request.json()
    const account = credentials[email]
    if (!account || account.password !== password) {
      return HttpResponse.json(
        errorBody(401, 'INVALID_CREDENTIALS', '이메일 또는 비밀번호가 올바르지 않습니다.', '/api/v1/auth/login'),
        { status: 401 },
      )
    }
    currentUserId = account.userId
    return HttpResponse.json({ expiresIn: 3600, user: findUserById(currentUserId) })
  }),

  http.post('/api/v1/auth/logout', () => {
    currentUserId = null
    return HttpResponse.json({ message: '로그아웃되었습니다.' })
  }),

  http.get('/api/v1/me', () => {
    if (!currentUserId) {
      return HttpResponse.json(
        errorBody(401, 'INVALID_ACCESS_TOKEN', '로그인이 필요합니다.', '/api/v1/me'),
        { status: 401 },
      )
    }
    return HttpResponse.json(findUserById(currentUserId))
  }),

  http.get('/api/v1/signup-departments', () =>
    HttpResponse.json({ items: departments }),
  ),

  http.post('/api/v1/auth/signup', async ({ request }) => {
    const body = await request.json()
    const exists = users.some((u) => u.email === body.email)
    if (exists) {
      return HttpResponse.json(
        errorBody(409, 'SIGNUP_ALREADY_PENDING', '이미 가입 승인 대기 중인 이메일입니다.', '/api/v1/auth/signup'),
        { status: 409 },
      )
    }
    return HttpResponse.json(
      {
        userId: String(users.length + 10),
        email: body.email,
        name: body.name,
        role: 'employee',
        departmentId: body.departmentId,
        employeeNo: null,
        signupStatus: 'pending',
        accountStatus: 'inactive',
      },
      { status: 202 },
    )
  }),

  http.post('/api/v1/auth/password-reset-requests', () =>
    HttpResponse.json({
      message: '입력한 이메일이 등록되어 있다면 비밀번호 재설정 안내를 전송했습니다.',
    }),
  ),

  http.post('/api/v1/auth/password-resets', () => new HttpResponse(null, { status: 204 })),

  ...documentHandlers,
  ...aiJobHandlers,
]
