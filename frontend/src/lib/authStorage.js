// 사용자 정보 캐시 계층.
// 인증 토큰은 HttpOnly 쿠키라 JS가 저장/조회하지 않는다. 여기서는 새로고침 시
// 첫 렌더 깜빡임을 줄이기 위한 비민감 사용자 요약만 캐시한다(진짜 인증 상태는 GET /me로 확정).
const USER_KEY = 'ajt.user'

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

export function clearStoredUser() {
  localStorage.removeItem(USER_KEY)
}
