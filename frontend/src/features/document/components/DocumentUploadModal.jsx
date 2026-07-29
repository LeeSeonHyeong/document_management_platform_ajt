import { useEffect, useMemo, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Upload, FileText, X } from 'lucide-react'
import { Modal, Button, Select, Chip } from '@/components/ui'
import { FILE_ACCEPT, MAX_FILE_SIZE_BYTES } from '@/shared/constants/enums'
import { useDepartments } from '@/features/department/useDepartments'
import { useUploadDocuments, useDocumentCategories } from '../queries'
import { buildScopeKey } from '../scope'

// 업로드 제약(요구사항 NFR-FILE-001).
// enums.js에는 파일당 크기(MAX_FILE_SIZE_BYTES)와 허용 확장자(FILE_ACCEPT)만 있고
// "최대 건수·총합 용량"에 해당하는 UPLOAD_LIMIT 상수는 아직 없다.
// 공유 상수(A 영역)를 건드리지 않기 위해 여기서 조합해 사용한다.
// TODO(A): enums.js에 UPLOAD_LIMIT이 추가되면 그쪽으로 이관.
const UPLOAD_LIMIT = {
  accept: FILE_ACCEPT.WIKI_SOURCE, // ['txt','md','pdf','docx']
  maxFileBytes: MAX_FILE_SIZE_BYTES, // 파일당 20MB
  maxCount: 20, // 최대 20건
  maxTotalBytes: 100 * 1024 * 1024, // 총합 100MB
}

const ACCEPT_ATTR = UPLOAD_LIMIT.accept.map((ext) => `.${ext}`).join(',')

const schema = z
  .object({
    visibilityType: z.enum(['all', 'department']),
    departmentIds: z.array(z.string()),
    documentCategoryId: z.string().min(1, '카테고리를 선택하세요'),
  })
  .refine((v) => v.visibilityType === 'all' || v.departmentIds.length > 0, {
    message: '부서를 1개 이상 선택하세요',
    path: ['departmentIds'],
  })

function formatBytes(bytes) {
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)}MB` : `${Math.max(1, Math.round(bytes / 1024))}KB`
}

// 파일 1건 검증. 통과하면 null, 실패하면 사유 문자열을 반환한다.
function validateFile(file) {
  const ext = file.name.split('.').pop()?.toLowerCase()
  if (!ext || !UPLOAD_LIMIT.accept.includes(ext)) {
    return `지원하지 않는 형식입니다 (${UPLOAD_LIMIT.accept.join(', ')}만 허용)`
  }
  if (file.size > UPLOAD_LIMIT.maxFileBytes) {
    return `파일당 최대 ${formatBytes(UPLOAD_LIMIT.maxFileBytes)}까지 가능합니다`
  }
  return null
}

// Figma 4-1R — 원본문서 업로드 모달. DocumentListPage의 업로드 버튼이 연다.
// 성공 시(202) 모달을 닫고 onUploaded(응답)로 jobId를 상위에 전달한다.
export default function DocumentUploadModal({ open, onClose, onUploaded }) {
  const inputRef = useRef(null)
  const fileSeq = useRef(0)
  const [files, setFiles] = useState([]) // [{ id, file, error }]
  const [progress, setProgress] = useState(0)

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    reset,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(schema),
    defaultValues: { visibilityType: 'all', departmentIds: [], documentCategoryId: '' },
  })

  const visibilityType = watch('visibilityType')
  const departmentIds = watch('departmentIds')
  const scopeKey = buildScopeKey(visibilityType, departmentIds)

  const { data: departments = [] } = useDepartments()
  const { data: categories = [] } = useDocumentCategories(scopeKey)
  const uploadMutation = useUploadDocuments()
  const uploading = uploadMutation.isPending

  // 공개 범위(scopeKey)가 바뀌면 카테고리 목록이 달라지므로 선택을 초기화한다.
  useEffect(() => {
    setValue('documentCategoryId', '')
  }, [scopeKey, setValue])

  const validFiles = useMemo(() => files.filter((f) => !f.error), [files])
  const totalBytes = useMemo(
    () => validFiles.reduce((sum, f) => sum + f.file.size, 0),
    [validFiles],
  )

  // 파일 목록 전체에 대한 집계 검증(건수·총합). 개별 파일 검증은 각 행에 표시한다.
  const aggregateError =
    files.length > UPLOAD_LIMIT.maxCount
      ? `한 번에 최대 ${UPLOAD_LIMIT.maxCount}건까지 업로드할 수 있습니다`
      : totalBytes > UPLOAD_LIMIT.maxTotalBytes
        ? `전체 용량은 최대 ${formatBytes(UPLOAD_LIMIT.maxTotalBytes)}까지 가능합니다`
        : null

  const hasInvalidFile = files.some((f) => f.error)
  const canSubmit = validFiles.length > 0 && !hasInvalidFile && !aggregateError && !uploading

  function addFiles(fileList) {
    const added = Array.from(fileList).map((file) => ({
      id: (fileSeq.current += 1),
      file,
      error: validateFile(file),
    }))
    setFiles((prev) => [...prev, ...added])
  }

  function removeFile(id) {
    setFiles((prev) => prev.filter((f) => f.id !== id))
  }

  function handleDrop(e) {
    e.preventDefault()
    if (uploading) return
    if (e.dataTransfer.files?.length) addFiles(e.dataTransfer.files)
  }

  function toggleDepartment(departmentId) {
    const next = departmentIds.includes(departmentId)
      ? departmentIds.filter((id) => id !== departmentId)
      : [...departmentIds, departmentId]
    setValue('departmentIds', next, { shouldValidate: true })
  }

  function setVisibility(next) {
    setValue('visibilityType', next)
    if (next === 'all') setValue('departmentIds', [])
  }

  function handleClose() {
    if (uploading) return // 업로드 중에는 닫을 수 없다
    reset()
    setFiles([])
    setProgress(0)
    onClose?.()
  }

  function onSubmit(values) {
    if (!canSubmit) return
    setProgress(0)
    uploadMutation.mutate(
      {
        files: validFiles.map((f) => f.file),
        documentCategoryId: values.documentCategoryId,
        visibilityType: values.visibilityType,
        departmentIds: values.departmentIds,
        onUploadProgress: (e) => {
          if (e.total) setProgress(Math.round((e.loaded / e.total) * 100))
        },
      },
      {
        onSuccess: (data) => {
          reset()
          setFiles([])
          setProgress(0)
          onUploaded?.(data)
          onClose?.()
        },
      },
    )
  }

  return (
    <Modal
      open={open}
      onClose={uploading ? undefined : handleClose}
      closeOnOverlay={!uploading}
      title="원본문서 업로드"
      description="Wiki로 변환할 원본문서를 업로드합니다. 업로드 후 순서대로 AI 작업이 진행됩니다."
      size="xl"
      footer={
        <>
          <Button variant="outline" onClick={handleClose} disabled={uploading}>
            취소
          </Button>
          <Button variant="primary" onClick={handleSubmit(onSubmit)} loading={uploading} disabled={!canSubmit}>
            {uploading ? `업로드 중… ${progress}%` : `업로드 (${validFiles.length}건)`}
          </Button>
        </>
      }
    >
      <form className="space-y-5" onSubmit={handleSubmit(onSubmit)}>
        {/* 1. 파일 — 드래그앤드롭 + 선택 */}
        <div>
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            onDrop={handleDrop}
            onDragOver={(e) => e.preventDefault()}
            disabled={uploading}
            className="focus-ring flex w-full flex-col items-center gap-2 rounded-xl border-2 border-dashed border-slate-300 bg-slate-50 px-4 py-8 text-slate-500 hover:border-primary-300 hover:bg-primary-50/40 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Upload className="size-8 text-slate-400" />
            <span className="text-sm font-medium text-slate-600">클릭하거나 파일을 끌어다 놓으세요</span>
            <span className="text-xs text-slate-400">
              {UPLOAD_LIMIT.accept.join(', ')} · 파일당 {formatBytes(UPLOAD_LIMIT.maxFileBytes)} · 최대 {UPLOAD_LIMIT.maxCount}건 · 총 {formatBytes(UPLOAD_LIMIT.maxTotalBytes)}
            </span>
          </button>
          <input
            ref={inputRef}
            type="file"
            multiple
            accept={ACCEPT_ATTR}
            className="hidden"
            onChange={(e) => {
              if (e.target.files?.length) addFiles(e.target.files)
              e.target.value = '' // 같은 파일 재선택 허용
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
                  <FileText className={`size-4 shrink-0 ${error ? 'text-rose-400' : 'text-slate-400'}`} />
                  <div className="min-w-0 flex-1">
                    <p className={`truncate ${error ? 'text-rose-700' : 'text-slate-700'}`}>{file.name}</p>
                    {error ? (
                      <p className="text-xs text-rose-600">{error}</p>
                    ) : (
                      <p className="text-xs text-slate-400">{formatBytes(file.size)}</p>
                    )}
                  </div>
                  {!uploading && (
                    <button
                      type="button"
                      onClick={() => removeFile(id)}
                      className="focus-ring rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                      aria-label="파일 제거"
                    >
                      <X className="size-4" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* 2. 공개 범위 */}
        <div className="space-y-2">
          <p className="text-sm font-medium text-slate-700">
            공개 범위<span className="ml-0.5 text-rose-500">*</span>
          </p>
          <div className="flex gap-2">
            <Button
              type="button"
              size="sm"
              variant={visibilityType === 'all' ? 'primary' : 'outline'}
              onClick={() => setVisibility('all')}
              disabled={uploading}
            >
              전체
            </Button>
            <Button
              type="button"
              size="sm"
              variant={visibilityType === 'department' ? 'primary' : 'outline'}
              onClick={() => setVisibility('department')}
              disabled={uploading}
            >
              부서 선택
            </Button>
          </div>
          {visibilityType === 'department' && (
            <div className="flex flex-wrap gap-2 pt-1">
              {departments.map((dept) => (
                <Chip
                  key={dept.departmentId}
                  role="button"
                  tabIndex={0}
                  selected={departmentIds.includes(dept.departmentId)}
                  onClick={() => !uploading && toggleDepartment(dept.departmentId)}
                  className="cursor-pointer"
                >
                  {dept.name}
                </Chip>
              ))}
            </div>
          )}
          {errors.departmentIds && <p className="text-xs text-rose-600">{errors.departmentIds.message}</p>}
        </div>

        {/* 3. 카테고리 — 선택한 공개 범위(scopeKey)의 카테고리만 */}
        <Select
          label="카테고리"
          required
          placeholder={scopeKey ? '카테고리 선택' : '공개 범위를 먼저 선택하세요'}
          disabled={!scopeKey || uploading}
          options={categories.map((c) => ({ value: c.documentCategoryId, label: c.name }))}
          error={errors.documentCategoryId?.message}
          {...register('documentCategoryId')}
        />

        {uploading && (
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
            <div className="h-full rounded-full bg-primary-500 transition-all" style={{ width: `${progress}%` }} />
          </div>
        )}
      </form>
    </Modal>
  )
}
