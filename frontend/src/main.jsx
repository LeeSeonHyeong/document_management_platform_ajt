import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import './index.css'
import AuthProvider from '@/context/AuthProvider'
import { ToastProvider } from '@/components/ui'
import { router } from '@/routes'
import { createQueryClient } from '@/shared/api/queryClient'

const queryClient = createQueryClient()

// VITE_ENABLE_MSW=true 이면 목 API 워커를 먼저 시작한 뒤 렌더한다.
async function enableMockingIfNeeded() {
  if (import.meta.env.VITE_ENABLE_MSW !== 'true') return
  const { startMockWorker } = await import('@/mocks/browser')
  await startMockWorker()
}

enableMockingIfNeeded().then(() => {
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <AuthProvider>
            <RouterProvider router={router} />
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
    </StrictMode>,
  )
})
