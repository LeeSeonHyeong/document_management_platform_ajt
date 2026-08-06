import { Link } from 'react-router-dom'
import { Download, FileText } from 'lucide-react'
import { Button, Modal, Spinner, useToast } from '@/components/ui'
import { fetchDocumentFile } from '@/features/document/api'
import { DocumentPreview } from '@/features/document/preview'
import { useDocument } from '@/features/document/queries'
import { useAuth } from '@/hooks/useAuth'
import { ROLES } from '@/shared/constants/enums'

function formatBytes(bytes) {
  if (!bytes && bytes !== 0) return '-'
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function formatDate(iso) {
  if (!iso) return '-'
  const date = new Date(iso)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function fileExtension(name) {
  return name?.includes('.') ? name.split('.').pop().toUpperCase() : '-'
}

export default function WikiSourcePreviewModal({ open, onClose, evidenceDocument }) {
  const toast = useToast()
  const { role } = useAuth()
  // 문서 상세는 ADMIN 전용 라우트(routes/index.jsx)라 관리자에게만 링크를 노출한다.
  const isAdmin = role === ROLES.ADMIN
  const documentId = evidenceDocument?.documentId
  const { data: doc, isLoading } = useDocument(open ? documentId : undefined)
  const fileName = evidenceDocument?.originalFileName ?? doc?.originalFileName

  async function handleDownload() {
    try {
      const { blob, fileName: downloadedName } = await fetchDocumentFile(documentId)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = downloadedName ?? fileName ?? 'document'
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      if (error?.response?.status === 403) toast.error('다운로드 권한이 없습니다.')
      else toast.error('다운로드에 실패했습니다.')
    }
  }

  const title = (
    <span className="flex min-w-0 items-center gap-3">
      <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary-50 text-primary-500">
        <FileText className="size-5" />
      </span>
      <span className="min-w-0 text-left">
        <span className="block truncate text-sm font-bold text-slate-800">{fileName ?? '원본 문서'}</span>
        <span className="mt-0.5 block text-xs font-normal text-slate-400">
          원본 문서 · {fileExtension(fileName)} · {formatBytes(doc?.fileSize)} · {formatDate(doc?.uploadedAt)}
          {isAdmin && documentId && (
            <Link
              to={`/admin/documents/source/${documentId}`}
              onClick={onClose}
              className="focus-ring ml-2 rounded font-semibold text-primary-600 hover:text-primary-700"
            >
              문서 관리에서 상세 보기 →
            </Link>
          )}
        </span>
      </span>
    </span>
  )

  const footer = (
    <div className="flex w-full items-center justify-between gap-3">
      <div className="flex gap-2">
        <Button variant="outline" onClick={handleDownload}>
          <Download className="size-4" />
          다운로드
        </Button>
      </div>
      <Button variant="primary" onClick={onClose}>확인</Button>
    </div>
  )

  return (
    <Modal open={open} onClose={onClose} title={title} size="xl" footer={footer}>
      {isLoading ? (
        <div className="flex h-96 items-center justify-center rounded-xl bg-slate-50">
          <Spinner size="sm" />
        </div>
      ) : (
        <div className="thin-scroll max-h-[65vh] overflow-y-auto rounded-xl bg-slate-50 p-5">
          <DocumentPreview
            // 이 화면 자체가 모달이라 전체보기를 끈다 — 모달 위에 모달이 겹친다.
            expandable={false}
            documentId={documentId}
            fileName={fileName}
            mimeType={doc?.mimeType}
            enabled={open}
          />
        </div>
      )}
    </Modal>
  )
}
