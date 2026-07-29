import { http, HttpResponse } from 'msw'
import { departments, users, credentials, findUserById, schedules } from './db'

// 목 세션(데모용). HttpOnly 쿠키를 흉내 내는 대신 메모리 플래그로 로그인 상태를 유지한다.
let currentUserId = null

// 일정 목 저장소(런타임 변경 가능). 생성 ID용 시퀀스.
let scheduleSeq = 100

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

  http.get('/api/v1/departments', () => HttpResponse.json({ items: departments })),

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

  // --- 일정 ---
  http.get('/api/v1/schedules', ({ request }) => {
    const url = new URL(request.url)
    const startDate = url.searchParams.get('startDate')
    const endDate = url.searchParams.get('endDate')
    const status = url.searchParams.get('status')
    const visibilityType = url.searchParams.get('visibilityType')
    const departmentId = url.searchParams.get('departmentId')
    let items = schedules
    // 기간 필터(겹치는 일정만).
    if (startDate && endDate) {
      const from = new Date(`${startDate}T00:00:00Z`).getTime()
      const to = new Date(`${endDate}T23:59:59Z`).getTime()
      items = items.filter(
        (s) => new Date(s.endAt).getTime() >= from && new Date(s.startAt).getTime() <= to,
      )
    }
    if (status) items = items.filter((s) => s.status === status)
    if (visibilityType) items = items.filter((s) => s.visibilityType === visibilityType)
    if (departmentId) items = items.filter((s) => (s.departmentIds ?? []).includes(departmentId))
    return HttpResponse.json({ items })
  }),

  http.post('/api/v1/schedules/:id/approve', ({ params }) => {
    const target = schedules.find((s) => s.scheduleId === params.id)
    if (!target) return new HttpResponse(null, { status: 404 })
    target.status = 'approved'
    return HttpResponse.json(target)
  }),

  http.post('/api/v1/schedules', async ({ request }) => {
    const body = await request.json()
    const created = {
      scheduleId: String(++scheduleSeq),
      title: body.title,
      content: body.content ?? null,
      targetText: body.targetText ?? null,
      location: body.location ?? null,
      visibilityType: body.visibilityType ?? 'personal',
      departmentIds: body.departmentIds ?? [],
      startAt: body.startAt,
      endAt: body.endAt,
      status: 'approved',
    }
    schedules.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.patch('/api/v1/schedules/:id', async ({ params, request }) => {
    const body = await request.json()
    const target = schedules.find((s) => s.scheduleId === params.id)
    if (!target) return new HttpResponse(null, { status: 404 })
    Object.assign(target, body)
    return HttpResponse.json(target)
  }),

  http.delete('/api/v1/schedules/:id', ({ params }) => {
    const idx = schedules.findIndex((s) => s.scheduleId === params.id)
    if (idx !== -1) schedules.splice(idx, 1)
    return new HttpResponse(null, { status: 204 })
  }),
]
