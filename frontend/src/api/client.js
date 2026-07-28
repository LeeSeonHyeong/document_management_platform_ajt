import axios from 'axios'
import { readCookie } from '@/shared/lib/cookies'

// 401 발생 시 AuthProvider가 구독해 로그아웃/리다이렉트를 처리하도록 알리는 이벤트 이름.
export const UNAUTHORIZED_EVENT = 'ajt:unauthorized'

// 백엔드 인증은 HttpOnly 쿠키(AJT_ACCESS_TOKEN) 기반이다.
// 프론트는 토큰을 직접 저장하지 않고, withCredentials로 브라우저가 쿠키를 자동 전송하게 한다.
const baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const UNSAFE_METHODS = new Set(['post', 'put', 'patch', 'delete'])

// CSRF 검사를 서버가 건너뛰는 공개 인증 경로(비로그인 상태 요청). 여기엔 토큰을 붙이지 않는다.
const CSRF_EXEMPT_PATHS = new Set([
  '/auth/login',
  '/auth/signup',
  '/auth/password-reset-requests',
  '/auth/password-resets',
])

const apiClient = axios.create({
  baseURL,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})

// CSRF 발급 전용 요청. apiClient 인터셉터 재귀를 피하려고 별도 인스턴스를 쓴다.
const csrfClient = axios.create({ baseURL, withCredentials: true })

// XSRF-TOKEN 쿠키가 없으면 서버에서 발급받는다. 로그인 후·부팅 시 최초 변경 요청 직전에 호출된다.
export async function ensureCsrfToken() {
  if (readCookie(CSRF_COOKIE)) return
  await csrfClient.get('/auth/csrf')
}

// 요청 인터셉터: 상태 변경(POST/PUT/PATCH/DELETE) 요청에 CSRF 토큰 헤더를 붙인다.
apiClient.interceptors.request.use(async (config) => {
  const method = (config.method ?? 'get').toLowerCase()
  const url = config.url ?? ''
  if (UNSAFE_METHODS.has(method) && !CSRF_EXEMPT_PATHS.has(url)) {
    await ensureCsrfToken()
    const token = readCookie(CSRF_COOKIE)
    if (token) config.headers[CSRF_HEADER] = token
  }
  return config
})

// 백엔드 공통 오류 envelope: { timestamp, status, error, code, message, path, fieldErrors }
// 애플리케이션에서 다루기 쉬운 형태로 정규화한다.
function normalizeError(error) {
  const res = error.response
  const body = res?.data ?? {}
  return {
    status: res?.status ?? 0,
    code: body.code ?? (res ? 'UNKNOWN_ERROR' : 'NETWORK_ERROR'),
    message:
      body.message ??
      (res ? '요청 처리 중 오류가 발생했습니다.' : '서버에 연결할 수 없습니다.'),
    fieldErrors: body.fieldErrors ?? [],
    raw: error,
  }
}

// 응답 인터셉터: 성공은 그대로, 실패는 정규화.
// 부팅 시 인증 확인(GET /me)처럼 401을 정상 흐름으로 처리해야 하는 요청은
// config.skipAuthRedirect === true 를 주면 전역 로그아웃 이벤트를 발생시키지 않는다.
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const normalized = normalizeError(error)
    const skipRedirect = error.config?.skipAuthRedirect
    if (normalized.status === 401 && !skipRedirect) {
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
    }
    return Promise.reject(normalized)
  },
)

export default apiClient
