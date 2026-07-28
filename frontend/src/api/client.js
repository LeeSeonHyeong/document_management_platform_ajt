import axios from 'axios'
import { getToken, clearAuth } from '@/lib/authStorage'

// 401 발생 시 AuthContext가 구독해 로그아웃/리다이렉트를 처리하도록 알리는 이벤트 이름.
export const UNAUTHORIZED_EVENT = 'ajt:unauthorized'

// 백엔드 공개 API 공통 인스턴스.
// baseURL 기본값은 Vite 프록시(/api/v1). 배포 시 VITE_API_BASE_URL로 오버라이드한다.
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  headers: { 'Content-Type': 'application/json' },
})

// 요청 인터셉터: 저장된 accessToken을 Authorization 헤더에 자동 첨부.
apiClient.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 백엔드 공통 오류 envelope: { timestamp, status, error, code, message, path, fieldErrors }
// 이를 애플리케이션에서 다루기 쉬운 형태로 정규화한다.
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

// 응답 인터셉터: 성공은 그대로, 실패는 정규화. 401은 인증 만료로 보고 전역 처리.
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const normalized = normalizeError(error)
    if (normalized.status === 401) {
      clearAuth()
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
    }
    return Promise.reject(normalized)
  },
)

export default apiClient
