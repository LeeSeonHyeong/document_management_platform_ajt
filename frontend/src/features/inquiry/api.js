import apiClient from '@/api/client'

// 문의 목록 응답:
// items=문의 배열, page/size/totalCount/totalPages=페이지 정보
// 문의 한 건은 inquiryId, title, author, assignee, priority, status, createdAt을 가집니다.
export async function fetchInquiries(params = {}) {
  const { data } = await apiClient.get('/inquiries', { params })
  return data
}

// 문의 상세에는 목록 필드 외에 content, attachments, answer가 추가됩니다.
export async function fetchInquiry(inquiryId) {
  const { data } = await apiClient.get(`/inquiries/${inquiryId}`)
  return data
}

// 답변 등록과 수정은 같은 PUT API를 사용합니다.
// content는 관리자가 작성한 최종 답변 문자열입니다.
export async function saveInquiryAnswer(inquiryId, content) {
  const { data } = await apiClient.put(`/inquiries/${inquiryId}/answer`, {
    content,
  })
  return data
}
