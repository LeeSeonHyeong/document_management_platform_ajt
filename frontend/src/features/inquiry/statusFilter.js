// 문의 목록 상태 필터입니다(S15P11B106-226).
//
// 문의관리 화면 상단 카드(전체 문의 / 미처리 / 처리 완료)를 누르면 이 값으로 목록을 필터링한다.
// 백엔드 `GET /api/v1/inquiries`의 기존 `status` 파라미터(pending|done)를 그대로 재사용하며,
// '전체'는 status를 보내지 않아 권한 범위 내 모든 문의를 조회한다(권한 정책·검색·정렬과 함께 동작).
// 백엔드는 이미 상태 필터를 지원하므로 서버는 변경하지 않는다.

export const INQUIRY_STATUS_FILTER = Object.freeze({
  ALL: 'all',
  PENDING: 'pending',
  DONE: 'done',
})

// 카드 순서·라벨과 선택 시 목록 제목. 카드와 목록 제목이 이 한 곳을 공유하도록 모아둔다.
export const INQUIRY_STATUS_FILTERS = Object.freeze([
  { value: INQUIRY_STATUS_FILTER.ALL, cardLabel: '전체 문의', listTitle: '전체 문의' },
  { value: INQUIRY_STATUS_FILTER.PENDING, cardLabel: '미처리', listTitle: '대기 중인 문의' },
  { value: INQUIRY_STATUS_FILTER.DONE, cardLabel: '처리 완료', listTitle: '처리 완료 문의' },
])

// 목록 API로 보낼 status 파라미터. '전체'는 undefined(파라미터 생략)로 두어 상태 제한 없이 조회한다.
// pending/done 값은 백엔드 InquiryStatus.apiValue와 그대로 일치한다.
export function toInquiryStatusParam(filter) {
  return filter === INQUIRY_STATUS_FILTER.PENDING || filter === INQUIRY_STATUS_FILTER.DONE
    ? filter
    : undefined
}

// 선택된 필터에 맞는 목록 제목. 알 수 없는 값은 '전체 문의'로 처리한다.
export function inquiryListTitle(filter) {
  const found = INQUIRY_STATUS_FILTERS.find((option) => option.value === filter)
  return found ? found.listTitle : '전체 문의'
}
