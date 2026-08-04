// 문의 목록에서 어떤 문의를 선택 상태로 둘지 결정한다.
//
// - 목록을 (재)조회하는 중(isFetching)에는 현재 선택을 그대로 유지한다. 작성 완료 후 넘어온
//   선택(location.state)이, 아직 갱신되지 않은 캐시 목록에 그 문의가 없다는 이유로 첫 항목으로
//   덮어써지는 것을 막기 위함이다. (캐시가 있으면 isLoading은 false라 이 구분이 필요하다.)
// - 조회가 끝난 뒤: 목록이 비면 선택 없음(null), 현재 선택이 목록에 있으면 유지, 없으면 첫 항목.
export function resolveSelectedInquiryId(inquiries, selectedId, isFetching) {
  if (isFetching) return selectedId
  if (!inquiries.length) return null
  if (inquiries.some((item) => item.inquiryId === selectedId)) return selectedId
  return inquiries[0].inquiryId
}
