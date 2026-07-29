import { Download, FileText } from 'lucide-react'
import { Button, Modal, Spinner, useToast } from '@/components/ui'
import { fetchDocumentFile } from '@/features/document/api'
import { useDocument } from '@/features/document/queries'

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
        <div className="rounded-xl bg-slate-50 p-5">
          <div className="mx-auto flex min-h-72 max-w-xl flex-col rounded-md border border-slate-200 bg-white px-8 py-7 shadow-sm">
            <h3 className="text-center text-lg font-bold text-slate-800">{fileName ?? '원본 문서'}</h3>
            <div className="mt-8 space-y-4 text-sm leading-7 text-slate-500">
              <p className="font-semibold text-slate-700">원본 문서 미리보기</p>
              <p>
                문서 미리보기 데이터가 연결되면 이 영역에 원본 문서의 내용이 표시됩니다.
              </p>
            </div>
          </div>
          <div className="mt-4 flex justify-center">
            <span className="rounded-lg border border-slate-200 bg-white px-3 py-1 text-xs text-slate-500">
              1 / 1
            </span>
          </div>
        </div>
      )}
    </Modal>
  )
}
