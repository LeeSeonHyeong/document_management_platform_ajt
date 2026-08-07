import { useEffect, useRef } from 'react'
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
  createAiJob,
} from './api'

// AI 작업이 끝나면 그 작업이 바꿔놓은 캐시를 모두 무효화한다.
//
// 위키 화면(features/wiki)은 작업을 폴링하지 않으므로, 작업을 지켜보는 쪽에서 위키 캐시를
// 걷어내지 않으면 편집이 끝난 위키가 새로고침 전까지 옛 내용으로 남는다 — spaces·categories는
// `wikis` 접두 밖이라 따로 지운다(카테고리가 새로 생기거나 비는 경우가 있다).
export function invalidateAfterAiJob(queryClient) {
  queryClient.invalidateQueries({ queryKey: qk.documents.all })
  queryClient.invalidateQueries({ queryKey: qk.wikis.all })
  queryClient.invalidateQueries({ queryKey: qk.wikis.spaces })
  queryClient.invalidateQueries({ queryKey: qk.wikis.categoriesAll })
}

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
    // payload: { file, onUploadProgress } — 진행률은 axios 업로드 이벤트를 그대로 넘긴다.
    mutationFn: ({ file, onUploadProgress }) =>
      replaceDocumentFile(documentId, file, { onUploadProgress }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.documents.detail(documentId) })
      queryClient.invalidateQueries({ queryKey: qk.aiJobs.all })
    },
  })
}

// 확정된 문서로 AI 작업을 만들고 바로 시작한다(S15P11B106-276).
// 작업이 문서 상태를 바꾸므로 문서 목록도 함께 무효화한다 — 대기 목록에서 빠져야 한다.
export function useCreateAiJob() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createAiJob,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: qk.documents.all })
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
      // 삭제는 위키를 걷어내는 재처리 작업(jobId)을 새로 만든다. 이걸 무효화하지 않으면
      // 요약 목록이 삭제 전 캐시(전부 종료 상태 → 폴링도 꺼짐)를 그대로 그려서,
      // 방금 시작된 「진행 중인 AI 작업 1건」이 새로고침해야 보인다.
      queryClient.invalidateQueries({ queryKey: qk.aiJobs.all })
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

// 카테고리 관리 화면(부서별 조회)용 전체 목록입니다(S15P11B106-290).
// scopeKey 없이 요청하면 백엔드가 로그인 관리자가 접근 가능한 모든 공개 범위의 카테고리를 반환한다.
export function useAllDocumentCategories() {
  return useQuery({
    queryKey: qk.documentCategories.list(null),
    queryFn: () => fetchDocumentCategories(),
  })
}

// 카테고리 생성/수정/삭제 후에는 카테고리 관련 모든 목록을 무효화한다(S15P11B106-290).
//   예전에는 'ALL' 캐시에 setQueryData로 직접 끼워넣어 실제 서버와 어긋났고, 삭제 후 재조회에서
//   부서 카테고리가 통째로 사라지는(증발) 문제가 있었다. 이제 전체 조회 API가 있어 무효화만 하면 된다.
export function useCreateDocumentCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createDocumentCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.documentCategories.all }),
  })
}

export function useUpdateDocumentCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ categoryId, ...payload }) => updateDocumentCategory(categoryId, payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.documentCategories.all }),
  })
}

export function useDeleteDocumentCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteDocumentCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.documentCategories.all }),
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

// 폴링하는 화면이 「지금 상태」를 보여주려면 전역 기본값 세 가지를 되돌려야 한다.
// 기본값은 관리 화면 일반을 위한 것이고(30초 캐시·포커스 재요청 없음), 진행 중인 작업을
// 지켜보는 화면에는 맞지 않는다 — 작업이 몇 분씩 걸려 사용자가 탭을 떠나 있기 때문이다.
//
//  - refetchIntervalInBackground: 탭이 뒤로 가면 react-query 가 폴링을 멈춘다. 그동안
//    작업이 끝나도 화면은 떠날 때 모습 그대로다.
//  - refetchOnWindowFocus: 돌아온 순간 바로 맞춘다. 없으면 다음 폴링까지 옛 화면을 본다.
//  - staleTime 0: 다른 화면에 갔다가 30초 안에 돌아오면 기본값은 캐시를 그대로 믿어
//    다시 읽지 않는다. 그 사이에 끝난 작업이 「처리 중」으로 남는다.
export const LIVE_QUERY_OPTIONS = {
  refetchIntervalInBackground: true,
  refetchOnWindowFocus: true,
  staleTime: 0,
}

export function useAiJobs(filters = {}) {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: qk.aiJobs.list(filters),
    queryFn: () => fetchAiJobs(filters),
    refetchInterval: (query) =>
      hasUnsettledWork(query.state.data?.items ?? []) ? RUNNING_JOB_POLL_MS : false,
    ...LIVE_QUERY_OPTIONS,
  })

  // 진행 중이던 작업이 전부 끝난 순간 한 번, 그 작업이 바꿔놓은 문서·위키 캐시를 걷어낸다.
  const unsettled = hasUnsettledWork(query.data?.items ?? [])
  const wasUnsettled = useRef(false)
  useEffect(() => {
    if (wasUnsettled.current && !unsettled) invalidateAfterAiJob(queryClient)
    wasUnsettled.current = unsettled
  }, [unsettled, queryClient])

  return query
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
