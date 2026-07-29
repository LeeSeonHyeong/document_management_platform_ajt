import { useNavigate, useSearchParams } from 'react-router-dom'
import { Link } from 'react-router-dom'
import { ChevronRight, Settings2 } from 'lucide-react'
import { Pagination, EmptyState } from '@/components/ui'
import { useDocuments } from '../queries'
import DocumentTable from '../components/DocumentTable'
import DocumentFilterBar from '../components/DocumentFilterBar'
import DocumentSectionTabs from '../components/DocumentSectionTabs'

const PAGE_SIZE = 20

function filtersFromParams(params) {
  const filters = { page: Number(params.get('page') ?? '1'), size: PAGE_SIZE }
  for (const key of ['scopeKey', 'categoryId', 'status', 'keyword', 'fileType', 'departmentId', 'uploadedFrom', 'uploadedTo']) {
    const value = params.get(key)
    if (value) filters[key] = value
  }
  return filters
}

// Figma 4-7R — 원본 문서 목록. 권한 내 Wiki 원본문서를 검색·열람하는 화면(FR-DOC-010).
export default function SourceDocumentListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const filters = filtersFromParams(searchParams)

  const { data, isLoading } = useDocuments(filters)
  const documents = data?.items ?? []
  const categoryCounts = documents.reduce((counts, document) => {
    const name = document.documentCategoryName ?? '미분류'
    counts.set(name, (counts.get(name) ?? 0) + 1)
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
        <aside className="flex w-52 shrink-0 flex-col rounded-2xl border border-slate-200 bg-white p-3">
          <div className="flex items-center justify-between px-2 py-2">
            <h2 className="font-bold text-slate-800">카테고리</h2>
            <span className="text-xs font-semibold text-slate-400">{categoryCounts.size}</span>
          </div>
          <button
            type="button"
            onClick={() => updateFilters({ categoryId: undefined })}
            className="focus-ring flex items-center gap-2 rounded-lg bg-primary-50 px-3 py-2 text-sm font-semibold text-primary-600"
          >
            <span className="size-1.5 rounded-full bg-primary-500" />
            전체 문서
            <span className="ml-auto">{data?.totalItems ?? data?.totalElements ?? documents.length}</span>
          </button>
          <div className="mt-1 space-y-0.5">
            {[...categoryCounts.entries()].map(([name, count]) => (
              <div key={name} className="flex items-center gap-2 rounded-lg px-3 py-2 text-sm text-slate-500">
                <span className="size-1.5 rounded-full bg-slate-300" />
                <span className="truncate">{name}</span>
                <span className="ml-auto text-xs">{count}</span>
              </div>
            ))}
          </div>
          <Link
            to="/admin/documents/categories"
            className="focus-ring mt-auto flex items-center justify-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-semibold text-slate-500 hover:bg-slate-100"
          >
            <Settings2 className="size-4" />
            카테고리 관리
            <ChevronRight className="size-3" />
          </Link>
        </aside>

        <div className="min-w-0 flex-1 overflow-hidden rounded-2xl border border-slate-200 bg-white">
          <div className="px-5 pt-4">
            <div className="flex items-start justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <h2 className="text-xl font-bold text-slate-800">전체 문서</h2>
                  <span className="rounded-full bg-primary-50 px-2 py-0.5 text-xs font-semibold text-primary-600">
                    {data?.totalItems ?? data?.totalElements ?? documents.length}건
                  </span>
                </div>
                <p className="mt-1 text-xs text-slate-400">
                  AI 작업에 사용된 원본 파일입니다. 삭제하면 위키 내용까지 함께 정리됩니다.
                </p>
              </div>
            </div>
            <DocumentFilterBar filters={filters} onChange={updateFilters} />
          </div>

          <DocumentTable
            documents={documents}
            loading={isLoading}
            onRowClick={(doc) => navigate(`/admin/documents/source/${doc.documentId}`)}
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
