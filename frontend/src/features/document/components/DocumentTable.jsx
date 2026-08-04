import { FileText } from 'lucide-react'
import { DataTable, Badge, Chip, Button } from '@/components/ui'
import { QueueCategorySelect, QueueVisibilityDropdown } from './QueueDocumentFields'

// shared/ui/Chip에는 tone(색상 변형)이 없어 상태 표시는 Badge(tone 지원)를 쓴다.
// Chip은 계획대로 부서 다중 표시에만 사용한다.
const STATUS_TONE = {
  uploaded: 'neutral',
  parsing: 'info',
  processing: 'info',
  // 삭제 대기 — Wiki 걷어내기가 끝나면 지워진다 (S15P11B106-195).
  deleting: 'warning',
  completed: 'success',
  failed: 'danger',
  cancelled: 'neutral',
}

// shared/constants/enums.js의 DOCUMENT_STATUS에는 라벨 맵이 없어(CANCELLED 값도 없음) 이 파일에서만 사용한다.
const STATUS_LABEL = {
  uploaded: '업로드 완료',
  parsing: '파싱 중',
  processing: '처리 중',
  deleting: '삭제 중',
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

function getDocumentType(doc) {
  const extension = doc.originalFileName?.split('.').pop()?.toLowerCase()
  if (extension === 'csv' || extension === 'xlsx' || extension === 'xls') return '일정'
  return '문서'
}

// DocumentListPage(4R)와 SourceDocumentListPage(4-7R)가 공유하는 테이블.
// renderAction으로 화면별 액션 셀(상세 이동 등)을 주입한다.
export default function DocumentTable({
  documents,
  loading,
  onRowClick,
  onDetailClick,
  emptyState,
  renderAction,
  onQueueMetadataChange,
  variant = 'status',
}) {
  const fileColumn = {
    key: 'originalFileName',
    header: '파일명',
    render: (doc) => (
      <div className="flex min-w-0 items-center gap-3 overflow-hidden">
        <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-500">
          <FileText className="size-4" />
        </span>
        <div className="min-w-0 flex-1 overflow-hidden">
          <p className="block truncate font-semibold text-slate-800" title={doc.originalFileName}>
            {doc.originalFileName}
          </p>
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
      // 「위키」만으로는 무엇을 세는 값인지 알 수 없었고, 「—」는 0개인지 알 수 없음인지
      // 모호했다 (S15P11B106-252). 이 문서를 근거로 만들어진 Wiki 문서 수다.
      key: 'wiki',
      header: '관련 위키',
      render: (doc) => {
        const count = doc.relatedWikis?.length ?? 0
        return (
          <span className={count ? 'font-semibold text-primary-600' : 'text-slate-400'}>
            {count ? `● ${count}개` : '0개'}
          </span>
        )
      },
    },
    // 상태 컬럼을 두지 않는다 (S15P11B106-248). 이 화면은 「지금 Wiki 의 근거가 무엇인가」를
    // 보는 곳이고, AI 작업이 어떻게 됐는지는 요약 목록의 관심사다. S15P11B106-200 이 진행
    // 상황을 볼 곳이 없어 여기에 넣었지만, 그때 요약 목록에도 「진행 중인 AI 작업」 묶음을
    // 함께 넣어서 지금은 두 곳에 있다 — 제자리인 요약 목록만 남긴다.
    {
      key: 'manage',
      header: '관리',
      align: 'right',
      render: (doc) => (
        <Button size="sm" variant="outline" onClick={() => (onDetailClick ?? onRowClick)?.(doc)}>
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
          <div className="flex flex-wrap justify-center gap-1">
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

  const queueColumns = [
    {
      ...fileColumn,
      className: 'w-[30%] max-w-0',
      headerClassName: 'w-[30%]',
    },
    {
      key: 'type',
      header: '종류',
      className: 'w-[10%]',
      headerClassName: 'w-[10%]',
      render: (doc) => (
        <span className="rounded-lg bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-600">
          {getDocumentType(doc)}
        </span>
      ),
    },
    {
      key: 'visibility',
      header: '공개 부서',
      className: 'w-[22%]',
      headerClassName: 'w-[22%]',
      render: (doc) => (
        <div className="flex justify-center">
          <QueueVisibilityDropdown
            item={doc}
            localOnly={doc.previewOnly}
            onApplied={(changes) => onQueueMetadataChange?.(doc.documentId, changes)}
          />
        </div>
      ),
    },
    {
      key: 'documentCategoryName',
      header: '카테고리',
      className: 'w-[22%]',
      headerClassName: 'w-[22%]',
      render: (doc) => (
        <QueueCategorySelect
          item={doc}
          localOnly={doc.previewOnly}
          onApplied={(changes) => onQueueMetadataChange?.(doc.documentId, changes)}
        />
      ),
    },
    {
      key: 'status',
      header: '상태',
      className: 'w-[11%]',
      headerClassName: 'w-[11%]',
      render: (doc) => (
        <Badge tone="success" className="whitespace-nowrap">
          {STATUS_LABEL[doc.status] ?? '업로드 완료'}
        </Badge>
      ),
    },
    ...(renderAction
      ? [{
          key: 'actions',
          header: '',
          align: 'right',
          className: 'w-[5%]',
          headerClassName: 'w-[5%]',
          render: renderAction,
        }]
      : []),
  ]

  const baseColumns =
    variant === 'source' ? sourceColumns : variant === 'queue' ? queueColumns : statusColumns
  // 문서 목록은 파일명 열만 왼쪽 정렬하고 나머지 데이터는 중앙 정렬한다.
  const columns = baseColumns.map((column, index) => ({
    ...column,
    align: index === 0 ? 'left' : 'center',
  }))

  return (
    <DataTable
      columns={columns}
      rows={documents}
      rowKey="documentId"
      loading={loading}
      onRowClick={onRowClick}
      emptyState={emptyState}
      headerAlign="center"
      tableClassName={variant === 'queue' ? 'table-fixed' : undefined}
    />
  )
}
