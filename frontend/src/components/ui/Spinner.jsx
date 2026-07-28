import { cn } from '@/shared/lib/cn'

// 로딩 스피너. 버튼 내부/영역 로딩 공용.
const SIZES = {
  sm: 'size-4 border-2',
  md: 'size-6 border-2',
  lg: 'size-8 border-[3px]',
}

export default function Spinner({ size = 'md', className }) {
  return (
    <span
      role="status"
      aria-label="로딩 중"
      className={cn(
        'inline-block animate-spin rounded-full border-slate-300 border-t-primary-600',
        SIZES[size],
        className,
      )}
    />
  )
}
