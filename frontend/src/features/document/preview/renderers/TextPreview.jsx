import { useEffect, useState } from 'react'
import { Spinner } from '@/components/ui'
import WikiMarkdown from '@/features/wiki/components/WikiMarkdown'
import { PREVIEW_KIND } from '../kind'
import { readPreviewText } from '../parse/text'

export default function TextPreview({ blob, kind, onPageCountChange, onError }) {
  const [text, setText] = useState(null)

  useEffect(() => {
    if (!blob) return
    let cancelled = false
    setText(null)

    readPreviewText(blob)
      .then((decoded) => {
        if (cancelled) return
        setText(decoded)
        onPageCountChange?.(1)
      })
      .catch((error) => {
        if (!cancelled) onError?.(error)
      })

    return () => {
      cancelled = true
    }
  }, [blob, onPageCountChange, onError])

  if (text == null) {
    return (
      <div className="flex h-40 items-center justify-center">
        <Spinner size="sm" />
      </div>
    )
  }

  if (kind === PREVIEW_KIND.MARKDOWN) {
    // 원본 문서는 Wiki 가 아니므로 내부 링크(pages/{id}.md)는 모두 "없는 문서"로 처리된다.
    return <WikiMarkdown markdown={text} wikiIdByPageKey={new Map()} />
  }

  return (
    <pre className="whitespace-pre-wrap break-words font-sans text-sm leading-7 text-slate-600">
      {text}
    </pre>
  )
}
