import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import {
  fetchWikiSpaces,
  fetchWikiCategories,
  fetchWikis,
  fetchWiki,
  fetchWikiChatMessages,
  sendWikiChatMessage,
} from './api'

export function useWikiSpaces() {
  return useQuery({
    queryKey: qk.wikis.spaces,
    queryFn: fetchWikiSpaces,
  })
}

export function useWikiCategories(scopeKey) {
  return useQuery({
    queryKey: qk.wikis.categories(scopeKey),
    queryFn: () => fetchWikiCategories(scopeKey),
    enabled: Boolean(scopeKey),
  })
}

export function useWikis(filters) {
  return useQuery({
    queryKey: qk.wikis.list(filters),
    queryFn: () => fetchWikis(filters),
  })
}

export function useWiki(wikiId) {
  return useQuery({
    queryKey: qk.wikis.detail(wikiId),
    queryFn: () => fetchWiki(wikiId),
    enabled: Boolean(wikiId),
  })
}

// 관리자 전용(FR-AI-004). 권한 분기는 화면(브랜치 10)에서 role로 처리한다.
export function useWikiChatMessages(wikiId) {
  return useQuery({
    queryKey: qk.wikis.chat(wikiId),
    queryFn: () => fetchWikiChatMessages(wikiId),
    enabled: Boolean(wikiId),
  })
}

// 응답에 이미 수정 반영된 Wiki(updatedWiki)가 함께 오므로, 상세 캐시는 재조회 없이 바로 채운다.
export function useSendWikiChatMessage(wikiId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (content) => sendWikiChatMessage(wikiId, content),
    onSuccess: (reply) => {
      queryClient.setQueryData(qk.wikis.detail(wikiId), reply.updatedWiki)
      queryClient.invalidateQueries({ queryKey: qk.wikis.chat(wikiId) })
    },
  })
}
