import { useEffect, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ChevronDown, ChevronUp, Send, Sparkles, X } from 'lucide-react'
import ColumnResizer from '@/features/wiki/components/ColumnResizer'
import ChatMarkdown from '@/features/wiki/components/ChatMarkdown'
import { askQuestion } from '../api'

// 대화창 폭(px). 최소 390, 최대 화면 절반(50vw). 조정값은 localStorage로 유지한다.
// 패널이 우측에 고정돼 있어 왼쪽 가장자리 손잡이를 왼쪽으로 끌면 넓어진다(ColumnResizer invert).
const MIN_WIDTH = 390
const WIDTH_STORAGE_KEY = 'chat-assistant-width'
const maxWidth = () => Math.max(MIN_WIDTH, Math.floor(window.innerWidth / 2))
const clampWidth = (value) => Math.min(Math.max(value, MIN_WIDTH), maxWidth())

const WELCOME_MESSAGE = {
  id: 'welcome',
  role: 'assistant',
  content: '안녕하세요! 사내 위키와 일정에 대해 궁금한 내용을 물어보세요.',
  sources: [],
}

function SourceCard({ source }) {
  const [open, setOpen] = useState(false)
  const isWiki = source.type === 'wiki'
  return (
    <div className="mt-2 overflow-hidden rounded-lg border border-primary-100 bg-primary-50/60">
      <button type="button" onClick={() => isWiki && setOpen((value) => !value)} className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left">
        <span>
          <span className="block text-xs font-semibold text-primary-700">{source.title}</span>
          <span className="text-[11px] text-slate-400">{isWiki ? '위키 출처' : '일정 출처'}</span>
        </span>
        {isWiki && (open ? <ChevronUp className="size-3.5 text-slate-400" /> : <ChevronDown className="size-3.5 text-slate-400" />)}
      </button>
      {open && source.evidenceDocuments?.length > 0 && (
        <div className="border-t border-primary-100 px-3 py-2">
          {source.evidenceDocuments.map((document) => (
            <a key={document.documentId} href={document.downloadUrl} className="block truncate text-[11px] text-primary-600 hover:underline">
              원본문서 · {document.originalFileName}
            </a>
          ))}
        </div>
      )}
    </div>
  )
}

function MessageBubble({ message }) {
  const mine = message.role === 'user'
  return (
    <div className={`flex ${mine ? 'justify-end' : 'justify-start gap-2'}`}>
      {!mine && <span className="mt-1 flex size-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-primary-500 to-violet-600 text-white"><Sparkles className="size-4" /></span>}
      <div className={`max-w-[82%] rounded-2xl px-4 py-3 text-sm leading-6 ${mine ? 'rounded-br-md bg-slate-200 text-slate-800' : 'rounded-bl-md border border-slate-200 bg-white text-slate-700 shadow-sm'}`}>
        <ChatMarkdown markdown={message.content} tone="agent" />
        {message.sources?.map((source) => <SourceCard key={`${source.type}-${source.wikiId ?? source.scheduleId}`} source={source} />)}
      </div>
    </div>
  )
}

export default function ChatAssistant() {
  const messagesEndRef = useRef(null)
  const [open, setOpen] = useState(false)
  const [question, setQuestion] = useState('')
  const [conversationId, setConversationId] = useState(null)
  const [messages, setMessages] = useState([WELCOME_MESSAGE])
  const [width, setWidth] = useState(() => {
    const stored = Number(globalThis.localStorage?.getItem(WIDTH_STORAGE_KEY))
    return clampWidth(Number.isFinite(stored) && stored > 0 ? stored : MIN_WIDTH)
  })
  const widthRef = useRef(width)
  widthRef.current = width
  const persistWidth = (value) => globalThis.localStorage?.setItem(WIDTH_STORAGE_KEY, String(value))

  const mutation = useMutation({
    mutationFn: (text) => askQuestion(text, conversationId),
    onSuccess: (data) => {
      setConversationId(data.conversationId)
      setMessages((current) => [...current, {
        id: data.questionId,
        role: 'assistant',
        content: data.answer,
        sources: data.sources ?? [],
      }])
    },
    onError: (error) => {
      setMessages((current) => [...current, {
        id: `error-${Date.now()}`,
        role: 'assistant',
        content: error.message ?? '답변을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.',
        sources: [],
      }])
    },
  })

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, mutation.isPending])

  const submit = () => {
    const text = question.trim()
    if (!text || mutation.isPending) return
    setMessages((current) => [...current, { id: `user-${Date.now()}`, role: 'user', content: text, sources: [] }])
    setQuestion('')
    mutation.mutate(text)
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label="AI 검색 어시스턴트 열기"
        className={`focus-ring fixed bottom-7 right-7 z-30 flex size-14 items-center justify-center rounded-full bg-gradient-to-br from-blue-500 to-violet-600 text-white shadow-lg transition duration-300 hover:scale-105 ${open ? 'pointer-events-none scale-75 opacity-0' : 'scale-100 opacity-100'}`}
      >
        <Sparkles className="size-6" />
      </button>

      <div
        aria-hidden="true"
        onClick={() => setOpen(false)}
        className={`fixed inset-0 z-40 bg-slate-900/25 backdrop-blur-[1px] transition-opacity duration-300 ${open ? 'pointer-events-auto opacity-100' : 'pointer-events-none opacity-0'}`}
      />

      <aside
        aria-label="AI 검색 어시스턴트"
        aria-hidden={!open}
        style={{ width }}
        className={`fixed bottom-4 right-4 top-4 z-50 flex max-w-[50vw] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-slate-50 shadow-2xl transition-transform duration-300 ease-out ${open ? 'translate-x-0' : 'translate-x-[calc(100%+2rem)]'}`}
      >
        <ColumnResizer
          label="어시스턴트 폭 조절"
          value={width}
          min={MIN_WIDTH}
          max={maxWidth()}
          invert
          onChange={(next) => setWidth(clampWidth(next))}
          onCommit={() => persistWidth(widthRef.current)}
          onReset={() => { setWidth(MIN_WIDTH); persistWidth(MIN_WIDTH) }}
          className="absolute inset-y-0 left-0 z-10"
        />
        <header className="flex h-[76px] shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-4">
          <span className="flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-violet-600 text-white"><Sparkles className="size-5" /></span>
          <div className="min-w-0 flex-1">
            <h2 className="font-bold text-slate-900">AI 검색 어시스턴트</h2>
            <p className="text-xs text-slate-400">사내 위키와 일정 기반으로 답변해요</p>
          </div>
          <button type="button" onClick={() => setOpen(false)} className="focus-ring rounded-full bg-slate-100 p-2 text-slate-400 hover:bg-slate-200 hover:text-slate-600" aria-label="AI 어시스턴트 닫기"><X className="size-4" /></button>
        </header>

        <div className="flex-1 space-y-4 overflow-y-auto bg-gradient-to-b from-blue-50/80 via-indigo-50/70 to-violet-50/80 p-4">
          {messages.map((message) => <MessageBubble key={message.id} message={message} />)}
          {mutation.isPending && (
            <div className="flex items-center gap-2 text-xs text-slate-400">
              <span className="flex size-7 items-center justify-center rounded-lg bg-primary-500 text-white"><Sparkles className="size-4" /></span>
              <span className="flex gap-1"><i className="size-1.5 animate-bounce rounded-full bg-slate-400" /><i className="size-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:120ms]" /><i className="size-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:240ms]" /></span>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>
        <div className="border-t border-slate-200 bg-white p-4">
          <div className="flex gap-2">
            <input
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); submit() } }}
              placeholder="질문을 입력하세요..."
              className="focus-ring min-w-0 flex-1 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm"
            />
            <button type="button" onClick={submit} disabled={!question.trim() || mutation.isPending} className="focus-ring flex size-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-violet-600 text-white disabled:opacity-40" aria-label="질문 보내기"><Send className="size-5" /></button>
          </div>
        </div>
      </aside>
    </>
  )
}
