import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, ChevronDown, Search } from 'lucide-react'
import { Button } from '@/components/ui'
import { useDepartments } from '@/features/department/useDepartments'
import { useDocumentCategories, useUpdateDocument } from '../queries'

function DropdownButton({ children, open, warning, onClick }) {
  return (
    <button
      ref={open?.ref}
      type="button"
      onClick={onClick}
      className={`focus-ring flex w-full min-w-40 items-center justify-between gap-3 rounded-lg border px-3 py-2 text-left text-xs ${
        warning
          ? 'border-amber-300 bg-amber-50 text-amber-600'
          : 'border-slate-200 bg-slate-50 text-slate-700 hover:border-primary-300'
      }`}
    >
      <span className="truncate">{children}</span>
      <ChevronDown className="size-4 shrink-0 text-slate-400" />
    </button>
  )
}

export function QueueVisibilityDropdown({ item, onApplied, localOnly = false }) {
  const buttonRef = useRef(null)
  const popupRef = useRef(null)
  const expectedSelectionRef = useRef(null)
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [allVisible, setAllVisible] = useState(item.visibilityType === 'all')
  const [selectedIds, setSelectedIds] = useState((item.departments ?? []).map((department) => department.departmentId))
  const [displayVisibility, setDisplayVisibility] = useState(item.visibilityType)
  const [displayDepartments, setDisplayDepartments] = useState(item.departments ?? [])
  const [position, setPosition] = useState({ top: 0, left: 0, width: 264 })
  const { data: departments = [] } = useDepartments()
  const updateMutation = useUpdateDocument(item.documentId)

  useEffect(() => {
    const incomingIds = (item.departments ?? []).map((department) => department.departmentId).sort()
    const incomingSignature = `${item.visibilityType}:${incomingIds.join(',')}`
    if (expectedSelectionRef.current && expectedSelectionRef.current !== incomingSignature) return
    expectedSelectionRef.current = null
    setAllVisible(item.visibilityType === 'all')
    setSelectedIds(incomingIds)
    setDisplayVisibility(item.visibilityType)
    setDisplayDepartments(item.departments ?? [])
  }, [item.departments, item.documentId, item.visibilityType])

  // 팝업 위치는 '실제로 렌더된 높이'를 재서 정한다(S15P11B106-266).
  //   예전에는 높이를 342px로 고정 가정해, 부서 항목이 적어 팝업이 짧을 때 openUpward가 과하게 걸리고
  //   버튼보다 342px나 위로 올려 배치돼, 팝업이 버튼과 떨어진 채 화면 상단에 둥둥 떠 보였다.
  //   이제 popupRef의 실제 높이로 아래/위를 정하고, 렌더 전에(useLayoutEffect) 배치해 깜빡임도 없앤다.
  useLayoutEffect(() => {
    if (!open) return

    function updatePosition() {
      const rect = buttonRef.current?.getBoundingClientRect()
      const popup = popupRef.current
      if (!rect || !popup) return
      const height = popup.getBoundingClientRect().height
      const gap = 6
      const pad = 12
      const spaceBelow = window.innerHeight - rect.bottom
      const spaceAbove = rect.top
      // 아래에 충분하면 아래로, 아니면 공간이 더 넓은 쪽으로 연 뒤 뷰포트 안에 가둔다.
      const openDown = spaceBelow >= height + pad || spaceBelow >= spaceAbove
      const rawTop = openDown ? rect.bottom + gap : rect.top - height - gap
      const width = Math.max(264, rect.width)
      setPosition({
        top: Math.min(Math.max(pad, rawTop), Math.max(pad, window.innerHeight - height - pad)),
        left: Math.min(rect.left, window.innerWidth - width - pad),
        width,
      })
    }

    function closeOnOutside(event) {
      if (!buttonRef.current?.contains(event.target) && !popupRef.current?.contains(event.target)) setOpen(false)
    }
    updatePosition()
    window.addEventListener('mousedown', closeOnOutside)
    window.addEventListener('resize', updatePosition)
    window.addEventListener('scroll', updatePosition, true)
    return () => {
      window.removeEventListener('mousedown', closeOnOutside)
      window.removeEventListener('resize', updatePosition)
      window.removeEventListener('scroll', updatePosition, true)
    }
    // departments 로드로 목록 높이가 바뀌면 다시 잰다.
  }, [open, departments.length])

  const previewDepartments = open
    ? departments.filter((department) => selectedIds.includes(department.departmentId))
    : displayDepartments
  const previewVisibility = open ? (allVisible ? 'all' : 'department') : displayVisibility
  const names = previewDepartments.map((department) => department.name)
  const label =
    previewVisibility === 'all'
      ? '전체 공개'
      : names.length > 1
        ? `${names[0]} 외 ${names.length - 1}`
        : names[0] ?? '부서 선택'
  const filtered = departments.filter((department) => department.name.toLowerCase().includes(search.toLowerCase()))

  function toggleDepartment(id) {
    setAllVisible(false)
    setSelectedIds((current) => (current.includes(id) ? current.filter((value) => value !== id) : [...current, id]))
  }

  function apply() {
    if (!allVisible && selectedIds.length === 0) return
    const nextVisibility = allVisible ? 'all' : 'department'
    const nextDepartments = allVisible
      ? []
      : departments.filter((department) => selectedIds.includes(department.departmentId))

    // 적용 버튼을 누른 즉시 드롭다운과 행 라벨을 갱신한다.
    // 서버 응답을 기다리면 재조회 시점에 따라 이전 "외 n" 값이 잠시 남을 수 있다.
    expectedSelectionRef.current = `${nextVisibility}:${[...selectedIds].sort().join(',')}`
    setDisplayVisibility(nextVisibility)
    setDisplayDepartments(nextDepartments)
    onApplied?.({
      visibilityType: nextVisibility,
      departments: nextDepartments,
      scopeKey: nextVisibility === 'all' ? 'ALL' : nextDepartments.map((department) => `D${department.departmentId}`).join('-'),
    })
    setOpen(false)
    setSearch('')

    if (!localOnly) {
      updateMutation.mutate(
        {
          documentCategoryId: item.documentCategoryId,
          visibilityType: nextVisibility,
          departmentIds: allVisible ? [] : selectedIds,
        },
        {
          onError: () => {
            expectedSelectionRef.current = null
          },
        },
      )
    }
  }

  return (
    <>
      <DropdownButton
        open={{ ref: buttonRef }}
        warning={!item.visibilityType || (item.visibilityType === 'department' && names.length === 0)}
        onClick={() => setOpen((value) => !value)}
      >
        {label}
      </DropdownButton>

      {open &&
        createPortal(
          <div
            ref={popupRef}
            style={{ position: 'fixed', top: position.top, left: position.left, width: position.width }}
            className="z-[80] overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl"
          >
            <div className="p-2">
              <label className="flex items-center gap-2 rounded-lg bg-slate-100 px-2.5 py-2">
                <Search className="size-4 text-slate-400" />
                <input
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder="부서 검색"
                  className="min-w-0 flex-1 bg-transparent text-xs outline-none placeholder:text-slate-400"
                />
              </label>
            </div>

            <div className="max-h-52 overflow-y-auto px-2 pb-2">
              <CheckOption
                label="전체 공개"
                checked={allVisible}
                onClick={() => {
                  setAllVisible(true)
                  setSelectedIds([])
                }}
              />
              {filtered.map((department) => (
                <CheckOption
                  key={department.departmentId}
                  label={department.name}
                  checked={!allVisible && selectedIds.includes(department.departmentId)}
                  onClick={() => toggleDepartment(department.departmentId)}
                />
              ))}
            </div>

            <div className="flex items-center justify-between border-t border-slate-100 px-3 py-2">
              <span className="text-[11px] text-slate-500">
                {allVisible ? '전체 공개' : `${selectedIds.length}개 선택됨`}
              </span>
              <div className="flex gap-2">
                <Button size="sm" variant="outline" onClick={() => setOpen(false)}>
                  취소
                </Button>
                <Button
                  size="sm"
                  variant="primary"
                  onClick={apply}
                  loading={updateMutation.isPending}
                  disabled={!allVisible && selectedIds.length === 0}
                >
                  적용
                </Button>
              </div>
            </div>
          </div>,
          document.body,
        )}
    </>
  )
}

function CheckOption({ label, checked, onClick }) {
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

export function QueueCategorySelect({ item, onApplied, localOnly = false }) {
  const { data: categories = [] } = useDocumentCategories(item.scopeKey)
  const updateMutation = useUpdateDocument(item.documentId)
  const scopeSelected = Boolean(item.scopeKey)
  const currentCategoryMissing =
    Boolean(item.documentCategoryId) &&
    !categories.some(
      (category) => String(category.documentCategoryId) === String(item.documentCategoryId),
    )

  function updateCategory(event) {
    const documentCategoryId = event.target.value
    if (!documentCategoryId) return
    const category = categories.find(
      (item) => String(item.documentCategoryId) === String(documentCategoryId),
    )
    if (localOnly) {
      onApplied?.({
        documentCategoryId,
        documentCategoryName: category?.name ?? null,
      })
      return
    }
    updateMutation.mutate({
      documentCategoryId,
      visibilityType: item.visibilityType,
      departmentIds: (item.departments ?? []).map((department) => department.departmentId),
    })
  }

  return (
    <div className="relative min-w-40">
      <select
        aria-label={`${item.originalFileName} 카테고리`}
        value={item.documentCategoryId ?? ''}
        onChange={updateCategory}
        disabled={!scopeSelected || updateMutation.isPending}
        className={`focus-ring w-full appearance-none rounded-lg border px-3 py-2 pr-8 text-left text-xs ${
          item.documentCategoryId
            ? 'border-slate-200 bg-slate-50 text-slate-700'
            : scopeSelected
              ? 'border-amber-300 bg-amber-50 text-amber-600'
              : 'cursor-not-allowed border-slate-200 bg-slate-100 text-slate-400'
        }`}
      >
        <option value="">
          {scopeSelected ? '카테고리 선택' : '공개 부서를 먼저 선택하세요'}
        </option>
        {currentCategoryMissing && (
          <option value={item.documentCategoryId}>{item.documentCategoryName ?? '현재 카테고리'}</option>
        )}
        {scopeSelected && categories.length === 0 && (
          <option value="" disabled>
            등록된 카테고리가 없습니다
          </option>
        )}
        {categories.map((category) => (
          <option key={category.documentCategoryId} value={category.documentCategoryId}>
            {category.name}
          </option>
        ))}
      </select>
      <ChevronDown className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-slate-400" />
    </div>
  )
}
