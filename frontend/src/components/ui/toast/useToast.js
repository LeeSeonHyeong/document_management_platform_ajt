import { useContext } from 'react'
import { ToastContext } from './ToastContext'

// 토스트 훅. toast({ tone, title, description }) 로 알림을 띄운다.
// 편의 메서드: toast.success(msg), toast.error(msg)
export function useToast() {
  const ctx = useContext(ToastContext)
  if (ctx === null) {
    throw new Error('useToast는 ToastProvider 내부에서만 사용할 수 있습니다.')
  }
  return ctx
}
