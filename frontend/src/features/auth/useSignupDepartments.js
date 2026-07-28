import { useQuery } from '@tanstack/react-query'
import { fetchSignupDepartments } from '@/api/auth'
import { qk } from '@/shared/api/queryKeys'

// 회원가입 화면의 소속 부서 선택 목록. 비로그인에서도 조회 가능한 공개 API.
export function useSignupDepartments() {
  return useQuery({
    queryKey: qk.departments.signupList,
    queryFn: fetchSignupDepartments,
    staleTime: 5 * 60 * 1000,
  })
}
