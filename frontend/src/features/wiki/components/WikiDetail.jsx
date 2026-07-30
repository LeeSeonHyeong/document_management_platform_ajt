import { useMemo } from 'react'
import { Badge, EmptyState, Spinner } from '@/components/ui'
import { useWiki, useWikis } from '../queries'
import WikiMarkdown from './WikiMarkdown'

function formatDate(iso) {
  if (!iso) return '-'
  const date = new Date(iso)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export default function WikiDetail({ wikiId }) {
  const { data: wiki, isLoading, isError } = useWiki(wikiId)
  const { data: scopeWikiPage } = useWikis(wiki ? { scopeKey: wiki.scopeKey, size: 200 } : undefined)

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

  if (isLoading) {
    return (
      <div className="flex flex-1 justify-center py-16">
        <Spinner />
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
        <p className="text-xs font-medium text-slate-400">
          위키　/　{wiki.category?.name ?? '미분류'}　/　
          <span className="text-primary-600">{wiki.title}</span>
        </p>
        <div className="mt-4 flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 className="truncate text-2xl font-bold text-slate-900">{wiki.title}</h1>
          </div>
        </div>
        <div className="mt-4 rounded-xl bg-slate-50 px-4 py-3">
          <p className="text-sm text-slate-500">
            {currentListItem?.summary ?? `${wiki.title}에 관한 사내 기준과 내용을 정리한 문서입니다.`}
          </p>
          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-400">
            <span>작성일 {formatDate(wiki.updatedAt)}</span>
            {wiki.category && <Badge tone="neutral">{wiki.category.name}</Badge>}
            <span className="rounded-full bg-primary-50 px-2 py-1 text-primary-600">위키</span>
          </div>
        </div>
      </div>

      <article className="min-w-0 flex-1 py-1">
        <WikiMarkdown markdown={wiki.contentMarkdown} validWikiIds={validWikiIds} />
      </article>
    </div>
  )
}
