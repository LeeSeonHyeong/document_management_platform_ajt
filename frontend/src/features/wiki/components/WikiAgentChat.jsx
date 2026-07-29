import { useEffect, useRef, useState } from 'react'
import { Send } from 'lucide-react'
import { Button, Spinner } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { useWikiChatMessages, useSendWikiChatMessage } from '../queries'

function formatTime(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

// Figma 6-1R (Jira -75) — Wiki 상세의 "AI 에이전트" 탭. 관리자 전용.
// 챗봇이 아니라, 관리자가 특정 Wiki에 대해 편집 에이전트에게 수정을 "지시"하는 기능이다.
// (C의 사원 챗봇 /questions와 API·모델이 다르며 재사용하지 않는다.)
export default function WikiAgentChat({ wikiId }) {
  const [content, setContent] = useState('')
  const listRef = useRef(null)

  const { data: messages = [], isLoading } = useWikiChatMessages(wikiId)
  const sendMutation = useSendWikiChatMessage(wikiId)
  const sending = sendMutation.isPending

  // 새 메시지가 오면 목록 맨 아래로 스크롤.
  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight
  }, [messages, sending])

  function handleSend() {
    const trimmed = content.trim()
    if (!trimmed || sending) return
    // 성공 시 useSendWikiChatMessage가 Wiki 상세 캐시를 갱신하고 메시지 목록을 무효화한다.
    sendMutation.mutate(trimmed, { onSuccess: () => setContent('') })
  }

  return (
    <div className="flex h-[28rem] flex-col rounded-xl border border-slate-200">
      <div ref={listRef} className="flex-1 space-y-3 overflow-y-auto p-4">
        {isLoading ? (
          <div className="flex justify-center py-8">
            <Spinner size="sm" />
          </div>
        ) : messages.length === 0 ? (
          <p className="py-8 text-center text-sm text-slate-400">
            이 Wiki에 대한 수정 지시를 입력하면 에이전트가 반영합니다.
          </p>
        ) : (
          messages.map((msg) => {
            const isAgent = msg.senderType === 'agent'
            return (
              <div key={msg.messageId} className={cn('flex', isAgent ? 'justify-start' : 'justify-end')}>
                <div className={cn('max-w-[80%]')}>
                  <span className="mb-1 block text-xs text-slate-400">{isAgent ? '에이전트' : '관리자'}</span>
                  <div
                    className={cn(
                      'rounded-2xl px-3 py-2 text-sm',
                      isAgent ? 'bg-slate-100 text-slate-700' : 'bg-primary-600 text-white',
                    )}
                  >
                    {msg.content}
                  </div>
                  <span className="mt-0.5 block text-right text-[11px] text-slate-300">{formatTime(msg.createdAt)}</span>
                </div>
              </div>
            )
          })
        )}

        {sending && (
          <div className="flex items-center gap-2 text-sm text-slate-400">
            <Spinner size="sm" />
            에이전트가 수정 사항을 반영하고 있습니다…
          </div>
        )}
      </div>

      <div className="flex items-end gap-2 border-t border-slate-100 p-3">
        <textarea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
          rows={2}
          placeholder="예: '연차 촉진 관련 문단을 최신 규정으로 업데이트해줘'"
          disabled={sending}
          className="focus-ring flex-1 resize-none rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:bg-slate-50"
        />
        <Button variant="primary" onClick={handleSend} loading={sending} disabled={!content.trim()}>
          <Send className="size-4" />
          전송
        </Button>
      </div>
    </div>
  )
}
