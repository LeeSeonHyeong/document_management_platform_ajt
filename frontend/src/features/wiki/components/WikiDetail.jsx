import { useMemo, useState } from 'react'
import { Download, FileText, Bot } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Badge, Button, Tabs, Spinner, EmptyState, useToast } from '@/components/ui'
import { useAuth } from '@/hooks/useAuth'
import { ROLES } from '@/shared/constants/enums'
import { fetchDocumentFile } from '@/features/document/api'
import { useWiki, useWikis } from '../queries'
import WikiMarkdown from './WikiMarkdown'

function formatDateTime(iso) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })
}

// Figma 6R(관리자)·S2(사원) 우측 — Wiki 상세. 같은 컴포넌트를 role로 분기한다.
export default function WikiDetail({ wikiId }) {
  const { role } = useAuth()
  const isAdmin = role === ROLES.ADMIN
  const toast = useToast()
  const [tab, setTab] = useState('toc')

  const { data: wiki, isLoading, isError } = useWiki(wikiId)
  // 본문 내부 링크의 유효성 판정을 위해 같은 공간의 Wiki ID 집합을 준비한다.
  const { data: scopeWikiPage } = useWikis(wiki ? { scopeKey: wiki.scopeKey, size: 200 } : undefined)

  const validWikiIds = useMemo(() => {
    const ids = new Set()
    if (wiki) ids.add(String(wiki.wikiId))
    ;(scopeWikiPage?.items ?? []).forEach((w) => ids.add(String(w.wikiId)))
    ;(wiki?.relatedWikis ?? []).forEach((w) => ids.add(String(w.wikiId)))
    return ids
  }, [wiki, scopeWikiPage])

  async function handleDownload(documentId, fallbackName) {
    try {
      const { blob, fileName } = await fetchDocumentFile(documentId)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = fileName ?? fallbackName ?? 'document'
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      if (e?.response?.status === 403) toast.error('다운로드 권한이 없습니다.')
      else toast.error('다운로드에 실패했습니다.')
    }
  }

  if (isLoading) {
    return (
      <div className="flex flex-1 justify-center py-16">
        <Spinner />
      </div>
    )
  }
  if (isError || !wiki) {
    return <EmptyState title="Wiki를 찾을 수 없습니다" description="삭제되었거나 접근 권한이 없을 수 있습니다." />
  }

  const tabItems = [
    { value: 'toc', label: '본문·관련 문서' },
    ...(isAdmin ? [{ value: 'agent', label: 'AI 에이전트', icon: <Bot className="size-4" /> }] : []),
  ]

  return (
    <div className="min-w-0 flex-1">
      <header className="mb-4">
        <div className="flex items-center gap-2">
          <h1 className="text-2xl font-semibold text-slate-800">{wiki.title}</h1>
          {wiki.category && <Badge tone="neutral">{wiki.category.name}</Badge>}
        </div>
        <p className="mt-1 text-sm text-slate-400">수정 {formatDateTime(wiki.updatedAt)}</p>
      </header>

      <Tabs items={tabItems} value={tab} onChange={setTab} className="mb-4" />

      {tab === 'toc' && (
        <div className="space-y-6">
          <article>
            <WikiMarkdown markdown={wiki.contentMarkdown} validWikiIds={validWikiIds} />
          </article>

          <section>
            <h2 className="mb-2 text-sm font-semibold text-slate-700">원본 문서</h2>
            {wiki.evidenceDocuments?.length > 0 ? (
              <ul className="space-y-1.5">
                {wiki.evidenceDocuments.map((doc) => (
                  <li key={doc.documentId} className="flex items-center gap-2 rounded-lg border border-slate-200 px-3 py-2 text-sm">
                    <FileText className="size-4 shrink-0 text-slate-400" />
                    <span className="min-w-0 flex-1 truncate text-slate-700">{doc.originalFileName}</span>
                    {/* 원본문서 다운로드는 관리자만 (FR-DOC-016). 사원에겐 경로/URL을 노출하지 않는다. */}
                    {isAdmin && (
                      <Button size="sm" variant="ghost" onClick={() => handleDownload(doc.documentId, doc.originalFileName)}>
                        <Download className="size-4" />
                        다운로드
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-slate-400">연결된 원본 문서가 없습니다.</p>
            )}
          </section>

          {wiki.relatedWikis?.length > 0 && (
            <section>
              <h2 className="mb-2 text-sm font-semibold text-slate-700">연관 Wiki</h2>
              <ul className="space-y-1">
                {wiki.relatedWikis.map((w) => (
                  <li key={w.wikiId}>
                    <Link to={`/wiki/${w.wikiId}`} className="text-sm text-primary-600 underline-offset-2 hover:underline">
                      {w.title}
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      )}

      {tab === 'agent' && isAdmin && (
        // Jira -75 (브랜치 10)에서 에이전트 채팅으로 채운다. 지금은 빈 탭.
        <div className="py-12 text-center text-sm text-slate-400">AI 에이전트 기능은 준비 중입니다.</div>
      )}
    </div>
  )
}
