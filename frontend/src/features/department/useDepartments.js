import { useQuery } from '@tanstack/react-query'
import { fetchDepartments } from '@/api/departments'
import { qk } from '@/shared/api/queryKeys'
import { excludeDefaultDepartment } from './defaultDepartment'

/**
 * 부서 목록 조회(관리자). 문서 공개 범위·일정 부서 선택에서 사용.
 *
 * 기본값은 시스템 기본 부서('미지정')를 **제외한** 목록이다. 이 부서는 공개 대상이 아니라
 * 소속이 애매한 회원을 담는 곳이므로, 공개 범위를 고르는 화면에 뜨면 '전체 공개'와 혼동된다.
 * 부서 관리·회원가입·직원 소속 변경 화면은 이 훅을 쓰지 않고 각자 fetchDepartments를 직접
 * 호출하므로 영향을 받지 않는다. 전체 목록이 필요하면 includeDefault: true 를 넘긴다.
 */
export function useDepartments({ includeDefault = false } = {}) {
  return useQuery({
    queryKey: qk.departments.list,
    queryFn: fetchDepartments,
    staleTime: 5 * 60 * 1000,
    select: includeDefault ? undefined : excludeDefaultDepartment,
  })
}
