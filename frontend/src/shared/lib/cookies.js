// 쿠키 읽기 유틸. HttpOnly가 아닌 쿠키(예: CSRF 토큰 XSRF-TOKEN)만 읽을 수 있다.
// 인증 토큰(AJT_ACCESS_TOKEN)은 HttpOnly라 여기서 읽히지 않으며, 브라우저가 자동 전송한다.
export function readCookie(name) {
  if (typeof document === 'undefined' || !document.cookie) return null
  const prefix = `${name}=`
  const found = document.cookie
    .split('; ')
    .find((row) => row.startsWith(prefix))
  return found ? decodeURIComponent(found.slice(prefix.length)) : null
}
