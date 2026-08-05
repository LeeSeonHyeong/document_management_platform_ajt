import { format } from 'date-fns'
import { MapPin, Pencil } from 'lucide-react'
import { Modal, Button, Badge } from '@/components/ui'
import {
  SCHEDULE_VISIBILITY,
  SCHEDULE_VISIBILITY_LABELS,
} from '@/shared/constants/enums'

const VIS_TONE = {
  [SCHEDULE_VISIBILITY.ALL]: 'primary',
  [SCHEDULE_VISIBILITY.DEPARTMENT]: 'info',
  [SCHEDULE_VISIBILITY.PERSONAL]: 'success',
}

function timeRange(event) {
  const sameDay = format(event.start, 'yyyy-MM-dd') === format(event.end, 'yyyy-MM-dd')
  return sameDay
    ? `${format(event.start, 'M.d HH:mm')} – ${format(event.end, 'HH:mm')}`
    : `${format(event.start, 'M.d HH:mm')} – ${format(event.end, 'M.d HH:mm')}`
}

// 일정 정보 읽기 전용 모달. 클릭한 일정의 내용을 먼저 확인만 한다.
// 개인 일정만 '수정하기'로 편집(ScheduleFormModal)에 진입할 수 있다.
export default function ScheduleDetailModal({ open, onClose, event, onEdit }) {
  if (!event) return null
  const editable = event.visibilityType === SCHEDULE_VISIBILITY.PERSONAL

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="일정 정보"
      size="md"
      footer={
        <div className="flex w-full justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            닫기
          </Button>
          {editable && (
            <Button onClick={() => onEdit?.(event)}>
              <Pencil className="size-4" />
              수정하기
            </Button>
          )}
        </div>
      }
    >
      <div className="space-y-4">
        <div className="flex items-center gap-2">
          <h3 className="text-lg font-semibold text-slate-800">{event.title}</h3>
          <Badge tone={VIS_TONE[event.visibilityType]}>
            {SCHEDULE_VISIBILITY_LABELS[event.visibilityType]}
          </Badge>
        </div>

        <dl className="space-y-2 text-sm">
          <div className="flex gap-2">
            <dt className="w-12 shrink-0 text-slate-400">시간</dt>
            <dd className="font-medium text-slate-700">{timeRange(event)}</dd>
          </div>
          {event.location && (
            <div className="flex gap-2">
              <dt className="w-12 shrink-0 text-slate-400">장소</dt>
              <dd className="flex items-center gap-1 font-medium text-slate-700">
                <MapPin className="size-3.5 text-slate-400" />
                {event.location}
              </dd>
            </div>
          )}
        </dl>

        {event.content && (
          <p className="whitespace-pre-line rounded-lg bg-slate-50 px-3 py-2.5 text-sm leading-6 text-slate-600">
            {event.content}
          </p>
        )}
      </div>
    </Modal>
  )
}
