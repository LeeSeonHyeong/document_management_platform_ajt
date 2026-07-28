import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import {
  uploadDocuments,
  fetchDocuments,
  fetchDocument,
  updateDocument,
  replaceDocumentFile,
  deleteDocument,
  retryDocument,
  fetchDocumentCategories,
  createDocumentCategory,
  updateDocumentCategory,
  deleteDocumentCategory,
  fetchAiJob,
  cancelAiJob,
} from './api'

export function useDocuments(filters) {
  return useQuery({
    queryKey: qk.documents.list(filters),
    queryFn: () => fetchDocuments(filters),
  })
}

export function useDocument(documentId) {
  return useQuery({
    queryKey: qk.documents.detail(documentId),
    queryFn: () => fetchDocument(documentId),
    enabled: Boolean(documentId),
  })
}

export function useUploadDocuments() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: uploadDocuments,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.documents.all })
    },
  })
}

// 공개 범위·카테고리 변경. 응답에 재처리 jobId가 포함되므로 aiJobs 캐시도 함께 무효화한다.
export function useUpdateDocument(documentId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => updateDocument(documentId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.documents.all })
      queryClient.invalidateQueries({ queryKey: qk.aiJobs.all })
    },
  })
}

export function useReplaceDocumentFile(documentId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file) => replaceDocumentFile(documentId, file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.documents.detail(documentId) })
      queryClient.invalidateQueries({ queryKey: qk.aiJobs.all })
    },
  })
}

export function useDeleteDocument() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteDocument,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.documents.all })
    },
  })
}

export function useRetryDocument() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: retryDocument,
    onSuccess: (_data, documentId) => {
      queryClient.invalidateQueries({ queryKey: qk.documents.detail(documentId) })
      queryClient.invalidateQueries({ queryKey: qk.aiJobs.all })
    },
  })
}

export function useDocumentCategories(scopeKey) {
  return useQuery({
    queryKey: qk.documentCategories.list(scopeKey),
    queryFn: () => fetchDocumentCategories(scopeKey),
    enabled: Boolean(scopeKey),
  })
}

export function useCreateDocumentCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createDocumentCategory,
    onSuccess: (category) => {
      queryClient.invalidateQueries({ queryKey: qk.documentCategories.list(category.scopeKey) })
    },
  })
}

export function useUpdateDocumentCategory(scopeKey) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ categoryId, ...payload }) => updateDocumentCategory(categoryId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.documentCategories.list(scopeKey) })
    },
  })
}

export function useDeleteDocumentCategory(scopeKey) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteDocumentCategory,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.documentCategories.list(scopeKey) })
    },
  })
}

// 단발 조회. 2초 폴링이 필요한 화면은 hooks/useAiJobPolling을 사용한다.
export function useAiJob(jobId) {
  return useQuery({
    queryKey: qk.aiJobs.detail(jobId),
    queryFn: () => fetchAiJob(jobId),
    enabled: Boolean(jobId),
  })
}

export function useCancelAiJob() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: cancelAiJob,
    onSuccess: (_data, jobId) => {
      queryClient.invalidateQueries({ queryKey: qk.aiJobs.detail(jobId) })
    },
  })
}
