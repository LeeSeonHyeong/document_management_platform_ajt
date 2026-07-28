import { useQueries } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import { fetchDocument } from '../api'

// documentId 목록 → { [documentId]: 문서상세 } 맵.
// AI 작업 응답(documentResults)에는 파일명·공개 범위가 없어, 작업 화면들이 이 훅으로 표시 필드를 채운다.
export function useDocumentDetails(documentIds = []) {
  const queries = useQueries({
    queries: documentIds.map((id) => ({
      queryKey: qk.documents.detail(id),
      queryFn: () => fetchDocument(id),
      enabled: Boolean(id),
    })),
  })
  const byId = {}
  documentIds.forEach((id, i) => {
    byId[id] = queries[i]?.data
  })
  return byId
}
