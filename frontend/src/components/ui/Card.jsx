import { cn } from '@/shared/lib/cn'

// 카드 컨테이너와 구성요소. 통계 카드·패널 등에 사용.
export default function Card({ className, children, ...props }) {
  return (
    <div
      className={cn('rounded-xl border border-slate-200 bg-white shadow-sm', className)}
      {...props}
    >
      {children}
    </div>
  )
}

export function CardHeader({ className, children }) {
  return (
    <div className={cn('flex items-center justify-between border-b border-slate-100 px-5 py-4', className)}>
      {children}
    </div>
  )
}

export function CardTitle({ className, children }) {
  return <h3 className={cn('text-base font-semibold text-slate-800', className)}>{children}</h3>
}

export function CardBody({ className, children }) {
  return <div className={cn('px-5 py-4', className)}>{children}</div>
}

export function CardFooter({ className, children }) {
  return (
    <div className={cn('flex items-center justify-end gap-2 border-t border-slate-100 px-5 py-3', className)}>
      {children}
    </div>
  )
}
