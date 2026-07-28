import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

// Tailwind 클래스 병합 유틸. 조건부 클래스와 충돌 클래스를 안전하게 합친다.
// 예: cn('px-2', isActive && 'bg-primary-600', className)
export function cn(...inputs) {
  return twMerge(clsx(inputs))
}
