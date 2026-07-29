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

// 문의 작성 화면의 담당자 후보입니다.
// assigneeId, name, department 필드는 DB 칼럼명이 아니라 공개 API 응답 이름입니다.
export async function fetchInquiryAssignees(keyword = '') {
  const { data } = await apiClient.get('/inquiry-assignees', {
    params: { keyword: keyword || undefined },
  })
  return data
}

// 문의 등록 API는 이미지 파일을 함께 보내므로 multipart/form-data를 사용합니다.
export async function createInquiry({ assigneeId, title, content, priority, attachments }) {
  const formData = new FormData()
  formData.append('assigneeId', assigneeId)
  formData.append('title', title)
  formData.append('content', content)
  formData.append('priority', priority)
  attachments.forEach((file) => formData.append('attachments', file))
  const { data } = await apiClient.post('/inquiries', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}
