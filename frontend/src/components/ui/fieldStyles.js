import { cn } from '@/shared/lib/cn'

// 폼 컨트롤(Input/Textarea/Select) 공통 클래스. 에러 상태를 반영한다.
export function controlClass(hasError) {
  return cn(
    'focus-ring w-full rounded-lg border bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 disabled:bg-slate-50 disabled:text-slate-400',
    hasError ? 'border-rose-400' : 'border-slate-300',
  )
}
