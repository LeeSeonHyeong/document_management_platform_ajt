import { useEffect, useRef, useState } from 'react'
import { ArrowDown, ArrowLeftRight, FileText, Info, Upload, X } from 'lucide-react'
import { Button, Modal, useToast } from '@/components/ui'
import { useReplaceDocumentFile } from '../queries'

const ALLOWED_EXTENSIONS = ['pdf', 'docx', 'txt', 'md', 'csv', 'xlsx']
const MAX_FILE_SIZE = 20 * 1024 * 1024

function formatBytes(bytes) {
  if (!bytes && bytes !== 0) return '-'
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function fileExtension(name) {
  return name?.includes('.') ? name.split('.').pop().toLowerCase() : ''
}

export default function DocumentReplaceDialog({ open, document, onClose, onStarted }) {
  const toast = useToast()
  const fileInputRef = useRef(null)
  const [file, setFile] = useState(null)
  const [progress, setProgress] = useState(0)
  const replaceMutation = useReplaceDocumentFile(document?.documentId)
  const preparing = Boolean(file) && progress < 100

  useEffect(() => {
    if (!open) {
      setFile(null)
      setProgress(0)
    }
  }, [open])

  useEffect(() => {
    if (!file || progress >= 100) return undefined
    const timer = window.setInterval(() => {
      setProgress((current) => Math.min(100, current + 20))
    }, 120)
    return () => window.clearInterval(timer)
  }, [file, progress])

  function selectFile(nextFile) {
    if (!nextFile) return
    const extension = fileExtension(nextFile.name)
    if (!ALLOWED_EXTENSIONS.includes(extension)) {
      toast.error('PDF, DOCX, TXT, MD, CSV, XLSX 파일만 선택할 수 있습니다.')
      return
    }
    if (nextFile.size > MAX_FILE_SIZE) {
      toast.error('파일은 최대 20MB까지 선택할 수 있습니다.')
      return
    }
    setFile(nextFile)
    setProgress(1)
  }

  function handleClose() {
    if (replaceMutation.isPending) return
    setFile(null)
    setProgress(0)
    onClose?.()
  }

  function handleReplace() {
    if (!file || preparing || replaceMutation.isPending) return
    replaceMutation.mutate(file, {
      onSuccess: (result) => {
        toast.success('원본 문서 교체 작업을 시작했습니다.')
        setFile(null)
        setProgress(0)
        onStarted?.(result)
      },
      onError: (error) => {
        if (error?.response?.status === 409) {
          toast.error('현재 처리 중인 문서는 교체할 수 없습니다.')
        } else if (error?.response?.status === 400) {
          toast.error('교체할 파일의 형식이나 용량을 확인해주세요.')
        } else {
          toast.error('원본 문서를 교체하지 못했습니다.')
        }
      },
    })
  }

  if (!document) return null

  const departmentLabel =
    document.visibilityType === 'all'
      ? '전체 공개'
      : (document.departments ?? []).map((department) => department.name).join(', ') || '미지정'

  return (
    <Modal
      open={open}
      onClose={handleClose}
      closeOnOverlay={!replaceMutation.isPending}
      showClose={false}
      size="lg"
      footerClassName="grid grid-cols-2 gap-3 bg-slate-50 px-6 py-4"
      footer={
        <>
          <Button variant="outline" onClick={handleClose} disabled={replaceMutation.isPending} fullWidth>
            취소
          </Button>
          <Button
            variant="danger"
            onClick={handleReplace}
            disabled={!file || preparing}
            loading={replaceMutation.isPending}
            fullWidth
          >
            교체
          </Button>
        </>
      }
    >
      <span className="flex size-12 items-center justify-center rounded-2xl bg-primary-50 text-primary-600">
        <ArrowLeftRight className="size-6" />
      </span>
      <h2 className="mt-4 text-xl font-bold text-slate-900">원본 문서를 교체할까요?</h2>
      <p className="mt-1.5 text-sm text-slate-500">
        기존 원본은 삭제되고, 새로 올린 문서가 AI 작업을 거쳐 위키에 반영됩니다.
      </p>

      <p className="mt-5 text-xs font-semibold text-slate-400">삭제되는 문서</p>
      <div className="mt-2 rounded-xl border border-rose-200 bg-rose-50 px-3 py-3">
        <div className="flex items-center gap-3">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-white text-rose-500">
            <FileText className="size-4" />
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-bold text-slate-800">{document.originalFileName}</p>
            <p className="mt-0.5 text-xs text-slate-400">
              {fileExtension(document.originalFileName).toUpperCase()} · {formatBytes(document.fileSize)}
            </p>
          </div>
          <span className="rounded-lg bg-rose-100 px-2 py-1 text-[11px] font-semibold text-rose-500">
            삭제 예정
          </span>
        </div>
        <div className="mt-2 flex flex-wrap gap-1.5 text-[11px]">
          <span className="rounded-md border border-rose-100 bg-white px-2 py-1 text-slate-600">
            공개 부서&nbsp; <strong>{departmentLabel}</strong>
          </span>
          <span className="rounded-md border border-rose-100 bg-white px-2 py-1 text-slate-600">
            카테고리&nbsp; <strong>{document.documentCategoryName ?? '미분류'}</strong>
          </span>
        </div>
      </div>

      <div className="flex justify-center py-2 text-slate-400">
        <ArrowDown className="size-4" />
      </div>

      <p className="text-xs font-semibold text-slate-400">
        새로 등록되는 문서{preparing ? ' · 업로드 중' : ''}
      </p>
      {!file ? (
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault()
            selectFile(event.dataTransfer.files?.[0])
          }}
          className="focus-ring mt-2 flex w-full flex-col items-center rounded-xl border border-dashed border-primary-300 bg-primary-50/50 px-4 py-5 text-center hover:bg-primary-50"
        >
          <span className="flex size-9 items-center justify-center rounded-xl border border-primary-200 bg-white text-primary-500">
            <Upload className="size-4" />
          </span>
          <span className="mt-2 text-sm font-semibold text-slate-700">
            파일을 끌어다 놓거나 클릭해서 선택
          </span>
          <span className="mt-1 text-xs text-slate-400">
            PDF · DOCX · TXT · MD · CSV · XLSX · 최대 20MB
          </span>
        </button>
      ) : (
        <div className="mt-2 rounded-xl border border-primary-200 bg-white px-3 py-3">
          <div className="flex items-center gap-3">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-500">
              <FileText className="size-4" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-bold text-slate-800">{file.name}</p>
              <p className="mt-0.5 text-xs text-slate-400">
                {fileExtension(file.name).toUpperCase()} · {formatBytes(file.size)}
              </p>
            </div>
            {preparing ? (
              <span className="text-xs font-bold text-primary-600">{progress}%</span>
            ) : (
              <>
                <span className="rounded-lg bg-primary-50 px-2 py-1 text-[11px] font-semibold text-primary-600">
                  업로드 완료
                </span>
                <button
                  type="button"
                  onClick={() => {
                    setFile(null)
                    setProgress(0)
                  }}
                  className="focus-ring rounded-md p-1 text-slate-400 hover:bg-slate-100"
                  aria-label="선택 파일 변경"
                >
                  <X className="size-4" />
                </button>
              </>
            )}
          </div>
          {preparing && (
            <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-100">
              <div
                className="h-full rounded-full bg-gradient-to-r from-blue-500 to-violet-600 transition-[width]"
                style={{ width: `${progress}%` }}
              />
            </div>
          )}
        </div>
      )}
      <input
        ref={fileInputRef}
        type="file"
        accept=".pdf,.docx,.txt,.md,.csv,.xlsx"
        className="hidden"
        onChange={(event) => {
          selectFile(event.target.files?.[0])
          event.target.value = ''
        }}
      />

      <div className="mt-3 grid grid-cols-2 gap-3">
        <MetadataField label="공개 부서" value={departmentLabel} />
        <MetadataField label="카테고리" value={document.documentCategoryName ?? '미분류'} />
      </div>
      <p className="mt-2 text-xs text-slate-400">
        기존 문서의 분류가 그대로 적용됩니다.
      </p>

      <div className="mt-3 flex items-center gap-2 rounded-xl bg-primary-50 px-3 py-2.5 text-xs text-slate-500">
        <Info className="size-4 shrink-0 text-primary-500" />
        새 문서는 AI 작업을 거쳐 기존 위키 문서에 반영됩니다.
      </div>
      <div className="mt-2 flex items-center gap-2 rounded-xl bg-slate-50 px-3 py-2.5 text-xs text-slate-500">
        <span className="flex size-4 shrink-0 items-center justify-center rounded-full bg-rose-100 text-[10px] font-bold text-rose-500">
          !
        </span>
        기존 원본은 위키 반영이 끝난 뒤 삭제되며, 삭제한 원본은 복구할 수 없습니다.
      </div>
    </Modal>
  )
}

function MetadataField({ label, value }) {
  return (
    <div>
      <p className="mb-1.5 text-xs font-medium text-slate-500">{label}</p>
      <div className="flex min-h-10 items-center rounded-lg border border-slate-200 bg-slate-50 px-3 text-sm font-semibold text-slate-700">
        <span className="truncate">{value}</span>
      </div>
    </div>
  )
}
