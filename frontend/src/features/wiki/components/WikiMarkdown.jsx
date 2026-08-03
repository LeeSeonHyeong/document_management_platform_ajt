import { Children, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { headingId, headingNumberMap } from '../headings'
import MermaidBlock from './MermaidBlock'

// 본문 내부 링크 패턴: pages/{wikiId}.md (상대/절대 경로 접두 허용)
const INTERNAL_LINK = /(?:^|\/)pages\/([^/]+)\.md$/

// 커스텀 헤딩 컴포넌트가 받는 children(React 노드)에서 순수 텍스트만 뽑는다.
// 목차와 같은 규칙(headingId)으로 id 를 만들기 위한 것이다.
function headingText(children) {
  return Children.toArray(children)
    .map((child) =>
      typeof child === 'string'
        ? child
        : child?.props?.children
          ? headingText(child.props.children)
          : '',
    )
    .join('')
}

// Wiki 본문 마크다운 렌더러.
// - pages/{wikiId}.md 내부 링크 → 새 페이지 대신 같은 화면에서 react-router로 해당 Wiki 상세 전환
// - 외부 링크(http/https) → 새 탭
// - 존재하지 않는 wikiId 링크 → 클릭 불가 + 안내
export default function WikiMarkdown({ markdown, validWikiIds }) {
  const navigate = useNavigate()
  // 목차와 동일한 계층 번호를 본문 헤딩에도 붙인다(id → 번호).
  const numberMap = useMemo(() => headingNumberMap(markdown), [markdown])

  const components = useMemo(() => {
    function Anchor({ href = '', children }) {
      const internal = INTERNAL_LINK.exec(href)
      if (internal) {
        const wikiId = internal[1]
        const exists = validWikiIds.has(String(wikiId))
        if (!exists) {
          return (
            <span
              className="cursor-not-allowed text-slate-400 line-through"
              title="존재하지 않거나 접근할 수 없는 Wiki입니다"
            >
              {children}
            </span>
          )
        }
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

    // react-markdown v9는 커스텀 컴포넌트에 hast `node`를 넘긴다. DOM에 spread되면 경고가 나므로
    // 필요한 값(children, GFM 표 정렬용 style)만 받는다.
    return {
      a: Anchor,
      h1: ({ children }) => (
        <h1 id={headingId(headingText(children))} className="mb-3 mt-6 scroll-mt-4 text-2xl font-bold text-slate-800">
          {children}
        </h1>
      ),
      h2: ({ children }) => {
        const id = headingId(headingText(children))
        return (
          <h2 id={id} className="mb-2 mt-5 scroll-mt-4 text-xl font-semibold text-slate-800">
            {numberMap[id] && <span className="mr-2 font-bold text-slate-400">{numberMap[id]}</span>}
            {children}
          </h2>
        )
      },
      h3: ({ children }) => {
        const id = headingId(headingText(children))
        return (
          <h3 id={id} className="mb-2 mt-4 scroll-mt-4 text-lg font-semibold text-slate-800">
            {numberMap[id] && <span className="mr-2 font-bold text-slate-400">{numberMap[id]}</span>}
            {children}
          </h3>
        )
      },
      p: ({ children }) => <p className="my-3 leading-7 text-slate-700">{children}</p>,
      ul: ({ children }) => <ul className="my-3 list-disc space-y-1 pl-6 text-slate-700">{children}</ul>,
      ol: ({ children }) => <ol className="my-3 list-decimal space-y-1 pl-6 text-slate-700">{children}</ol>,
      blockquote: ({ children }) => (
        <blockquote className="my-3 border-l-4 border-slate-200 pl-4 text-slate-600">{children}</blockquote>
      ),
      code: ({ children }) => (
        <code className="rounded bg-slate-100 px-1 py-0.5 text-[0.85em] text-primary-700">{children}</code>
      ),
      pre: ({ children }) => {
        // 코드블록의 언어가 mermaid 면 다이어그램으로 렌더한다. (그 외 코드는 기존 코드박스)
        const codeElement = Array.isArray(children) ? children[0] : children
        const codeClassName = codeElement?.props?.className ?? ''
        if (/\blanguage-mermaid\b/.test(codeClassName)) {
          const raw = codeElement.props.children
          const code = (Array.isArray(raw) ? raw.join('') : String(raw ?? '')).replace(/\n$/, '')
          return <MermaidBlock code={code} />
        }
        return (
          <pre className="my-3 overflow-x-auto rounded-lg bg-slate-900 p-4 text-sm text-slate-100 [&_code]:bg-transparent [&_code]:p-0 [&_code]:text-inherit">
            {children}
          </pre>
        )
      },
      table: ({ children }) => (
        <div className="my-3 overflow-x-auto">
          <table className="w-full border-collapse text-sm">{children}</table>
        </div>
      ),
      th: ({ children, style }) => (
        <th style={style} className="border border-slate-200 bg-slate-50 px-3 py-1.5 text-left">{children}</th>
      ),
      td: ({ children, style }) => (
        <td style={style} className="border border-slate-200 px-3 py-1.5">{children}</td>
      ),
    }
  }, [navigate, validWikiIds, numberMap])

  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
      {markdown ?? ''}
    </ReactMarkdown>
  )
}
