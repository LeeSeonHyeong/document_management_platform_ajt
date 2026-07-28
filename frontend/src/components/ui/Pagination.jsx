import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '@/shared/lib/cn'

// 페이지네이션. 1-based page. 백엔드 목록 응답의 page/totalPages와 맞춘다.
function pageWindow(page, totalPages, span = 2) {
  const start = Math.max(1, page - span)
  const end = Math.min(totalPages, page + span)
  const pages = []
  for (let p = start; p <= end; p++) pages.push(p)
  return pages
}

export default function Pagination({ page, totalPages, onChange, className }) {
  if (!totalPages || totalPages <= 1) return null
  const pages = pageWindow(page, totalPages)

  const btn = 'focus-ring flex size-8 items-center justify-center rounded-lg text-sm'

  return (
    <nav className={cn('flex items-center justify-center gap-1', className)} aria-label="페이지네이션">
      <button
        type="button"
        className={cn(btn, 'text-slate-500 hover:bg-slate-100 disabled:opacity-40')}
        onClick={() => onChange(page - 1)}
        disabled={page <= 1}
        aria-label="이전 페이지"
      >
        <ChevronLeft className="size-4" />
      </button>
      {pages[0] > 1 && <span className="px-1 text-slate-400">…</span>}
      {pages.map((p) => (
        <button
          key={p}
          type="button"
          aria-current={p === page ? 'page' : undefined}
          className={cn(
            btn,
            p === page
              ? 'bg-primary-600 font-medium text-white'
              : 'text-slate-600 hover:bg-slate-100',
          )}
          onClick={() => onChange(p)}
        >
          {p}
        </button>
      ))}
      {pages[pages.length - 1] < totalPages && <span className="px-1 text-slate-400">…</span>}
      <button
        type="button"
        className={cn(btn, 'text-slate-500 hover:bg-slate-100 disabled:opacity-40')}
        onClick={() => onChange(page + 1)}
        disabled={page >= totalPages}
        aria-label="다음 페이지"
      >
        <ChevronRight className="size-4" />
      </button>
    </nav>
  )
}
