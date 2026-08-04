import { useMemo, useState } from 'react'
import { Badge, EmptyState } from '@/components/ui'
import { useWiki, useWikis } from '../queries'
import WikiMarkdown from './WikiMarkdown'
import WikiRelationGraph from './WikiRelationGraph'
import WikiSourcePreviewModal from './WikiSourcePreviewModal'

function formatDate(iso) {
  if (!iso) return '-'
  const date = new Date(iso)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export default function WikiDetail({ wikiId }) {
  const [previewDoc, setPreviewDoc] = useState(null)
  const { data: wiki, isLoading, isError } = useWiki(wikiId)
  const { data: scopeWikiPage } = useWikis(wiki ? { scopeKey: wiki.scopeKey, size: 100 } : undefined)

  const currentListItem = (scopeWikiPage?.items ?? []).find(
    (item) => String(item.wikiId) === String(wikiId),
  )
  const validWikiIds = useMemo(() => {
    const ids = new Set()
    if (wiki) ids.add(String(wiki.wikiId))
    ;(scopeWikiPage?.items ?? []).forEach((item) => ids.add(String(item.wikiId)))
    ;(wiki?.relatedWikis ?? []).forEach((item) => ids.add(String(item.wikiId)))
    return ids
  }, [wiki, scopeWikiPage])

  // 실제 레이아웃(제목·메타·본문 문단) 모양을 흉내낸다 — 빙글빙글 도는 스피너보다
  // "곧 이 모양으로 채워진다"는 정보를 더 준다.
  if (isLoading) {
    return (
      <div className="flex min-w-0 flex-1 animate-pulse flex-col">
        <div className="border-b border-slate-200 pb-5">
          <div className="h-3 w-36 rounded-full bg-slate-200" />
          <div className="mt-4 h-8 w-2/3 rounded-lg bg-slate-200" />
          <div className="mt-4 h-16 rounded-xl bg-slate-100" />
        </div>
        <div className="mt-6 max-w-[72ch] space-y-3">
          <div className="h-4 w-full rounded bg-slate-100" />
          <div className="h-4 w-11/12 rounded bg-slate-100" />
          <div className="h-4 w-4/5 rounded bg-slate-100" />
          <div className="h-4 w-full rounded bg-slate-100" />
          <div className="h-4 w-3/4 rounded bg-slate-100" />
        </div>
      </div>
    )
  }

  if (isError || !wiki) {
    return (
      <EmptyState
        title="위키를 찾을 수 없습니다"
        description="삭제되었거나 접근 권한이 없는 문서일 수 있습니다."
      />
    )
  }

  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <div className="border-b border-slate-200 pb-5">
        <p className="flex min-w-0 items-center gap-1.5 text-xs font-medium text-slate-400">
          <span className="shrink-0">위키</span>
          <span className="shrink-0 text-slate-300">/</span>
          <span className="shrink-0">{wiki.category?.name ?? '미분류'}</span>
          <span className="shrink-0 text-slate-300">/</span>
          <span className="min-w-0 truncate text-primary-600">{wiki.title}</span>
        </p>
        <h1 className="mt-4 truncate text-3xl font-bold tracking-tight text-slate-900">{wiki.title}</h1>
        <div className="mt-4 rounded-xl bg-slate-50 px-4 py-3">
          <p className="text-sm text-slate-500">
            {currentListItem?.summary ?? `${wiki.title}에 관한 사내 기준과 내용을 정리한 문서입니다.`}
          </p>
          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-400">
            {wiki.category && <Badge tone="neutral">{wiki.category.name}</Badge>}
            <span className="rounded-full bg-primary-50 px-2 py-1 text-primary-600">위키</span>
            <span className="ml-auto">작성일 {formatDate(wiki.updatedAt)}</span>
          </div>
        </div>
      </div>

      <WikiRelationGraph
        wikiId={wikiId}
        height={220}
        className="mt-6"
        onDocumentClick={setPreviewDoc}
      />

      {/* 본문 폭을 제한한다. 넓은 화면에서 한 줄이 100자를 넘어가면 눈이 줄을 놓친다 —
          읽기 편한 한 줄은 65자 안팎이다. 표·다이어그램은 아래에서 폭을 되찾는다. */}
      <article className="min-w-0 max-w-[72ch] flex-1 py-1">
        <WikiMarkdown markdown={wiki.contentMarkdown} validWikiIds={validWikiIds} />
      </article>

      <WikiSourcePreviewModal
        open={Boolean(previewDoc)}
        evidenceDocument={previewDoc}
        onClose={() => setPreviewDoc(null)}
      />
    </div>
  )
}
