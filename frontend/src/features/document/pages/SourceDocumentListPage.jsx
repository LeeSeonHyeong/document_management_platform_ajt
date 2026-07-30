import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { ChevronDown } from 'lucide-react'
import { Pagination, EmptyState, SearchBar, Select } from '@/components/ui'
import { useDepartments } from '@/features/department/useDepartments'
import { useDocuments } from '../queries'
import DocumentTable from '../components/DocumentTable'
import DocumentSectionTabs from '../components/DocumentSectionTabs'
import { readPreviewSourceDocuments } from '../previewStorage'

const PAGE_SIZE = 20

function filtersFromParams(params) {
  const filters = { page: Number(params.get('page') ?? '1'), size: PAGE_SIZE }
  // 새 4-7R에서 실제로 노출하는 필터만 API 요청에 포함한다.
  // 제거된 예전 필터가 URL에 남아 목록을 0건으로 만드는 문제를 방지한다.
  for (const key of ['categoryId', 'keyword', 'departmentId']) {
    const value = params.get(key)
    if (value) filters[key] = value
  }
  return filters
}

// Figma 4-7R — 원본 문서 목록. 권한 내 Wiki 원본문서를 검색·열람하는 화면(FR-DOC-010).
export default function SourceDocumentListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const [sortOrder, setSortOrder] = useState('latest')
  const [previewDocuments] = useState(readPreviewSourceDocuments)
  const filters = filtersFromParams(searchParams)

  const { data, isLoading } = useDocuments(filters)
  const { data: allData } = useDocuments({ page: 1, size: 100 })
  const { data: departments = [] } = useDepartments()
  const matchingPreviewDocuments = previewDocuments.filter((document) => {
    if (filters.keyword && !document.originalFileName.toLowerCase().includes(filters.keyword.toLowerCase())) {
      return false
    }
    if (filters.categoryId && String(document.documentCategoryId) !== String(filters.categoryId)) {
      return false
    }
    if (
      filters.departmentId &&
      !(document.departments ?? []).some(
        (department) => String(department.departmentId) === String(filters.departmentId),
      )
    ) {
      return false
    }
    return true
  })
  const documents = [...matchingPreviewDocuments, ...(data?.items ?? [])]
  const sortedDocuments = [...documents].sort((a, b) => {
    const left = new Date(a.uploadedAt ?? 0).getTime()
    const right = new Date(b.uploadedAt ?? 0).getTime()
    return sortOrder === 'latest' ? right - left : left - right
  })
  const allDocuments = [...previewDocuments, ...(allData?.items ?? [])]
  const visibleDepartments = departments.slice(0, 5)
  const selectedDepartment = departments.find(
    (department) => String(department.departmentId) === String(filters.departmentId),
  )
  const departmentBaseDocuments = filters.departmentId
    ? allDocuments.filter((document) =>
        (document.departments ?? []).some(
          (department) => String(department.departmentId) === String(filters.departmentId),
        ),
      )
    : allDocuments
  const categoryCounts = departmentBaseDocuments.reduce((counts, document) => {
    const categoryId = document.documentCategoryId
      ? String(document.documentCategoryId)
      : null
    const categoryName = document.documentCategoryName?.trim() || '미분류'
    const key = categoryId ?? 'uncategorized'
    const current = counts.get(key) ?? {
      id: categoryId,
      name: categoryName,
      count: 0,
    }
    current.count += 1
    counts.set(key, current)
    return counts
  }, new Map())

  function updateFilters(patch) {
    const next = { ...filters, ...patch, page: 1 }
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(next)) {
      if (value && key !== 'size') params.set(key, String(value))
    }
    setSearchParams(params)
  }

  function goToPage(page) {
    const params = new URLSearchParams(searchParams)
    params.set('page', String(page))
    setSearchParams(params)
  }

  return (
    <section className="space-y-5">
      <DocumentSectionTabs />

      <div className="flex min-h-[620px] items-stretch gap-4">
        <aside className="flex w-64 shrink-0 flex-col rounded-2xl border border-slate-200 bg-white p-4">
          <div>
            <div className="flex items-center justify-between px-2 py-1">
              <h2 className="font-bold text-slate-800">부서</h2>
              <span className="text-xs font-semibold text-slate-400">{visibleDepartments.length}</span>
            </div>

            <DepartmentDropdown
              selectedDepartment={selectedDepartment}
              selectedDepartmentId={filters.departmentId}
              departments={visibleDepartments}
              documents={allDocuments}
              onSelect={(departmentId) =>
                updateFilters({ departmentId: departmentId || undefined, categoryId: undefined })
              }
            />
          </div>

          <div className="my-4 border-t border-slate-200" />

          <div className="flex items-center justify-between px-2 py-1">
            <h2 className="font-bold text-slate-800">카테고리</h2>
            <span className="text-xs font-semibold text-slate-400">{categoryCounts.size + 1}</span>
          </div>
          <div className="mt-2 space-y-0.5">
            <FilterItem
              label="전체 문서"
              count={departmentBaseDocuments.length}
              active={!filters.categoryId}
              onClick={() => updateFilters({ categoryId: undefined })}
            />
            {[...categoryCounts.values()].filter((category) => category.count > 0).map((category) => (
              <FilterItem
                key={category.id ?? category.name}
                label={category.name}
                count={category.count}
                active={String(filters.categoryId) === String(category.id)}
                onClick={() => updateFilters({ categoryId: category.id })}
              />
            ))}
          </div>
        </aside>

        <div className="min-w-0 flex-1 overflow-hidden rounded-2xl border border-slate-200 bg-white">
          <div className="px-5 pt-4">
            <div className="flex items-start justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <h2 className="text-xl font-bold text-slate-800">전체 문서</h2>
                  <span className="rounded-full bg-primary-50 px-2 py-0.5 text-xs font-semibold text-primary-600">
                    {(data?.totalItems ?? data?.totalElements ?? data?.items?.length ?? 0) +
                      matchingPreviewDocuments.length}건
                  </span>
                </div>
                <p className="mt-1 text-xs text-slate-400">
                  AI 작업에 사용된 원본 파일입니다. 삭제하면 위키 내용까지 함께 정리됩니다.
                </p>
              </div>
            </div>
            <div className="my-4 flex items-center gap-3">
              <SearchBar
                placeholder="파일명으로 검색"
                defaultValue={filters.keyword ?? ''}
                onSearch={(keyword) => updateFilters({ keyword: keyword || undefined })}
                className="min-w-0 flex-1"
              />
              <Select
                aria-label="정렬 순서"
                value={sortOrder}
                onChange={(event) => setSortOrder(event.target.value)}
                options={[
                  { value: 'latest', label: '최신순' },
                  { value: 'oldest', label: '오래된순' },
                ]}
                className="w-28"
              />
            </div>
          </div>

          <DocumentTable
            documents={sortedDocuments}
            loading={isLoading}
            onDetailClick={(doc) => navigate(`/admin/documents/source/${doc.documentId}`)}
            emptyState={<EmptyState title="검색 결과가 없습니다" description="필터 조건을 변경해보세요." />}
            variant="source"
          />

          <Pagination
            page={filters.page}
            totalPages={data?.totalPages ?? 0}
            onChange={goToPage}
            className="border-t border-slate-100 py-4"
          />
        </div>
      </div>
    </section>
  )
}

function DepartmentDropdown({ selectedDepartment, selectedDepartmentId, departments, documents, onSelect }) {
  const [open, setOpen] = useState(false)
  const buttonRef = useRef(null)
  const menuRef = useRef(null)
  const [position, setPosition] = useState({ top: 0, left: 0, width: 0 })

  useEffect(() => {
    if (!open) return

    function updatePosition() {
      const rect = buttonRef.current?.getBoundingClientRect()
      if (!rect) return
      setPosition({ top: rect.bottom + 6, left: rect.left, width: rect.width })
    }

    function closeOnOutside(event) {
      if (!buttonRef.current?.contains(event.target) && !menuRef.current?.contains(event.target)) setOpen(false)
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

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen((current) => !current)}
        className={`focus-ring mt-2 flex w-full items-center gap-2 rounded-xl border px-3 py-3 text-sm font-semibold ${
          selectedDepartmentId
            ? 'border-primary-300 bg-primary-50 text-primary-600'
            : 'border-slate-200 bg-white text-slate-500'
        }`}
      >
        <span className={`size-2 rounded-full ${selectedDepartmentId ? 'bg-primary-500' : 'bg-slate-300'}`} />
        <span className="truncate">{selectedDepartment?.name ?? '전체 부서'}</span>
        <ChevronDown className={`ml-auto size-4 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open &&
        createPortal(
          <div
            ref={menuRef}
            style={{ position: 'fixed', top: position.top, left: position.left, width: position.width }}
            className="z-[80] space-y-0.5 rounded-xl border border-slate-200 bg-white p-1.5 shadow-xl"
          >
            <FilterItem
              label="전체 부서"
              count={documents.length}
              active={!selectedDepartmentId}
              onClick={() => {
                onSelect('')
                setOpen(false)
              }}
            />
            {departments.map((department) => {
              const count = documents.filter((document) =>
                (document.departments ?? []).some(
                  (item) => String(item.departmentId) === String(department.departmentId),
                ),
              ).length
              return (
                <FilterItem
                  key={department.departmentId}
                  label={department.name}
                  count={count}
                  active={String(selectedDepartmentId) === String(department.departmentId)}
                  onClick={() => {
                    onSelect(department.departmentId)
                    setOpen(false)
                  }}
                />
              )
            })}
          </div>,
          document.body,
        )}
    </>
  )
}

function FilterItem({ label, count, active, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`focus-ring flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-sm ${
        active ? 'bg-primary-50 font-semibold text-primary-600' : 'text-slate-500 hover:bg-slate-50'
      }`}
    >
      <span className={`size-2 shrink-0 rounded-full ${active ? 'bg-primary-500' : 'bg-slate-300'}`} />
      <span className="truncate">{label}</span>
      <span className="ml-auto text-xs">{count}</span>
    </button>
  )
}
