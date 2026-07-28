import { cn } from '@/shared/lib/cn'

// 필터 컨트롤을 가로로 배치하는 컨테이너. 검색바·셀렉트·칩 등을 children으로 넣는다.
// 목록 화면 상단에서 SearchBar/Select와 함께 사용한다.
export default function FilterBar({ className, children }) {
  return (
    <div className={cn('flex flex-wrap items-center gap-3', className)}>{children}</div>
  )
}
