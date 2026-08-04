import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { cn } from '@/shared/lib/cn'

// 채팅 말풍선 전용 경량 마크다운 렌더러.
// 본문용 `WikiMarkdown`은 각주 툴팁·헤딩 번호·내부 위키 링크·머메이드까지 처리하는데,
// 채팅 메시지(수정 요청·에이전트 응답)엔 그런 요소가 나오지 않는다 — 딱 GFM 기본만 쓴다.
// 관리자 말풍선은 흰 글자, 에이전트 말풍선은 진한 글자라 링크·코드 색을 톤별로 나눈다.
export default function ChatMarkdown({ markdown, tone = 'agent' }) {
  const mine = tone === 'admin'

  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        p: ({ children }) => <p className="my-1.5 leading-6 first:mt-0 last:mb-0">{children}</p>,
        ul: ({ children }) => <ul className="my-1.5 list-disc space-y-0.5 pl-4">{children}</ul>,
        ol: ({ children }) => <ol className="my-1.5 list-decimal space-y-0.5 pl-4">{children}</ol>,
        li: ({ children }) => <li className="leading-6">{children}</li>,
        a: ({ href, children }) => (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className={cn('underline underline-offset-2', mine ? 'text-white' : 'text-primary-600')}
          >
            {children}
          </a>
        ),
        strong: ({ children }) => <strong className="font-bold">{children}</strong>,
        blockquote: ({ children }) => (
          <blockquote
            className={cn(
              'my-1.5 border-l-2 pl-3',
              mine ? 'border-white/40 text-white/90' : 'border-slate-300 text-slate-500',
            )}
          >
            {children}
          </blockquote>
        ),
        code: ({ children }) => (
          <code
            className={cn(
              'rounded px-1 py-0.5 text-[0.85em]',
              mine ? 'bg-white/20 text-white' : 'bg-slate-100 text-primary-700',
            )}
          >
            {children}
          </code>
        ),
        pre: ({ children }) => (
          <pre
            className={cn(
              'thin-scroll my-1.5 overflow-x-auto rounded-lg p-2.5 text-xs [&_code]:bg-transparent [&_code]:p-0 [&_code]:text-inherit',
              mine ? 'bg-white/15 text-white' : 'bg-slate-900 text-slate-100',
            )}
          >
            {children}
          </pre>
        ),
        table: ({ children }) => (
          <div className="thin-scroll my-1.5 overflow-x-auto">
            <table className="w-full border-collapse text-xs">{children}</table>
          </div>
        ),
        th: ({ children }) => (
          <th
            className={cn(
              'border px-2 py-1 text-left',
              mine ? 'border-white/30 bg-white/10' : 'border-slate-200 bg-slate-50',
            )}
          >
            {children}
          </th>
        ),
        td: ({ children }) => (
          <td className={cn('border px-2 py-1', mine ? 'border-white/30' : 'border-slate-200')}>{children}</td>
        ),
      }}
    >
      {markdown ?? ''}
    </ReactMarkdown>
  )
}
