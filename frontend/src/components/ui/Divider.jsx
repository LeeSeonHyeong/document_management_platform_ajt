import { cn } from '@/shared/lib/cn'

// 구분선. label을 주면 가운데 텍스트가 있는 구분선이 된다("또는" 등).
export default function Divider({ label, className }) {
  if (!label) {
    return <hr className={cn('border-slate-200', className)} />
  }
  return (
    <div className={cn('flex items-center gap-3', className)}>
      <span className="h-px flex-1 bg-slate-200" />
      <span className="text-xs text-slate-400">{label}</span>
      <span className="h-px flex-1 bg-slate-200" />
    </div>
  )
}
