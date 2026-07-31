import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { format } from 'date-fns'
import { Trash2 } from 'lucide-react'
import { Modal, Button, Input, Textarea, ConfirmDialog } from '@/components/ui'
import { useToast } from '@/components/ui'
import { scheduleFormSchema } from '@/shared/validation/schedule'
import { applyFieldErrors } from '@/shared/lib/fieldErrors'
import { SCHEDULE_VISIBILITY } from '@/shared/constants/enums'
import {
  useCreateSchedule,
  useUpdateSchedule,
  useDeleteSchedule,
} from '@/features/schedule/useSchedules'

// datetime-local 입력 형식.
function toLocalInput(date) {
  return format(date, "yyyy-MM-dd'T'HH:mm")
}

function defaultValuesFor(initial, defaultDate) {
  if (initial) {
    return {
      title: initial.title ?? '',
      startAt: toLocalInput(new Date(initial.start)),
      endAt: toLocalInput(new Date(initial.end)),
      location: initial.location ?? '',
      content: initial.content ?? '',
    }
  }
  const base = defaultDate ? new Date(defaultDate) : new Date()
  base.setHours(9, 0, 0, 0)
  const end = new Date(base)
  end.setHours(10, 0, 0, 0)
  return {
    title: '',
    startAt: toLocalInput(base),
    endAt: toLocalInput(end),
    location: '',
    content: '',
  }
}

// 개인 일정 추가/수정 모달(사원 통합 달력). visibilityType은 개인으로 고정한다.
export default function ScheduleFormModal({ open, onClose, initial, defaultDate }) {
  const isEdit = Boolean(initial)
  const toast = useToast()
  const createMutation = useCreateSchedule()
  const updateMutation = useUpdateSchedule()
  const deleteMutation = useDeleteSchedule()
  const [confirmDelete, setConfirmDelete] = useState(false)

  const handleDelete = async () => {
    try {
      await deleteMutation.mutateAsync(initial.id)
      toast.success('일정을 삭제했어요')
      setConfirmDelete(false)
      onClose?.()
    } catch (err) {
      toast.error('삭제하지 못했어요', err.message)
      setConfirmDelete(false)
    }
  }

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(scheduleFormSchema) })

  // 열릴 때마다 초기값을 다시 세팅(선택 날짜/수정 대상 반영).
  useEffect(() => {
    if (open) reset(defaultValuesFor(initial, defaultDate))
  }, [open, initial, defaultDate, reset])

  const onSubmit = async (values) => {
    // 개인 일정 생성 계약(docs/api): targetText·departmentIds 포함, visibilityType=personal.
    const payload = {
      title: values.title,
      content: values.content || null,
      targetText: '본인',
      location: values.location || null,
      visibilityType: SCHEDULE_VISIBILITY.PERSONAL,
      departmentIds: [],
      startAt: new Date(values.startAt).toISOString(),
      endAt: new Date(values.endAt).toISOString(),
    }
    try {
      if (isEdit) {
        // S15P11B106-87: 마지막으로 본 일정의 updatedAt을 expectedUpdatedAt으로 보내 동시 수정 충돌을 막는다.
        await updateMutation.mutateAsync({ scheduleId: initial.id, expectedUpdatedAt: initial.updatedAt, ...payload })
        toast.success('일정을 수정했어요')
      } else {
        await createMutation.mutateAsync(payload)
        toast.success('일정이 추가됐어요')
      }
      onClose?.()
    } catch (err) {
      if (err?.status === 409) {
        toast.error('다른 사용자가 먼저 수정한 일정입니다.', '새로고침 후 다시 시도해주세요.')
        onClose?.()
        return
      }
      applyFieldErrors(err, setError, { fallbackField: 'root' })
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isEdit ? '일정 수정' : '새 일정 추가'}
      size="md"
      footer={
        <div className="flex w-full items-center justify-between">
          {isEdit ? (
            <Button variant="ghost" onClick={() => setConfirmDelete(true)}>
              <Trash2 className="size-4" />
              삭제
            </Button>
          ) : (
            <span />
          )}
          <div className="flex gap-2">
            <Button variant="outline" onClick={onClose}>
              취소
            </Button>
            <Button type="submit" form="schedule-form" loading={isSubmitting}>
              {isEdit ? '수정' : '추가'}
            </Button>
          </div>
        </div>
      }
    >
      <form id="schedule-form" onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
        <Input label="제목" required placeholder="일정 제목" error={errors.title?.message} {...register('title')} />
        <div className="grid grid-cols-2 gap-3">
          <Input
            label="시작"
            required
            type="datetime-local"
            error={errors.startAt?.message}
            {...register('startAt')}
          />
          <Input
            label="종료"
            required
            type="datetime-local"
            error={errors.endAt?.message}
            {...register('endAt')}
          />
        </div>
        <Input label="장소" placeholder="장소(선택)" error={errors.location?.message} {...register('location')} />
        <Textarea label="상세" placeholder="상세 내용(선택)" rows={3} {...register('content')} />
        {errors.root && (
          <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-600">{errors.root.message}</p>
        )}
      </form>

      <ConfirmDialog
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        onConfirm={handleDelete}
        title="일정을 삭제할까요?"
        description="삭제한 일정은 되돌릴 수 없습니다."
        confirmLabel="삭제"
        tone="danger"
        loading={deleteMutation.isPending}
      />
    </Modal>
  )
}
