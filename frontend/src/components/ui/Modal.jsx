import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'
import { cn } from '@/shared/lib/cn'

// 접근성 있는 모달. ESC/오버레이 클릭으로 닫히고, 열려 있는 동안 본문 스크롤을 잠근다.
const SIZES = {
  sm: 'max-w-sm',
  md: 'max-w-md',
  lg: 'max-w-lg',
  xl: 'max-w-2xl',
}

export default function Modal({
  open,
  onClose,
  title,
  description,
  size = 'md',
  footer,
  footerClassName,
  closeOnOverlay = true,
  showClose = true,
  children,
}) {
  useEffect(() => {
    if (!open) return
    const onKey = (e) => {
      if (e.key === 'Escape') onClose?.()
    }
    document.addEventListener('keydown', onKey)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = prevOverflow
    }
  }, [open, onClose])

  if (!open) return null

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div
        className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm"
        onClick={closeOnOverlay ? onClose : undefined}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        className={cn(
          'relative z-10 w-full overflow-hidden rounded-2xl bg-white shadow-xl',
          SIZES[size],
        )}
      >
        {(title || (onClose && showClose)) && (
          <div className="flex items-start justify-between gap-4 px-6 pt-5">
            <div>
              {title && <h2 className="text-lg font-semibold text-slate-800">{title}</h2>}
              {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
            </div>
            {onClose && showClose && (
              <button
                type="button"
                onClick={onClose}
                className="focus-ring -mr-2 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                aria-label="닫기"
              >
                <X className="size-5" />
              </button>
            )}
          </div>
        )}
        <div className="px-6 py-4">{children}</div>
        {footer && (
          <div className={cn('flex items-center justify-end gap-2 border-t border-slate-100 px-6 py-4', footerClassName)}>
            {footer}
          </div>
        )}
      </div>
    </div>,
    document.body,
  )
}
