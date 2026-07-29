import apiClient from '@/api/client'

// conversationId가 없으면 새 대화, 있으면 같은 대화의 후속 질문입니다.
export async function askQuestion(question, conversationId = null) {
  const { data } = await apiClient.post('/questions', {
    conversationId,
    question,
  })
  return data
}

// 로그인한 사용자의 질문 이력만 서버에서 반환합니다.
export async function fetchQuestionHistory(params = {}) {
  const { data } = await apiClient.get('/questions', { params })
  return data
}
