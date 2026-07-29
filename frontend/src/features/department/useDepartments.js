import { useQuery } from '@tanstack/react-query'
import { fetchDepartments } from '@/api/departments'
import { qk } from '@/shared/api/queryKeys'

// 부서 목록 조회(관리자). 공개 범위 선택 등에서 사용.
export function useDepartments() {
  return useQuery({
    queryKey: qk.departments.list,
    queryFn: fetchDepartments,
    staleTime: 5 * 60 * 1000,
  })
}
