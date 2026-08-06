import { useEffect, useRef, useState } from 'react'
import { Check, ChevronDown } from 'lucide-react'

export default function DepartmentMultiSelect({
  value,
  departments,
  onChange,
  placeholder = '공개 부서 (선택)',
  allowWrap = false,
  // 부서관리자 제한(S15P11B106-289). 전체 공개를 막고, 담당 부서를 반드시 포함시킨다.
  // 서버가 403으로 거절하는 조합을 화면에서 고를 수 없게 하려는 것이다.
  requiredDepartmentId = null,
  allowAllScope = true,
}) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef(null)
  const allSelected = value.includes('ALL')
  const selectedDepartments = departments.filter((department) =>
    value.includes(String(department.departmentId)),
  )
  const label = allSelected
    ? '전체 공개'
    : selectedDepartments.length > 0
      ? selectedDepartments.map((department) => department.name).join(', ')
      : placeholder

  useEffect(() => {
    if (!open) return undefined
    function closeOnOutside(event) {
      if (!containerRef.current?.contains(event.target)) setOpen(false)
    }
    window.addEventListener('mousedown', closeOnOutside)
    return () => window.removeEventListener('mousedown', closeOnOutside)
  }, [open])

  function toggleAll() {
    if (!allowAllScope) return
    onChange(allSelected ? [] : ['ALL'])
  }

  function toggleDepartment(departmentId) {
    const id = String(departmentId)
    const required = requiredDepartmentId == null ? null : String(requiredDepartmentId)
    // 담당 부서는 뺄 수 없다 — 빼면 서버가 거절하는 조합이 된다.
    if (required && id === required) return
    const current = value.filter((selected) => selected !== 'ALL')
    const next = current.includes(id)
      ? current.filter((selected) => selected !== id)
      : [...current, id]
    // 부서를 고르는 순간 담당 부서를 함께 넣는다.
    if (required && next.length > 0 && !next.includes(required)) {
      next.push(required)
    }
    onChange(next)
  }

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className={`focus-ring flex min-h-10 w-full items-center justify-between gap-2 rounded-lg border border-slate-300 bg-white px-3 text-left text-sm text-slate-900 ${
          allowWrap ? 'py-2' : 'h-10'
        }`}
      >
        <span
          className={`${allowWrap ? 'whitespace-normal break-keep' : 'truncate'} ${
            value.length === 0 ? 'text-slate-400' : ''
          }`}
        >
          {label}
        </span>
        <ChevronDown className="size-4 shrink-0 text-slate-400" />
      </button>

      {open && (
        <div className="absolute right-0 top-[calc(100%+6px)] z-30 w-full min-w-52 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl">
          <div className="max-h-56 overflow-y-auto p-2">
            {allowAllScope && (
              <DepartmentCheckOption label="전체 공개" checked={allSelected} onClick={toggleAll} />
            )}
            {departments.map((department) => (
              <DepartmentCheckOption
                key={department.departmentId}
                label={department.name}
                checked={!allSelected && value.includes(String(department.departmentId))}
                onClick={() => toggleDepartment(department.departmentId)}
              />
            ))}
          </div>
          <div className="border-t border-slate-100 px-3 py-2 text-[11px] text-slate-500">
            {allSelected ? '전체 공개' : `${selectedDepartments.length}개 선택됨`}
          </div>
        </div>
      )}
    </div>
  )
}

function DepartmentCheckOption({ label, checked, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-xs ${
        checked ? 'bg-primary-50 font-semibold text-slate-800' : 'text-slate-600 hover:bg-slate-50'
      }`}
    >
      <span
        className={`flex size-4 items-center justify-center rounded border ${
          checked ? 'border-primary-500 bg-primary-500 text-white' : 'border-slate-300 bg-white'
        }`}
      >
        {checked && <Check className="size-3" />}
      </span>
      {label}
    </button>
  )
}
