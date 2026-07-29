import { FileText } from 'lucide-react'
import { DataTable, Badge, Chip, Button } from '@/components/ui'

// shared/ui/Chip에는 tone(색상 변형)이 없어 상태 표시는 Badge(tone 지원)를 쓴다.
// Chip은 계획대로 부서 다중 표시에만 사용한다.
const STATUS_TONE = {
  uploaded: 'neutral',
  parsing: 'info',
  processing: 'info',
  completed: 'success',
  failed: 'danger',
  cancelled: 'neutral',
}

// shared/constants/enums.js의 DOCUMENT_STATUS에는 라벨 맵이 없어(CANCELLED 값도 없음) 이 파일에서만 사용한다.
const STATUS_LABEL = {
  uploaded: '업로드 완료',
  parsing: '파싱 중',
  processing: '처리 중',
  completed: '처리 완료',
  failed: '실패',
  cancelled: '취소',
}

function formatDateTime(iso) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })
}

function formatDate(iso) {
  if (!iso) return '-'
  const date = new Date(iso)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function formatFileSize(bytes) {
  if (!bytes && bytes !== 0) return '-'
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)}MB` : `${Math.max(1, Math.round(bytes / 1024))}KB`
}

// DocumentListPage(4R)와 SourceDocumentListPage(4-7R)가 공유하는 테이블.
// renderAction으로 화면별 액션 셀(상세 이동 등)을 주입한다.
export default function DocumentTable({ documents, loading, onRowClick, emptyState, renderAction, variant = 'status' }) {
  const fileColumn = {
    key: 'originalFileName',
    header: '파일명',
    render: (doc) => (
      <div className="flex items-center gap-3">
        <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-500">
          <FileText className="size-4" />
        </span>
        <div className="min-w-0">
          <p className="truncate font-semibold text-slate-800">{doc.originalFileName}</p>
          <p className="text-xs text-slate-400">{formatFileSize(doc.fileSize)}</p>
        </div>
      </div>
    ),
  }

  const sourceColumns = [
    fileColumn,
    {
      key: 'documentCategoryName',
      header: '카테고리',
      render: (doc) => (
        <span className="rounded-full bg-slate-100 px-2 py-1 text-xs text-slate-600">
          {doc.documentCategoryName ?? '미분류'}
        </span>
      ),
    },
    {
      key: 'visibility',
      header: '공개 부서',
      render: (doc) =>
        doc.visibilityType === 'all'
          ? '전체 공개'
          : (doc.departments ?? []).map((department) => department.name).join(', ') || '-',
    },
    { key: 'uploadedAt', header: '업로드일', render: (doc) => formatDate(doc.uploadedAt) },
    {
      key: 'wiki',
      header: '위키',
      render: (doc) => (
        <span className="font-semibold text-primary-600">
          {doc.relatedWikis?.length ? `● ${doc.relatedWikis.length}건` : '—'}
        </span>
      ),
    },
    {
      key: 'manage',
      header: '관리',
      align: 'right',
      render: (doc) => (
        <Button size="sm" variant="outline" onClick={() => onRowClick?.(doc)}>
          상세
        </Button>
      ),
    },
  ]

  const statusColumns = [
    {
      ...fileColumn,
    },
    {
      key: 'documentCategoryName',
      header: '카테고리',
      render: (doc) => doc.documentCategoryName ?? '-',
    },
    {
      key: 'visibility',
      header: '공개 범위',
      render: (doc) =>
        doc.visibilityType === 'all' ? (
          <Badge tone="primary">전체</Badge>
        ) : (
          <div className="flex flex-wrap gap-1">
            {(doc.departments ?? []).map((dept) => (
              <Chip key={dept.departmentId}>{dept.name}</Chip>
            ))}
          </div>
        ),
    },
    {
      key: 'status',
      header: '상태',
      render: (doc) => (
        <Badge tone={STATUS_TONE[doc.status] ?? 'neutral'}>
          {STATUS_LABEL[doc.status] ?? doc.status}
        </Badge>
      ),
    },
    {
      key: 'uploadedBy',
      header: '업로드자',
      render: (doc) => doc.uploadedBy?.name ?? '-',
    },
    {
      key: 'uploadedAt',
      header: '업로드 시각',
      render: (doc) => formatDateTime(doc.uploadedAt),
    },
    ...(renderAction ? [{ key: 'actions', header: '', align: 'right', render: renderAction }] : []),
  ]
  const columns = variant === 'source' ? sourceColumns : statusColumns

  return (
    <DataTable
      columns={columns}
      rows={documents}
      rowKey="documentId"
      loading={loading}
      onRowClick={onRowClick}
      emptyState={emptyState}
    />
  )
}
