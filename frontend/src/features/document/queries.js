import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import {
  uploadDocuments,
  uploadScheduleSource,
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
  fetchAiJobs,
  startAiJob,
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

export function useUploadScheduleSource() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: uploadScheduleSource,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.schedules.all })
    },
  })
}

// 공개 범위·카테고리 변경. 응답에 재처리 jobId가 포함되므로 aiJobs 캐시도 함께 무효화한다.
export function useUpdateDocument(documentId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => updateDocument(documentId, payload),
    onSuccess: (updatedDocument) => {
      // PATCH 응답에는 수정된 문서 정보가 함께 오므로 목록에 즉시 반영한다.
      // invalidateQueries의 재조회만 기다리면 드롭다운 라벨이 한동안 이전 값으로 남는다.
      if (updatedDocument?.documentId) {
        queryClient.setQueriesData({ queryKey: qk.documents.all }, (current) => {
          if (!current?.items) return current
          return {
            ...current,
            items: current.items.map((item) =>
              item.documentId === updatedDocument.documentId ? { ...item, ...updatedDocument } : item,
            ),
          }
        })
        queryClient.setQueryData(qk.documents.detail(updatedDocument.documentId), updatedDocument)
      }
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
    onSuccess: (createdCategory, variables) => {
      // 관리 화면은 `ALL` 키를 전체 카테고리 목록으로 사용한다. 실제 API는 scopeKey가
      // 정확히 일치하는 항목만 반환하므로, 부서 범위(D1-D2 등)로 생성한 항목은 생성
      // 응답을 관리 화면 캐시에 직접 추가해야 즉시 목록에 유지된다.
      queryClient.setQueryData(qk.documentCategories.list('ALL'), (current = []) => {
        const exists = current.some(
          (category) => category.documentCategoryId === createdCategory.documentCategoryId,
        )
        return exists ? current : [...current, createdCategory]
      })

      // 업로드 화면 등 실제 공개 범위별 카테고리 목록은 서버 값으로 다시 동기화한다.
      if (variables.scopeKey !== 'ALL') {
        queryClient.invalidateQueries({
          queryKey: qk.documentCategories.list(variables.scopeKey),
        })
      }
    },
  })
}

export function useUpdateDocumentCategory(scopeKey) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ categoryId, ...payload }) => updateDocumentCategory(categoryId, payload),
    onSuccess: (updatedCategory) => {
      // 카테고리 관리 화면의 `ALL` 캐시는 여러 공개 범위의 카테고리를 합쳐 보여주는
      // 화면용 목록이다. 수정 후 `ALL`을 재조회하면 실제 scopeKey가 D1-D2인 항목은
      // 응답에서 빠지므로, 수정된 행만 응답값으로 교체한다.
      queryClient.setQueryData(qk.documentCategories.list(scopeKey), (current = []) =>
        current.map((category) =>
          category.documentCategoryId === updatedCategory.documentCategoryId
            ? { ...category, ...updatedCategory }
            : category,
        ),
      )

      if (scopeKey !== updatedCategory.scopeKey) {
        queryClient.invalidateQueries({
          queryKey: qk.documentCategories.list(updatedCategory.scopeKey),
        })
      }
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

// 작업 이력 목록. 요약 목록 화면이 회차별 묶음으로 그린다.
//
// 아직 끝나지 않은 작업이 목록에 있으면 5초마다 다시 읽는다 — 그 화면이 진행 중인 작업도
// 보여주므로(S15P11B106-200) 갱신이 없으면 끝난 작업이 계속 "처리 중"으로 남는다.
// 전부 종료됐으면 멈춘다. 단건 폴링(useAiJobPolling, 2초)보다 느슨하게 둔 것은 이 목록이
// 문서 상세까지 함께 당기기 때문이다.
const RUNNING_JOB_POLL_MS = 5000
const TERMINAL_JOB_STATUSES = new Set(['completed', 'failed', 'cancelled'])
// 문서 결과의 종료 상태. 작업 상태와 값이 겹치지만 다른 축이라 따로 둔다 —
// 문서는 uploaded·parsing·processing 을 더 거친다(status.js).
const TERMINAL_DOCUMENT_STATUSES = new Set(['completed', 'failed', 'cancelled'])

// 작업이 끝나도 그 문서들이 아직 종료 상태가 아니면 계속 읽는다 (S15P11B106-244).
//
// 화면이 그리는 배지는 작업 상태가 아니라 **문서 상태**다(`AiJobSummaryListPage` 의
// `result.status`, 백엔드가 `document.status()` 로 만든다). 작업이 종료로 바뀌는 시점과
// 문서가 완료로 바뀌는 시점 사이에 틈이 있어서, 작업 상태만 보고 끊으면 그 틈에 받은
// 응답이 그대로 굳는다 — 끝난 작업이 「처리 중」으로 남아 새로고침해야 바뀌었다.
export function hasUnsettledWork(items) {
  return items.some(
    (job) =>
      !TERMINAL_JOB_STATUSES.has(job.status) ||
      (job.documentResults ?? []).some(
        (result) => !TERMINAL_DOCUMENT_STATUSES.has(result.status),
      ),
  )
}

export function useAiJobs(filters = {}) {
  return useQuery({
    queryKey: qk.aiJobs.list(filters),
    queryFn: () => fetchAiJobs(filters),
    refetchInterval: (query) =>
      hasUnsettledWork(query.state.data?.items ?? []) ? RUNNING_JOB_POLL_MS : false,
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

// 대기 화면의 "AI 작업 시작". 이 호출 전까지 백엔드는 파싱을 시작하지 않는다.
export function useStartAiJob() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: startAiJob,
    onSuccess: (_data, jobId) => {
      queryClient.invalidateQueries({ queryKey: qk.aiJobs.detail(jobId) })
    },
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
