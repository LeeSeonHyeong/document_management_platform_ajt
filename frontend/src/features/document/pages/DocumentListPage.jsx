import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Upload, Settings2 } from 'lucide-react'
import { Button, Pagination, EmptyState } from '@/components/ui'
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

// Figma 4R — 문서 관리 목록. 업로드·처리 현황을 관리자가 확인하는 화면.
export default function DocumentListPage() {
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
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">문서 관리</h1>
        <div className="flex gap-2">
          <Link to="/admin/documents/categories">
            <Button variant="outline">
              <Settings2 className="size-4" />
              카테고리 관리
            </Button>
          </Link>
          {/* 업로드 모달 연결은 다음 브랜치(S15P11B106-65)에서 진행한다. */}
          <Button variant="primary">
            <Upload className="size-4" />
            업로드
          </Button>
        </div>
      </div>

      <DocumentFilterBar filters={filters} onChange={updateFilters} />

      <DocumentTable
        documents={data?.items ?? []}
        loading={isLoading}
        onRowClick={(doc) => navigate(`/admin/documents/source/${doc.documentId}`)}
        emptyState={
          <EmptyState
            title="업로드된 문서가 없습니다"
            description="Wiki 원본문서를 업로드하면 여기에서 처리 현황을 확인할 수 있습니다."
          />
        }
      />

      <Pagination page={filters.page} totalPages={data?.totalPages ?? 0} onChange={goToPage} />
    </section>
  )
}
