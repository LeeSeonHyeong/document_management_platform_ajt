import { useEffect, useMemo, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { FileText, Upload, X } from 'lucide-react'
import { Button, Chip, Modal, Select } from '@/components/ui'
import { useDepartments } from '@/features/department/useDepartments'
import { useDocumentCategories, useUploadDocuments } from '../queries'
import { buildScopeKey } from '../scope'
import {
  buildWikiUploadPayload,
  createWikiUploadEntries,
  validateWikiUpload,
  WIKI_UPLOAD_LIMIT,
} from '../uploadFlow'

const schema = z
  .object({
    visibilityType: z.enum(['all', 'department']),
    departmentIds: z.array(z.string()),
    documentCategoryId: z.string().min(1, '카테고리를 선택하세요'),
  })
  .refine((values) => values.visibilityType === 'all' || values.departmentIds.length > 0, {
    message: '부서를 1개 이상 선택하세요',
    path: ['departmentIds'],
  })

const ACCEPT_ATTRIBUTE = WIKI_UPLOAD_LIMIT.extensions.map((extension) => `.${extension}`).join(',')
const EMPTY_FILES = []

function formatBytes(bytes) {
  const mb = bytes / (1024 * 1024)
  if (mb >= 1) return `${mb.toFixed(1)}MB`
  return `${Math.max(1, Math.round(bytes / 1024))}KB`
}

export default function DocumentUploadModal({
  open,
  initialFiles = EMPTY_FILES,
  onClose,
  onUploaded,
}) {
  const inputRef = useRef(null)
  const fileSequence = useRef(0)
  const [files, setFiles] = useState([])
  const [progress, setProgress] = useState(0)
  const [transferred, setTransferred] = useState({ loaded: 0, total: 0 })

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    reset,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(schema),
    defaultValues: {
      visibilityType: 'all',
      departmentIds: [],
      documentCategoryId: '',
    },
  })

  const visibilityType = watch('visibilityType')
  const departmentIds = watch('departmentIds')
  const scopeKey = buildScopeKey(visibilityType, departmentIds)

  const { data: departments = [] } = useDepartments()
  const { data: categories = [] } = useDocumentCategories(scopeKey)
  const uploadMutation = useUploadDocuments()
  const uploading = uploadMutation.isPending

  useEffect(() => {
    if (!open) return

    const { entries, nextSequence } = createWikiUploadEntries(initialFiles)
    fileSequence.current = nextSequence
    setFiles(entries)
    setProgress(0)
    setTransferred({ loaded: 0, total: 0 })
    reset()
  }, [initialFiles, open, reset])

  useEffect(() => {
    setValue('documentCategoryId', '')
  }, [scopeKey, setValue])

  const validFiles = useMemo(() => files.filter((entry) => !entry.error), [files])
  const selectedFiles = useMemo(() => files.map((entry) => entry.file), [files])
  const aggregateError = files.length ? validateWikiUpload(selectedFiles) : null
  const totalBytes = useMemo(
    () => validFiles.reduce((sum, entry) => sum + entry.file.size, 0),
    [validFiles],
  )
  const hasInvalidFile = files.some((entry) => entry.error)
  const canSubmit = validFiles.length > 0 && !hasInvalidFile && !aggregateError && !uploading

  function addFiles(fileList) {
    const { entries, nextSequence } = createWikiUploadEntries(fileList, fileSequence.current)
    fileSequence.current = nextSequence
    setFiles((current) => [...current, ...entries])
  }

  function removeFile(id) {
    setFiles((current) => current.filter((entry) => entry.id !== id))
  }

  function toggleDepartment(departmentId) {
    const next = departmentIds.includes(departmentId)
      ? departmentIds.filter((id) => id !== departmentId)
      : [...departmentIds, departmentId]
    setValue('departmentIds', next, { shouldValidate: true })
  }

  function selectVisibility(nextVisibility) {
    setValue('visibilityType', nextVisibility)
    if (nextVisibility === 'all') setValue('departmentIds', [])
  }

  function resetModal() {
    reset()
    setFiles([])
    setProgress(0)
    setTransferred({ loaded: 0, total: 0 })
  }

  function closeModal() {
    if (uploading) return
    resetModal()
    onClose?.()
  }

  function submit(values) {
    if (!canSubmit) return
    setProgress(0)
    setTransferred({ loaded: 0, total: totalBytes })

    uploadMutation.mutate(
      buildWikiUploadPayload(
        validFiles.map((entry) => entry.file),
        {
          documentCategoryId: values.documentCategoryId,
          visibilityType: values.visibilityType,
          departmentIds: values.departmentIds,
        },
        (event) => {
          const total = event.total ?? totalBytes
          setTransferred({ loaded: event.loaded, total })
          if (total) setProgress(Math.round((event.loaded / total) * 100))
        },
      ),
      {
        onSuccess: (result) => {
          resetModal()
          onUploaded?.(result)
          onClose?.()
        },
      },
    )
  }

  return (
    <Modal
      open={open}
      onClose={uploading ? undefined : closeModal}
      closeOnOverlay={!uploading}
      title="원본문서 업로드"
      description="Wiki로 변환할 문서와 공개 범위·카테고리를 선택하세요."
      size="xl"
      footer={
        <>
          <Button variant="outline" onClick={closeModal} disabled={uploading}>
            취소
          </Button>
          <Button
            variant="primary"
            onClick={handleSubmit(submit)}
            loading={uploading}
            disabled={!canSubmit}
          >
            {uploading ? `업로드 중… ${progress}%` : `업로드 (${validFiles.length}건)`}
          </Button>
        </>
      }
    >
      <form className="space-y-5" onSubmit={handleSubmit(submit)}>
        <div>
          <button
            type="button"
            disabled={uploading}
            onClick={() => inputRef.current?.click()}
            onDrop={(event) => {
              event.preventDefault()
              if (!uploading) addFiles(event.dataTransfer.files)
            }}
            onDragOver={(event) => event.preventDefault()}
            className="focus-ring flex w-full flex-col items-center gap-2 rounded-xl border-2 border-dashed border-slate-300 bg-slate-50 px-4 py-8 text-slate-500 hover:border-primary-300 hover:bg-primary-50/40 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Upload className="size-8 text-slate-400" />
            <span className="text-sm font-medium text-slate-600">
              클릭하거나 파일을 끌어다 놓으세요
            </span>
            <span className="text-xs text-slate-400">
              TXT, MD, PDF, DOCX · 파일당 20MB · 최대 20건 · 총 100MB
            </span>
          </button>
          <input
            ref={inputRef}
            type="file"
            multiple
            accept={ACCEPT_ATTRIBUTE}
            className="hidden"
            onChange={(event) => {
              addFiles(event.target.files)
              event.target.value = ''
            }}
          />

          {aggregateError && <p className="mt-2 text-xs text-rose-600">{aggregateError}</p>}

          {files.length > 0 && (
            <ul className="mt-3 space-y-1.5">
              {files.map(({ id, file, error }) => (
                <li
                  key={id}
                  className={`flex items-center gap-3 rounded-lg border px-3 py-2 text-sm ${
                    error ? 'border-rose-200 bg-rose-50' : 'border-slate-200 bg-white'
                  }`}
                >
                  <FileText
                    className={`size-4 shrink-0 ${error ? 'text-rose-400' : 'text-slate-400'}`}
                  />
                  <div className="min-w-0 flex-1">
                    <p className={`truncate ${error ? 'text-rose-700' : 'text-slate-700'}`}>
                      {file.name}
                    </p>
                    <p className={`text-xs ${error ? 'text-rose-600' : 'text-slate-400'}`}>
                      {error ?? formatBytes(file.size)}
                    </p>
                  </div>
                  {!uploading && (
                    <button
                      type="button"
                      aria-label={`${file.name} 제거`}
                      onClick={() => removeFile(id)}
                      className="focus-ring rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                    >
                      <X className="size-4" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="space-y-2">
          <p className="text-sm font-medium text-slate-700">
            공개 범위<span className="ml-0.5 text-rose-500">*</span>
          </p>
          <div className="flex gap-2">
            <Button
              type="button"
              size="sm"
              variant={visibilityType === 'all' ? 'primary' : 'outline'}
              onClick={() => selectVisibility('all')}
              disabled={uploading}
            >
              전체
            </Button>
            <Button
              type="button"
              size="sm"
              variant={visibilityType === 'department' ? 'primary' : 'outline'}
              onClick={() => selectVisibility('department')}
              disabled={uploading}
            >
              부서 선택
            </Button>
          </div>
          {visibilityType === 'department' && (
            <div className="flex flex-wrap gap-2 pt-1">
              {departments.map((department) => (
                <Chip
                  key={department.departmentId}
                  role="button"
                  tabIndex={0}
                  selected={departmentIds.includes(department.departmentId)}
                  onClick={() => !uploading && toggleDepartment(department.departmentId)}
                  className="cursor-pointer"
                >
                  {department.name}
                </Chip>
              ))}
            </div>
          )}
          {errors.departmentIds && (
            <p className="text-xs text-rose-600">{errors.departmentIds.message}</p>
          )}
        </div>

        <Select
          label="카테고리"
          required
          placeholder={scopeKey ? '카테고리 선택' : '공개 범위를 먼저 선택하세요'}
          disabled={!scopeKey || uploading}
          options={categories.map((category) => ({
            value: category.documentCategoryId,
            label: category.name,
          }))}
          error={errors.documentCategoryId?.message}
          {...register('documentCategoryId')}
        />

        {uploading && (
          <div className="rounded-xl border border-primary-200 bg-primary-50/40 p-3">
            <div className="flex items-center justify-between text-xs">
              <span className="font-semibold text-slate-700">서버로 원본문서를 전송하고 있습니다.</span>
              <span className="font-bold text-primary-600">{progress}%</span>
            </div>
            <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-white">
              <div
                className="h-full rounded-full bg-primary-500 transition-all"
                style={{ width: `${progress}%` }}
              />
            </div>
            <p className="mt-1.5 text-[11px] text-slate-400">
              {formatBytes(transferred.loaded)} / {formatBytes(transferred.total || totalBytes)}
            </p>
          </div>
        )}

        {uploadMutation.isError && (
          <p className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
            {uploadMutation.error?.message ?? '원본문서 업로드에 실패했습니다. 다시 시도하세요.'}
          </p>
        )}
      </form>
    </Modal>
  )
}
