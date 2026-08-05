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

export function useWikis(filters, { enabled = true } = {}) {
  return useQuery({
    queryKey: qk.wikis.list(filters),
    queryFn: () => fetchWikis(filters),
    enabled,
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
//
// 관리자 메시지는 응답을 기다리지 않고 낙관적으로 먼저 그린다 — 왕복 지연 동안 화면에
// 아무것도 안 뜨면 사용자가 입력이 씹혔다고 오해한다. 실패하면 그 임시 메시지만 걷어낸다.
export function useSendWikiChatMessage(wikiId) {
  const queryClient = useQueryClient()
  const chatKey = qk.wikis.chat(wikiId)
  return useMutation({
    mutationFn: (content) => sendWikiChatMessage(wikiId, content),
    onMutate: async (content) => {
      await queryClient.cancelQueries({ queryKey: chatKey })
      const previousMessages = queryClient.getQueryData(chatKey)
      const optimisticMessage = {
        messageId: `optimistic-${Date.now()}`,
        senderType: 'admin',
        content,
        createdAt: new Date().toISOString(),
      }
      queryClient.setQueryData(chatKey, (current = []) => [...current, optimisticMessage])
      return { previousMessages }
    },
    onError: (error, content, context) => {
      if (context?.previousMessages) queryClient.setQueryData(chatKey, context.previousMessages)
    },
    onSuccess: (reply) => {
      queryClient.setQueryData(qk.wikis.detail(wikiId), reply.updatedWiki)
      queryClient.invalidateQueries({ queryKey: chatKey })
      // 제목·수정일은 목록에도 실린다. 목록을 걷어내지 않으면 왼쪽 트리가 수정 전 제목을 계속 건다.
      queryClient.invalidateQueries({ queryKey: qk.wikis.listAll })
    },
  })
}
