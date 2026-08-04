// 비밀번호 찾기 인증번호 재발송 UX 메시지입니다(S15P11B106-219).
// 백엔드는 그대로 두고 프론트 안내 문구만 담당한다.

export const RESEND_SUCCESS_MESSAGE = '인증번호를 다시 보냈습니다.'

const TOO_MANY_REQUESTS_MESSAGE = '요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.'
const GENERIC_RESEND_ERROR_MESSAGE = '인증번호 재발송에 실패했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * 재발송 실패 시 보여줄 메시지를 고른다.
 * client.js normalizeError 결과({ status, code, message })를 기준으로 판단한다.
 * - 429 / TOO_MANY_REQUESTS: 너무 잦은 요청 안내
 * - 그 외: 서버 메시지가 있으면 그대로, 없으면 일반 실패 안내
 */
export function resendErrorMessage(error) {
  if (error?.status === 429 || error?.code === 'TOO_MANY_REQUESTS') {
    return TOO_MANY_REQUESTS_MESSAGE
  }
  return error?.message || GENERIC_RESEND_ERROR_MESSAGE
}
