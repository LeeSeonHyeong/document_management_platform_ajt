import { describe, it, expect } from 'vitest'
import {
  INQUIRY_STATUS_FILTER,
  INQUIRY_STATUS_FILTERS,
  toInquiryStatusParam,
  inquiryListTitle,
} from './statusFilter'

describe('문의 목록 상태 필터(S15P11B106-226)', () => {
  it('카드는 전체/미처리/처리완료 3개 순서다', () => {
    expect(INQUIRY_STATUS_FILTERS.map((option) => option.value)).toEqual(['all', 'pending', 'done'])
    expect(INQUIRY_STATUS_FILTERS.map((option) => option.cardLabel)).toEqual([
      '전체 문의',
      '미처리',
      '처리 완료',
    ])
  })

  it("'전체' 선택은 status 파라미터를 보내지 않는다(권한 범위 전체 조회)", () => {
    expect(toInquiryStatusParam(INQUIRY_STATUS_FILTER.ALL)).toBeUndefined()
    expect(toInquiryStatusParam('')).toBeUndefined()
    expect(toInquiryStatusParam(undefined)).toBeUndefined()
  })

  it('미처리·처리완료 카드는 백엔드 status 값(pending/done)으로 필터링한다', () => {
    expect(toInquiryStatusParam(INQUIRY_STATUS_FILTER.PENDING)).toBe('pending')
    expect(toInquiryStatusParam(INQUIRY_STATUS_FILTER.DONE)).toBe('done')
  })

  it('선택한 카드에 맞게 목록 제목이 바뀐다', () => {
    expect(inquiryListTitle(INQUIRY_STATUS_FILTER.ALL)).toBe('전체 문의')
    expect(inquiryListTitle(INQUIRY_STATUS_FILTER.PENDING)).toBe('대기 중인 문의')
    expect(inquiryListTitle(INQUIRY_STATUS_FILTER.DONE)).toBe('처리 완료 문의')
  })

  it('알 수 없는 값은 전체 문의 제목으로 처리한다', () => {
    expect(inquiryListTitle(undefined)).toBe('전체 문의')
    expect(inquiryListTitle('weird')).toBe('전체 문의')
  })
})
