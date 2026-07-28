import { cn } from '@/shared/lib/cn'

// 제어형 탭. items=[{ value, label, icon }], value/onChange로 제어한다.
// 위키 상세의 "목차·관련 문서 / AI 에이전트" 탭 등에서 사용.
export default function Tabs({ items, value, onChange, className }) {
  return (
    <div className={cn('flex gap-1 border-b border-slate-200', className)} role="tablist">
      {items.map((item) => {
        const active = item.value === value
        return (
          <button
            key={item.value}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(item.value)}
            className={cn(
              'focus-ring -mb-px flex items-center gap-1.5 border-b-2 px-4 py-2.5 text-sm font-medium transition-colors',
              active
                ? 'border-primary-600 text-primary-700'
                : 'border-transparent text-slate-500 hover:text-slate-700',
            )}
          >
            {item.icon}
            {item.label}
          </button>
        )
      })}
    </div>
  )
}
