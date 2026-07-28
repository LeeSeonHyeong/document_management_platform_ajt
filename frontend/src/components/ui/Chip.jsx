import { X } from 'lucide-react'
import { cn } from '@/shared/lib/cn'

// 선택/필터 칩. onRemove가 있으면 삭제 버튼을 표시한다(카테고리·부서 다중 선택 등).
export default function Chip({ children, onRemove, selected = false, className, ...props }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs font-medium',
        selected
          ? 'border-primary-200 bg-primary-50 text-primary-700'
          : 'border-slate-200 bg-white text-slate-600',
        className,
      )}
      {...props}
    >
      {children}
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          className="focus-ring -mr-1 rounded-full p-0.5 hover:bg-slate-200/60"
          aria-label="제거"
        >
          <X className="size-3" />
        </button>
      )}
    </span>
  )
}
