import { useQuery } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import { fetchDepartments } from './api'

// 부서 목록. 공개 범위 지정(문서 업로드·재지정)에서 사용한다.
export function useDepartments() {
  return useQuery({
    queryKey: qk.departments.list,
    queryFn: fetchDepartments,
    staleTime: 5 * 60 * 1000,
  })
}
