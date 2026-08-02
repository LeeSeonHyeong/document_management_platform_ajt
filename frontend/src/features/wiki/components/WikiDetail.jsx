import { useMemo, useState } from 'react'
import { Download } from 'lucide-react'
import { Badge, Button, EmptyState, Spinner, useToast } from '@/components/ui'
import { fetchDocumentFile } from '@/features/document/api'
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
  const toast = useToast()
  const [downloading, setDownloading] = useState(false)
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

  if (isLoading) {
    return (
      <div className="flex flex-1 justify-center py-16">
        <Spinner />
      </div>
    )
  }

  const hasSourceDocument = Boolean(wiki?.evidenceDocuments?.length)

  // TODO(API): 위키 자체를 파일로 내려주는 엔드포인트가 계약에 없다.
  // 지금은 출처 원본(evidenceDocuments) 첫 문서를 GET /documents/:documentId/file 로 내려준다.
  async function handleDownload() {
    const sourceDocument = wiki?.evidenceDocuments?.[0]
    if (!sourceDocument) return
    setDownloading(true)
    try {
      const { blob, fileName } = await fetchDocumentFile(sourceDocument.documentId)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = fileName ?? sourceDocument.originalFileName ?? 'document'
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      if (error?.status === 403) toast.error('다운로드 권한이 없습니다.')
      else toast.error('다운로드에 실패했습니다.')
    } finally {
      setDownloading(false)
    }
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
          <Button
            variant="secondary"
            size="sm"
            onClick={handleDownload}
            loading={downloading}
            disabled={!hasSourceDocument}
            title={
              hasSourceDocument
                ? '출처 원본 문서를 다운로드합니다'
                : '다운로드할 출처 원본 문서가 없습니다'
            }
            className="shrink-0 rounded-full"
          >
            <Download className="size-4" />
            다운로드
          </Button>
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
