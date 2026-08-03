import { useEffect, useState } from 'react'
import { Spinner } from '@/components/ui'
import { MAX_ROWS, parseSheet } from '../parse/sheet'

export default function SheetPreview({ blob, fileName, onPageCountChange, onError }) {
  const [sheets, setSheets] = useState(null)
  const [activeSheet, setActiveSheet] = useState(0)

  useEffect(() => {
    if (!blob) return
    let cancelled = false
    setSheets(null)
    setActiveSheet(0)

    parseSheet(blob, fileName)
      .then((parsed) => {
        if (cancelled) return
        setSheets(parsed)
        onPageCountChange?.(parsed.length || 1)
      })
      .catch((error) => {
        if (!cancelled) onError?.(error)
      })

    return () => {
      cancelled = true
    }
  }, [blob, fileName, onPageCountChange, onError])

  if (!sheets) {
    return (
      <div className="flex h-40 items-center justify-center">
        <Spinner size="sm" />
      </div>
    )
  }

  const sheet = sheets[activeSheet]
  const hidden = Math.max(0, (sheet?.totalRows ?? 0) - (sheet?.rows.length ?? 0))

  return (
    <div className="w-full">
      {sheets.length > 1 && (
        <div className="mb-3 flex flex-wrap gap-1.5">
          {sheets.map((item, index) => (
            <button
              key={item.name}
              type="button"
              onClick={() => setActiveSheet(index)}
              className={`focus-ring cursor-pointer rounded-lg px-2.5 py-1 text-xs font-semibold ${
                index === activeSheet
                  ? 'bg-primary-50 text-primary-700'
                  : 'text-slate-500 hover:bg-slate-100'
              }`}
            >
              {item.name}
            </button>
          ))}
        </div>
      )}

      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-left text-xs">
          <tbody>
            {sheet?.rows.map((row, rowIndex) => (
              // 시트 행에는 안정적인 키가 없어 인덱스를 쓴다(읽기 전용이라 재정렬이 없다).
              <tr key={rowIndex} className={rowIndex === 0 ? 'bg-slate-100 font-semibold' : ''}>
                {row.map((cell, cellIndex) => (
                  <td
                    key={cellIndex}
                    className="max-w-56 truncate border border-slate-200 px-2 py-1 text-slate-600"
                    title={cell}
                  >
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {hidden > 0 && (
        <p className="mt-2 text-xs text-slate-400">
          미리보기는 앞 {MAX_ROWS}행까지만 표시합니다 (남은 {hidden}행 생략).
        </p>
      )}
    </div>
  )
}
