import { useState } from 'react'
import { Link } from 'react-router-dom'
import { FileText, Sparkles } from 'lucide-react'
import { Badge, Button, EmptyState, Spinner } from '@/components/ui'
import { useDocuments } from '../queries'
import DocumentSectionTabs from '../components/DocumentSectionTabs'
import { readPreviewSummaries } from '../previewStorage'

function formatDateTime(iso) {
  if (!iso) return '최근 작업'
  return new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatFileSize(bytes) {
  if (!bytes && bytes !== 0) return '-'
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

export default function AiJobSummaryListPage() {
  const { data, isLoading } = useDocuments({ page: 1, size: 100, status: 'completed' })
  const [previewSummaries] = useState(readPreviewSummaries)
  const serverDocuments = data?.items ?? []
  const summaries = [
    ...previewSummaries,
    ...(serverDocuments.length
      ? [{
          summaryId: 'server-completed-documents',
          createdAt: serverDocuments[0]?.uploadedAt,
          documents: serverDocuments,
          previewOnly: false,
        }]
      : []),
  ].sort((left, right) => new Date(right.createdAt ?? 0) - new Date(left.createdAt ?? 0))
  const totalDocuments = summaries.reduce((sum, summary) => sum + summary.documents.length, 0)

  return (
    <section className="space-y-5">
      <DocumentSectionTabs />

      <div className="flex items-center justify-between">
        <p className="text-sm text-slate-500">
          총 <strong className="text-slate-800">{summaries.length}회 작업</strong>
          <span className="mx-2 text-slate-300">•</span>
          문서 {totalDocuments}개
        </p>
        {summaries.length > 0 && <Badge tone="success">모두 위키 반영 완료</Badge>}
      </div>

      {isLoading && summaries.length === 0 ? (
        <div className="flex justify-center py-20">
          <Spinner />
        </div>
      ) : summaries.length === 0 ? (
        <div className="rounded-2xl border border-slate-200 bg-white">
          <EmptyState title="완료된 AI 작업이 없습니다." description="문서를 업로드하고 AI 작업을 실행해보세요." />
        </div>
      ) : (
        <div className="space-y-4">
          {summaries.map((summary) => (
            <SummaryCard key={summary.summaryId} summary={summary} />
          ))}
        </div>
      )}
    </section>
  )
}

function SummaryCard({ summary }) {
  const documents = summary.documents

  return (
    <article className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
        <div className="flex items-center gap-3">
          <span className="flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-violet-600 text-white">
            <Sparkles className="size-5" />
          </span>
          <div className="flex items-center gap-2">
            <h2 className="font-bold text-slate-800">{formatDateTime(summary.createdAt)} 작업</h2>
            <span className="rounded-full bg-primary-50 px-2 py-0.5 text-xs font-semibold text-primary-600">
              문서 {documents.length}개
            </span>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-[minmax(0,2fr)_1fr_1fr_minmax(0,1.4fr)_110px_100px] gap-3 bg-slate-50 px-5 py-3 text-xs font-semibold text-slate-500">
        <span>파일명</span>
        <span className="text-center">공개 부서</span>
        <span className="text-center">카테고리</span>
        <span className="text-center">생성된 위키 문서</span>
        <span className="text-center">상태</span>
        <span className="text-center">관리</span>
      </div>

      <ul className="divide-y divide-slate-100">
        {documents.map((document) => (
          <li
            key={document.documentId}
            className="grid grid-cols-[minmax(0,2fr)_1fr_1fr_minmax(0,1.4fr)_110px_100px] items-center gap-3 px-5 py-3 text-sm"
          >
            <div className="flex min-w-0 items-center gap-3">
              <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-500">
                <FileText className="size-4" />
              </span>
              <div className="min-w-0">
                <p className="truncate font-semibold text-slate-800">{document.originalFileName}</p>
                <p className="text-xs text-slate-400">{formatFileSize(document.fileSize)}</p>
              </div>
            </div>
            <span className="truncate text-center text-slate-500">
              {document.visibilityType === 'all'
                ? '전체 공개'
                : (document.departments ?? []).map((department) => department.name).join(', ') || '-'}
            </span>
            <span className="truncate text-center text-slate-500">
              {document.documentCategoryName ?? '미분류'}
            </span>
            <span className="truncate text-center font-semibold text-primary-600">
              {document.relatedWikis?.[0]?.title ?? '위키 반영 완료'}
            </span>
            <div className="flex justify-center">
              <Badge tone="success">반영 완료</Badge>
            </div>
            <div className="flex justify-center">
              {summary.previewOnly ? (
                <Link to={`/admin/documents/summaries/${summary.summaryId}`}>
                  <Button size="sm" variant="outline">요약 보기</Button>
                </Link>
              ) : (
                <Link to={`/admin/documents/source/${document.documentId}`}>
                  <Button size="sm" variant="outline">요약 보기</Button>
                </Link>
              )}
            </div>
          </li>
        ))}
      </ul>
    </article>
  )
}
