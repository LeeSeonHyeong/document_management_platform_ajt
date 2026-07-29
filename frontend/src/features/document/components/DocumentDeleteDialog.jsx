import { useEffect, useState } from 'react'
import { ConfirmDialog, ProcessDialog, Modal, Button } from '@/components/ui'
import { useDeleteDocument } from '../queries'

// Figma 4-7-2R ~ 4-7-4R — 삭제 확인/처리 중/완료. 세 화면은 별도 라우트가 아니라
// 다이얼로그 하나의 3가지 상태(confirm → processing → done)다.
export default function DocumentDeleteDialog({ open, onClose, documentId, onDeleted }) {
  const [phase, setPhase] = useState('confirm')
  const deleteMutation = useDeleteDocument()

  // 다이얼로그가 다시 열릴 때 항상 확인 단계부터 시작한다.
  useEffect(() => {
    if (open) setPhase('confirm')
  }, [open])

  function handleDelete() {
    setPhase('processing')
    deleteMutation.mutate(documentId, {
      onSuccess: () => setPhase('done'),
      onError: () => setPhase('confirm'),
    })
  }

  if (!open) return null

  if (phase === 'processing') {
    return (
      <ProcessDialog
        open
        title="문서를 삭제하는 중"
        description="문서와 파일을 제거하고 연관 Wiki를 재처리하고 있습니다."
        steps={[
          { label: '문서와 파일 삭제', status: 'active' },
          { label: '연관 Wiki 재처리', status: 'pending' },
        ]}
      />
    )
  }

  if (phase === 'done') {
    return (
      <Modal
        open
        title="삭제 완료"
        size="sm"
        footer={
          <Button variant="primary" onClick={onDeleted}>
            확인
          </Button>
        }
      >
        <p className="text-sm text-slate-600">문서가 삭제되었습니다.</p>
      </Modal>
    )
  }

  // confirm — FR-DOC-015 안내 문구
  return (
    <ConfirmDialog
      open
      onClose={onClose}
      onConfirm={handleDelete}
      title="이 문서를 삭제할까요?"
      confirmLabel="삭제"
      tone="danger"
    >
      <div className="space-y-2 text-sm text-slate-600">
        <p>문서와 파일이 제거되고, 해당 공개 범위(scopeKey)의 Wiki가 재처리됩니다.</p>
        <p>재처리 반영에 실패하면 기존 문서와 Wiki가 그대로 유지됩니다.</p>
        <p className="font-medium text-rose-600">삭제한 문서는 복구할 수 없습니다.</p>
      </div>
    </ConfirmDialog>
  )
}
