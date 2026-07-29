import { useState } from 'react'
import { format } from 'date-fns'
import { ko } from 'date-fns/locale'
import { FileText, MapPin, Clock, Users } from 'lucide-react'
import { Modal, Button, Badge, ConfirmDialog } from '@/components/ui'
import { useToast } from '@/components/ui'
import {
  SCHEDULE_VISIBILITY,
  SCHEDULE_VISIBILITY_LABELS,
} from '@/shared/constants/enums'
import { useApproveSchedule, useDeleteSchedule } from '@/features/schedule/useSchedules'

const VIS_TONE = {
  [SCHEDULE_VISIBILITY.ALL]: 'primary',
  [SCHEDULE_VISIBILITY.DEPARTMENT]: 'info',
  [SCHEDULE_VISIBILITY.PERSONAL]: 'success',
}

function InfoRow({ icon: Icon, children }) {
  return (
    <div className="flex items-start gap-2 text-sm text-slate-600">
      <Icon className="mt-0.5 size-4 shrink-0 text-slate-400" />
      <span>{children}</span>
    </div>
  )
}

// 5-1R 일정 초안 상세. 관리자가 AI 추출 초안을 검토하고 승인/거부한다.
// 거부는 하드 삭제(FR-SCH-003)라 확인 모달을 거친다.
export default function ScheduleDraftDetailModal({ open, onClose, draft, departments = [] }) {
  const toast = useToast()
  const approveMutation = useApproveSchedule()
  const deleteMutation = useDeleteSchedule()
  const [confirmReject, setConfirmReject] = useState(false)

  if (!draft) return null

  const deptNames = (draft.departmentIds ?? [])
    .map((id) => departments.find((d) => d.departmentId === id)?.name ?? `부서 ${id}`)
    .join(', ')

  const handleApprove = async () => {
    try {
      await approveMutation.mutateAsync(draft.id)
      toast.success('일정을 승인했어요', '이제 대상 사원의 달력에 표시됩니다.')
      onClose?.()
    } catch (err) {
      toast.error('승인하지 못했어요', err.message)
    }
  }

  const handleReject = async () => {
    try {
      await deleteMutation.mutateAsync(draft.id)
      toast.success('초안을 거부했어요')
      setConfirmReject(false)
      onClose?.()
    } catch (err) {
      toast.error('거부하지 못했어요', err.message)
      setConfirmReject(false)
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="일정 초안 검수"
      size="md"
      footer={
        <div className="flex w-full items-center justify-between">
          <Button variant="ghost" onClick={() => setConfirmReject(true)}>
            거부
          </Button>
          <div className="flex gap-2">
            <Button variant="outline" onClick={onClose}>
              닫기
            </Button>
            <Button onClick={handleApprove} loading={approveMutation.isPending}>
              승인
            </Button>
          </div>
        </div>
      }
    >
      <div className="space-y-4">
        <div className="flex items-center gap-2">
          <h3 className="text-base font-semibold text-slate-800">{draft.title}</h3>
          <Badge tone={VIS_TONE[draft.visibilityType]}>
            {SCHEDULE_VISIBILITY_LABELS[draft.visibilityType]}
          </Badge>
          <Badge tone="warning">승인 대기</Badge>
        </div>

        <div className="space-y-2 rounded-lg bg-slate-50 p-3">
          <InfoRow icon={Clock}>
            {format(draft.start, 'M월 d일 (EEE) HH:mm', { locale: ko })} –{' '}
            {format(draft.end, 'M월 d일 (EEE) HH:mm', { locale: ko })}
          </InfoRow>
          {draft.visibilityType === SCHEDULE_VISIBILITY.DEPARTMENT && (
            <InfoRow icon={Users}>{deptNames || '지정된 부서 없음'}</InfoRow>
          )}
          {draft.location && <InfoRow icon={MapPin}>{draft.location}</InfoRow>}
        </div>

        {draft.content && (
          <div>
            <p className="mb-1 text-xs font-medium text-slate-500">내용</p>
            <p className="whitespace-pre-wrap text-sm text-slate-700">{draft.content}</p>
          </div>
        )}

        {draft.sourceGroupKey && (
          <div className="flex items-center gap-2 rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-500">
            <FileText className="size-4 text-slate-400" />
            <span>AI가 일정 문서에서 추출한 초안입니다.</span>
          </div>
        )}
      </div>

      <ConfirmDialog
        open={confirmReject}
        onClose={() => setConfirmReject(false)}
        onConfirm={handleReject}
        title="이 초안을 거부할까요?"
        description="거부한 초안은 즉시 삭제되며 되돌릴 수 없습니다."
        confirmLabel="거부"
        tone="danger"
        loading={deleteMutation.isPending}
      />
    </Modal>
  )
}
