import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Upload, Settings2 } from 'lucide-react'
import { Button, Pagination, EmptyState, useToast } from '@/components/ui'
import { useDocuments } from '../queries'
import DocumentTable from '../components/DocumentTable'
import DocumentFilterBar from '../components/DocumentFilterBar'
import DocumentUploadModal from '../components/DocumentUploadModal'

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
  const toast = useToast()
  const [uploadOpen, setUploadOpen] = useState(false)
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
          <Button variant="primary" onClick={() => setUploadOpen(true)}>
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

      <DocumentUploadModal
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onUploaded={(result) => {
          // 업로드 성공(202). 목록은 useUploadDocuments가 invalidate 한다.
          // AI 작업 진행 화면 연결은 다음 브랜치(S15P11B106-74)에서 jobId로 진행한다.
          toast.success(`업로드가 시작되었습니다 (문서 ${result?.documentIds?.length ?? 0}건)`)
        }}
      />
    </section>
  )
}
