import { useEffect, useRef } from 'react'

// docx-preview 는 컨테이너 DOM 에 직접 렌더한다(React 트리 밖).
// 문서 바뀔 때마다 컨테이너를 비우고 다시 그린다.
export default function DocxPreview({ blob, onPageCountChange, onError }) {
  const containerRef = useRef(null)

  useEffect(() => {
    const container = containerRef.current
    if (!container || !blob) return

    let cancelled = false
    container.replaceChildren()

    import('docx-preview')
      .then(({ renderAsync }) =>
        renderAsync(blob, container, undefined, {
          className: 'docx',
          inWrapper: true,
          ignoreWidth: true,
          ignoreHeight: true,
          breakPages: true,
          experimental: true,
        }),
      )
      .then(() => {
        if (cancelled) return
        // 렌더된 페이지(section) 수를 세어 표시용 페이지 수로 쓴다.
        const pages = container.querySelectorAll('section.docx').length
        onPageCountChange?.(pages || 1)
      })
      .catch((error) => {
        if (!cancelled) onError?.(error)
      })

    return () => {
      cancelled = true
    }
  }, [blob, onPageCountChange, onError])

  // docx-preview 가 넣는 인라인 폭(A4 고정)이 좁은 컨테이너를 넘치므로 래퍼 폭을 눌러 준다.
  return (
    <div
      ref={containerRef}
      className="w-full [&_.docx-wrapper]:!bg-transparent [&_.docx-wrapper]:!p-0 [&_.docx-wrapper>section.docx]:!mb-4 [&_.docx-wrapper>section.docx]:!w-full [&_.docx-wrapper>section.docx]:!min-w-0 [&_.docx-wrapper>section.docx]:!max-w-full [&_table]:!max-w-full [&_img]:!h-auto [&_img]:!max-w-full"
    />
  )
}
