import { http, HttpResponse } from 'msw'
import {
  departments,
  users,
  signupRequests,
  inquiries,
  credentials,
  findUserById,
  schedules,
} from './db'

// 목 세션(데모용). HttpOnly 쿠키를 흉내 내는 대신 메모리 플래그로 로그인 상태를 유지한다.
let currentUserId = null
const inquiryAttachmentFiles = new Map()
const questionHistory = []

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

  http.patch('/api/v1/me/password', async ({ request }) => {
    const body = await request.json()
    const credentialEntry = Object.entries(credentials).find(([, account]) => account.userId === currentUserId)
    if (!credentialEntry || credentialEntry[1].password !== body.currentPassword) {
      return HttpResponse.json(
        errorBody(401, 'INVALID_CURRENT_PASSWORD', '현재 비밀번호가 올바르지 않습니다.', '/api/v1/me/password'),
        { status: 401 },
      )
    }
    credentialEntry[1].password = body.newPassword
    const user = findUserById(currentUserId)
    if (user) user.updatedAt = new Date().toISOString()
    return HttpResponse.json({ message: '비밀번호가 변경되었습니다.' })
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

  http.get('/api/v1/users', ({ request }) => {
    const url = new URL(request.url)
    const keyword = (url.searchParams.get('keyword') ?? '').toLowerCase()
    const role = url.searchParams.get('role')
    const status = url.searchParams.get('status')
    const items = users.filter((employee) => {
      const matchesKeyword =
        !keyword ||
        employee.name.toLowerCase().includes(keyword) ||
        employee.department?.name.toLowerCase().includes(keyword)
      return matchesKeyword && (!role || employee.role === role) && (!status || employee.accountStatus === status)
    })
    return HttpResponse.json({ items, page: 1, size: 100, totalCount: items.length, totalPages: 1 })
  }),

  http.patch('/api/v1/users/:userId', async ({ params, request }) => {
    const employee = findUserById(params.userId)
    if (!employee) {
      return HttpResponse.json(
        errorBody(404, 'USER_NOT_FOUND', '직원을 찾을 수 없습니다.', `/api/v1/users/${params.userId}`),
        { status: 404 },
      )
    }
    const changes = await request.json()
    Object.assign(employee, changes, {
      department: departments.find((item) => item.departmentId === changes.departmentId) ?? employee.department,
      updatedAt: new Date().toISOString(),
    })
    delete employee.departmentId
    return HttpResponse.json(employee)
  }),

  http.get('/api/v1/departments', () =>
    HttpResponse.json({ items: departments, page: 1, size: departments.length, totalCount: departments.length, totalPages: 1 }),
  ),

  http.post('/api/v1/departments', async ({ request }) => {
    const body = await request.json()
    if (departments.some((department) => department.name === body.name)) {
      return HttpResponse.json(
        errorBody(409, 'DUPLICATE_DEPARTMENT_NAME', '이미 사용 중인 부서명입니다.', '/api/v1/departments'),
        { status: 409 },
      )
    }
    const manager = body.managerId
      ? users.find((user) => user.userId === body.managerId)
      : null
    const department = {
      departmentId: String(Math.max(...departments.map((item) => Number(item.departmentId)), 0) + 1),
      name: body.name,
      manager: manager ? { userId: manager.userId, name: manager.name } : null,
      memberCount: 0,
    }
    departments.push(department)
    return HttpResponse.json(department, { status: 201 })
  }),

  http.patch('/api/v1/departments/:departmentId', async ({ params, request }) => {
    const department = departments.find((item) => item.departmentId === params.departmentId)
    if (!department) {
      return HttpResponse.json(
        errorBody(404, 'DEPARTMENT_NOT_FOUND', '부서를 찾을 수 없습니다.', `/api/v1/departments/${params.departmentId}`),
        { status: 404 },
      )
    }
    const body = await request.json()
    if (body.name !== undefined) department.name = body.name
    if (Object.prototype.hasOwnProperty.call(body, 'managerId')) {
      const manager = body.managerId
        ? users.find((user) => user.userId === body.managerId)
        : null
      department.manager = manager ? { userId: manager.userId, name: manager.name } : null
    }
    return HttpResponse.json(department)
  }),

  http.delete('/api/v1/departments/:departmentId', ({ params }) => {
    const index = departments.findIndex((item) => item.departmentId === params.departmentId)
    if (index < 0) {
      return HttpResponse.json(
        errorBody(404, 'DEPARTMENT_NOT_FOUND', '부서를 찾을 수 없습니다.', `/api/v1/departments/${params.departmentId}`),
        { status: 404 },
      )
    }
    if ((departments[index].memberCount ?? 0) > 0) {
      return HttpResponse.json(
        errorBody(409, 'DEPARTMENT_HAS_MEMBERS', '소속 직원이 있는 부서는 삭제할 수 없습니다.', `/api/v1/departments/${params.departmentId}`),
        { status: 409 },
      )
    }
    departments.splice(index, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/signup-requests', ({ request }) => {
    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const page = Math.max(1, Number(url.searchParams.get('page')) || 1)
    const size = Math.max(1, Number(url.searchParams.get('size')) || 100)
    const items = signupRequests.filter((item) => !status || item.signupStatus === status)
    const start = (page - 1) * size
    return HttpResponse.json({
      items: items.slice(start, start + size),
      page,
      size,
      totalCount: items.length,
      totalPages: Math.max(1, Math.ceil(items.length / size)),
    })
  }),

  http.post('/api/v1/signup-requests/:userId/approve', ({ params }) => {
    const signup = signupRequests.find((item) => item.userId === params.userId)
    if (!signup) return HttpResponse.json(errorBody(404, 'USER_NOT_FOUND', '가입 요청을 찾을 수 없습니다.', `/api/v1/signup-requests/${params.userId}/approve`), { status: 404 })
    const approvedAt = new Date().toISOString()
    signup.signupStatus = 'approved'
    signup.employeeNo = `2026-${params.userId}`

    // 실제 환경에서는 백엔드가 승인 트랜잭션에서 회원을 활성 직원으로 변경합니다.
    // 목 환경에서도 같은 결과를 확인할 수 있도록 직원 목록 데이터에 승인자를 반영합니다.
    if (!users.some((user) => user.userId === signup.userId)) {
      users.push({
        userId: signup.userId,
        email: signup.email,
        name: signup.name,
        employeeNo: signup.employeeNo,
        role: 'employee',
        department: signup.department,
        signupStatus: 'approved',
        accountStatus: 'active',
        createdAt: signup.requestedAt,
        updatedAt: approvedAt,
      })
    }

    return HttpResponse.json({
      userId: signup.userId,
      employeeNo: signup.employeeNo,
      signupStatus: 'approved',
      accountStatus: 'active',
      approvedAt,
    })
  }),

  http.post('/api/v1/signup-requests/:userId/reject', ({ params }) => {
    const signup = signupRequests.find((item) => item.userId === params.userId)
    if (!signup) return HttpResponse.json(errorBody(404, 'USER_NOT_FOUND', '가입 요청을 찾을 수 없습니다.', `/api/v1/signup-requests/${params.userId}/reject`), { status: 404 })
    signup.signupStatus = 'rejected'
    return HttpResponse.json({ userId: signup.userId, signupStatus: 'rejected', accountStatus: 'inactive', rejectedAt: new Date().toISOString() })
  }),

  http.get('/api/v1/inquiries', ({ request }) => {
    const url = new URL(request.url)
    const keyword = (url.searchParams.get('keyword') ?? '').toLowerCase()
    const status = url.searchParams.get('status')
    const priority = url.searchParams.get('priority')
    const page = Math.max(1, Number(url.searchParams.get('page')) || 1)
    const size = Math.max(1, Number(url.searchParams.get('size')) || 20)
    const [sortField, sortDirection = 'desc'] = (url.searchParams.get('sort') ?? 'createdAt,desc').split(',')
    const priorityOrder = { high: 3, normal: 2, low: 1 }

    const currentUser = findUserById(currentUserId)
    const visibleInquiries = currentUser?.role === 'employee'
      ? inquiries.filter((inquiry) => inquiry.author.userId === currentUserId)
      : inquiries.filter((inquiry) => inquiry.author.userId !== '1')
    const items = visibleInquiries
      .filter((inquiry) => {
        const searchable = `${inquiry.title} ${inquiry.author.name}`.toLowerCase()
        return (!keyword || searchable.includes(keyword))
          && (!status || inquiry.status === status)
          && (!priority || inquiry.priority === priority)
      })
      .sort((a, b) => {
        const left = sortField === 'priority' ? priorityOrder[a.priority] : new Date(a.createdAt).getTime()
        const right = sortField === 'priority' ? priorityOrder[b.priority] : new Date(b.createdAt).getTime()
        return sortDirection === 'asc' ? left - right : right - left
      })

    const start = (page - 1) * size
    return HttpResponse.json({
      items: items.slice(start, start + size),
      page,
      size,
      totalCount: items.length,
      totalPages: Math.max(1, Math.ceil(items.length / size)),
    })
  }),

  http.get('/api/v1/inquiry-assignees', ({ request }) => {
    const keyword = (new URL(request.url).searchParams.get('keyword') ?? '').toLowerCase()
    const items = users
      .filter((user) =>
        user.role === 'admin'
        && user.signupStatus === 'approved'
        && user.accountStatus === 'active'
        && (!keyword || user.name.toLowerCase().includes(keyword)),
      )
      .map((user) => ({
        assigneeId: user.userId,
        name: user.name,
        department: user.department,
      }))
    return HttpResponse.json({ items })
  }),

  http.post('/api/v1/inquiries', async ({ request }) => {
    const author = findUserById(currentUserId)
    if (!author) {
      return HttpResponse.json(
        errorBody(401, 'INVALID_ACCESS_TOKEN', '로그인이 필요합니다.', '/api/v1/inquiries'),
        { status: 401 },
      )
    }
    const formData = await request.formData()
    const assigneeId = String(formData.get('assigneeId') ?? '')
    const assignee = users.find((user) =>
      user.userId === assigneeId
      && user.role === 'admin'
      && user.signupStatus === 'approved'
      && user.accountStatus === 'active',
    )
    if (!assignee) {
      return HttpResponse.json(
        errorBody(400, 'INVALID_INQUIRY_ASSIGNEE', '선택할 수 없는 담당자입니다.', '/api/v1/inquiries'),
        { status: 400 },
      )
    }
    const nextId = String(Math.max(...inquiries.map((item) => Number(item.inquiryId)), 0) + 1)
    const attachmentFiles = formData.getAll('attachments')
    const attachments = attachmentFiles.map((file, index) => {
      const attachmentId = `${nextId}-${index + 1}`
      inquiryAttachmentFiles.set(attachmentId, file)
      return {
        attachmentId,
        name: file.name,
        sizeLabel: `${Math.max(1, Math.ceil(file.size / 1024))}KB`,
        contentType: file.type,
        downloadUrl: `/api/v1/inquiries/${nextId}/attachments/${attachmentId}`,
      }
    })
    const inquiry = {
      inquiryId: nextId,
      displayId: `INQ-2024-${String(nextId).padStart(3, '0')}`,
      title: String(formData.get('title') ?? ''),
      content: String(formData.get('content') ?? ''),
      priority: String(formData.get('priority') ?? 'normal'),
      status: 'pending',
      author: {
        userId: author.userId,
        name: author.name,
        department: author.department,
      },
      assignee: {
        assigneeId: assignee.userId,
        name: assignee.name,
        department: assignee.department,
      },
      attachments,
      answer: null,
      createdAt: new Date().toISOString(),
    }
    inquiries.unshift(inquiry)
    return HttpResponse.json(inquiry, { status: 201 })
  }),

  http.get('/api/v1/inquiries/:inquiryId', ({ params }) => {
    const inquiry = inquiries.find((item) => item.inquiryId === params.inquiryId)
    if (!inquiry) {
      return HttpResponse.json(
        errorBody(404, 'INQUIRY_NOT_FOUND', '문의를 찾을 수 없습니다.', `/api/v1/inquiries/${params.inquiryId}`),
        { status: 404 },
      )
    }
    return HttpResponse.json(inquiry)
  }),

  http.get('/api/v1/inquiries/:inquiryId/attachments/:attachmentId', ({ params }) => {
    const inquiry = inquiries.find((item) => item.inquiryId === params.inquiryId)
    const attachment = inquiry?.attachments?.find((item) => item.attachmentId === params.attachmentId)
    const file = inquiryAttachmentFiles.get(params.attachmentId)
    if (!inquiry || !attachment || !file) {
      return HttpResponse.json(
        errorBody(
          404,
          'INQUIRY_ATTACHMENT_NOT_FOUND',
          '첨부 이미지를 찾을 수 없습니다.',
          `/api/v1/inquiries/${params.inquiryId}/attachments/${params.attachmentId}`,
        ),
        { status: 404 },
      )
    }
    return new HttpResponse(file, {
      headers: {
        'Content-Type': file.type || 'application/octet-stream',
        'Content-Disposition': `inline; filename="${encodeURIComponent(file.name)}"`,
      },
    })
  }),

  http.put('/api/v1/inquiries/:inquiryId/answer', async ({ params, request }) => {
    const inquiry = inquiries.find((item) => item.inquiryId === params.inquiryId)
    if (!inquiry) {
      return HttpResponse.json(
        errorBody(404, 'INQUIRY_NOT_FOUND', '문의를 찾을 수 없습니다.', `/api/v1/inquiries/${params.inquiryId}/answer`),
        { status: 404 },
      )
    }
    const { content } = await request.json()
    inquiry.answer = { content, answeredAt: new Date().toISOString() }
    inquiry.status = 'done'
    return HttpResponse.json(inquiry.answer)
  }),

  http.post('/api/v1/questions', async ({ request }) => {
    if (!currentUserId) {
      return HttpResponse.json(
        errorBody(401, 'INVALID_ACCESS_TOKEN', '로그인이 필요합니다.', '/api/v1/questions'),
        { status: 401 },
      )
    }
    const body = await request.json()
    const question = String(body.question ?? '').trim()
    if (!question) {
      return HttpResponse.json(
        errorBody(400, 'INVALID_QUESTION', '질문을 입력해주세요.', '/api/v1/questions'),
        { status: 400 },
      )
    }

    const conversationId = body.conversationId || `chat-${Date.now()}`
    const questionId = String(Date.now())
    const mentionsSchedule = /일정|연차|휴가|회의|행사/.test(question)
    const mentionsWiki = /규정|위키|문서|절차|정책/.test(question)
    const questionType = mentionsSchedule && mentionsWiki ? 'mixed' : mentionsSchedule ? 'schedule' : 'wiki'
    const wikiSource = {
      type: 'wiki',
      wikiId: '101',
      title: '근무 규정 및 복리후생 안내',
      evidenceDocuments: [{
        documentId: '15',
        originalFileName: '취업규칙.pdf',
        downloadUrl: '/api/v1/documents/15/file',
      }],
    }
    const scheduleSource = {
      type: 'schedule',
      scheduleId: '31',
      title: '사내 주요 일정',
    }
    const answers = {
      mixed: '관련 사내 규정과 등록된 일정을 함께 확인했습니다. 연차는 근태관리 시스템에서 신청하며, 일정에 표시된 승인 기한 전에 결재를 완료해주세요.',
      schedule: '등록된 일정 기준으로 확인했습니다. 상세한 날짜와 시간은 홈 캘린더의 해당 일정을 선택해 확인할 수 있습니다.',
      wiki: '사내 위키 기준으로 확인했습니다. 관련 규정과 절차는 아래 출처에서 자세히 확인할 수 있습니다.',
    }
    const sources = questionType === 'mixed'
      ? [wikiSource, scheduleSource]
      : questionType === 'schedule'
        ? [scheduleSource]
        : [wikiSource]
    const result = {
      conversationId,
      questionId,
      questionType,
      question,
      answer: answers[questionType],
      sources,
      createdAt: new Date().toISOString(),
    }
    questionHistory.unshift({ ...result, userId: currentUserId })
    return HttpResponse.json(result)
  }),

  http.get('/api/v1/questions', ({ request }) => {
    const url = new URL(request.url)
    const page = Math.max(1, Number(url.searchParams.get('page')) || 1)
    const size = Math.max(1, Number(url.searchParams.get('size')) || 20)
    const conversationId = url.searchParams.get('conversationId')
    const questionType = url.searchParams.get('questionType')
    const items = questionHistory.filter((item) =>
      item.userId === currentUserId
      && (!conversationId || item.conversationId === conversationId)
      && (!questionType || item.questionType === questionType),
    )
    const start = (page - 1) * size
    return HttpResponse.json({
      items: items.slice(start, start + size).map(({ userId: _userId, ...item }) => item),
      page,
      size,
      totalCount: items.length,
      totalPages: Math.max(1, Math.ceil(items.length / size)),
    })
  }),

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
