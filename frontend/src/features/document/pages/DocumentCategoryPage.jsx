import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { AlertTriangle, Check, ChevronDown, ChevronLeft, Info, Plus } from 'lucide-react'
import { Button, Modal, useToast } from '@/components/ui'
import { useDepartments } from '@/features/department/useDepartments'
import {
  useCreateDocumentCategory,
  useDeleteDocumentCategory,
  useDocumentCategories,
  useDocuments,
} from '../queries'
import DocumentCategoryFormModal from '../components/DocumentCategoryFormModal'
import DepartmentMultiSelect from '../components/DepartmentMultiSelect'
import { buildScopeKey } from '../scope'

const CATEGORY_TONES = [
  'bg-blue-100 text-blue-700',
  'bg-rose-100 text-rose-700',
  'bg-emerald-100 text-emerald-700',
  'bg-amber-100 text-amber-700',
  'bg-violet-100 text-violet-700',
  'bg-cyan-100 text-cyan-700',
  'bg-slate-200 text-slate-600',
]

// Figma 4-8R — 원본문서 카테고리 관리.
// 기본 공개 부서는 아직 카테고리 API 계약에 없는 화면용 값이다.
export default function DocumentCategoryPage() {
  const toast = useToast()
  const [newName, setNewName] = useState('')
  const [newDepartments, setNewDepartments] = useState([])
  const [scopeFilter, setScopeFilter] = useState('')
  const [defaultDepartments, setDefaultDepartments] = useState({})
  const [editing, setEditing] = useState(null)
  const [deleting, setDeleting] = useState(null)

  // 현재 목 API는 scopeKey와 무관하게 전체 카테고리를 반환한다.
  const scopeKey = 'ALL'
  const { data: categories = [], isLoading } = useDocumentCategories(scopeKey)
  const { data: documentData } = useDocuments({ page: 1, size: 100 })
  const { data: departments = [] } = useDepartments()
  const createMutation = useCreateDocumentCategory()
  const deleteMutation = useDeleteDocumentCategory(scopeKey)
  const documents = useMemo(() => documentData?.items ?? [], [documentData])

  const documentCounts = useMemo(
    () =>
      documents.reduce((counts, document) => {
        const key = document.documentCategoryId
        counts[key] = (counts[key] ?? 0) + 1
        return counts
      }, {}),
    [documents],
  )

  function departmentValuesFor(category) {
    if (defaultDepartments[category.documentCategoryId] !== undefined) {
      const saved = defaultDepartments[category.documentCategoryId]
      return Array.isArray(saved) ? saved : [saved]
    }
    if (category.scopeKey === 'ALL') return ['ALL']
    return [...(category.scopeKey?.matchAll(/D(\d+)/g) ?? [])].map((match) => match[1])
  }

  function departmentValueFor(category) {
    return departmentValuesFor(category)[0] ?? ''
  }

  function handleAdd() {
    const name = newName.trim()
    if (!name || newDepartments.length === 0 || createMutation.isPending) return
    const categoryScopeKey =
      newDepartments.includes('ALL') || newDepartments.length === 0
        ? 'ALL'
        : buildScopeKey('department', newDepartments)
    createMutation.mutate(
      { scopeKey: categoryScopeKey, name, description: '' },
      {
        onSuccess: (category) => {
          if (newDepartments.length > 0) {
            setDefaultDepartments((current) => ({
              ...current,
              [category.documentCategoryId]: newDepartments,
            }))
          }
          setNewName('')
          setNewDepartments([])
          toast.success('카테고리를 추가했습니다.')
        },
        onError: (error) => {
          if (error?.response?.status === 409) toast.error('같은 이름의 카테고리가 이미 있습니다.')
          else toast.error('카테고리를 추가하지 못했습니다.')
        },
      },
    )
  }

  function handleDelete() {
    if (!deleting) return
    deleteMutation.mutate(deleting.documentCategoryId, {
      onSuccess: () => {
        setDeleting(null)
        toast.success('카테고리를 삭제했습니다.')
      },
      onError: (error) => {
        if (error?.response?.status === 409) {
          toast.error('문서가 있는 카테고리는 삭제할 수 없습니다.')
        } else {
          toast.error('삭제하지 못했습니다.')
        }
        setDeleting(null)
      },
    })
  }

  const scopeOptions = Array.from(
    new Map(
      categories.map((category) => {
        const values = departmentValuesFor(category)
        const key = scopeFilterKey(values)
        return [key, { value: key, label: departmentLabelFor(values, departments) }]
      }),
    ).values(),
  )
  const filteredCategories =
    !scopeFilter
      ? categories
      : categories.filter((category) =>
          scopeFilter === scopeFilterKey(departmentValuesFor(category)),
        )

  return (
    <section className="space-y-5">
      <Link
        to="/admin/documents"
        className="focus-ring inline-flex cursor-pointer items-center gap-1.5 rounded-md text-sm font-semibold text-slate-500 hover:text-primary-600"
      >
        <ChevronLeft className="size-4" />
        문서 관리
      </Link>

      <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <header className="flex flex-wrap items-start justify-between gap-4 px-5 py-4">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-bold text-slate-900">카테고리 현황</h2>
              <span className="rounded-full bg-primary-50 px-2 py-0.5 text-xs font-bold text-primary-600">
                {categories.length}개
              </span>
            </div>
            <p className="mt-1 text-xs text-slate-400">
              원본 문서를 분류하는 체계입니다. 업로드 시 선택지로 노출됩니다.
            </p>
          </div>
          <span className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-400">
            전체 문서 <strong className="ml-1 text-slate-700">{documents.length}건</strong>
          </span>
        </header>

        <div className="mx-5 mb-4 flex min-w-0 items-center gap-2 rounded-xl border border-primary-200 bg-primary-50/40 p-3">
          <span className="flex shrink-0 items-center gap-2 text-sm font-bold text-slate-700">
            <span className="flex size-7 items-center justify-center rounded-lg bg-gradient-to-br from-blue-500 to-violet-600 text-white">
              <Plus className="size-4" />
            </span>
            카테고리 추가
          </span>
          <input
            value={newName}
            onChange={(event) => setNewName(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') handleAdd()
            }}
            placeholder="추가할 카테고리명 입력"
            className="focus-ring h-9 min-w-0 flex-1 rounded-lg border border-slate-200 bg-white px-3 text-sm text-slate-700 placeholder:text-slate-400"
          />
          <div className="w-52 shrink-0">
            <DepartmentMultiSelect
              value={newDepartments}
              departments={departments}
              onChange={setNewDepartments}
              placeholder="공개 부서 선택"
            />
          </div>
          <Button
            size="sm"
            onClick={handleAdd}
            loading={createMutation.isPending}
            disabled={!newName.trim() || newDepartments.length === 0}
            className="min-w-16"
          >
            추가
          </Button>
        </div>

        <div className="mx-5 mb-4 flex items-center justify-end gap-2">
          <Button
            size="md"
            variant="primary"
            onClick={() => setScopeFilter('')}
            disabled={!scopeFilter}
            className="bg-gradient-to-r from-blue-500 to-violet-600 hover:from-blue-600 hover:to-violet-700 disabled:from-slate-300 disabled:to-slate-300"
          >
            필터 초기화
          </Button>
          <div className="w-56">
            <ScopeCombinationFilter
              value={scopeFilter}
              options={scopeOptions}
              onChange={setScopeFilter}
              placeholder="공개 부서 필터"
            />
          </div>
        </div>

        <div className="overflow-x-auto border-t border-slate-200">
          <table className="w-full min-w-[760px] border-collapse text-left">
            <thead className="bg-slate-50 text-xs font-semibold text-slate-500">
              <tr>
                <th className="px-5 py-3 text-center">카테고리명</th>
                <th className="w-28 px-4 py-3 text-center">문서 수</th>
                <th className="w-64 px-4 py-3 text-center">공개 부서</th>
                <th className="w-44 px-5 py-3 text-center">관리</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr>
                  <td colSpan={4} className="px-5 py-12 text-center text-sm text-slate-400">
                    카테고리를 불러오는 중입니다.
                  </td>
                </tr>
              ) : (
                filteredCategories.map((category, index) => {
                  const count = documentCounts[category.documentCategoryId] ?? 0
                  const deletable = count === 0
                  return (
                    <tr key={category.documentCategoryId} className="border-t border-slate-100">
                      <td className="px-5 py-3">
                        <div className="flex items-center gap-3">
                          <span
                            className={`flex size-8 shrink-0 items-center justify-center rounded-lg text-xs font-bold ${
                              CATEGORY_TONES[index % CATEGORY_TONES.length]
                            }`}
                          >
                            {category.name.slice(0, 1)}
                          </span>
                          <span className="font-semibold text-slate-800">{category.name}</span>
                        </div>
                      </td>
                      <td className={`px-4 py-3 text-center text-sm font-semibold ${count ? 'text-slate-700' : 'text-slate-400'}`}>
                        {count}건
                      </td>
                      <td className="px-4 py-3 text-center text-sm font-medium text-slate-700">
                        <DepartmentSummary
                          values={departmentValuesFor(category)}
                          departments={departments}
                        />
                      </td>
                      <td className="px-5 py-3">
                        <div className="flex flex-nowrap justify-center gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            className="whitespace-nowrap"
                            onClick={() => setEditing(category)}
                          >
                            수정
                          </Button>
                          <span className="group relative inline-flex">
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={!deletable}
                              className={`whitespace-nowrap ${
                                deletable ? 'border-rose-200 text-rose-500 hover:bg-rose-50' : ''
                              }`}
                              onClick={() => setDeleting(category)}
                            >
                              삭제
                            </Button>
                            {!deletable && (
                              <span
                                role="tooltip"
                                className="pointer-events-none absolute bottom-[calc(100%+8px)] right-0 z-20 hidden whitespace-nowrap rounded-lg bg-slate-900 px-3 py-2 text-xs font-medium text-white shadow-lg group-hover:block"
                              >
                                문서 {count}건이 있어 삭제할 수 없습니다.
                                <span className="absolute -bottom-1 right-5 size-2 rotate-45 bg-slate-900" />
                              </span>
                            )}
                          </span>
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
              {!isLoading && filteredCategories.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-5 py-12 text-center text-sm text-slate-400">
                    선택한 공개 부서에 해당하는 카테고리가 없습니다.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="flex items-center gap-2 border-t border-slate-100 bg-primary-50/40 px-5 py-3 text-xs text-slate-500">
          <Info className="size-4 text-primary-500" />
          문서가 없는 카테고리만 삭제할 수 있습니다. 문서가 있으면 먼저 다른 카테고리로 옮겨 주세요.
        </div>
      </div>

      <DocumentCategoryFormModal
        open={Boolean(editing)}
        mode="edit"
        scopeKey={scopeKey}
        category={editing}
        departments={departments}
        defaultDepartments={editing ? departmentValuesFor(editing) : []}
        departmentLabel={
          editing
            ? departmentLabelFor(departmentValuesFor(editing), departments)
            : '부서별 지정'
        }
        documentCount={editing ? (documentCounts[editing.documentCategoryId] ?? 0) : 0}
        onDefaultDepartmentsChange={(values) =>
          setDefaultDepartments((current) => ({
            ...current,
            [editing.documentCategoryId]: values,
          }))
        }
        onClose={() => setEditing(null)}
        onSaved={() => setEditing(null)}
      />

      <CategoryDeleteDialog
        open={Boolean(deleting)}
        category={deleting}
        departmentLabel={
          deleting
            ? departmentLabelFor(departmentValueFor(deleting), departments)
            : '부서 미지정'
        }
        onClose={() => setDeleting(null)}
        onConfirm={handleDelete}
        loading={deleteMutation.isPending}
      />
    </section>
  )
}

function departmentLabelFor(value, departments) {
  const values = Array.isArray(value) ? value : [value]
  if (values.includes('ALL')) return '전체 공개'
  const names = values
    .map((departmentId) =>
      departments.find((department) => String(department.departmentId) === String(departmentId))?.name,
    )
    .filter(Boolean)
  return names.length > 0 ? names.join(', ') : '부서별 지정'
}

function scopeFilterKey(values) {
  if (values.includes('ALL')) return 'ALL'
  return [...values].sort((left, right) => Number(left) - Number(right)).join('-')
}

function ScopeCombinationFilter({ value, options, onChange, placeholder }) {
  const [open, setOpen] = useState(false)
  const buttonRef = useRef(null)
  const popupRef = useRef(null)
  const [position, setPosition] = useState({ top: 0, left: 0 })
  const selectedOption = options.find((option) => option.value === value)
  const label = selectedOption?.label ?? placeholder

  useEffect(() => {
    if (!open) return undefined

    function updatePosition() {
      const rect = buttonRef.current?.getBoundingClientRect()
      if (!rect) return
      const popupWidth = 288
      setPosition({
        top: rect.bottom + 6,
        left: Math.max(12, Math.min(rect.right - popupWidth, window.innerWidth - popupWidth - 12)),
      })
    }

    function closeOnOutside(event) {
      if (!buttonRef.current?.contains(event.target) && !popupRef.current?.contains(event.target)) {
        setOpen(false)
      }
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
  }, [open])

  function select(optionValue) {
    onChange(value === optionValue ? '' : optionValue)
    setOpen(false)
  }

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="focus-ring flex h-10 w-full items-center justify-between gap-2 rounded-lg border border-slate-300 bg-white px-3 text-left text-sm text-slate-700"
      >
        <span className={`truncate ${!value ? 'text-slate-400' : ''}`}>{label}</span>
        <ChevronDown className="size-4 shrink-0 text-slate-400" />
      </button>

      {open && (
        createPortal(
          <div
            ref={popupRef}
            style={{ position: 'fixed', top: position.top, left: position.left, width: 288 }}
            className="z-[100] overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl"
          >
            <div className="max-h-44 overflow-y-auto p-2">
              {options.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  onClick={() => select(option.value)}
                  className={`flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-xs ${
                    value === option.value
                      ? 'bg-primary-50 font-semibold text-slate-800'
                      : 'text-slate-600 hover:bg-slate-50'
                  }`}
                >
                  <span
                    className={`flex size-4 shrink-0 items-center justify-center rounded border ${
                      value === option.value
                        ? 'border-primary-500 bg-primary-500 text-white'
                        : 'border-slate-300 bg-white'
                    }`}
                  >
                    {value === option.value && <Check className="size-3" />}
                  </span>
                  <span className="min-w-0 whitespace-normal break-keep">{option.label}</span>
                </button>
              ))}
            </div>
          </div>
          ,
          document.body,
        )
      )}
    </>
  )
}

function DepartmentSummary({ values, departments }) {
  const [open, setOpen] = useState(false)
  const buttonRef = useRef(null)
  const popupRef = useRef(null)
  const [position, setPosition] = useState({ top: 0, left: 0 })
  const allVisible = values.includes('ALL')
  const names = values
    .map((departmentId) =>
      departments.find((department) => String(department.departmentId) === String(departmentId))?.name,
    )
    .filter(Boolean)
  const label = allVisible
    ? '전체 공개'
    : names.length > 2
      ? `${names[0]} 외 ${names.length - 1}팀`
      : names.join(', ') || '부서별 지정'

  useEffect(() => {
    if (!open) return undefined

    function updatePosition() {
      const rect = buttonRef.current?.getBoundingClientRect()
      if (!rect) return
      setPosition({
        top: rect.bottom + 6,
        left: rect.left + rect.width / 2,
      })
    }

    function closeOnOutside(event) {
      if (!buttonRef.current?.contains(event.target) && !popupRef.current?.contains(event.target)) {
        setOpen(false)
      }
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
  }, [open])

  if (allVisible || names.length <= 2) return <span>{label}</span>

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="focus-ring inline-flex cursor-pointer items-center gap-1 rounded-md px-1.5 py-1 hover:bg-slate-100"
        aria-expanded={open}
      >
        {label}
        <ChevronDown className="size-3.5 text-slate-400" />
      </button>
      {open &&
        createPortal(
          <div
            ref={popupRef}
            style={{
              position: 'fixed',
              top: position.top,
              left: position.left,
              transform: 'translateX(-50%)',
            }}
            className="z-[100] min-w-44 overflow-hidden rounded-xl border border-slate-200 bg-white text-left shadow-xl"
          >
            <div className="border-b border-slate-100 bg-slate-50 px-3 py-2">
              <p className="text-xs font-semibold text-slate-700">공개 부서</p>
              <p className="mt-0.5 text-[11px] text-slate-400">총 {names.length}팀</p>
            </div>
            <div className="space-y-1 p-2">
              {names.map((name) => (
                <span
                  key={name}
                  className="flex items-center gap-2 whitespace-nowrap rounded-lg px-2 py-1.5 text-xs font-medium text-slate-600"
                >
                  <span className="size-1.5 rounded-full bg-primary-500" />
                  {name}
                </span>
              ))}
            </div>
          </div>,
          document.body,
        )}
    </>
  )
}

function CategoryDeleteDialog({ open, category, departmentLabel, onClose, onConfirm, loading }) {
  return (
    <Modal
      open={open}
      onClose={loading ? undefined : onClose}
      closeOnOverlay={!loading}
      showClose={false}
      size="lg"
      footerClassName="grid grid-cols-2 gap-3 bg-slate-50 px-6 py-4"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading} fullWidth>
            취소
          </Button>
          <Button variant="danger" onClick={onConfirm} loading={loading} fullWidth>
            삭제
          </Button>
        </>
      }
    >
      <span className="flex size-12 items-center justify-center rounded-2xl bg-rose-50 text-rose-500">
        <AlertTriangle className="size-6" />
      </span>
      <h2 className="mt-4 text-xl font-bold text-slate-900">
        ‘{category?.name}’ 카테고리를 삭제할까요?
      </h2>
      <p className="mt-1.5 text-sm text-slate-500">
        문서가 없는 카테고리라 바로 삭제할 수 있습니다.
      </p>

      <div className="mt-4 flex items-center gap-3 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-cyan-100 text-xs font-bold text-cyan-700">
          {category?.name?.slice(0, 1)}
        </span>
        <div>
          <p className="text-sm font-bold text-slate-800">{category?.name}</p>
          <p className="mt-0.5 text-xs text-slate-400">문서 0건 · 기본 공개 부서 {departmentLabel}</p>
        </div>
      </div>

      <div className="mt-3 space-y-2">
        <div className="flex items-center gap-2 rounded-xl bg-emerald-50 px-3 py-2.5 text-xs text-emerald-700">
          <span className="flex size-5 items-center justify-center rounded-full bg-emerald-100">
            <Check className="size-3" />
          </span>
          속한 문서가 없어 옮길 문서도 없습니다. 바로 삭제됩니다.
        </div>
        <div className="flex items-center gap-2 rounded-xl bg-primary-50 px-3 py-2.5 text-xs text-slate-500">
          <Info className="size-4 text-primary-500" />
          업로드 화면의 카테고리 선택지에서 즉시 사라집니다.
        </div>
      </div>
    </Modal>
  )
}
