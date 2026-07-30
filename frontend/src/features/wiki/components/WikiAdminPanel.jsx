import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { FileText } from 'lucide-react'
import { Spinner } from '@/components/ui'
import { useWiki } from '../queries'
import WikiAgentChat from './WikiAgentChat'
import WikiSourcePreviewModal from './WikiSourcePreviewModal'

function extractHeadings(markdown = '') {
  return markdown
    .split('\n')
    .map((line) => /^(#{1,3})\s+(.+)$/.exec(line))
    .filter(Boolean)
    .map((match) => ({ level: match[1].length, label: match[2].replace(/[*_`]/g, '') }))
}

export default function WikiAdminPanel({ wikiId, showEditor = true }) {
  const [tab, setTab] = useState('related')
  const [previewDoc, setPreviewDoc] = useState(null)
  const { data: wiki, isLoading } = useWiki(wikiId)
  const headings = useMemo(() => extractHeadings(wiki?.contentMarkdown), [wiki?.contentMarkdown])

  if (isLoading) {
    return (
      <aside className="flex w-72 shrink-0 justify-center rounded-2xl border border-slate-200 bg-white py-12">
        <Spinner size="sm" />
      </aside>
    )
  }

  if (!wiki) return null

  return (
    <aside className={`flex w-72 shrink-0 flex-col gap-3 ${showEditor ? 'pb-24' : ''}`}>
      {showEditor && (
        <div className="grid grid-cols-2 rounded-xl bg-white p-1">
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
        <>
          <PanelCard title="목차">
            {headings.length > 0 ? (
              <ol className="space-y-1.5">
                {headings.map((heading, index) => (
                  <li
                    key={`${heading.label}-${index}`}
                    className={`rounded-lg px-3 py-2 text-xs ${
                      index === 0 ? 'bg-primary-50 font-semibold text-primary-600' : 'text-slate-500'
                    }`}
                    style={{ marginLeft: `${Math.max(0, heading.level - 1) * 8}px` }}
                  >
                    {index + 1}. {heading.label}
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
        </>
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

function PanelCard({ title, subtitle, count, children }) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-4">
      <div className="mb-3">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-bold text-slate-800">{title}</h2>
          {count != null && <span className="text-xs font-semibold text-slate-400">{count}</span>}
        </div>
        {subtitle && <p className="mt-1 text-xs text-slate-400">{subtitle}</p>}
      </div>
      {children}
    </section>
  )
}
