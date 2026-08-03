import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { FileText } from 'lucide-react'
import { Spinner } from '@/components/ui'
import { useWiki } from '../queries'
import { extractHeadings, numberHeadings } from '../headings'
import WikiAgentChat from './WikiAgentChat'
import WikiSourcePreviewModal from './WikiSourcePreviewModal'

// 목차 클릭 → 본문의 같은 id 헤딩으로 스크롤. 본문은 별도 컬럼(스크롤 컨테이너)이라
// DOM id 로 찾아 scrollIntoView 한다. scroll-mt-* 로 상단 여백을 준다.
function scrollToHeading(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

export default function WikiAdminPanel({ wikiId, showEditor = true }) {
  const [tab, setTab] = useState('related')
  const [previewDoc, setPreviewDoc] = useState(null)
  const { data: wiki, isLoading } = useWiki(wikiId)
  // H1(문서 제목)도 목차에 표시하되 번호는 매기지 않고, H2를 최상위 번호로 본다(A안).
  const headings = useMemo(
    () => numberHeadings(extractHeadings(wiki?.contentMarkdown)),
    [wiki?.contentMarkdown],
  )

  if (isLoading) {
    return (
      <aside
        className="flex justify-center rounded-2xl border border-slate-200 bg-white py-12"
        style={{ flexGrow: 0, flexShrink: 100, flexBasis: '18rem', minWidth: '13rem' }}
      >
        <Spinner size="sm" />
      </aside>
    )
  }

  if (!wiki) return null

  return (
    <aside
      className="flex h-full min-h-0 flex-col gap-3"
      style={{ flexGrow: 0, flexShrink: 100, flexBasis: '18rem', minWidth: '13rem' }}
    >
      {showEditor && (
        <div className="grid shrink-0 grid-cols-2 rounded-xl bg-white p-1">
          <button
            type="button"
            onClick={() => setTab('related')}
            className={`focus-ring rounded-lg px-2 py-2 text-xs font-semibold ${
              tab === 'related'
                ? 'bg-gradient-to-r from-blue-500 to-violet-600 text-white'
                : 'text-slate-400'
            }`}
          >
            목차·관련 문서
          </button>
          <button
            type="button"
            onClick={() => setTab('agent')}
            className={`focus-ring rounded-lg px-2 py-2 text-xs font-semibold ${
              tab === 'agent'
                ? 'bg-gradient-to-r from-blue-500 to-violet-600 text-white'
                : 'text-slate-400'
            }`}
          >
            AI 문서 편집
          </button>
        </div>
      )}

      {!showEditor || tab === 'related' ? (
        <div className="flex min-h-0 flex-1 flex-col gap-3">
          <PanelCard title="목차" grow={2}>
            {headings.length > 0 ? (
              <ol className="space-y-1.5">
                {headings.map((heading, index) => (
                  <li
                    key={`${heading.id}-${index}`}
                    style={{ marginLeft: `${(heading.level - 1) * 12}px` }}
                  >
                    <button
                      type="button"
                      onClick={() => scrollToHeading(heading.id)}
                      className={`focus-ring block w-full rounded-lg px-3 py-2 text-left transition-colors hover:bg-primary-50 ${
                        heading.level === 1
                          ? 'text-xs font-semibold text-slate-700 hover:text-primary-600'
                          : 'text-xs text-slate-500 hover:text-primary-600'
                      }`}
                    >
                      {heading.number && (
                        <span className="mr-1.5 font-semibold text-slate-400">{heading.number}</span>
                      )}
                      {heading.label}
                    </button>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="text-xs text-slate-400">표시할 목차가 없습니다.</p>
            )}
          </PanelCard>

          <PanelCard
            title="출처 원본"
            subtitle="이 위키를 생성할 때 참고한 원본 문서"
            count={wiki.evidenceDocuments?.length ?? 0}
          >
            {wiki.evidenceDocuments?.length > 0 ? (
              <ul className="space-y-2">
                {wiki.evidenceDocuments.map((document) => (
                  <li key={document.documentId}>
                    <button
                      type="button"
                      onClick={() => setPreviewDoc(document)}
                      className="focus-ring flex w-full items-center gap-2 rounded-xl bg-slate-50 px-3 py-3 text-left text-xs text-slate-500 hover:bg-primary-50"
                    >
                      <span className="flex size-6 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-500">
                        <FileText className="size-3.5" />
                      </span>
                      <span className="min-w-0 flex-1 truncate">{document.originalFileName}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs text-slate-400">연결된 문서가 없습니다.</p>
            )}
          </PanelCard>

          <PanelCard title="관련 위키" count={wiki.relatedWikis?.length ?? 0}>
            {wiki.relatedWikis?.length > 0 ? (
              <ul className="space-y-2">
                {wiki.relatedWikis.map((relatedWiki) => (
                  <li key={relatedWiki.wikiId}>
                    <Link
                      to={`/wiki/${relatedWiki.wikiId}`}
                      className="focus-ring block truncate rounded-xl bg-slate-50 px-3 py-3 text-xs font-medium text-slate-600 hover:bg-violet-50 hover:text-violet-600"
                    >
                      {relatedWiki.title}
                    </Link>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs text-slate-400">관련 위키가 없습니다.</p>
            )}
          </PanelCard>
        </div>
      ) : (
        <WikiAgentChat wikiId={wikiId} />
      )}

      <WikiSourcePreviewModal
        open={Boolean(previewDoc)}
        evidenceDocument={previewDoc}
        onClose={() => setPreviewDoc(null)}
      />
    </aside>
  )
}

// grow: 세 박스가 세로 공간을 나눠 갖는 비율(flex-grow). flexBasis 0 과 함께 써서 내용 길이와
// 무관하게 화면을 일정 비율로 고정한다. 각 박스는 헤더 고정 + 본문 개별 스크롤이다.
function PanelCard({ title, subtitle, count, grow = 1, children }) {
  return (
    <section
      className="flex min-h-0 flex-col rounded-2xl border border-slate-200 bg-white p-4"
      style={{ flexGrow: grow, flexBasis: 0 }}
    >
      <div className="mb-3 shrink-0">
        <div className="flex items-center justify-between">
          <h2 className="text-[15px] font-bold text-slate-800">{title}</h2>
          {count != null && <span className="text-xs font-semibold text-slate-400">{count}</span>}
        </div>
        {subtitle && <p className="mt-1 text-xs text-slate-400">{subtitle}</p>}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto pr-1">{children}</div>
    </section>
  )
}
