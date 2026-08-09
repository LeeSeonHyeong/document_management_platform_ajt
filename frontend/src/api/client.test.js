// 오류 정규화 정책 (S15P11B106-319): 5xx 의 서버 message 는 개발자용 원문일 확률이
// 높아(역직렬화 실패·SQL 예외 원문이 토스트에 뜬 사례) 화면에 내보내지 않는다.
// 4xx 업무 검증 문구와 네트워크 단절 안내는 기존 그대로다.
import { describe, expect, it } from 'vitest'
import { normalizeError } from './client'

function axiosError(status, body) {
  return { response: status == null ? undefined : { status, data: body } }
}

describe('normalizeError', () => {
  it('5xx 는 서버 원문을 버리고 일반 안내와 코드만 보여준다', () => {
    const normalized = normalizeError(
      axiosError(500, { code: 'INTERNAL_ERROR', message: "Duplicate entry 'ALL-휴가 정책'" }),
    )
    expect(normalized.message).toContain('서버 처리 중 문제가 발생했습니다')
    expect(normalized.message).toContain('INTERNAL_ERROR')
    expect(normalized.message).not.toContain('Duplicate entry')
  })

  it('4xx 는 백엔드 업무 문구를 그대로 신뢰한다', () => {
    const normalized = normalizeError(
      axiosError(409, { code: 'DUPLICATE_DOCUMENT', message: '같은 파일이 이미 있습니다.' }),
    )
    expect(normalized.message).toBe('같은 파일이 이미 있습니다.')
  })

  it('응답 자체가 없으면 연결 안내를 보여준다', () => {
    const normalized = normalizeError(axiosError(null))
    expect(normalized.code).toBe('NETWORK_ERROR')
    expect(normalized.message).toBe('서버에 연결할 수 없습니다.')
  })

  it('5xx 인데 code 도 없으면 UNKNOWN_ERROR 코드로 표기한다', () => {
    const normalized = normalizeError(axiosError(502, {}))
    expect(normalized.message).toContain('UNKNOWN_ERROR')
  })

  it('원문은 raw 로 남는다 — 개발 콘솔 진단용', () => {
    const error = axiosError(500, { message: '원문' })
    expect(normalizeError(error).raw).toBe(error)
  })
})
