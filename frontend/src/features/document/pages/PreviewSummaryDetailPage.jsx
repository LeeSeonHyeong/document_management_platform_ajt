import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, FileText, Sparkles } from 'lucide-react'
import { Badge, EmptyState } from '@/components/ui'
import WikiMarkdown from '@/features/wiki/components/WikiMarkdown'
import { readPreviewSummaries } from '../previewStorage'

function formatDateTime(iso) {
  return new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export default function PreviewSummaryDetailPage() {
  const { summaryId } = useParams()
  const summary = useMemo(
    () => readPreviewSummaries().find((item) => item.summaryId === summaryId),
    [summaryId],
  )
  const [selectedDocumentId, setSelectedDocumentId] = useState(
    () => summary?.documents[0]?.documentId ?? null,
  )
  const selectedDocument = summary?.documents.find(
    (document) => document.documentId === selectedDocumentId,
  )

  if (!summary) {
    return (
      <section className="space-y-4">
        <Link
          to="/admin/documents/summaries"
          className="focus-ring inline-flex items-center gap-1.5 rounded-md text-sm font-semibold text-slate-500 hover:text-primary-600"
        >
          <ArrowLeft className="size-4" />
          요약 목록
        </Link>
        <div className="rounded-2xl border border-slate-200 bg-white">
          <EmptyState title="요약을 찾을 수 없습니다." description="요약 목록에서 다시 선택해주세요." />
        </div>
      </section>
    )
  }

  return (
    <section className="space-y-4">
      <Link
        to="/admin/documents/summaries"
        className="focus-ring inline-flex items-center gap-1.5 rounded-md text-sm font-semibold text-slate-500 hover:text-primary-600"
      >
        <ArrowLeft className="size-4" />
        요약 목록
      </Link>

      <div className="flex items-center justify-between rounded-2xl border border-slate-200 bg-white px-5 py-4">
        <div className="flex items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-violet-600 text-white">
            <Sparkles className="size-5" />
          </span>
          <div>
            <h1 className="text-lg font-bold text-slate-900">AI 작업 요약</h1>
            <p className="mt-0.5 text-xs text-slate-400">
              {formatDateTime(summary.createdAt)} · 문서 {summary.documents.length}개
            </p>
          </div>
        </div>
        <Badge tone="success">위키 반영 완료</Badge>
      </div>

      <div className="grid items-start gap-4 lg:grid-cols-[280px_minmax(0,1fr)]">
        <aside className="rounded-2xl border border-slate-200 bg-white p-3">
          <p className="px-2 py-2 text-xs font-semibold text-slate-400">처리 문서</p>
          <div className="space-y-1">
            {summary.documents.map((document) => (
              <button
                key={document.documentId}
                type="button"
                onClick={() => setSelectedDocumentId(document.documentId)}
                className={`focus-ring flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left ${
                  selectedDocumentId === document.documentId
                    ? 'bg-primary-50 text-primary-700'
                    : 'text-slate-600 hover:bg-slate-50'
                }`}
              >
                <FileText className="size-4 shrink-0" />
                <span className="min-w-0 flex-1 truncate text-sm font-semibold">
                  {document.originalFileName}
                </span>
              </button>
            ))}
          </div>
        </aside>

        <article className="min-h-[620px] rounded-2xl border border-slate-200 bg-white p-6">
          <div className="border-b border-slate-200 pb-4">
            <h2 className="text-xl font-bold text-slate-900">
              {selectedDocument?.relatedWikis?.[0]?.title ?? '생성된 위키 문서'}
            </h2>
            <p className="mt-1 text-xs text-slate-400">
              {selectedDocument?.documentCategoryName} · {selectedDocument?.originalFileName}
            </p>
          </div>

          <div className="mx-auto mt-2 max-w-4xl py-3">
            {selectedDocument?.previewContent ? (
              <WikiMarkdown markdown={selectedDocument.previewContent} validWikiIds={new Set()} />
            ) : (
              <EmptyState
                title="미리볼 수 있는 Markdown 내용이 없습니다."
                description="현재 시뮬레이션에서는 MD 파일의 본문을 렌더링합니다."
              />
            )}
          </div>
        </article>
      </div>
    </section>
  )
}
