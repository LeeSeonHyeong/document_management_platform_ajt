import { createContext } from 'react'

// 토스트 컨텍스트. value: { toast(options), remove(id) }
export const ToastContext = createContext(null)
