import { cn } from '@/shared/lib/cn'

// 이름 이니셜 아바타. 이미지가 없어도 이름 첫 글자로 원형 배지를 만든다.
const SIZES = {
  sm: 'size-8 text-xs',
  md: 'size-10 text-sm',
  lg: 'size-12 text-base',
}

function initial(name) {
  return name?.trim()?.charAt(0)?.toUpperCase() ?? '?'
}

export default function Avatar({ name, src, size = 'md', className }) {
  if (src) {
    return (
      <img
        src={src}
        alt={name ?? '사용자'}
        className={cn('rounded-full object-cover', SIZES[size], className)}
      />
    )
  }
  return (
    <span
      className={cn(
        'inline-flex items-center justify-center rounded-full bg-primary-100 font-semibold text-primary-700',
        SIZES[size],
        className,
      )}
      aria-hidden="true"
    >
      {initial(name)}
    </span>
  )
}
