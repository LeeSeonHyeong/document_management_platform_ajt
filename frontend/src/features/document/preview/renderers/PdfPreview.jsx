import { useEffect, useRef, useState } from 'react'
import { Document, Page, pdfjs } from 'react-pdf'
import 'react-pdf/dist/Page/AnnotationLayer.css'
import 'react-pdf/dist/Page/TextLayer.css'

// 워커는 react-pdf 를 쓰는 모듈에서 지정해야 한다(라이브러리 요구사항).
pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString()

// 한글 PDF 는 CJK cmap 없이는 본문이 비어 보인다. 자산은 vite.config.js 가 배포 루트로 복사한다.
const PDF_OPTIONS = {
  cMapUrl: `${import.meta.env.BASE_URL}cmaps/`,
  cMapPacked: true,
  standardFontDataUrl: `${import.meta.env.BASE_URL}standard_fonts/`,
}

export default function PdfPreview({ blob, pageNumber, onPageCountChange, onError }) {
  const containerRef = useRef(null)
  const [width, setWidth] = useState(0)

  // 부모 폭에 맞춰 렌더한다. 모달과 상세 화면의 폭이 달라 고정 scale 은 쓸 수 없다.
  useEffect(() => {
    const element = containerRef.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  return (
    <div ref={containerRef} className="w-full">
      <Document
        file={blob}
        options={PDF_OPTIONS}
        onLoadSuccess={({ numPages }) => onPageCountChange?.(numPages)}
        onLoadError={onError}
        loading={null}
        error={null}
        noData={null}
      >
        {width > 0 && (
          <Page
            pageNumber={pageNumber}
            width={width}
            onRenderError={onError}
            loading={null}
            error={null}
            className="[&>canvas]:!h-auto [&>canvas]:!w-full"
          />
        )}
      </Document>
    </div>
  )
}
