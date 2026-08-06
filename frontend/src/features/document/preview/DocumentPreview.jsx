import { Suspense, lazy, useCallback, useEffect, useState } from 'react'
import {
  ChevronLeft,
  ChevronRight,
  FileWarning,
  Maximize2,
  Minimize2,
  Minus,
  Plus,
  RotateCcw,
} from 'lucide-react'
import { Modal, Spinner } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { PREVIEW_KIND, fileExtension } from './kind'
import { useDocumentPreview } from './useDocumentPreview'

// 확장자별 렌더러는 실제로 그 확장자를 열 때만 내려받는다.
// pdf.js·docx-preview·exceljs 는 각각 수백 KB라 초기 번들에 넣지 않는다.
const PdfPreview = lazy(() => import('./renderers/PdfPreview'))
const DocxPreview = lazy(() => import('./renderers/DocxPreview'))
const SheetPreview = lazy(() => import('./renderers/SheetPreview'))
const TextPreview = lazy(() => import('./renderers/TextPreview'))

// 확대 단계. 100%를 기준으로 위아래로 오간다. 최대 300%는 A4 문서를 화면 폭에서
// 읽을 수 있는 정도이고, 그 이상은 스크롤이 과해 실용성이 떨어진다.
const ZOOM_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2, 2.5, 3]
const DEFAULT_ZOOM_INDEX = 2

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
  // 전체보기 버튼 노출 여부(S15P11B106-298). 이미 모달 안에서 보여주는 곳은 끈다 —
  // 모달 위에 모달이 겹치고 ESC 가 둘을 함께 닫아 바깥 모달까지 사라진다.
  expandable = true,
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
  const [zoomIndex, setZoomIndex] = useState(DEFAULT_ZOOM_INDEX)
  const [expanded, setExpanded] = useState(false)
  const zoom = ZOOM_STEPS[zoomIndex]

  // 다른 문서로 바뀌면 페이지·확대 상태를 초기화한다.
  useEffect(() => {
    setPageCount(1)
    setPageNumber(1)
    setRenderError(null)
    setZoomIndex(DEFAULT_ZOOM_INDEX)
    setExpanded(false)
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
        {kind === PREVIEW_KIND.PDF && (
          <PdfPreview {...common} pageNumber={pageNumber} zoom={zoom} />
        )}
        {kind === PREVIEW_KIND.DOCX && <DocxPreview {...common} />}
        {kind === PREVIEW_KIND.SHEET && <SheetPreview {...common} />}
        {(kind === PREVIEW_KIND.TEXT || kind === PREVIEW_KIND.MARKDOWN) && <TextPreview {...common} />}
      </Suspense>
    )
  }

  // 페이지 넘김은 한 장씩 그리는 PDF 에만 필요하다. docx·시트는 전체를 스크롤로 본다.
  const paged = kind === PREVIEW_KIND.PDF && pageCount > 1 && !renderError && !error
  // 내용이 실제로 그려질 때만 확대 버튼을 보여준다(오류·미지원 화면에서는 의미가 없다).
  const rendered =
    enabled && Boolean(blob) && !isLoading && !error && !renderError
      && kind !== PREVIEW_KIND.UNSUPPORTED
  const zoomable = rendered
  // PDF는 렌더 폭으로 확대하므로 CSS zoom을 걸지 않는다 — 이중으로 커진다.
  const cssZoomed = rendered && kind !== PREVIEW_KIND.PDF && zoom !== 1

  const content = (
    <div className={cn('flex flex-col items-center', expanded ? 'w-full' : className)}>
      <div className="mb-3 flex items-center gap-1.5">
        {zoomable && (
          <>
            <button
              type="button"
              onClick={() => setZoomIndex((index) => Math.max(0, index - 1))}
              disabled={zoomIndex <= 0}
              aria-label="축소"
              className="focus-ring cursor-pointer rounded-lg border border-slate-200 bg-white p-1 text-slate-500 hover:text-primary-600 disabled:cursor-default disabled:opacity-40"
            >
              <Minus className="size-4" />
            </button>
            <button
              type="button"
              onClick={() => setZoomIndex(DEFAULT_ZOOM_INDEX)}
              disabled={zoomIndex === DEFAULT_ZOOM_INDEX}
              aria-label="확대 초기화"
              title="100%로"
              className="focus-ring flex cursor-pointer items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-500 hover:text-primary-600 disabled:cursor-default disabled:opacity-60"
            >
              <RotateCcw className="size-3" />
              {Math.round(zoom * 100)}%
            </button>
            <button
              type="button"
              onClick={() => setZoomIndex((index) => Math.min(ZOOM_STEPS.length - 1, index + 1))}
              disabled={zoomIndex >= ZOOM_STEPS.length - 1}
              aria-label="확대"
              className="focus-ring cursor-pointer rounded-lg border border-slate-200 bg-white p-1 text-slate-500 hover:text-primary-600 disabled:cursor-default disabled:opacity-40"
            >
              <Plus className="size-4" />
            </button>
            {expandable && (
            <button
              type="button"
              onClick={() => setExpanded((current) => !current)}
              aria-label={expanded ? '전체화면 닫기' : '전체화면으로 보기'}
              title={expanded ? '전체화면 닫기' : '전체화면으로 보기'}
              className="focus-ring cursor-pointer rounded-lg border border-slate-200 bg-white p-1 text-slate-500 hover:text-primary-600"
            >
              {expanded ? <Minimize2 className="size-4" /> : <Maximize2 className="size-4" />}
            </button>
            )}
            <span className="mx-1 h-4 w-px bg-slate-200" aria-hidden="true" />
          </>
        )}
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
      {/*
        확대한 내용은 넘치는 만큼 가로·세로로 스크롤한다. PDF는 렌더 폭 자체가 커지고,
        나머지 형식은 CSS zoom으로 키운다 — transform scale과 달리 레이아웃을 다시 잡아
        스크롤 크기가 내용과 맞는다.
      */}
      <div
        className={cn(
          'w-full overflow-auto rounded-md border border-slate-200 bg-white p-8 shadow-sm',
          // 전체화면에서는 폭 제한을 풀고 높이를 화면에 맞춘다. PDF는 ResizeObserver로
          // 부모 폭을 보므로 이것만으로 더 크게 렌더된다.
          expanded ? 'h-[78vh] max-w-none' : 'max-w-2xl',
        )}
      >
        {cssZoomed ? (
          <div style={{ zoom }}>{body()}</div>
        ) : (
          body()
        )}
      </div>

    </div>
  )

  if (expanded) {
    return (
      <Modal open onClose={() => setExpanded(false)} title={fileName} size="full">
        {content}
      </Modal>
    )
  }
  return content
}
