import { cn } from '@/shared/lib/cn'

// 폼 컨트롤 공통 껍데기: 라벨 + 컨트롤 + 힌트/에러 메시지.
// Input/Select/Textarea가 재사용한다. 컨트롤 공통 클래스는 ./fieldStyles 참고.
export default function Field({ id, label, required, hint, error, className, children }) {
  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      {label && (
        <label htmlFor={id} className="text-sm font-medium text-slate-700">
          {label}
          {required && <span className="ml-0.5 text-rose-500">*</span>}
        </label>
      )}
      {children}
      {error ? (
        <p className="text-xs text-rose-600">{error}</p>
      ) : (
        hint && <p className="text-xs text-slate-400">{hint}</p>
      )}
    </div>
  )
}
