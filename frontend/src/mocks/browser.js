import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

// 브라우저 환경 MSW 워커. VITE_ENABLE_MSW=true 일 때만 main.jsx에서 시작한다.
export const worker = setupWorker(...handlers)

export async function startMockWorker() {
  await worker.start({
    // 목 핸들러가 없는 요청은 실제 네트워크로 통과시킨다.
    onUnhandledRequest: 'bypass',
    quiet: true,
  })
}
