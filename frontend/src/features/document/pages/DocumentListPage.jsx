import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Upload, FileText, CalendarDays, X, AlertTriangle, Check } from 'lucide-react'
import { Button, EmptyState, useToast } from '@/components/ui'
import { useAuth } from '@/hooks/useAuth'
import {
  FILE_ACCEPT,
  FILE_MIME_TYPES,
  MAX_FILE_SIZE_BYTES,
  MAX_UPLOAD_FILE_COUNT,
  MAX_UPLOAD_TOTAL_SIZE_BYTES,
} from '@/shared/constants/enums'
import { useStartAiJob, useUploadDocuments, useUploadScheduleSource } from '../queries'
import DocumentTable from '../components/DocumentTable'
import DocumentSectionTabs from '../components/DocumentSectionTabs'
import { useAiJobQueue } from '../useAiJobQueue'
import AiJobStartDialog from '../components/AiJobStartDialog'
import AiJobProgressDialog from '../components/AiJobProgressDialog'

// Figma 4R — 문서 관리 목록. 업로드·처리 현황을 관리자가 확인하는 화면.
// 카테고리와 공개 부서가 모두 지정된 문서인지 판단한다.
function isAssigned(document) {
  if (document.uploadKind === 'schedule') {
    return document.visibilityType === 'all' || (document.departments ?? []).length > 0
  }
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
  const [startOpen, setStartOpen] = useState(false)
  const [progressOpen, setProgressOpen] = useState(false)
  const [progressJobIds, setProgressJobIds] = useState([])
  const [progressDocumentCount, setProgressDocumentCount] = useState(0)
  // 실제 파일 전송률. axios onUploadProgress가 보고한 누적 바이트로만 계산한다.
  const [uploadProgress, setUploadProgress] = useState(null)
  // 대기 목록은 셸(AiJobQueueProvider)이 들고 있다 — 이 페이지의 상태로 두면 다른 화면에
  // 갔다 오는 사이 언마운트돼 올린 파일이 사라진다 (S15P11B106-230).
  const {
    queueDocuments: previewQueueDocuments,
    queueMetadata,
    addDocuments,
    updateMetadata,
    removeDocuments,
  } = useAiJobQueue()
  const uploadDocumentsMutation = useUploadDocuments()
  const uploadScheduleMutation = useUploadScheduleSource()
  const startAiJobMutation = useStartAiJob()
  const isStarting =
    uploadDocumentsMutation.isPending ||
    uploadScheduleMutation.isPending ||
    startAiJobMutation.isPending
  // 업로드 카드에서 완료된 로컬 파일은 document.status=UPLOADED에 대응하는 대기 행으로 표시한다.
  // 필수 메타데이터 확정 후 POST /documents가 WAITING 작업을 만들고, /start가 실제 처리를 시작한다.
  const waitingDocuments = previewQueueDocuments.map((document) => ({
    ...document,
    ...queueMetadata[document.documentId],
  }))
  const readyDocuments = waitingDocuments.filter(isAssigned)
  const allAssigned = waitingDocuments.length > 0 && readyDocuments.length === waitingDocuments.length
  const unassignedCount = waitingDocuments.filter((document) => !isAssigned(document)).length
  // 업로드 카드가 개수·총합 제한을 판단할 때 쓴다.
  const queuedBytes = waitingDocuments.reduce(
    (total, document) => total + (document.fileSize ?? 0),
    0,
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
          accept={FILE_ACCEPT.WIKI_SOURCE}
          selectedFiles={previewUploadFiles}
          onFilesSelected={setPreviewUploadFiles}
          queuedCount={waitingDocuments.length}
          queuedBytes={queuedBytes}
          onUploadComplete={async (files, batch) => {
            const documents = await createPreviewDocuments(files, user, 'document')
            addDocuments(documents)
            if (batch.isLast) {
              const extraCount = batch.files.length - 1
              toast.success(
                extraCount > 0
                  ? `${batch.files[0].name} 외 ${extraCount}개 문서가 AI 작업 대기 목록에 추가되었습니다.`
                  : `${batch.files[0].name} 문서가 AI 작업 대기 목록에 추가되었습니다.`,
              )
            }
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
          queuedCount={waitingDocuments.length}
          queuedBytes={queuedBytes}
          onUploadComplete={async (files, batch) => {
            const documents = await createPreviewDocuments(files, user, 'schedule')
            addDocuments(documents)
            if (batch.isLast) {
              const extraCount = batch.files.length - 1
              toast.success(
                extraCount > 0
                  ? `${batch.files[0].name} 외 ${extraCount}개 일정 파일이 AI 작업 대기 목록에 추가되었습니다.`
                  : `${batch.files[0].name} 일정 파일이 AI 작업 대기 목록에 추가되었습니다.`,
              )
            }
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
          loading={false}
          onQueueMetadataChange={(documentId, changes) =>
            updateMetadata(documentId, changes)
          }
          renderAction={(doc) => (
            <button
              type="button"
              aria-label={`${doc.originalFileName} 대기 목록에서 삭제`}
              onClick={(event) => {
                event.stopPropagation()
                removeDocuments([doc.documentId])
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
        pending={isStarting}
        uploadProgress={uploadProgress}
        onClose={() => {
          if (!isStarting) setStartOpen(false)
        }}
        onConfirm={async () => {
          const localDocuments = readyDocuments.filter(
            (document) => document.previewOnly && document.sourceFile,
          )
          const documentGroups = groupDocumentUploads(
            localDocuments.filter((document) => document.uploadKind !== 'schedule'),
          )
          const scheduleDocuments = localDocuments.filter(
            (document) => document.uploadKind === 'schedule',
          )
          const uploadedIds = new Set()
          const startedJobIds = []
          // 전체 바이트 대비 전송된 바이트로 진행률을 만든다. 요청이 여러 번 나가므로
          // 끝난 요청의 바이트(sentBytes)에 진행 중인 요청의 loaded를 더한다.
          const totalBytes = localDocuments.reduce(
            (total, document) => total + (document.sourceFile?.size ?? 0),
            0,
          )
          let sentBytes = 0
          setUploadProgress({ loaded: 0, total: totalBytes })
          const trackUpload = (event) => {
            setUploadProgress({
              loaded: Math.min(totalBytes, sentBytes + (event.loaded ?? 0)),
              total: totalBytes,
            })
          }
          const commitSent = (files) => {
            sentBytes += files.reduce((total, file) => total + (file?.size ?? 0), 0)
            setUploadProgress({ loaded: Math.min(totalBytes, sentBytes), total: totalBytes })
          }

          try {
            for (const group of documentGroups) {
              const groupFiles = group.documents.map((document) => document.sourceFile)
              const result = await uploadDocumentsMutation.mutateAsync({
                files: groupFiles,
                documentCategoryId: group.documentCategoryId,
                visibilityType: group.visibilityType,
                departmentIds: group.departmentIds,
                onUploadProgress: trackUpload,
              })
              commitSent(groupFiles)
              await startAiJobMutation.mutateAsync(result.jobId)
              startedJobIds.push(result.jobId)
              group.documents.forEach((document) => uploadedIds.add(document.documentId))
            }

            for (const document of scheduleDocuments) {
              await uploadScheduleMutation.mutateAsync({
                file: document.sourceFile,
                visibilityType: document.visibilityType,
                departmentIds: getDepartmentIds(document),
                onUploadProgress: trackUpload,
              })
              commitSent([document.sourceFile])
              uploadedIds.add(document.documentId)
            }

            setStartOpen(false)
            toast.success(`${uploadedIds.size}개 파일의 AI 작업을 시작했습니다.`)

            // 페이지 이동 없이 진행 모달을 띄운다. 공개 범위별로 여러 작업으로 쪼개져도
            // 모든 작업을 한 모달에 합쳐 진행률을 보여주고, 끝나면 요약 목록으로 안내한다
            // (S15P11B106-210). 일정 파일만 있으면 AI 작업이 없으므로 원본 목록으로 보낸다.
            if (startedJobIds.length > 0) {
              const documentTotal = documentGroups.reduce(
                (total, group) => total + group.documents.length,
                0,
              )
              setProgressJobIds(startedJobIds)
              setProgressDocumentCount(documentTotal)
              setProgressOpen(true)
            } else {
              navigate('/admin/documents/source')
            }
          } catch (error) {
            const message =
              error?.response?.data?.message ??
              error?.response?.data?.error?.message ??
              '파일 업로드에 실패했습니다. 입력값과 서버 연결을 확인해주세요.'
            toast.error(message)
          } finally {
            setUploadProgress(null)
            if (uploadedIds.size > 0) {
              removeDocuments(uploadedIds)
            }
          }
        }}
      />

      <AiJobProgressDialog
        open={progressOpen}
        jobIds={progressJobIds}
        documentCount={progressDocumentCount}
        onBackground={() => setProgressOpen(false)}
        onDone={() => {
          setProgressOpen(false)
          navigate('/admin/documents/summaries')
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
  queuedCount = 0,
  queuedBytes = 0,
}) {
  const schedule = tone === 'schedule'
  const toast = useToast()
  const inputRef = useRef(null)
  const onUploadCompleteRef = useRef(onUploadComplete)
  const [dragging, setDragging] = useState(false)
  const [currentIndex, setCurrentIndex] = useState(0)
  const acceptsFilesDirectly = Boolean(onFilesSelected)

  useEffect(() => {
    onUploadCompleteRef.current = onUploadComplete
  }, [onUploadComplete])

  // 이 카드는 서버로 전송하지 않는다. POST /documents는 카테고리·공개 부서가 정해진 뒤
  // 'AI 작업 시작'에서 한 번에 호출되므로, 여기서는 파일을 대기 목록에 담기만 한다.
  // 따라서 전송률 대신 '몇 번째 파일을 담고 있는지'만 보여준다(가짜 진행률 제거).
  useEffect(() => {
    if (!selectedFiles.length) {
      setCurrentIndex(0)
      return undefined
    }

    let cancelled = false
    setCurrentIndex(0)
    ;(async () => {
      for (let index = 0; index < selectedFiles.length; index += 1) {
        if (cancelled) return
        setCurrentIndex(index)
        await onUploadCompleteRef.current?.([selectedFiles[index]], {
          index,
          isLast: index === selectedFiles.length - 1,
          files: selectedFiles,
        })
      }
      if (!cancelled) onFilesSelected?.([])
    })()

    return () => {
      cancelled = true
    }
  }, [selectedFiles, onFilesSelected])

  function openFilePicker() {
    if (acceptsFilesDirectly) inputRef.current?.click()
    else onClick?.()
  }

  // input의 accept는 파일 대화상자 필터일 뿐이고(‘모든 파일’로 우회 가능), 드래그&드롭은
  // accept를 아예 무시한다. 그래서 여기서 확장자·용량을 직접 검사한다.
  // 백엔드(DocumentUploadRequest·ScheduleSourceService)는 확장자+MIME까지 다시 검증한다 —
  // 이 검사는 대기 목록에 못 쓸 파일이 쌓여 'AI 작업 시작'에서야 400으로 터지는 것을 막는 용도다.
  function selectFiles(fileList) {
    const files = Array.from(fileList ?? [])
    if (!files.length) return

    const rejected = []
    const accepted = []
    // 개수·총합은 요청 단위 제한이라, 대기 목록에 이미 쌓인 파일까지 합산해서 본다.
    let runningCount = queuedCount
    let runningBytes = queuedBytes

    files.forEach((file) => {
      const extension = fileExtensionOf(file.name)
      if (!accept.includes(extension)) {
        rejected.push(`${file.name} (지원하지 않는 형식)`)
        return
      }
      // 확장자만 바꿔치기한 파일 걸러내기. 브라우저가 type을 비워 보내는 경우가 있어
      // 값이 있을 때만 대조한다(비었으면 백엔드 검증에 맡긴다).
      const allowedMimeTypes = FILE_MIME_TYPES[extension] ?? []
      if (file.type && !allowedMimeTypes.includes(file.type)) {
        rejected.push(`${file.name} (확장자와 파일 형식이 다름)`)
        return
      }
      if (file.size > MAX_FILE_SIZE_BYTES) {
        rejected.push(`${file.name} (20MB 초과)`)
        return
      }
      if (runningCount + 1 > MAX_UPLOAD_FILE_COUNT) {
        rejected.push(`${file.name} (한 번에 ${MAX_UPLOAD_FILE_COUNT}개까지)`)
        return
      }
      if (runningBytes + file.size > MAX_UPLOAD_TOTAL_SIZE_BYTES) {
        rejected.push(`${file.name} (합계 100MB 초과)`)
        return
      }
      runningCount += 1
      runningBytes += file.size
      accepted.push(file)
    })

    if (rejected.length) {
      const extra = rejected.length > 1 ? ` 외 ${rejected.length - 1}개` : ''
      toast.error(
        `${rejected[0]}${extra}는 업로드할 수 없습니다. ${accept
          .map((extension) => extension.toUpperCase())
          .join(' · ')} · 파일당 20MB · 최대 ${MAX_UPLOAD_FILE_COUNT}개 · 합계 100MB까지 가능합니다.`,
      )
    }
    if (accepted.length) onFilesSelected?.(accepted)
  }

  function handleDrop(event) {
    if (!acceptsFilesDirectly) return
    event.preventDefault()
    setDragging(false)
    selectFiles(event.dataTransfer.files)
  }

  function cancelSelection(event) {
    event.stopPropagation()
    setCurrentIndex(0)
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
        className={`focus-ring mt-4 flex min-h-40 w-full flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed py-3 transition ${
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
                  <p className="text-sm font-bold text-slate-800">대기 목록에 추가 중</p>
                  <p className="text-[11px] text-slate-400">
                    실제 업로드는 &lsquo;AI 작업 시작&rsquo;에서 진행됩니다
                  </p>
                </div>
              </div>
              <span className="rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-semibold text-slate-500">
                {currentIndex + 1} / {selectedFiles.length}
              </span>
            </div>
            <div className="mt-3 max-h-[328px] space-y-1.5 overflow-y-auto pr-1">
              {selectedFiles.map((file, index) => {
                const status =
                  index < currentIndex ? '추가 완료' : index === currentIndex ? '추가 중' : '대기'
                return (
                  <div
                    key={`${file.name}-${file.lastModified}-${index}`}
                    className="rounded-lg border border-slate-200 bg-white px-3 py-2"
                  >
                    <div className="flex items-center gap-2">
                      <FileText
                        className={`size-4 shrink-0 ${
                          index > currentIndex
                            ? 'text-slate-300'
                            : schedule
                              ? 'text-emerald-600'
                              : 'text-primary-500'
                        }`}
                      />
                      <span className="min-w-0 flex-1 truncate text-xs font-semibold text-slate-700">
                        {file.name}
                      </span>
                      <span
                        className={`text-[11px] font-bold ${
                          index > currentIndex
                            ? 'text-slate-400'
                            : schedule
                              ? 'text-emerald-600'
                              : 'text-primary-600'
                        }`}
                      >
                        {status}
                      </span>
                    </div>
                    <p className="mt-1 text-right text-[10px] text-slate-400">
                      {formatUploadBytes(file.size)}
                    </p>
                  </div>
                )
              })}
            </div>
            <div className="mt-1.5 flex justify-end text-[11px]">
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

function fileExtensionOf(fileName) {
  const extensionStart = fileName?.lastIndexOf('.') ?? -1
  if (extensionStart < 1) return ''
  return fileName.slice(extensionStart + 1).toLowerCase()
}

function formatUploadBytes(bytes) {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} B`
}

async function createPreviewDocuments(files, uploader, uploadKind) {
  const createdAt = new Date().toISOString()
  const batchId = Date.now()

  return Promise.all(
    files.map(async (file, index) => ({
      documentId: `preview-${batchId}-${index}`,
      originalFileName: file.name,
      fileSize: file.size,
      mimeType: file.type,
      sourceFile: file,
      uploadKind,
      previewContent: file.name.toLowerCase().endsWith('.md') ? await file.text() : null,
      downloadUrl: URL.createObjectURL(file),
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

function getDepartmentIds(document) {
  return (document.departments ?? [])
    .map((department) => department.departmentId ?? department.id)
    .filter(Boolean)
}

function groupDocumentUploads(documents) {
  const groups = new Map()

  documents.forEach((document) => {
    const departmentIds = getDepartmentIds(document).sort((a, b) =>
      String(a).localeCompare(String(b)),
    )
    const key = JSON.stringify([
      document.documentCategoryId,
      document.visibilityType,
      departmentIds,
    ])
    const existing = groups.get(key)
    if (existing) {
      existing.documents.push(document)
      return
    }
    groups.set(key, {
      documents: [document],
      documentCategoryId: document.documentCategoryId,
      visibilityType: document.visibilityType,
      departmentIds,
    })
  })

  return [...groups.values()]
}
