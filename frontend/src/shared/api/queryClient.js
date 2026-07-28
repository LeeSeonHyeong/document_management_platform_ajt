import { QueryClient } from '@tanstack/react-query'

// 서버 상태 전역 클라이언트 기본 정책.
//  - staleTime 30초: 짧은 상호작용 동안 불필요한 재요청을 줄인다.
//  - retry 1: 일시 오류만 1회 재시도(4xx는 재시도 이득이 없어 최소화).
//  - refetchOnWindowFocus off: 관리 도구 특성상 포커스 전환 재요청은 과함.
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30 * 1000,
        retry: 1,
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: 0,
      },
    },
  })
}
