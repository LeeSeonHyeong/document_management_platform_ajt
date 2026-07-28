import { cn } from '@/shared/lib/cn'

// 우하단 플로팅 액션 버튼. AI 검색 어시스턴트/일정 추가 등에 사용한다.
// items=[{ icon, label, onClick, tone }] — 아래에서 위로 쌓인다.
export default function Fab({ items = [] }) {
  if (items.length === 0) return null
  return (
    <div className="fixed bottom-6 right-6 z-40 flex flex-col-reverse items-end gap-3">
      {items.map((item, i) => {
        const Icon = item.icon
        const primary = item.tone !== 'secondary'
        return (
          <button
            key={i}
            type="button"
            onClick={item.onClick}
            aria-label={item.label}
            title={item.label}
            className={cn(
              'focus-ring flex size-13 items-center justify-center rounded-full shadow-lg transition-transform hover:scale-105',
              primary
                ? 'bg-gradient-to-br from-primary-500 to-primary-700 text-white'
                : 'border border-slate-200 bg-white text-primary-600',
            )}
          >
            <Icon className="size-6" />
          </button>
        )
      })}
    </div>
  )
}
