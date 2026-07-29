import { useNavigate } from 'react-router-dom'
import { Download, ExternalLink } from 'lucide-react'
import { Modal, Button, Spinner, useToast } from '@/components/ui'
import { useAuth } from '@/hooks/useAuth'
import { ROLES } from '@/shared/constants/enums'
import { useDocument } from '@/features/document/queries'
import { fetchDocumentFile } from '@/features/document/api'

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
    <div className="flex gap-4 py-1.5">
      <dt className="w-20 shrink-0 text-sm text-slate-500">{label}</dt>
      <dd className="text-sm text-slate-800">{children}</dd>
    </div>
  )
}

// Figma 6-2R(관리자) / S2-1(사원) — Wiki 상세의 원본문서 미리보기.
// 같은 컴포넌트를 role로 분기한다. 사원에겐 다운로드·파일 경로/URL과 상세 이동을 노출하지 않는다(FR-DOC-016).
export default function WikiSourcePreviewModal({ open, onClose, evidenceDocument }) {
  const { role } = useAuth()
  const isAdmin = role === ROLES.ADMIN
  const toast = useToast()
  const navigate = useNavigate()

  const documentId = evidenceDocument?.documentId
  // 크기·업로드 시각·형식 등 추가 메타데이터는 문서 상세에서 가져온다.
  const { data: doc, isLoading } = useDocument(open ? documentId : undefined)

  const fileName = evidenceDocument?.originalFileName ?? doc?.originalFileName

  async function handleDownload() {
    try {
      const { blob, fileName: fn } = await fetchDocumentFile(documentId)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = fn ?? fileName ?? 'document'
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      if (e?.response?.status === 403) toast.error('다운로드 권한이 없습니다.')
      else toast.error('다운로드에 실패했습니다.')
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="원본 문서"
      size="md"
      footer={
        isAdmin ? (
          <>
            <Button
              variant="outline"
              onClick={() => {
                onClose?.()
                navigate(`/admin/documents/source/${documentId}`)
              }}
            >
              <ExternalLink className="size-4" />
              원본 문서 상세로 이동
            </Button>
            <Button variant="primary" onClick={handleDownload}>
              <Download className="size-4" />
              다운로드
            </Button>
          </>
        ) : (
          <Button variant="outline" onClick={onClose}>
            닫기
          </Button>
        )
      }
    >
      {isLoading ? (
        <div className="flex justify-center py-8">
          <Spinner size="sm" />
        </div>
      ) : (
        <dl className="divide-y divide-slate-100">
          <Row label="파일명">{fileName ?? '-'}</Row>
          <Row label="형식">{fileExtension(fileName)}{doc?.mimeType ? ` · ${doc.mimeType}` : ''}</Row>
          <Row label="크기">{formatBytes(doc?.fileSize)}</Row>
          <Row label="업로드 시각">{formatDateTime(doc?.uploadedAt)}</Row>
        </dl>
      )}
    </Modal>
  )
}
