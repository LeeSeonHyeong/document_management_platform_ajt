import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowRight, FileText, Sparkles } from 'lucide-react'
import { Button, useToast } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { useSendWikiChatMessage, useWiki, useWikiChatMessages, useWikiSpaces } from '../queries'
import ChatMarkdown from './ChatMarkdown'

function formatTime(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

// 지금 열려 있는 위키가 실제로 속한 scope(부서)를 그대로 보여준다.
//
// 사이드바의 "부서" 드롭다운으로 짐작하게 두지 않는다 — 검색 결과·관련 위키 링크·직접
// 진입처럼 드롭다운을 거치지 않는 경로가 많아서, 드롭다운 상태와 실제로 열린 위키의
// scope가 항상 같다는 보장이 없다. 이 채팅은 같은 scope의 위키를 넘나들며 이어지므로
// (S15P11B106-220), "지금 어느 방에 들어와 있는지"를 위키 자신의 scopeKey로 고정해 보여준다.
function useScopeLabel(wikiId) {
  const { data: wiki } = useWiki(wikiId)
  const { data: spaces = [] } = useWikiSpaces()
  if (!wiki) return null
  const space = spaces.find((item) => item.scopeKey === wiki.scopeKey)
  return space?.displayName ?? wiki.scopeKey
}

// 에이전트 메시지가 실제로 바꾼 위키로 가는 태그(S15P11B106-243).
// 대화는 scope 전체를 넘나들 수 있어 지금 보고 있는 위키와 다를 수 있다 — 눌러서 바로 이동한다.
function WikiLinkTag({ wikiId, wikiTitle, currentWikiId }) {
  const navigate = useNavigate()
  if (!wikiId) return null
  const isCurrent = wikiId === currentWikiId
  return (
    <button
      type="button"
      onClick={() => navigate(`/wiki/${wikiId}`)}
      disabled={isCurrent}
      className={cn(
        'mt-1.5 inline-flex max-w-full items-center gap-1 rounded-full border px-2.5 py-1 text-[11px] font-medium transition-colors',
        isCurrent
          ? 'cursor-default border-slate-200 text-slate-400'
          : 'border-primary-200 bg-primary-50 text-primary-700 hover:bg-primary-100',
      )}
    >
      <FileText className="size-3 shrink-0" />
      <span className="truncate">{wikiTitle ?? '위키'}</span>
    </button>
  )
}

export default function WikiAgentChat({ wikiId }) {
  const toast = useToast()
  const [content, setContent] = useState('')
  const listRef = useRef(null)
  const { data: messages = [], isLoading } = useWikiChatMessages(wikiId)
  const sendMutation = useSendWikiChatMessage(wikiId)
  const sending = sendMutation.isPending
  const scopeLabel = useScopeLabel(wikiId)

  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight
  }, [messages, sending])

  function handleSend() {
    const trimmed = content.trim()
    if (!trimmed || sending) return
    setContent('')
    sendMutation.mutate(trimmed, {
      onError: () => toast.error('메시지 전송에 실패했습니다. 다시 시도해주세요.'),
    })
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white">
      <header className="flex shrink-0 items-center gap-3 border-b border-slate-200 px-4 py-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 text-white">
          <Sparkles className="size-5" />
        </span>
        <div className="min-w-0">
          <h2 className="text-sm font-bold text-slate-800">AI 문서 편집 에이전트</h2>
          <p className="mt-0.5 flex items-center gap-1.5 truncate text-[11px] text-slate-400">
            <span className="size-1.5 shrink-0 rounded-full bg-emerald-500" />
            {scopeLabel ? `${scopeLabel} 공유 중` : '문서 편집 도우미'}
          </p>
        </div>
      </header>

      <div ref={listRef} className="thin-scroll flex-1 space-y-4 overflow-y-auto p-5">
        {isLoading ? (
          <div className="animate-pulse space-y-4">
            <div className="flex gap-2">
              <div className="size-7 shrink-0 rounded-lg bg-slate-100" />
              <div className="h-16 w-2/3 rounded-2xl rounded-bl-md bg-slate-100" />
            </div>
            <div className="flex justify-end">
              <div className="h-10 w-1/2 rounded-2xl rounded-br-md bg-slate-100" />
            </div>
            <div className="flex gap-2">
              <div className="size-7 shrink-0 rounded-lg bg-slate-100" />
              <div className="h-12 w-3/5 rounded-2xl rounded-bl-md bg-slate-100" />
            </div>
          </div>
        ) : messages.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center text-center">
            <span className="flex size-12 items-center justify-center rounded-2xl bg-gradient-to-br from-primary-500 to-primary-700 text-white">
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
                  <span className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-primary-500 to-primary-700 text-white">
                    <span className="text-[10px] font-bold">AI</span>
                  </span>
                )}
                <div className="max-w-[78%]">
                  <div
                    className={cn(
                      'rounded-2xl px-4 py-3 text-sm',
                      agent
                        ? 'rounded-bl-md border border-slate-200 bg-white text-slate-600'
                        : 'rounded-br-md bg-gradient-to-r from-primary-500 to-primary-700 text-white',
                    )}
                  >
                    <ChatMarkdown markdown={message.content} tone={message.senderType} />
                  </div>
                  {agent && (
                    <WikiLinkTag
                      wikiId={message.wikiId}
                      wikiTitle={message.wikiTitle}
                      currentWikiId={wikiId}
                    />
                  )}
                  <span className="mt-1 block text-right text-[11px] text-slate-300">
                    {formatTime(message.createdAt)}
                  </span>
                </div>
              </div>
            )
          })
        )}

        {sending && (
          <div className="flex justify-start gap-2">
            <span className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-primary-500 to-primary-700 text-white">
              <span className="text-[10px] font-bold">AI</span>
            </span>
            <div className="flex items-center gap-1.5 rounded-2xl rounded-bl-md border border-slate-200 bg-white px-4 py-3">
              <i className="size-1.5 animate-bounce rounded-full bg-slate-400" />
              <i className="size-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:120ms]" />
              <i className="size-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:240ms]" />
            </div>
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
