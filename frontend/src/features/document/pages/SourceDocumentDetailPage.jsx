import { useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Download, RefreshCw, Pencil, Trash2 } from 'lucide-react'
import { Button, Badge, Chip, Spinner, useToast } from '@/components/ui'
import { useDocument, useReplaceDocumentFile } from '../queries'
import { fetchDocumentFile } from '../api'
import { DOC_STATUS_TONE, DOC_STATUS_LABEL } from '../status'
import DocumentMetaEditModal from '../components/DocumentMetaEditModal'
import DocumentDeleteDialog from '../components/DocumentDeleteDialog'

function formatBytes(bytes) {
  if (!bytes && bytes !== 0) return '-'
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}
function formatDateTime(iso) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })
}
function fileExtension(name) {
  return name?.includes('.') ? name.split('.').pop().toUpperCase() : '-'
}

function Row({ label, children }) {
  return (
    <div className="flex gap-4 py-2">
      <dt className="w-28 shrink-0 text-sm text-slate-500">{label}</dt>
      <dd className="text-sm text-slate-800">{children}</dd>
    </div>
  )
}

// Figma 4-7-1R — 원본 문서 상세. 메타데이터·연관 Wiki 표시와 다운로드/파일 교체/메타 수정/삭제 액션.
export default function SourceDocumentDetailPage() {
  const { documentId } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const fileInputRef = useRef(null)
  const [downloading, setDownloading] = useState(false)
  const [metaOpen, setMetaOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)

  const { data: doc, isLoading } = useDocument(documentId)
  const replaceMutation = useReplaceDocumentFile(documentId)

  async function handleDownload() {
    setDownloading(true)
    try {
      const { blob, fileName } = await fetchDocumentFile(documentId)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = fileName ?? doc?.originalFileName ?? 'document'
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      // 서버가 매 요청 권한을 검사하므로 403을 구분해 안내한다.
      if (e?.response?.status === 403) toast.error('이 문서를 다운로드할 권한이 없습니다.')
      else toast.error('다운로드에 실패했습니다.')
    } finally {
      setDownloading(false)
    }
  }

  function handleReplaceFile(e) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    replaceMutation.mutate(file, {
      onSuccess: () => toast.success('파일을 교체했습니다. 문서가 다시 처리됩니다.'),
      onError: () => toast.error('파일 교체에 실패했습니다.'),
    })
  }

  if (isLoading || !doc) {
    return (
      <div className="flex justify-center py-16">
        <Spinner />
      </div>
    )
  }

  return (
    <section className="space-y-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{doc.originalFileName}</h1>
          <p className="mt-1 text-sm text-slate-500">원본 문서 상세</p>
        </div>
        <Badge tone={DOC_STATUS_TONE[doc.status] ?? 'neutral'}>{DOC_STATUS_LABEL[doc.status] ?? doc.status}</Badge>
      </div>

      {doc.status === 'failed' && doc.failureReason && (
        <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">처리 실패: {doc.failureReason}</p>
      )}

      <dl className="divide-y divide-slate-100 rounded-xl border border-slate-200 px-4 py-2">
        <Row label="파일 형식">{fileExtension(doc.originalFileName)} · {doc.mimeType}</Row>
        <Row label="크기">{formatBytes(doc.fileSize)}</Row>
        <Row label="카테고리">{doc.documentCategoryName ?? '-'}</Row>
        <Row label="공개 범위">
          {doc.visibilityType === 'all' ? (
            <Badge tone="primary">전체</Badge>
          ) : (
            <div className="flex flex-wrap gap-1">
              {(doc.departments ?? []).map((dept) => (
                <Chip key={dept.departmentId}>{dept.name}</Chip>
              ))}
            </div>
          )}
        </Row>
        <Row label="업로드자">{doc.uploadedBy?.name ?? '-'}</Row>
        <Row label="업로드 시각">{formatDateTime(doc.uploadedAt)}</Row>
      </dl>

      {/* 연관 Wiki (FR-DOC-013) — 서버가 권한 없는/미반영 Wiki를 걸러서 내려준다. */}
      <div>
        <h2 className="mb-2 text-sm font-medium text-slate-700">연관 Wiki</h2>
        {doc.relatedWikis?.length > 0 ? (
          <ul className="space-y-1">
            {doc.relatedWikis.map((w) => (
              <li key={w.wikiId}>
                {/* Wiki 상세 라우트는 브랜치 8에서 확정된다. */}
                <Link to={`/wiki/${w.wikiId}`} className="text-sm text-primary-600 underline-offset-2 hover:underline">
                  {w.title}
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-slate-400">이 문서로 반영된 Wiki가 없습니다.</p>
        )}
      </div>

      <div className="flex flex-wrap gap-2 border-t border-slate-100 pt-4">
        <Button variant="outline" onClick={handleDownload} loading={downloading}>
          <Download className="size-4" />
          다운로드
        </Button>
        <Button variant="outline" onClick={() => fileInputRef.current?.click()} loading={replaceMutation.isPending}>
          <RefreshCw className="size-4" />
          파일 교체
        </Button>
        <Button variant="outline" onClick={() => setMetaOpen(true)}>
          <Pencil className="size-4" />
          메타 수정
        </Button>
        <Button variant="danger" onClick={() => setDeleteOpen(true)}>
          <Trash2 className="size-4" />
          삭제
        </Button>
        <input ref={fileInputRef} type="file" className="hidden" onChange={handleReplaceFile} />
      </div>

      <DocumentMetaEditModal open={metaOpen} doc={doc} onClose={() => setMetaOpen(false)} onSaved={() => setMetaOpen(false)} />

      <DocumentDeleteDialog
        open={deleteOpen}
        documentId={documentId}
        onClose={() => setDeleteOpen(false)}
        onDeleted={() => {
          setDeleteOpen(false)
          navigate('/admin/documents/source')
        }}
      />
    </section>
  )
}
