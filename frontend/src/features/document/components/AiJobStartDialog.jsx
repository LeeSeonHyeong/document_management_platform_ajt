import { ConfirmDialog } from '@/components/ui'

// Figma 4-4R — AI 작업 시작 확인.
// 작업은 업로드 시점에 이미 생성(waiting)되어 있으므로, 확인 시 별도 생성 없이 상위 콜백만 호출한다.
export default function AiJobStartDialog({ open, onClose, onConfirm, documentCount = 0 }) {
  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      onConfirm={onConfirm}
      title="AI 작업을 시작할까요?"
      confirmLabel="시작"
    >
      <div className="space-y-2 text-sm text-slate-600">
        <p>
          대상 문서 <strong className="text-slate-800">{documentCount}건</strong>
        </p>
        <p>문서는 업로드 순서대로 1건씩 처리되며, 문서 1건당 최대 10분이 소요될 수 있습니다.</p>
      </div>
    </ConfirmDialog>
  )
}
