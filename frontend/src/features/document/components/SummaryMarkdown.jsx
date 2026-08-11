import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

// AI 요약이 쓰는 내부 링크 패턴. WikiMarkdown 과 같은 규칙이다.
const INTERNAL_LINK = /(?:^|\/)pages\/([^/]+)\.md$/

/**
 * AI 작업 요약(result.summary) 렌더러.
 *
 * 요약은 표·목록·링크가 섞인 마크다운으로 온다. 본문용 WikiMarkdown 을 쓰지 않는 이유는
 * 그쪽이 목차 번호·mermaid·본문 크기 타이포까지 얹기 때문이다 — 모달 안에서는 과하다.
 * 여기서는 모달 폭에 맞는 작은 타이포만 준다.
 */
export default function SummaryMarkdown({ markdown }) {
  const navigate = useNavigate()

  const components = useMemo(() => {
    function Anchor({ href = '', children }) {
      const internal = INTERNAL_LINK.exec(href)
      if (internal) {
        const wikiId = internal[1]
        return (
          <a
            href={`/wiki/${wikiId}`}
            onClick={(e) => {
              e.preventDefault()
              navigate(`/wiki/${wikiId}`)
            }}
            className="text-primary-600 underline-offset-2 hover:underline"
          >
            {children}
          </a>
        )
      }
      const isExternal = /^https?:\/\//.test(href)
      return (
        <a
          href={href}
          target={isExternal ? '_blank' : undefined}
          rel={isExternal ? 'noopener noreferrer' : undefined}
          className="text-primary-600 underline-offset-2 hover:underline"
        >
          {children}
        </a>
      )
    }

    return {
      a: Anchor,
      // 요약의 헤딩은 문서 제목이 아니라 구획 표시다. 모달 안에서는 한 단계로 눌러 쓴다.
      h1: ({ children }) => <p className="mt-3 text-sm font-semibold text-slate-800">{children}</p>,
      h2: ({ children }) => <p className="mt-3 text-sm font-semibold text-slate-800">{children}</p>,
      h3: ({ children }) => <p className="mt-3 text-sm font-semibold text-slate-800">{children}</p>,
      p: ({ children }) => <p className="my-2 text-sm leading-7 text-slate-700">{children}</p>,
      ul: ({ children }) => <ul className="my-2 list-disc space-y-1 pl-5 text-sm text-slate-700">{children}</ul>,
      ol: ({ children }) => <ol className="my-2 list-decimal space-y-1 pl-5 text-sm text-slate-700">{children}</ol>,
      strong: ({ children }) => <strong className="font-semibold text-slate-800">{children}</strong>,
      code: ({ children }) => (
        <code className="rounded bg-slate-100 px-1 py-0.5 text-[0.85em] text-primary-700">{children}</code>
      ),
      pre: ({ children }) => (
        <pre className="my-2 overflow-x-auto rounded-lg bg-slate-900 p-3 text-xs text-slate-100 [&_code]:bg-transparent [&_code]:p-0 [&_code]:text-inherit">
          {children}
        </pre>
      ),
      // 요약의 표는 열이 많아 모달 폭을 넘긴다. 표만 가로로 스크롤시켜 모달은 안 넘치게 둔다.
      table: ({ children }) => (
        <div className="my-2 overflow-x-auto">
          <table className="w-full border-collapse text-xs">{children}</table>
        </div>
      ),
      th: ({ children, style }) => (
        <th style={style} className="whitespace-nowrap border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-left font-semibold text-slate-600">
          {children}
        </th>
      ),
      td: ({ children, style }) => (
        <td style={style} className="border border-slate-200 px-2.5 py-1.5 text-slate-700">{children}</td>
      ),
    }
  }, [navigate])

  return (
    <ReactMarkdown remarkPlugins={[[remarkGfm, { singleTilde: false }]]} components={components}>
      {markdown ?? ''}
    </ReactMarkdown>
  )
}
