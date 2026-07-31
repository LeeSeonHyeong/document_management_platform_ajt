import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Upload, FileText, CalendarDays, X, AlertTriangle, Check } from 'lucide-react'
import { Button, EmptyState, useToast } from '@/components/ui'
import { useAuth } from '@/hooks/useAuth'
import { FILE_ACCEPT } from '@/shared/constants/enums'
import { useDocuments } from '../queries'
import DocumentTable from '../components/DocumentTable'
import DocumentSectionTabs from '../components/DocumentSectionTabs'
import AiJobStartDialog from '../components/AiJobStartDialog'
import { addPreviewSourceDocuments, addPreviewSummary } from '../previewStorage'

// Figma 4R — 문서 관리 목록. 업로드·처리 현황을 관리자가 확인하는 화면.
// 카테고리와 공개 부서가 모두 지정된 문서인지 판단한다.
function isAssigned(document) {
  return (
    Boolean(document.documentCategoryId) &&
    (document.visibilityType === 'all' || (document.departments ?? []).length > 0)
  )
}

export default function DocumentListPage() {
  const toast = useToast()
  const { user } = useAuth()
  const navigate = useNavigate()
  const [previewUploadFiles, setPreviewUploadFiles] = useState([])
  const [previewScheduleFiles, setPreviewScheduleFiles] = useState([])
  const [previewQueueDocuments, setPreviewQueueDocuments] = useState([])
  const [removedQueueDocumentIds, setRemovedQueueDocumentIds] = useState([])
  const [startOpen, setStartOpen] = useState(false)
  const [queueMetadata, setQueueMetadata] = useState({})
  const { data, isLoading } = useDocuments({ page: 1, size: 20 })
  // AI 작업 대기에는 아직 처리가 시작되지 않은 uploaded 문서만 둔다.
  // processing/completed/failed/cancelled 원본은 원본 문서 탭에서만 관리한다.
  const serverWaitingDocuments = (data?.items ?? []).filter(
    (document) =>
      document.status === 'uploaded' &&
      !removedQueueDocumentIds.includes(document.documentId),
  )
  const waitingDocuments = [...previewQueueDocuments, ...serverWaitingDocuments].map(
    (document) => ({
      ...document,
      ...queueMetadata[document.documentId],
    }),
  )
  const readyDocuments = waitingDocuments.filter(isAssigned)
  const allAssigned = waitingDocuments.length > 0 && readyDocuments.length === waitingDocuments.length
  const unassignedCount = waitingDocuments.filter((document) => !isAssigned(document)).length

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
          accept={FILE_ACCEPT.WIKI_SOURCE}
          selectedFiles={previewUploadFiles}
          onFilesSelected={setPreviewUploadFiles}
          onUploadComplete={async (files) => {
            const documents = await createPreviewDocuments(files, user)
            setPreviewQueueDocuments((current) => [...documents, ...current])
            setPreviewUploadFiles([])
            toast.success(`${files.length}개 문서가 AI 작업 대기 목록에 추가되었습니다.`)
          }}
        />
        <UploadCard
          tone="schedule"
          icon={CalendarDays}
          title="일정 파일"
          description="회의, 교육, 행사 등 일정 파일을 업로드하세요."
          extensions={['TXT', 'MD', 'DOCX', 'PDF', 'CSV', 'XLSX']}
          accept={FILE_ACCEPT.SCHEDULE}
          selectedFiles={previewScheduleFiles}
          onFilesSelected={setPreviewScheduleFiles}
          onUploadComplete={async (files) => {
            const documents = await createPreviewDocuments(files, user)
            setPreviewQueueDocuments((current) => [...documents, ...current])
            setPreviewScheduleFiles([])
            toast.success(`${files.length}개 일정 파일이 AI 작업 대기 목록에 추가되었습니다.`)
          }}
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
            <p className="mt-1 text-xs text-slate-400">
              {waitingDocuments.length && !unassignedCount
                ? '지정이 끝난 파일부터 AI 작업을 시작할 수 있습니다.'
                : '카테고리와 공개 부서를 지정한 뒤 AI 작업을 시작하세요.'}
            </p>
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
                if (doc.previewOnly) {
                  setPreviewQueueDocuments((current) =>
                    current.filter((document) => document.documentId !== doc.documentId),
                  )
                } else {
                  setRemovedQueueDocumentIds((current) => [...current, doc.documentId])
                }
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

        {/* Figma 4-2R·4-3R — 지정 현황 안내. 미지정이 남아 있으면 경고, 모두 끝나면 완료로 바뀐다. */}
        {waitingDocuments.length > 0 && (
          <div
            className={`flex items-start gap-2 border-t px-5 py-3 text-xs leading-5 ${
              unassignedCount
                ? 'border-amber-100 bg-amber-50 text-amber-700'
                : 'border-emerald-100 bg-emerald-50 text-emerald-700'
            }`}
          >
            <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-white/80">
              {unassignedCount ? <AlertTriangle className="size-3" /> : <Check className="size-3" strokeWidth={3} />}
            </span>
            <p>
              {unassignedCount
                ? `${unassignedCount}개 파일에 카테고리·공개 부서가 지정되지 않았습니다. 모두 지정해야 AI 작업을 시작할 수 있습니다.`
                : `${waitingDocuments.length}개 파일 모두 카테고리와 공개 부서가 지정되었습니다. AI 작업을 시작하세요.`}
            </p>
          </div>
        )}
      </div>

      <AiJobStartDialog
        open={startOpen}
        documents={readyDocuments}
        onClose={() => setStartOpen(false)}
        onConfirm={() => {
          setStartOpen(false)
          addPreviewSummary(readyDocuments)
          const previewDocuments = readyDocuments
            .filter((document) => document.previewOnly)
            .map((document) => ({
              ...document,
              status: 'completed',
              previewOnly: true,
            }))
          if (previewDocuments.length) addPreviewSourceDocuments(previewDocuments)

          setPreviewQueueDocuments([])
          setRemovedQueueDocumentIds((current) => [
            ...new Set([
              ...current,
              ...readyDocuments
                .filter((document) => !document.previewOnly)
                .map((document) => document.documentId),
            ]),
          ])
          setQueueMetadata({})
          toast.success(`${readyDocuments.length}개 파일의 AI 작업을 시작했습니다.`)
          navigate('/admin/documents/source')
        }}
      />
    </section>
  )
}

function UploadCard({
  tone,
  icon: Icon,
  title,
  description,
  extensions,
  accept,
  selectedFiles = [],
  onFilesSelected,
  onUploadComplete,
  onClick,
}) {
  const schedule = tone === 'schedule'
  const inputRef = useRef(null)
  const [dragging, setDragging] = useState(false)
  const [progress, setProgress] = useState(0)
  const acceptsFilesDirectly = Boolean(onFilesSelected)
  const totalBytes = selectedFiles.reduce((sum, file) => sum + file.size, 0)
  const loadedBytes = Math.round(totalBytes * (progress / 100))

  useEffect(() => {
    if (!selectedFiles.length) {
      setProgress(0)
      return undefined
    }

    setProgress(0)
    const timer = window.setInterval(() => {
      setProgress((current) => {
        if (current >= 100) {
          window.clearInterval(timer)
          return 100
        }
        return Math.min(100, current + 4)
      })
    }, 120)

    return () => window.clearInterval(timer)
  }, [selectedFiles])

  useEffect(() => {
    if (progress !== 100 || !selectedFiles.length) return undefined
    const timer = window.setTimeout(() => onUploadComplete?.(selectedFiles), 700)
    return () => window.clearTimeout(timer)
  }, [onUploadComplete, progress, selectedFiles])

  function openFilePicker() {
    if (acceptsFilesDirectly) inputRef.current?.click()
    else onClick?.()
  }

  function selectFiles(fileList) {
    const files = Array.from(fileList ?? [])
    if (files.length) onFilesSelected?.(files)
  }

  function handleDrop(event) {
    if (!acceptsFilesDirectly) return
    event.preventDefault()
    setDragging(false)
    selectFiles(event.dataTransfer.files)
  }

  function cancelSelection(event) {
    event.stopPropagation()
    setProgress(0)
    onFilesSelected?.([])
  }

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

      <div
        role="button"
        tabIndex={0}
        onClick={openFilePicker}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault()
            openFilePicker()
          }
        }}
        onDragEnter={(event) => {
          if (!acceptsFilesDirectly) return
          event.preventDefault()
          setDragging(true)
        }}
        onDragOver={(event) => {
          if (acceptsFilesDirectly) event.preventDefault()
        }}
        onDragLeave={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget)) setDragging(false)
        }}
        onDrop={handleDrop}
        className={`focus-ring mt-4 flex h-40 w-full flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed transition ${
          schedule
            ? dragging
              ? 'border-emerald-500 bg-emerald-100/70 text-emerald-700'
              : 'border-emerald-300 bg-emerald-50/50 text-emerald-700 hover:bg-emerald-50'
            : dragging
              ? 'border-primary-500 bg-primary-100/70 text-primary-700'
              : 'border-primary-300 bg-primary-50/50 text-primary-700 hover:bg-primary-50'
        }`}
      >
        {selectedFiles.length > 0 ? (
          <div className="w-full px-4 text-left">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span
                  className={`flex size-9 items-center justify-center rounded-lg text-white ${
                    schedule ? 'bg-emerald-600' : 'bg-gradient-to-br from-blue-500 to-violet-600'
                  }`}
                >
                  <Upload className="size-4" />
                </span>
                <div>
                  <p className="text-sm font-bold text-slate-800">
                    {progress < 100 ? '업로드 중' : '업로드 완료'}
                  </p>
                  <p className="text-[11px] text-slate-400">완료되면 아래 AI 작업 대기 목록에 추가됩니다</p>
                </div>
              </div>
              <span className="rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-semibold text-slate-500">
                {selectedFiles.length} / {selectedFiles.length}
              </span>
            </div>
            <div className="mt-3 flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2.5">
              <FileText className={`size-4 shrink-0 ${schedule ? 'text-emerald-600' : 'text-primary-500'}`} />
              <span className="min-w-0 flex-1 truncate text-sm font-semibold text-slate-700">
                {selectedFiles[0].name}
                {selectedFiles.length > 1 ? ` 외 ${selectedFiles.length - 1}건` : ''}
              </span>
              <span className={`text-xs font-bold ${schedule ? 'text-emerald-600' : 'text-primary-600'}`}>
                {progress}%
              </span>
            </div>
            <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
              <div
                className={`h-full rounded-full transition-[width] duration-150 ${
                  schedule ? 'bg-emerald-500' : 'bg-gradient-to-r from-blue-500 to-violet-600'
                }`}
                style={{ width: `${progress}%` }}
              />
            </div>
            <div className="mt-1.5 flex items-center justify-between text-[11px]">
              <span className="text-slate-400">
                {formatUploadBytes(loadedBytes)} / {formatUploadBytes(totalBytes)}
              </span>
              <button
                type="button"
                onClick={cancelSelection}
                className="focus-ring rounded px-1 font-medium text-slate-500 hover:text-rose-500"
              >
                취소
              </button>
            </div>
          </div>
        ) : (
          <>
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
          </>
        )}
      </div>
      {acceptsFilesDirectly && (
        <input
          ref={inputRef}
          type="file"
          multiple
          accept={accept.map((extension) => `.${extension}`).join(',')}
          className="hidden"
          onChange={(event) => {
            selectFiles(event.target.files)
            event.target.value = ''
          }}
        />
      )}
    </article>
  )
}

function formatUploadBytes(bytes) {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} B`
}

async function createPreviewDocuments(files, uploader) {
  const createdAt = new Date().toISOString()
  const batchId = Date.now()

  return Promise.all(
    files.map(async (file, index) => ({
      documentId: `preview-${batchId}-${index}`,
      originalFileName: file.name,
      fileSize: file.size,
      mimeType: file.type,
      previewContent: file.name.toLowerCase().endsWith('.md') ? await file.text() : null,
      documentCategoryId: null,
      documentCategoryName: null,
      visibilityType: null,
      departments: [],
      status: 'uploaded',
      uploadedAt: createdAt,
      uploadedBy: uploader
        ? { userId: uploader.userId, name: uploader.name }
        : null,
      previewOnly: true,
    })),
  )
}
