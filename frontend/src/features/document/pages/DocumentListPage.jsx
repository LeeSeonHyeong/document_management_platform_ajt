import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Upload, FileText, CalendarDays, X, AlertTriangle, Check } from 'lucide-react'
import { Button, EmptyState, Spinner, useToast } from '@/components/ui'
import { useAuth } from '@/hooks/useAuth'
import {
  FILE_ACCEPT,
  FILE_MIME_TYPES,
  MAX_FILE_SIZE_BYTES,
  MAX_UPLOAD_FILE_COUNT,
  MAX_UPLOAD_TOTAL_SIZE_BYTES,
} from '@/shared/constants/enums'
import {
  useCreateAiJob,
  useDeleteDocument,
  useDocuments,
  useUploadDocuments,
  useUploadScheduleSource,
} from '../queries'
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
  // 전송·추출이 진행 중인 일정 파일. 파싱·추출이 동기라 몇 분씩 걸리는데, 그 사이 대기 목록에
  // 남아 있어 다시 시작하면 같은 파일이 또 전송돼 일정 초안이 중복 생성된다(S15P11B106-284).
  const [processingScheduleIds, setProcessingScheduleIds] = useState(() => new Set())
  const scheduleUploadCount = processingScheduleIds.size
  // 문서 파일 대기 목록은 서버에서 읽는다 (S15P11B106-276). 파일을 고르는 즉시 업로드하므로
  // 새로고침해도 남아 있다.
  //
  // 기준은 status=uploaded다 — 업로드는 끝났고 아직 AI 작업이 시작되지 않은 문서. classified로
  // 거르면 분류를 지정하는 순간 목록에서 사라져 「지정 후 시작」을 할 수 없다. 작업이 시작되면
  // 문서가 parsing으로 넘어가면서 자연히 빠진다.
  const pendingDocumentsQuery = useDocuments({ status: 'uploaded', size: 100 })
  const pendingDocuments = pendingDocumentsQuery.data?.items ?? []
  // 일정 파일은 아직 로컬 대기다. POST /schedule-sources가 공개 범위를 필수로 받아
  // 분류 전에 올릴 수 없다 — 일정 쪽 초안 지원은 별건이다.
  const {
    queueDocuments: previewQueueDocuments,
    queueMetadata,
    addDocuments,
    updateMetadata,
    removeDocuments,
  } = useAiJobQueue()
  const uploadDocumentsMutation = useUploadDocuments()
  const uploadScheduleMutation = useUploadScheduleSource()
  const createAiJobMutation = useCreateAiJob()
  const deleteDocumentMutation = useDeleteDocument()
  // 모달의 「시작 중…」은 작업 생성만 기다린다. 일정 파일 전송은 모달을 닫은 뒤 진행한다 —
  // 파싱·추출이 동기라 오래 걸려서, 기다리면 화면이 붙잡힌다.
  const isStarting = createAiJobMutation.isPending || scheduleUploadCount > 0
  // 오버레이(queueMetadata)는 서버 문서에도 씌운다. 공개 부서만 먼저 고른 상태는 아직
  // 서버에 저장할 수 없어(카테고리와 함께 보내야 한다) 화면에만 담아 둔다.
  const withOverlay = (document) => ({ ...document, ...queueMetadata[document.documentId] })
  const scheduleDocuments = previewQueueDocuments.map(withOverlay)
  const waitingDocuments = [...pendingDocuments.map(withOverlay), ...scheduleDocuments]
  // 전송 중인 일정 파일은 시작 대상에서 뺀다 — 버튼을 잠그긴 했지만 중복 전송의 유일한 방어선을
  // 화면 상태 하나에 걸지 않는다.
  const readyDocuments = waitingDocuments
    .filter(isAssigned)
    .filter((document) => !processingScheduleIds.has(document.documentId))
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
          // 파일을 고르는 즉시 서버로 보낸다. 분류는 대기 목록에서 지정한다(S15P11B106-276).
          // 파일별로 요청을 나누는 이유: 진행률을 파일 단위로 보여주고, 한 건이 실패해도
          // 나머지는 올라가게 하려는 것이다.
          onUploadFile={async (file, { onProgress }) => {
            await uploadDocumentsMutation.mutateAsync({
              files: [file],
              onUploadProgress: (event) => {
                const total = event.total ?? file.size
                if (total) onProgress(Math.min(100, Math.round((event.loaded / total) * 100)))
              },
            })
          }}
          onUploaded={(uploadedFiles) => {
            const extraCount = uploadedFiles.length - 1
            toast.success(
              extraCount > 0
                ? `${uploadedFiles[0].name} 외 ${extraCount}개 문서를 올렸습니다. 카테고리와 공개 부서를 지정해주세요.`
                : `${uploadedFiles[0].name}을 올렸습니다. 카테고리와 공개 부서를 지정해주세요.`,
            )
          }}
          onUploadFailed={(file, error) => {
            toast.error(`${shortFileName(file.name)} — ${uploadErrorMessage(error)}`)
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
          loading={pendingDocumentsQuery.isLoading}
          // 서버에 있는 문서는 지정 즉시 PATCH로 저장한다 — 그래야 새로고침해도 남는다.
          // 일정 파일은 아직 로컬 대기라 화면 상태만 바꾼다.
          // 지정값은 화면 오버레이에 담는다. 서버 저장은 필드 컴포넌트가 한다 —
          // 카테고리를 고르는 시점에 공개 범위까지 함께 PATCH로 보낸다.
          onQueueMetadataChange={(documentId, changes) => updateMetadata(documentId, changes)}
          renderAction={(doc) => (
            <button
              type="button"
              aria-label={`${doc.originalFileName} 대기 목록에서 삭제`}
              onClick={async (event) => {
                event.stopPropagation()
                if (doc.previewOnly) {
                  if (processingScheduleIds.has(doc.documentId)) {
                    toast.error('일정을 추출하는 중인 파일은 지울 수 없습니다.')
                    return
                  }
                  removeDocuments([doc.documentId])
                  return
                }
                // 서버에 올라간 문서다. 위키에 반영된 적이 없으므로 서버가 행과 파일을
                // 즉시 지운다(deleted=true·status=skipped).
                try {
                  await deleteDocumentMutation.mutateAsync(doc.documentId)
                } catch (error) {
                  toast.error(
                    error?.message ?? '문서를 지우지 못했습니다.',
                  )
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
        pending={isStarting}
        onClose={() => {
          if (!isStarting) setStartOpen(false)
        }}
        onConfirm={async () => {
          // 문서 파일은 이미 서버에 있다. 작업 생성만 하면 된다 — 공개 범위가 섞여 있어도
          // 서버가 범위별로 작업을 나눈다(POST /ai-jobs).
          const serverDocuments = readyDocuments.filter((document) => !document.previewOnly)
          // 일정 파일은 아직 로컬 대기다. 여기서 처음 전송된다.
          const scheduleDocuments = readyDocuments.filter(
            (document) => document.previewOnly && document.sourceFile,
          )

          // 1) 위키 작업을 먼저 만들고 진행 모달을 띄운다.
          //
          // 일정 업로드를 기다린 뒤에 띄우면 화면이 「시작 중…」에 오래 머문다 —
          // POST /schedule-sources는 파싱·추출까지 동기로 끝내 파일당 최대 180초다.
          // 위키 작업은 이미 백그라운드에서 돌고 있으니 그 진행부터 보여준다.
          let startedJobIds = []
          if (serverDocuments.length > 0) {
            try {
              const result = await createAiJobMutation.mutateAsync(
                serverDocuments.map((document) => Number(document.documentId)),
              )
              startedJobIds = (result.jobs ?? []).map((job) => job.jobId)
            } catch (error) {
              toast.error(uploadErrorMessage(error))
              return
            }
          }

          setStartOpen(false)
          if (startedJobIds.length > 0) {
            toast.success(`${serverDocuments.length}개 문서의 AI 작업을 시작했습니다.`)
            setProgressJobIds(startedJobIds)
            setProgressDocumentCount(serverDocuments.length)
            setProgressOpen(true)
          }

          if (scheduleDocuments.length === 0) {
            if (startedJobIds.length === 0) navigate('/admin/documents/source')
            return
          }

          // 2) 일정 파일은 병렬로 보낸다. 서로 독립적인 요청이라 순차로 돌리면 파일 수만큼
          //    시간이 곱해진다(3개면 최장 9분). 실패한 파일만 대기 목록에 남긴다.
          setProcessingScheduleIds(new Set(scheduleDocuments.map((d) => d.documentId)))
          const results = await Promise.allSettled(
            scheduleDocuments.map((document) =>
              uploadScheduleMutation
                .mutateAsync({
                  file: document.sourceFile,
                  visibilityType: document.visibilityType,
                  departmentIds: getDepartmentIds(document),
                })
                .then(() => document.documentId),
            ),
          )
          setProcessingScheduleIds(new Set())

          const uploadedIds = results
            .filter((result) => result.status === 'fulfilled')
            .map((result) => result.value)
          const failed = scheduleDocuments.filter(
            (document) => !uploadedIds.includes(document.documentId),
          )
          if (uploadedIds.length > 0) {
            removeDocuments(uploadedIds)
            toast.success(`${uploadedIds.length}개 일정 파일에서 일정을 추출했습니다.`)
          }
          if (failed.length > 0) {
            const extra = failed.length > 1 ? ` 외 ${failed.length - 1}개` : ''
            toast.error(
              `${failed[0].originalFileName}${extra}의 일정 추출에 실패했습니다. 대기 목록에 남겨두었습니다.`,
            )
          }
          if (startedJobIds.length === 0 && uploadedIds.length > 0) {
            navigate('/admin/schedules')
          }
        }}
      />

      {scheduleUploadCount > 0 && (
        <div className="flex items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-xs text-emerald-700">
          <Spinner className="size-4" />
          <p>
            일정 파일 {scheduleUploadCount}개에서 일정을 추출하고 있습니다. 파일에 따라 몇 분
            걸릴 수 있습니다. 다른 메뉴로 이동해도 계속되지만, 새로고침하거나 탭을 닫으면
            중단됩니다.
          </p>
        </div>
      )}

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
  onUploadFile,
  onUploaded,
  onUploadFailed,
  onClick,
  queuedCount = 0,
  queuedBytes = 0,
}) {
  const schedule = tone === 'schedule'
  const toast = useToast()
  const inputRef = useRef(null)
  const handlersRef = useRef({})
  const [dragging, setDragging] = useState(false)
  const [currentIndex, setCurrentIndex] = useState(0)
  // 진행 중인 파일의 실제 전송률(%). 서버로 보내지 않는 카드에서는 null이다.
  const [progress, setProgress] = useState(null)
  const [failedNames, setFailedNames] = useState([])
  const acceptsFilesDirectly = Boolean(onFilesSelected)
  // 이 카드가 파일을 서버로 직접 보내는지. 보내는 카드만 실제 전송률을 표시한다.
  const uploadsToServer = Boolean(onUploadFile)

  useEffect(() => {
    handlersRef.current = { onUploadComplete, onUploadFile, onUploaded, onUploadFailed }
  }, [onUploadComplete, onUploadFile, onUploaded, onUploadFailed])

  // 고른 파일을 하나씩 처리한다.
  //
  // onUploadFile이 있으면 그 파일을 실제로 서버에 보내고 전송률을 그대로 보여준다
  // (S15P11B106-276). 없으면(일정 파일) 예전처럼 로컬 대기 목록에 담기만 한다 —
  // POST /schedule-sources가 공개 범위를 필수로 받아 분류 전에 올릴 수 없다.
  useEffect(() => {
    if (!selectedFiles.length) {
      setCurrentIndex(0)
      setProgress(null)
      setFailedNames([])
      return undefined
    }

    let cancelled = false
    setCurrentIndex(0)
    setFailedNames([])
    ;(async () => {
      const uploaded = []
      const failed = []
      for (let index = 0; index < selectedFiles.length; index += 1) {
        if (cancelled) return
        const file = selectedFiles[index]
        setCurrentIndex(index)
        const handlers = handlersRef.current
        if (handlers.onUploadFile) {
          setProgress(0)
          try {
            await handlers.onUploadFile(file, {
              onProgress: (percent) => {
                if (!cancelled) setProgress(percent)
              },
            })
            uploaded.push(file)
          } catch (error) {
            // 한 건이 실패해도 나머지는 계속 올린다. 실패한 파일만 알려준다.
            failed.push(file.name)
            setFailedNames((current) => [...current, file.name])
            handlers.onUploadFailed?.(file, error)
          }
        } else {
          await handlers.onUploadComplete?.([file], {
            index,
            isLast: index === selectedFiles.length - 1,
            files: selectedFiles,
          })
          uploaded.push(file)
        }
      }
      if (cancelled) return
      setProgress(null)
      if (uploaded.length) handlersRef.current.onUploaded?.(uploaded)
      // 실패한 파일이 있으면 목록을 남겨 무엇이 안 올라갔는지 보이게 한다.
      if (failed.length === 0) onFilesSelected?.([])
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
    setProgress(null)
    setFailedNames([])
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
                  <p className="text-sm font-bold text-slate-800">
                    {uploadsToServer ? '업로드 중' : '대기 목록에 추가 중'}
                  </p>
                  <p className="text-[11px] text-slate-400">
                    {uploadsToServer
                      ? '올린 뒤 카테고리와 공개 부서를 지정하세요'
                      : '실제 업로드는 ‘AI 작업 시작’에서 진행됩니다'}
                  </p>
                </div>
              </div>
              <span className="rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-semibold text-slate-500">
                {currentIndex + 1} / {selectedFiles.length}
              </span>
            </div>
            <div className="mt-3 max-h-[328px] space-y-1.5 overflow-y-auto pr-1">
              {selectedFiles.map((file, index) => {
                const failed = failedNames.includes(file.name)
                const done = index < currentIndex
                const status = failed
                  ? '실패'
                  : done
                    ? uploadsToServer ? '업로드 완료' : '추가 완료'
                    : index === currentIndex
                      ? uploadsToServer && progress !== null
                        ? `${progress}%`
                        : uploadsToServer ? '업로드 중' : '추가 중'
                      : '대기'
                // 서버로 보내는 카드만 전송률 바를 그린다. 값은 axios가 보고한 실제 전송량이다.
                const barPercent = failed ? 0 : done ? 100 : index === currentIndex ? (progress ?? 0) : 0
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
                          failed
                            ? 'text-rose-500'
                            : index > currentIndex
                              ? 'text-slate-400'
                              : schedule
                                ? 'text-emerald-600'
                                : 'text-primary-600'
                        }`}
                      >
                        {status}
                      </span>
                    </div>
                    {uploadsToServer && !failed && (
                      <div className="mt-1.5 h-1 w-full overflow-hidden rounded-full bg-slate-100">
                        <div
                          className="h-full rounded-full bg-gradient-to-r from-blue-500 to-violet-600 transition-[width]"
                          style={{ width: `${barPercent}%` }}
                        />
                      </div>
                    )}
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

// 실패 원인을 그대로 보여준다.
//
// api/client.js 의 응답 인터셉터가 axios 에러를 { status, code, message, fieldErrors } 로
// 정규화해 reject 한다 — `error.response` 는 없다. 그걸 읽으려 하면 서버가 준 이유가
// 가려지고 매번 기본 문구만 뜬다.
function uploadErrorMessage(error) {
  const fieldMessage = error?.fieldErrors?.[0]?.message
  if (fieldMessage) return fieldMessage
  if (error?.message) return error.message
  return '서버에 연결하지 못했습니다.'
}

// 토스트가 파일명으로 가득 차지 않게 줄인다. 긴 회의록 파일명이 흔하다.
function shortFileName(fileName) {
  return fileName.length > 40 ? `${fileName.slice(0, 37)}…` : fileName
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

