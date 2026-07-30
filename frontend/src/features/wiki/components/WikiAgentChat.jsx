import { useEffect, useRef, useState } from 'react'
import { ArrowRight, Sparkles } from 'lucide-react'
import { Button, Spinner } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { useSendWikiChatMessage, useWikiChatMessages } from '../queries'

function formatTime(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

export default function WikiAgentChat({ wikiId }) {
  const [content, setContent] = useState('')
  const listRef = useRef(null)
  const { data: messages = [], isLoading } = useWikiChatMessages(wikiId)
  const sendMutation = useSendWikiChatMessage(wikiId)
  const sending = sendMutation.isPending

  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight
  }, [messages, sending])

  function handleSend() {
    const trimmed = content.trim()
    if (!trimmed || sending) return
    sendMutation.mutate(trimmed, { onSuccess: () => setContent('') })
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white">
      <header className="flex shrink-0 items-center gap-3 border-b border-slate-200 px-4 py-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-violet-600 text-white">
          <Sparkles className="size-5" />
        </span>
        <div>
          <h2 className="text-sm font-bold text-slate-800">AI 문서 편집 에이전트</h2>
          <p className="mt-0.5 flex items-center gap-1.5 text-[11px] text-slate-400">
            <span className="size-1.5 rounded-full bg-emerald-500" />
            온라인 · 문서 편집 도우미
          </p>
        </div>
      </header>

      <div ref={listRef} className="flex-1 space-y-4 overflow-y-auto p-5">
        {isLoading ? (
          <div className="flex justify-center py-8">
            <Spinner size="sm" />
          </div>
        ) : messages.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center text-center">
            <span className="flex size-12 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500 to-violet-600 text-white">
              <Sparkles className="size-6" />
            </span>
            <p className="mt-4 text-sm font-semibold text-slate-700">수정할 내용을 알려주세요.</p>
            <p className="mt-1 text-xs text-slate-400">AI 에이전트가 현재 위키 문서에 반영합니다.</p>
          </div>
        ) : (
          messages.map((message) => {
            const agent = message.senderType === 'agent'
            return (
              <div key={message.messageId} className={cn('flex gap-2', agent ? 'justify-start' : 'justify-end')}>
                {agent && (
                  <span className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-blue-500 to-violet-600 text-white">
                    <span className="text-[10px] font-bold">AI</span>
                  </span>
                )}
                <div className="max-w-[78%]">
                  <div
                    className={cn(
                      'rounded-2xl px-4 py-3 text-sm leading-6',
                      agent
                        ? 'rounded-bl-md border border-slate-200 bg-white text-slate-600'
                        : 'rounded-br-md bg-gradient-to-r from-blue-500 to-violet-600 text-white',
                    )}
                  >
                    {message.content}
                  </div>
                  <span className="mt-1 block text-right text-[11px] text-slate-300">
                    {formatTime(message.createdAt)}
                  </span>
                </div>
              </div>
            )
          })
        )}

        {sending && (
          <div className="flex items-center gap-2 text-sm text-slate-400">
            <Spinner size="sm" />
            에이전트가 수정 사항을 반영하고 있습니다.
          </div>
        )}
      </div>

      <div className="flex items-end gap-2 border-t border-slate-200 bg-white p-3">
        <textarea
          value={content}
          onChange={(event) => setContent(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              handleSend()
            }
          }}
          rows={1}
          placeholder="메시지를 입력하세요..."
          disabled={sending}
          className="focus-ring h-11 min-h-11 flex-1 resize-none rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm disabled:opacity-60"
        />
        <Button
          variant="primary"
          onClick={handleSend}
          loading={sending}
          disabled={!content.trim()}
          className="size-11 shrink-0 p-0"
          aria-label="메시지 전송"
        >
          <ArrowRight className="size-5" />
        </Button>
      </div>
    </div>
  )
}
