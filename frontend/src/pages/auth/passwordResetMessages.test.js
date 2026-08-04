import { describe, it, expect } from 'vitest'
import { RESEND_SUCCESS_MESSAGE, resendErrorMessage } from './passwordResetMessages'

describe('비밀번호 찾기 재발송 메시지(S15P11B106-219)', () => {
  it('재발송 성공 메시지는 "인증번호를 다시 보냈습니다."다', () => {
    expect(RESEND_SUCCESS_MESSAGE).toBe('인증번호를 다시 보냈습니다.')
  })

  it('429 응답은 너무 잦은 요청 안내를 반환한다', () => {
    expect(resendErrorMessage({ status: 429 })).toContain('너무 잦')
  })

  it('code가 TOO_MANY_REQUESTS면 상태와 무관하게 너무 잦은 요청 안내를 반환한다', () => {
    expect(resendErrorMessage({ status: 400, code: 'TOO_MANY_REQUESTS' })).toContain('너무 잦')
  })

  it('그 외 에러는 서버 메시지를 그대로 보여준다', () => {
    expect(resendErrorMessage({ status: 500, message: '서버 오류입니다.' })).toBe('서버 오류입니다.')
  })

  it('메시지가 없으면 일반 실패 안내를 반환한다', () => {
    expect(resendErrorMessage({ status: 0 })).toContain('재발송에 실패')
  })

  it('null이어도 일반 실패 안내를 반환한다', () => {
    expect(resendErrorMessage(null)).toContain('재발송에 실패')
  })
})
