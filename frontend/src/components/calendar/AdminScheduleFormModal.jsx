import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { format } from 'date-fns'
import { Trash2 } from 'lucide-react'
import { Modal, Button, Input, Textarea, Chip, ConfirmDialog } from '@/components/ui'
import { useToast } from '@/components/ui'
import { adminScheduleFormSchema } from '@/shared/validation/scheduleAdmin'
import { applyFieldErrors } from '@/shared/lib/fieldErrors'
import { SCHEDULE_VISIBILITY } from '@/shared/constants/enums'
import { cn } from '@/shared/lib/cn'
import { useDepartments } from '@/features/department/useDepartments'
import {
  useCreateSchedule,
  useUpdateSchedule,
  useDeleteSchedule,
} from '@/features/schedule/useSchedules'

function toLocalInput(date) {
  return format(date, "yyyy-MM-dd'T'HH:mm")
}

function defaultValues(initial, defaultDate) {
  if (initial) {
    return {
      title: initial.title ?? '',
      visibilityType: initial.visibilityType ?? SCHEDULE_VISIBILITY.ALL,
      departmentIds: initial.departmentIds ?? [],
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
    visibilityType: SCHEDULE_VISIBILITY.ALL,
    departmentIds: [],
    startAt: toLocalInput(base),
    endAt: toLocalInput(end),
    location: '',
    content: '',
  }
}

// 관리자 전체·부서 일정 등록/수정/삭제 모달(공개 범위 + 부서 다중 선택).
export default function AdminScheduleFormModal({ open, onClose, initial, defaultDate }) {
  const isEdit = Boolean(initial)
  const toast = useToast()
  const createMutation = useCreateSchedule()
  const updateMutation = useUpdateSchedule()
  const deleteMutation = useDeleteSchedule()
  const { data: departments = [] } = useDepartments()
  const [confirmDelete, setConfirmDelete] = useState(false)

  const {
    register,
    handleSubmit,
    reset,
    watch,
    setValue,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(adminScheduleFormSchema) })

  useEffect(() => {
    if (open) reset(defaultValues(initial, defaultDate))
  }, [open, initial, defaultDate, reset])

  const visibilityType = watch('visibilityType')
  const departmentIds = watch('departmentIds') ?? []
  const isDept = visibilityType === SCHEDULE_VISIBILITY.DEPARTMENT

  const setVisibility = (value) => {
    setValue('visibilityType', value, { shouldValidate: true })
    if (value === SCHEDULE_VISIBILITY.ALL) setValue('departmentIds', [], { shouldValidate: true })
  }

  const toggleDept = (id) => {
    const next = departmentIds.includes(id)
      ? departmentIds.filter((d) => d !== id)
      : [...departmentIds, id]
    setValue('departmentIds', next, { shouldValidate: true })
  }

  const onSubmit = async (values) => {
    const payload = {
      title: values.title,
      content: values.content || null,
      targetText: null,
      location: values.location || null,
      visibilityType: values.visibilityType,
      departmentIds: values.visibilityType === SCHEDULE_VISIBILITY.ALL ? [] : values.departmentIds,
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
        toast.success('일정이 등록됐어요')
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

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isEdit ? '일정 수정' : '일정 등록'}
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
            <Button type="submit" form="admin-schedule-form" loading={isSubmitting}>
              {isEdit ? '수정' : '등록'}
            </Button>
          </div>
        </div>
      }
    >
      <form id="admin-schedule-form" onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
        <Input label="제목" required placeholder="일정 제목" error={errors.title?.message} {...register('title')} />

        <div className="space-y-1.5">
          <span className="text-sm font-medium text-slate-700">
            공개 범위<span className="ml-0.5 text-rose-500">*</span>
          </span>
          <div className="flex gap-2">
            {[
              { value: SCHEDULE_VISIBILITY.ALL, label: '전체 공개' },
              { value: SCHEDULE_VISIBILITY.DEPARTMENT, label: '부서 공개' },
            ].map((opt) => (
              <button
                key={opt.value}
                type="button"
                onClick={() => setVisibility(opt.value)}
                className={cn(
                  'focus-ring flex-1 rounded-lg border px-3 py-2 text-sm font-medium',
                  visibilityType === opt.value
                    ? 'border-primary-300 bg-primary-50 text-primary-700'
                    : 'border-slate-300 text-slate-600 hover:bg-slate-50',
                )}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        {isDept && (
          <div className="space-y-1.5">
            <span className="text-sm font-medium text-slate-700">공개 부서</span>
            <div className="flex flex-wrap gap-2">
              {departments.map((d) => (
                <Chip
                  key={d.departmentId}
                  selected={departmentIds.includes(d.departmentId)}
                  onClick={() => toggleDept(d.departmentId)}
                  className="cursor-pointer"
                >
                  {d.name}
                </Chip>
              ))}
            </div>
            {errors.departmentIds && (
              <p className="text-xs text-rose-600">{errors.departmentIds.message}</p>
            )}
          </div>
        )}

        <div className="grid grid-cols-2 gap-3">
          <Input label="시작" required type="datetime-local" error={errors.startAt?.message} {...register('startAt')} />
          <Input label="종료" required type="datetime-local" error={errors.endAt?.message} {...register('endAt')} />
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
