import { cn } from '@/shared/lib/cn'

// 제어형 탭. items=[{ value, label, icon }], value/onChange로 제어한다.
// 위키 상세의 "목차·관련 문서 / AI 에이전트" 탭 등에서 사용.
// 탭이 컨테이너 폭을 넘으면 가로로 스크롤한다. 각 탭은 shrink-0·whitespace-nowrap 이라
// 폭이 눌리지 않는다 — 한글 라벨이 글자 단위로 세로로 쪼개지는 깨짐을 막는다.
export default function Tabs({ items, value, onChange, className }) {
  return (
    <div className={cn('flex gap-1 overflow-x-auto border-b border-slate-200', className)} role="tablist">
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
              'focus-ring -mb-px flex shrink-0 items-center gap-1.5 whitespace-nowrap border-b-2 px-4 py-2.5 text-sm font-medium transition-colors',
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
