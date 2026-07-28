import { Inbox } from 'lucide-react'
import { cn } from '@/shared/lib/cn'

// 빈 목록/결과 없음 안내. icon·title·description·action을 조합한다.
export default function EmptyState({
  icon: Icon = Inbox,
  title = '표시할 내용이 없습니다',
  description,
  action,
  className,
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-slate-200 bg-slate-50/50 px-6 py-12 text-center',
        className,
      )}
    >
      <span className="flex size-12 items-center justify-center rounded-full bg-slate-100 text-slate-400">
        <Icon className="size-6" />
      </span>
      <div className="space-y-1">
        <p className="text-sm font-medium text-slate-700">{title}</p>
        {description && <p className="text-xs text-slate-400">{description}</p>}
      </div>
      {action}
    </div>
  )
}
