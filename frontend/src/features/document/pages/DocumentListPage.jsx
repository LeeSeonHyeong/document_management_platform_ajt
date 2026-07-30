import { useState } from 'react'
import { Upload, FileText, CalendarDays, X } from 'lucide-react'
import { Button, EmptyState } from '@/components/ui'
import { useDocuments } from '../queries'
import DocumentTable from '../components/DocumentTable'
import DocumentUploadModal from '../components/DocumentUploadModal'
import DocumentSectionTabs from '../components/DocumentSectionTabs'
import DocumentDeleteDialog from '../components/DocumentDeleteDialog'
import AiJobStartDialog from '../components/AiJobStartDialog'
import AiJobProgressDialog from '../components/AiJobProgressDialog'

// Figma 4R — 문서 관리 목록. 업로드·처리 현황을 관리자가 확인하는 화면.
export default function DocumentListPage() {
  const [uploadOpen, setUploadOpen] = useState(false)
  const [startOpen, setStartOpen] = useState(false)
  const [progressOpen, setProgressOpen] = useState(false)
  const [deletingDocument, setDeletingDocument] = useState(null)
  const [queueMetadata, setQueueMetadata] = useState({})
  const { data, isLoading } = useDocuments({ page: 1, size: 20 })
  // 현재 문서 목록 API에는 AI 대기 전용 필터가 없으므로 기존 목데이터를 포함한 전체 업로드 문서를 표시한다.
  const waitingDocuments = (data?.items ?? []).map((document) => ({
    ...document,
    ...queueMetadata[document.documentId],
  }))
  const completedDocuments = waitingDocuments.filter((document) => document.status === 'completed')
  const allAssigned =
    completedDocuments.length > 0 &&
    completedDocuments.every(
      (document) =>
        Boolean(document.documentCategoryId) &&
        (document.visibilityType === 'all' || (document.departments ?? []).length > 0),
    )

  return (
    <section className="space-y-5">
      <DocumentSectionTabs />

      <div className="grid gap-5 lg:grid-cols-2">
        <UploadCard
          tone="document"
          icon={FileText}
          title="문서 파일"
          description="일반 문서, 규정, 안내문 등 다양한 문서를 업로드하세요."
          extensions={['TXT', 'MD', 'PDF', 'DOCX']}
          onClick={() => setUploadOpen(true)}
        />
        <UploadCard
          tone="schedule"
          icon={CalendarDays}
          title="일정 파일"
          description="회의, 교육, 행사 등 일정 파일을 업로드하세요."
          extensions={['TXT', 'MD', 'DOCX', 'PDF', 'CSV', 'XLSX']}
          onClick={() => setUploadOpen(true)}
        />
      </div>

      <div className="min-h-[420px] overflow-hidden rounded-2xl border border-slate-200 bg-white">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-bold text-slate-800">AI 작업 대기</h2>
              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-semibold text-slate-500">
                {waitingDocuments.length}개
              </span>
            </div>
            <p className="mt-1 text-xs text-slate-400">카테고리와 공개 부서를 지정한 뒤 AI 작업을 시작하세요.</p>
          </div>
          <Button variant="primary" disabled={!allAssigned} onClick={() => setStartOpen(true)}>
            AI 작업 시작
          </Button>
        </div>

        <DocumentTable
          variant="queue"
          documents={waitingDocuments}
          loading={isLoading}
          onQueueMetadataChange={(documentId, changes) =>
            setQueueMetadata((current) => ({
              ...current,
              [documentId]: { ...current[documentId], ...changes },
            }))
          }
          renderAction={(doc) => (
            <button
              type="button"
              aria-label={`${doc.originalFileName} 대기 목록에서 삭제`}
              onClick={(event) => {
                event.stopPropagation()
                setDeletingDocument(doc)
              }}
              className="focus-ring flex size-8 items-center justify-center rounded-lg bg-slate-100 text-slate-400 hover:bg-rose-50 hover:text-rose-500"
            >
              <X className="size-4" />
            </button>
          )}
          emptyState={
            <EmptyState
              title="업로드가 끝난 파일이 여기에 쌓입니다."
              description="카테고리와 공개 부서를 지정한 뒤 AI 작업을 시작하세요."
            />
          }
        />
      </div>

      <DocumentUploadModal
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onUploaded={() => {
          setUploadOpen(false)
        }}
      />

      <DocumentDeleteDialog
        open={Boolean(deletingDocument)}
        document={deletingDocument}
        onClose={() => setDeletingDocument(null)}
        onBackground={() => setDeletingDocument(null)}
      />

      <AiJobStartDialog
        open={startOpen}
        documents={completedDocuments}
        onClose={() => setStartOpen(false)}
        onConfirm={() => {
          setStartOpen(false)
          setProgressOpen(true)
        }}
      />

      <AiJobProgressDialog
        open={progressOpen}
        documentCount={completedDocuments.length}
        onBackground={() => setProgressOpen(false)}
      />
    </section>
  )
}

function UploadCard({ tone, icon: Icon, title, description, extensions, onClick }) {
  const schedule = tone === 'schedule'

  return (
    <article className="rounded-2xl border border-slate-200 bg-white p-5">
      <div className="flex items-start gap-3">
        <span
          className={`flex size-10 shrink-0 items-center justify-center rounded-xl text-white ${
            schedule ? 'bg-emerald-600' : 'bg-gradient-to-br from-blue-500 to-violet-600'
          }`}
        >
          <Icon className="size-5" />
        </span>
        <div>
          <h2 className="font-bold text-slate-800">{title}</h2>
          <p className="mt-0.5 text-xs text-slate-400">{description}</p>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {extensions.map((extension) => (
          <span
            key={extension}
            className={`rounded-md border px-2 py-0.5 text-[11px] font-medium ${
              schedule
                ? 'border-emerald-200 bg-emerald-50 text-emerald-600'
                : 'border-primary-200 bg-primary-50 text-primary-600'
            }`}
          >
            {extension}
          </span>
        ))}
      </div>

      <button
        type="button"
        onClick={onClick}
        className={`focus-ring mt-4 flex h-40 w-full flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed transition ${
          schedule
            ? 'border-emerald-300 bg-emerald-50/50 text-emerald-700 hover:bg-emerald-50'
            : 'border-primary-300 bg-primary-50/50 text-primary-700 hover:bg-primary-50'
        }`}
      >
        <span className="flex size-11 items-center justify-center rounded-full bg-white shadow-sm">
          <Upload className="size-5" />
        </span>
        <span className="text-xs text-slate-500">파일을 끌어다 놓거나 클릭하여 업로드</span>
        <span
          className={`rounded-lg px-5 py-2 text-sm font-semibold text-white ${
            schedule ? 'bg-emerald-600' : 'bg-gradient-to-r from-blue-500 to-violet-600'
          }`}
        >
          파일 선택
        </span>
      </button>
    </article>
  )
}
