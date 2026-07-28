// 인증 정보의 localStorage 영속 계층.
// 새로고침 후에도 로그인 상태를 복원할 수 있도록 토큰과 사용자 정보를 보관한다.
const TOKEN_KEY = 'ajt.accessToken'
const USER_KEY = 'ajt.user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export function getStoredUser() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    // 저장값이 손상된 경우 조용히 폐기한다.
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export function setStoredUser(user) {
  if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
  else localStorage.removeItem(USER_KEY)
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
