import { useState } from 'react'
import { Search } from 'lucide-react'
import { cn } from '@/shared/lib/cn'

// 검색 입력. Enter 또는 버튼으로 onSearch(term)을 호출한다.
// 제어(value/onChange)와 비제어(defaultValue) 모두 지원한다.
export default function SearchBar({
  value,
  onChange,
  onSearch,
  placeholder = '검색',
  defaultValue = '',
  className,
}) {
  const [inner, setInner] = useState(defaultValue)
  const controlled = value !== undefined
  const current = controlled ? value : inner

  const setValue = (v) => {
    if (controlled) onChange?.(v)
    else setInner(v)
  }

  const submit = (e) => {
    e.preventDefault()
    onSearch?.(current)
  }

  return (
    <form onSubmit={submit} className={cn('relative', className)} role="search">
      <Search className="pointer-events-none absolute inset-y-0 left-3 my-auto size-4 text-slate-400" />
      <input
        type="search"
        value={current}
        onChange={(e) => setValue(e.target.value)}
        placeholder={placeholder}
        className="focus-ring h-10 w-full rounded-lg border border-slate-300 bg-white pl-9 pr-3 text-sm text-slate-900 placeholder:text-slate-400"
      />
    </form>
  )
}
