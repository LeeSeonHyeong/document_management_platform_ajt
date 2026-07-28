import { useNavigate, useSearchParams } from 'react-router-dom'
import { Pagination, EmptyState } from '@/components/ui'
import { useDocuments } from '../queries'
import DocumentTable from '../components/DocumentTable'
import DocumentFilterBar from '../components/DocumentFilterBar'

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
    <section className="space-y-4">
      <h1 className="text-2xl font-semibold">원본 문서</h1>

      <DocumentFilterBar filters={filters} onChange={updateFilters} />

      <DocumentTable
        documents={data?.items ?? []}
        loading={isLoading}
        // 상세 화면은 다음 브랜치(S15P11B106-68 원본문서 상세)에서 만든다. 지금은 라우트만 연결한다.
        onRowClick={(doc) => navigate(`/admin/documents/source/${doc.documentId}`)}
        emptyState={<EmptyState title="검색 결과가 없습니다" description="필터 조건을 변경해보세요." />}
      />

      <Pagination page={filters.page} totalPages={data?.totalPages ?? 0} onChange={goToPage} />
    </section>
  )
}
