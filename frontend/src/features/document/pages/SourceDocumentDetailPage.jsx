import { useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ChevronRight, Download, FileText, Pencil, RefreshCw, Trash2 } from 'lucide-react'
import { Badge, Button, Chip, Spinner, useToast } from '@/components/ui'
import { fetchDocumentFile } from '../api'
import { useDocument, useReplaceDocumentFile } from '../queries'
import { DOC_STATUS_LABEL, DOC_STATUS_TONE } from '../status'
import DocumentDeleteDialog from '../components/DocumentDeleteDialog'
import DocumentMetaEditModal from '../components/DocumentMetaEditModal'

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

function InfoRow({ label, children }) {
  return (
    <div className="grid grid-cols-[88px_minmax(0,1fr)] gap-3 py-2.5">
      <dt className="text-xs text-slate-400">{label}</dt>
      <dd className="min-w-0 text-sm font-medium text-slate-700">{children}</dd>
    </div>
  )
}

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
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = fileName ?? doc?.originalFileName ?? 'document'
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      if (error?.response?.status === 403) toast.error('이 문서를 다운로드할 권한이 없습니다.')
      else toast.error('다운로드에 실패했습니다.')
    } finally {
      setDownloading(false)
    }
  }

  function handleReplaceFile(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
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
    <section className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
      <div className="flex min-h-[720px] min-w-0 flex-col rounded-2xl border border-slate-200 bg-white p-5">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-4">
          <div className="flex min-w-0 items-center gap-3">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary-50 text-primary-500">
              <FileText className="size-5" />
            </span>
            <div className="min-w-0">
              <h1 className="truncate text-lg font-bold text-slate-800">{doc.originalFileName}</h1>
              <p className="mt-0.5 text-xs text-slate-400">
                {fileExtension(doc.originalFileName)} · {formatBytes(doc.fileSize)}
              </p>
            </div>
          </div>
          <Button variant="outline" onClick={() => fileInputRef.current?.click()} loading={replaceMutation.isPending}>
            <RefreshCw className="size-4" />
            파일 교체
          </Button>
        </div>

        {doc.status === 'failed' && doc.failureReason && (
          <p className="mt-4 rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">
            처리 실패: {doc.failureReason}
          </p>
        )}

        <div className="mt-4 flex flex-1 items-start justify-center rounded-2xl bg-slate-50 p-8">
          <div className="min-h-80 w-full max-w-xl rounded-md border border-slate-200 bg-white p-8 shadow-sm">
            <p className="text-center text-lg font-bold text-slate-800">{doc.originalFileName}</p>
            <p className="mt-10 text-sm leading-7 text-slate-500">
              원본 문서 미리보기 영역입니다. 파일 미리보기 데이터가 연결되면 이 영역에 문서 내용이 표시됩니다.
            </p>
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
          <p className="text-xs text-slate-400">삭제하면 위키 반영이 먼저 정리되고, 완료된 뒤 원본이 삭제됩니다.</p>
          <div className="flex gap-2">
            <Button variant="danger" onClick={() => setDeleteOpen(true)}>
              <Trash2 className="size-4" />
              삭제
            </Button>
            <Button variant="primary" onClick={handleDownload} loading={downloading}>
              <Download className="size-4" />
              다운로드
            </Button>
          </div>
        </div>
        <input ref={fileInputRef} type="file" className="hidden" onChange={handleReplaceFile} />
      </div>

      <aside className="space-y-4">
        <div className="rounded-2xl border border-slate-200 bg-white p-5">
          <div className="flex items-center justify-between border-b border-slate-200 pb-4">
            <h2 className="font-bold text-slate-800">문서 정보</h2>
            <Button size="sm" variant="ghost" onClick={() => setMetaOpen(true)}>
              <Pencil className="size-4" />
              수정
            </Button>
          </div>
          <dl className="mt-2">
            <InfoRow label="카테고리">{doc.documentCategoryName ?? '-'}</InfoRow>
            <InfoRow label="공개 부서">
              {doc.visibilityType === 'all' ? (
                <Badge tone="primary">전체 공개</Badge>
              ) : (
                <div className="flex flex-wrap gap-1">
                  {(doc.departments ?? []).map((department) => (
                    <Chip key={department.departmentId}>{department.name}</Chip>
                  ))}
                </div>
              )}
            </InfoRow>
            <InfoRow label="업로더">{doc.uploadedBy?.name ?? '-'}</InfoRow>
            <InfoRow label="업로드일">{formatDateTime(doc.uploadedAt)}</InfoRow>
            <InfoRow label="용량 · 형식">
              {formatBytes(doc.fileSize)} · {fileExtension(doc.originalFileName)}
            </InfoRow>
          </dl>
          <Badge tone={DOC_STATUS_TONE[doc.status] ?? 'neutral'}>
            {DOC_STATUS_LABEL[doc.status] ?? doc.status}
          </Badge>
        </div>

        <div className="rounded-2xl border border-slate-200 bg-white p-5">
          <h2 className="mb-4 font-bold text-slate-800">연결된 위키 문서</h2>
          {doc.relatedWikis?.length > 0 ? (
            <ul className="space-y-2">
              {doc.relatedWikis.map((wiki) => (
                <li key={wiki.wikiId}>
                  <Link
                    to={`/wiki/${wiki.wikiId}`}
                    className="focus-ring flex items-center gap-3 rounded-xl bg-slate-50 px-3 py-3 text-sm font-semibold text-slate-700 hover:bg-primary-50"
                  >
                    <span className="size-2 rounded-sm bg-violet-500" />
                    <span className="min-w-0 flex-1 truncate">{wiki.title}</span>
                    <ChevronRight className="size-4 text-slate-400" />
                  </Link>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-slate-400">이 문서로 반영된 위키가 없습니다.</p>
          )}
        </div>
      </aside>

      <DocumentMetaEditModal
        open={metaOpen}
        doc={doc}
        onClose={() => setMetaOpen(false)}
        onSaved={() => setMetaOpen(false)}
      />
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
