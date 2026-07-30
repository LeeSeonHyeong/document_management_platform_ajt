import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, ChevronDown, ChevronRight, Download, FileText, Maximize2, Trash2 } from 'lucide-react'
import { Badge, Button, Spinner, useToast } from '@/components/ui'
import { fetchDocumentFile } from '../api'
import { useDocument } from '../queries'
import DocumentDeleteDialog from '../components/DocumentDeleteDialog'
import DocumentMetaEditModal from '../components/DocumentMetaEditModal'

function formatBytes(bytes) {
  if (!bytes && bytes !== 0) return '-'
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function formatDateTime(iso) {
  if (!iso) return '-'
  const date = new Date(iso)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${year}-${month}-${day} ${hour}:${minute}`
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
  const [downloading, setDownloading] = useState(false)
  const [metaOpen, setMetaOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)

  const { data: doc, isLoading } = useDocument(documentId)

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

  if (isLoading || !doc) {
    return (
      <div className="flex justify-center py-16">
        <Spinner />
      </div>
    )
  }

  return (
    <section>
      <Link
        to="/admin/documents/source"
        className="focus-ring mb-4 inline-flex cursor-pointer items-center gap-1.5 rounded-md text-sm font-semibold text-slate-500 hover:text-primary-600"
      >
        <ArrowLeft className="size-3.5" />
        원본 문서
      </Link>

      <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
      <div className="flex min-h-[760px] min-w-0 flex-col rounded-2xl border border-slate-200 bg-white p-5">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-4">
          <div className="flex min-w-0 items-center gap-3">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary-50 text-primary-500">
              <FileText className="size-5" />
            </span>
            <div className="min-w-0">
              <h1 className="truncate text-lg font-bold text-slate-800">{doc.originalFileName}</h1>
              {/* TODO(API): 문서 페이지 수 필드가 계약에 없어 1페이지로 고정 표시된다. */}
              <p className="mt-0.5 text-xs text-slate-400">
                {fileExtension(doc.originalFileName)} · {formatBytes(doc.fileSize)} · 1페이지
              </p>
            </div>
          </div>
          <Button variant="outline">
            <Maximize2 className="size-4" />
            전체 화면
          </Button>
        </div>

        {doc.status === 'failed' && doc.failureReason && (
          <p className="mt-4 rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">
            처리 실패: {doc.failureReason}
          </p>
        )}

        <div className="mt-4 flex flex-1 flex-col items-center rounded-2xl bg-slate-50 p-8">
          <div className="min-h-80 w-full max-w-2xl rounded-md border border-slate-200 bg-white p-10 shadow-sm">
            <p className="text-center text-lg font-bold text-slate-800">원본 문서 미리보기</p>
            <p className="mt-8 text-sm font-semibold text-slate-700">{doc.originalFileName}</p>
            <p className="mt-5 text-sm leading-8 text-slate-500">
              파일 미리보기 데이터가 연결되면 이 영역에 실제 문서 내용이 표시됩니다.
            </p>
          </div>
          {/* TODO(API): 미리보기 본문·페이지 수 필드가 계약에 없어 플레이스홀더와 1 / 1로 둔다. */}
          <span className="mt-3 rounded-lg border border-slate-200 bg-white px-3 py-1 text-xs text-slate-500">1 / 1</span>
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
      </div>

      <aside className="space-y-4">
        <div className="rounded-2xl border border-slate-200 bg-white p-5">
          <div className="border-b border-slate-200 pb-4">
            <h2 className="font-bold text-slate-800">문서 정보</h2>
          </div>
          <dl>
            <InfoRow label="카테고리">
              <MetadataButton onClick={() => setMetaOpen(true)}>
                {doc.documentCategoryName ?? '미분류'}
              </MetadataButton>
            </InfoRow>
            <InfoRow label="공개 부서">
              <MetadataButton onClick={() => setMetaOpen(true)}>
                {doc.visibilityType === 'all'
                  ? '전체 공개'
                  : (doc.departments ?? []).length > 1
                    ? `${doc.departments[0].name} 외 ${doc.departments.length - 1}`
                    : doc.departments?.[0]?.name ?? '미지정'}
              </MetadataButton>
            </InfoRow>
            <div className="my-2 border-t border-slate-200" />
            <InfoRow label="업로더">
              <span className="flex items-center justify-end gap-2">
                <span className="flex size-6 items-center justify-center rounded-full bg-primary-600 text-[10px] font-bold text-white">
                  {doc.uploadedBy?.name?.slice(0, 1) ?? '?'}
                </span>
                {doc.uploadedBy?.name ?? '-'}
              </span>
            </InfoRow>
            <InfoRow label="업로드일">{formatDateTime(doc.uploadedAt)}</InfoRow>
            <InfoRow label="용량 · 형식">
              {formatBytes(doc.fileSize)} · {fileExtension(doc.originalFileName)}
            </InfoRow>
          </dl>
        </div>

        <div className="rounded-2xl border border-slate-200 bg-white p-5">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="font-bold text-slate-800">연결된 위키 문서</h2>
            {doc.relatedWikis?.length > 0 && <Badge tone="success">반영 완료</Badge>}
          </div>
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
      </div>

      <DocumentMetaEditModal
        open={metaOpen}
        doc={doc}
        onClose={() => setMetaOpen(false)}
        onSaved={() => setMetaOpen(false)}
      />
      <DocumentDeleteDialog
        open={deleteOpen}
        document={doc}
        onClose={() => setDeleteOpen(false)}
        onBackground={() => {
          setDeleteOpen(false)
          navigate('/admin/documents/source')
        }}
        onViewWiki={(wikiId) => {
          setDeleteOpen(false)
          navigate(`/wiki/${wikiId}`)
        }}
        onGoToList={() => {
          setDeleteOpen(false)
          navigate('/admin/documents/source')
        }}
      />
    </section>
  )
}

function MetadataButton({ children, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="focus-ring flex w-full items-center justify-between rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-left text-sm font-semibold text-slate-700 hover:border-primary-300"
    >
      <span className="truncate">{children}</span>
      <ChevronDown className="size-4 shrink-0 text-slate-400" />
    </button>
  )
}
