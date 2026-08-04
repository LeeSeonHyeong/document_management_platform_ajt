import { describe, it, expect } from 'vitest'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { resolveSelectedInquiryId } from './inquirySelection'

const list = (...ids) => ids.map((id) => ({ inquiryId: id }))

describe('resolveSelectedInquiryId', () => {
  it('조회(재검증) 중에는 선택을 유지한다 — 작성 완료 후 넘어온 선택이 옛 캐시로 덮어써지지 않음', () => {
    // 캐시된 옛 목록엔 새 문의('101')가 없지만, 재검증 중이므로 선택을 유지해야 한다.
    expect(resolveSelectedInquiryId(list('5', '4', '3'), '101', true)).toBe('101')
  })

  it('재검증이 끝나 새 문의가 목록에 들어오면 그 선택을 유지한다', () => {
    expect(resolveSelectedInquiryId(list('101', '5', '4'), '101', false)).toBe('101')
  })

  it('선택이 없으면 첫 항목을 선택한다', () => {
    expect(resolveSelectedInquiryId(list('5', '4'), null, false)).toBe('5')
  })

  it('현재 선택이 목록에 없고 조회가 끝났으면 첫 항목으로 바꾼다 (필터 변경 등)', () => {
    expect(resolveSelectedInquiryId(list('5', '4'), '99', false)).toBe('5')
  })

  it('현재 선택이 목록에 있으면 유지한다', () => {
    expect(resolveSelectedInquiryId(list('5', '4', '3'), '4', false)).toBe('4')
  })

  it('목록이 비어 있으면 선택 없음(null)', () => {
    expect(resolveSelectedInquiryId([], '101', false)).toBeNull()
  })
})

// 실제 런타임 조건 검증: 목록 캐시가 있어도 마운트 시 (무효화로) 재검증하므로 isFetching=true가 되어
// 위 게이트가 작동한다. 이게 성립하지 않으면(구버전처럼 isLoading으로 판정하면) 선택이 덮어써진다.
describe('react-query 런타임: 옛 캐시 + 무효화 상태로 마운트하면 isFetching=true', () => {
  it('마운트 직후 isFetching=true라 선택이 유지되고, 재검증 후 새 문의를 계속 선택한다', async () => {
    const queryClient = new QueryClient()
    const key = ['inquiries', 'list', { page: 1 }]
    // 사용자가 이전에 본 목록(새 문의 '101' 없음)을 캐시에 심는다.
    queryClient.setQueryData(key, { items: list('5', '4', '3') })
    // 문의 작성 완료 시의 동작과 동일하게 무효화한다.
    queryClient.invalidateQueries({ queryKey: ['inquiries'] })

    const observer = new QueryObserver(queryClient, {
      queryKey: key,
      // 재검증하면 새 문의('101')가 최신순 맨 앞에 포함된 목록을 준다.
      queryFn: async () => ({ items: list('101', '5', '4', '3') }),
    })
    const unsubscribe = observer.subscribe(() => {})

    // 마운트 직후: 캐시를 제공하면서 재검증을 시작 → isFetching=true.
    const onMount = observer.getCurrentResult()
    expect(onMount.isFetching).toBe(true)
    // 이 순간 게이트가 있어야 선택('101')이 유지된다(없으면 첫 항목 '5'로 덮어써짐).
    expect(
      resolveSelectedInquiryId(onMount.data.items, '101', onMount.isFetching),
    ).toBe('101')

    // 재검증 완료까지 대기.
    for (let i = 0; i < 100 && observer.getCurrentResult().isFetching; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10))
    }

    const settled = observer.getCurrentResult()
    expect(settled.isFetching).toBe(false)
    expect(settled.data.items.some((item) => item.inquiryId === '101')).toBe(true)
    // 최종 상태에서도 방금 작성한 문의가 선택된 채로 유지된다.
    expect(
      resolveSelectedInquiryId(settled.data.items, '101', settled.isFetching),
    ).toBe('101')

    unsubscribe()
  })
})
