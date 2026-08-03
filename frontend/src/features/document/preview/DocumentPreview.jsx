import { Suspense, lazy, useCallback, useEffect, useState } from 'react'
import { ChevronLeft, ChevronRight, FileWarning } from 'lucide-react'
import { Spinner } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { PREVIEW_KIND, fileExtension } from './kind'
import { useDocumentPreview } from './useDocumentPreview'

// 확장자별 렌더러는 실제로 그 확장자를 열 때만 내려받는다.
// pdf.js·docx-preview·exceljs 는 각각 수백 KB라 초기 번들에 넣지 않는다.
const PdfPreview = lazy(() => import('./renderers/PdfPreview'))
const DocxPreview = lazy(() => import('./renderers/DocxPreview'))
const SheetPreview = lazy(() => import('./renderers/SheetPreview'))
const TextPreview = lazy(() => import('./renderers/TextPreview'))

function Centered({ children, className }) {
  return (
    <div className={cn('flex min-h-72 flex-col items-center justify-center gap-2 text-center', className)}>
      {children}
    </div>
  )
}

function Message({ title, detail }) {
  return (
    <Centered>
      <FileWarning className="size-8 text-slate-300" />
      <p className="text-sm font-semibold text-slate-600">{title}</p>
      {detail && <p className="max-w-sm text-xs leading-6 text-slate-400">{detail}</p>}
    </Centered>
  )
}

/**
 * 원본 문서 미리보기.
 * 저장된 문서는 GET /api/v1/documents/:id/file 의 실제 파일을, 업로드 대기 중 문서는
 * 손에 있는 File 을 렌더한다. 렌더는 전부 브라우저에서 하며 별도 미리보기 API 를 쓰지 않는다.
 */
export default function DocumentPreview({
  documentId,
  fileName,
  mimeType,
  sourceFile,
  localOnly = false,
  enabled = true,
  className,
}) {
  const { kind, blob, isLoading, error } = useDocumentPreview({
    documentId,
    fileName,
    mimeType,
    sourceFile,
    localOnly,
    enabled,
  })

  const [pageCount, setPageCount] = useState(1)
  const [pageNumber, setPageNumber] = useState(1)
  const [renderError, setRenderError] = useState(null)

  // 다른 문서로 바뀌면 페이지 상태를 초기화한다.
  useEffect(() => {
    setPageCount(1)
    setPageNumber(1)
    setRenderError(null)
  }, [documentId, blob])

  const handlePageCount = useCallback((count) => setPageCount(count), [])
  const handleError = useCallback((cause) => setRenderError(cause ?? new Error('render failed')), [])

  function body() {
    if (!enabled) return null

    if (isLoading) {
      return (
        <Centered>
          <Spinner size="sm" />
        </Centered>
      )
    }

    if (error) {
      const forbidden = error?.response?.status === 403
      return (
        <Message
          title={forbidden ? '이 문서를 볼 권한이 없습니다.' : '원본 파일을 불러오지 못했습니다.'}
          detail={forbidden ? null : '잠시 후 다시 시도하거나 다운로드로 확인해주세요.'}
        />
      )
    }

    if (!blob) {
      return (
        <Message
          title="원본 파일을 찾을 수 없습니다."
          detail="업로드 대기 중인 문서라면 페이지를 새로 고친 뒤 다시 업로드해주세요."
        />
      )
    }

    if (kind === PREVIEW_KIND.UNSUPPORTED) {
      return (
        <Message
          title={`${fileExtension(fileName).toUpperCase() || '이'} 형식은 미리보기를 지원하지 않습니다.`}
          detail="다운로드해서 확인해주세요."
        />
      )
    }

    if (renderError) {
      return (
        <Message
          title="문서를 표시할 수 없습니다."
          detail="파일이 손상되었거나 암호가 걸려 있을 수 있습니다. 다운로드해서 확인해주세요."
        />
      )
    }

    const common = {
      blob,
      kind,
      fileName,
      onPageCountChange: handlePageCount,
      onError: handleError,
    }

    return (
      <Suspense
        fallback={
          <Centered>
            <Spinner size="sm" />
          </Centered>
        }
      >
        {kind === PREVIEW_KIND.PDF && <PdfPreview {...common} pageNumber={pageNumber} />}
        {kind === PREVIEW_KIND.DOCX && <DocxPreview {...common} />}
        {kind === PREVIEW_KIND.SHEET && <SheetPreview {...common} />}
        {(kind === PREVIEW_KIND.TEXT || kind === PREVIEW_KIND.MARKDOWN) && <TextPreview {...common} />}
      </Suspense>
    )
  }

  // 페이지 넘김은 한 장씩 그리는 PDF 에만 필요하다. docx·시트는 전체를 스크롤로 본다.
  const paged = kind === PREVIEW_KIND.PDF && pageCount > 1 && !renderError && !error

  return (
    <div className={cn('flex flex-col items-center', className)}>
      <div className="w-full max-w-2xl overflow-hidden rounded-md border border-slate-200 bg-white p-8 shadow-sm">
        {body()}
      </div>

      <div className="mt-3 flex items-center gap-1.5">
        {paged && (
          <button
            type="button"
            onClick={() => setPageNumber((page) => Math.max(1, page - 1))}
            disabled={pageNumber <= 1}
            aria-label="이전 페이지"
            className="focus-ring cursor-pointer rounded-lg border border-slate-200 bg-white p-1 text-slate-500 hover:text-primary-600 disabled:cursor-default disabled:opacity-40"
          >
            <ChevronLeft className="size-4" />
          </button>
        )}
        <span className="rounded-lg border border-slate-200 bg-white px-3 py-1 text-xs text-slate-500">
          {pageNumber} / {pageCount}
        </span>
        {paged && (
          <button
            type="button"
            onClick={() => setPageNumber((page) => Math.min(pageCount, page + 1))}
            disabled={pageNumber >= pageCount}
            aria-label="다음 페이지"
            className="focus-ring cursor-pointer rounded-lg border border-slate-200 bg-white p-1 text-slate-500 hover:text-primary-600 disabled:cursor-default disabled:opacity-40"
          >
            <ChevronRight className="size-4" />
          </button>
        )}
      </div>
    </div>
  )
}
