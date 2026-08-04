import { Children, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkFrontmatter from 'remark-frontmatter'
import { headingId, headingNumberMap } from '../headings'
import { collectFootnotes, footnoteLabelFromHref } from '../footnotes'
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
// 각주 번호에 올렸을 때 근거를 보여준다. **이 제품의 핵심 가치가 근거 확인**인데, 지금까지는
// 번호를 누르면 페이지 맨 아래로 점프하는 것이 전부였다 — 읽던 자리를 잃고 되돌아와야 했다.
// 원문 인용문은 본문 각주 정의에만 있다(`../footnotes` 주석 참고).
function FootnoteRef({ note, children, ...rest }) {
  if (!note) return <a {...rest}>{children}</a>

  return (
    <span className="group relative inline-block">
      <a {...rest}>{children}</a>
      <span
        role="tooltip"
        className="pointer-events-none absolute right-0 bottom-full z-20 mb-2 hidden w-max max-w-[22rem] rounded-lg bg-slate-900/95 px-3 py-2 text-left text-xs leading-relaxed font-normal text-white shadow-lg group-hover:block group-focus-within:block"
      >
        {note.quote && <span className="block text-slate-100">“{note.quote}”</span>}
        <span className={`block text-slate-400 ${note.quote ? 'mt-1' : ''}`}>
          {[note.source, note.location].filter(Boolean).join(' · ')}
        </span>
      </span>
    </span>
  )
}

export default function WikiMarkdown({ markdown, validWikiIds }) {
  const navigate = useNavigate()
  // 목차와 동일한 계층 번호를 본문 헤딩에도 붙인다(id → 번호).
  const numberMap = useMemo(() => headingNumberMap(markdown), [markdown])
  // 각주 라벨 → 근거(파일명·위치·인용문). 툴팁이 이것을 읽는다.
  const footnotes = useMemo(() => collectFootnotes(markdown), [markdown])

  const components = useMemo(() => {
    function Anchor({ href = '', children, ...rest }) {
      // 각주 참조(`[^1]`)는 위키 링크가 아니다 — 근거 툴팁을 붙여 읽던 자리에서 확인하게 한다.
      const footnote = footnoteLabelFromHref(href)
      if (footnote) {
        return (
          <FootnoteRef
            note={footnotes[footnote]}
            href={href}
            id={rest.id}
            className="text-primary-600 no-underline hover:underline"
          >
            {children}
          </FootnoteRef>
        )
      }

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
      h2: ({ children, className }) => {
        // 각주 묶음 제목은 본문 절 제목이 아니다 — 작고 옅은 라벨로 낸다.
        if (String(className ?? '').includes('wiki-footnote-label')) {
          return (
            <h2 className="mb-2 text-xs font-semibold tracking-wide text-slate-400">{children}</h2>
          )
        }
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
      // 각주 참조 번호. 인쇄물처럼 작게 올려 본문 흐름을 끊지 않게 한다.
      sup: ({ children }) => (
        <sup className="ml-0.5 align-super text-[0.65em] leading-none font-medium">{children}</sup>
      ),
      // 각주 묶음: 위에 실선을 그어 본문과 끊고, 본문보다 작고 옅게 둔다.
      section: ({ children, ...rest }) => {
        if (!('data-footnotes' in rest)) return <section>{children}</section>
        return (
          <section
            data-footnotes
            className="mt-10 border-t border-slate-200 pt-4 text-[13px] leading-relaxed text-slate-500 [&_a]:text-slate-400 [&_li]:marker:text-slate-400 [&_ol]:my-0 [&_ol]:space-y-1.5 [&_p]:my-0"
          >
            {children}
          </section>
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
          <pre className="thin-scroll my-3 overflow-x-auto rounded-lg bg-slate-900 p-4 text-sm text-slate-100 [&_code]:bg-transparent [&_code]:p-0 [&_code]:text-inherit">
            {children}
          </pre>
        )
      },
      // 표는 본문 폭을 넘어도 된다 — 수치 표가 좁은 단에 갇히면 줄바꿈으로 읽기 어려워진다.
      // `tabular-nums` 로 숫자 폭을 고정해 열이 흔들리지 않게 한다.
      table: ({ children }) => (
        <div className="thin-scroll my-3 overflow-x-auto">
          <table className="w-full border-collapse text-sm">{children}</table>
        </div>
      ),
      // 6행 넘는 표가 흔해 행 구분이 테두리뿐이면 눈이 미끄러진다. 얼룩을 넣는다.
      tbody: ({ children }) => <tbody className="[&>tr:nth-child(even)]:bg-slate-50/60">{children}</tbody>,
      th: ({ children, style }) => (
        <th style={style} className="border border-slate-200 bg-slate-50 px-3 py-1.5 text-left">{children}</th>
      ),
      td: ({ children, style }) => (
        <td style={style} className="border border-slate-200 px-3 py-1.5 tabular-nums">{children}</td>
      ),
    }
  }, [navigate, validWikiIds, numberMap, footnotes])

  return (
    <ReactMarkdown
      // `remarkFrontmatter` 가 없으면 본문 맨 앞의 `---` YAML 블록이 frontmatter 로 파싱되지
      // 않아 **제목처럼 렌더링된다** — `title: ... description: ... tags: [...]` 가 큰 글씨로
      // 화면에 찍혔다. 이 플러그인이 그 블록을 yaml 노드로 만들고, 렌더러가 없어 버려진다.
      remarkPlugins={[remarkGfm, remarkFrontmatter]}
      // 각주 묶음 앞에 remark 가 넣는 기본 제목이 영문 `Footnotes` 였다 — 한국어 화면에 그것만
      // 튀었다. 저장된 본문에는 없는 글자이므로(생성 위키 전수 확인) 여기서 말을 정한다.
      remarkRehypeOptions={{
        footnoteLabel: '출처',
        // 각주 묶음 제목에 표식을 달아 본문 `h2` 와 구분한다 — 본문 제목 크기를 그대로
        // 물려받으면 각주가 새 절처럼 보인다.
        footnoteLabelProperties: { className: ['wiki-footnote-label'] },
        footnoteBackLabel: '본문으로 돌아가기',
      }}
      components={components}
    >
      {markdown ?? ''}
    </ReactMarkdown>
  )
}
