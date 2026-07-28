import { useCallback, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { CheckCircle2, AlertCircle, Info, X } from 'lucide-react'
import { cn } from '@/shared/lib/cn'
import { ToastContext } from './ToastContext'

const TONE_STYLES = {
  success: { icon: CheckCircle2, bar: 'bg-emerald-500', iconColor: 'text-emerald-500' },
  error: { icon: AlertCircle, bar: 'bg-rose-500', iconColor: 'text-rose-500' },
  info: { icon: Info, bar: 'bg-primary-500', iconColor: 'text-primary-500' },
}

const DEFAULT_DURATION = 4000

// 앱 전역 토스트 공급자. main.jsx에서 AuthProvider 바깥 또는 안쪽에 감싼다.
export default function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const idRef = useRef(0)

  const remove = useCallback((id) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const toast = useCallback(
    ({ tone = 'info', title, description, duration = DEFAULT_DURATION }) => {
      const id = ++idRef.current
      setToasts((prev) => [...prev, { id, tone, title, description }])
      if (duration > 0) {
        setTimeout(() => remove(id), duration)
      }
      return id
    },
    [remove],
  )

  const api = useMemo(() => {
    const fn = (opts) => toast(opts)
    fn.success = (title, description) => toast({ tone: 'success', title, description })
    fn.error = (title, description) => toast({ tone: 'error', title, description })
    fn.info = (title, description) => toast({ tone: 'info', title, description })
    fn.remove = remove
    return fn
  }, [toast, remove])

  return (
    <ToastContext.Provider value={api}>
      {children}
      {createPortal(
        <div className="pointer-events-none fixed right-4 top-4 z-[100] flex w-80 flex-col gap-2">
          {toasts.map((t) => {
            const style = TONE_STYLES[t.tone] ?? TONE_STYLES.info
            const Icon = style.icon
            return (
              <div
                key={t.id}
                role="status"
                className="pointer-events-auto flex overflow-hidden rounded-lg border border-slate-200 bg-white shadow-lg"
              >
                <span className={cn('w-1 shrink-0', style.bar)} />
                <div className="flex flex-1 items-start gap-2 px-3 py-2.5">
                  <Icon className={cn('mt-0.5 size-4 shrink-0', style.iconColor)} />
                  <div className="flex-1">
                    {t.title && <p className="text-sm font-medium text-slate-800">{t.title}</p>}
                    {t.description && <p className="text-xs text-slate-500">{t.description}</p>}
                  </div>
                  <button
                    type="button"
                    onClick={() => remove(t.id)}
                    className="focus-ring rounded p-0.5 text-slate-400 hover:text-slate-600"
                    aria-label="닫기"
                  >
                    <X className="size-4" />
                  </button>
                </div>
              </div>
            )
          })}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  )
}
