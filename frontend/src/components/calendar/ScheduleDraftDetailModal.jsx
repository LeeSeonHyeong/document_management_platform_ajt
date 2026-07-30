import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { differenceInCalendarDays, format } from 'date-fns'
import { FileText, Info } from 'lucide-react'
import { Modal, Button, Badge, Input, Textarea, ConfirmDialog } from '@/components/ui'
import { useToast } from '@/components/ui'
import { adminScheduleFormSchema } from '@/shared/validation/scheduleAdmin'
import { applyFieldErrors } from '@/shared/lib/fieldErrors'
import { cn } from '@/shared/lib/cn'
import { SCHEDULE_VISIBILITY } from '@/shared/constants/enums'
import {
  useApproveSchedule,
  useDeleteSchedule,
  useUpdateSchedule,
} from '@/features/schedule/useSchedules'

function toLocalInput(date) {
  return format(new Date(date), "yyyy-MM-dd'T'HH:mm")
}

function draftValues(draft) {
  return {
    title: draft?.title ?? '',
    visibilityType: draft?.visibilityType ?? SCHEDULE_VISIBILITY.ALL,
    departmentIds: draft?.departmentIds ?? [],
    startAt: draft ? toLocalInput(draft.start) : '',
    endAt: draft ? toLocalInput(draft.end) : '',
    location: draft?.location ?? '',
    content: draft?.content ?? '',
  }
}

// 공개 대상 범위 체크박스(전체 공개 + 부서 다중 선택).
function ScopeCheckbox({ checked, label, onChange }) {
  return (
    <label
      className={cn(
        'flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium',
        checked
          ? 'border-primary-400 bg-primary-50 text-primary-700'
          : 'border-slate-200 text-slate-600 hover:bg-slate-50',
      )}
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={onChange}
        className="focus-ring size-4 rounded border-slate-300 accent-primary-600"
      />
      {label}
    </label>
  )
}

// 5-1R 일정 상세(초안 검수). 관리자가 AI 추출 초안을 수정·승인하거나 거부한다.
// 수정 내용이 있으면 승인 전에 PATCH로 먼저 반영한다.
// 거부는 하드 삭제(FR-SCH-003)라 확인 모달을 거친다.
export default function ScheduleDraftDetailModal({ open, onClose, draft, departments = [] }) {
  const toast = useToast()
  const approveMutation = useApproveSchedule()
  const updateMutation = useUpdateSchedule()
  const deleteMutation = useDeleteSchedule()
  const [confirmReject, setConfirmReject] = useState(false)

  const {
    register,
    handleSubmit,
    reset,
    watch,
    setValue,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm({ resolver: zodResolver(adminScheduleFormSchema) })

  useEffect(() => {
    if (open && draft) reset(draftValues(draft))
  }, [open, draft, reset])

  const visibilityType = watch('visibilityType')
  const departmentIds = watch('departmentIds') ?? []
  const startAtValue = watch('startAt')
  const endAtValue = watch('endAt')

  if (!draft) return null

  const isAll = visibilityType === SCHEDULE_VISIBILITY.ALL

  const toggleAll = () => {
    setValue('visibilityType', isAll ? SCHEDULE_VISIBILITY.DEPARTMENT : SCHEDULE_VISIBILITY.ALL, {
      shouldValidate: true,
      shouldDirty: true,
    })
    if (!isAll) setValue('departmentIds', [], { shouldValidate: true, shouldDirty: true })
  }

  const toggleDept = (id) => {
    const next = departmentIds.includes(id)
      ? departmentIds.filter((d) => d !== id)
      : [...departmentIds, id]
    setValue('visibilityType', SCHEDULE_VISIBILITY.DEPARTMENT, {
      shouldValidate: true,
      shouldDirty: true,
    })
    setValue('departmentIds', next, { shouldValidate: true, shouldDirty: true })
  }

  // 다일(멀티데이) 여부. 종료일이 시작일과 다르면 며칠에 걸치는지 안내한다.
  const startDate = startAtValue ? new Date(startAtValue) : null
  const endDate = endAtValue ? new Date(endAtValue) : null
  const spanDays =
    startDate && endDate && !Number.isNaN(startDate.getTime()) && !Number.isNaN(endDate.getTime())
      ? differenceInCalendarDays(endDate, startDate) + 1
      : 1

  // 출처 문서는 관리자 상세 응답(sourceDocument)에만 실린다.
  const sourceDocument = draft.raw?.sourceDocument ?? null

  const onApprove = async (values) => {
    const payload = {
      title: values.title,
      content: values.content || null,
      targetText: draft.targetText ?? null,
      location: values.location || null,
      visibilityType: values.visibilityType,
      departmentIds:
        values.visibilityType === SCHEDULE_VISIBILITY.ALL ? [] : values.departmentIds,
      startAt: new Date(values.startAt).toISOString(),
      endAt: new Date(values.endAt).toISOString(),
    }
    try {
      // 검수 중 수정한 내용이 있으면 승인 전에 먼저 저장한다.
      if (isDirty) await updateMutation.mutateAsync({ scheduleId: draft.id, ...payload })
      await approveMutation.mutateAsync(draft.id)
      toast.success('일정을 승인했어요', '이제 대상 사원의 달력에 표시됩니다.')
      onClose?.()
    } catch (err) {
      applyFieldErrors(err, setError, { fallbackField: 'root' })
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
      title="일정 상세"
      size="xl"
      footer={
        <div className="flex w-full flex-wrap items-center justify-between gap-3">
          <p className="text-xs text-slate-400">승인하면 선택한 부서의 캘린더에 즉시 반영됩니다</p>
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => setConfirmReject(true)}>
              거부
            </Button>
            <Button variant="outline" onClick={onClose}>
              취소
            </Button>
            <Button
              type="submit"
              form="schedule-draft-detail-form"
              loading={isSubmitting || updateMutation.isPending || approveMutation.isPending}
            >
              승인
            </Button>
          </div>
        </div>
      }
    >
      <form
        id="schedule-draft-detail-form"
        onSubmit={handleSubmit(onApprove)}
        className="max-h-[65vh] space-y-4 overflow-y-auto pr-1"
        noValidate
      >
        <div className="flex items-center justify-between gap-2 border-b border-slate-100 pb-3">
          <h3 className="text-base font-semibold text-slate-800">일정 정보</h3>
          <Badge tone="warning">승인 대기</Badge>
        </div>

        <Input label="제목" required error={errors.title?.message} {...register('title')} />

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Input
            label="시작 일시"
            required
            type="datetime-local"
            error={errors.startAt?.message}
            {...register('startAt')}
          />
          <Input
            label="종료 일시"
            required
            type="datetime-local"
            error={errors.endAt?.message}
            {...register('endAt')}
          />
        </div>

        {spanDays > 1 && (
          <p className="flex items-start gap-2 rounded-lg bg-primary-50 px-3 py-2 text-xs text-primary-700">
            <Info className="mt-0.5 size-3.5 shrink-0" />
            종료일이 시작일과 다른 다일 일정입니다. 캘린더에 {spanDays}일에 걸쳐 표시합니다
          </p>
        )}

        {/* TODO(API): 일정 응답에 작성자(이름·부서) 필드가 없어 와이어프레임의 "작성자" 항목은 생략한다.
            추가되면 장소 옆 2열로 함께 표시한다. */}
        <Input label="장소" placeholder="장소(선택)" error={errors.location?.message} {...register('location')} />

        <Textarea label="상세 내용" placeholder="상세 내용(선택)" rows={4} {...register('content')} />

        {/* 출처 문서.
            TODO(API): GET /api/v1/schedules 목록 응답에는 sourceDocument(originalFileName·sourceFileUrl)가 없다.
            상세 조회(GET /api/v1/schedules/:scheduleId) 연동 전까지 파일명·미리보기 버튼은 노출할 수 없다. */}
        {draft.sourceGroupKey && (
          <div className="space-y-1.5">
            <span className="text-sm font-medium text-slate-700">출처 문서</span>
            <div className="flex items-center gap-2 rounded-lg border border-slate-200 px-3 py-2.5 text-sm">
              <FileText className="size-4 shrink-0 text-primary-500" />
              {sourceDocument?.originalFileName ? (
                <span className="truncate text-slate-700">{sourceDocument.originalFileName}</span>
              ) : (
                <span className="text-slate-500">AI가 일정 문서에서 추출한 초안입니다</span>
              )}
            </div>
          </div>
        )}

        <div className="space-y-1.5 border-t border-slate-100 pt-4">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-slate-800">공개 대상 범위</span>
            <span className="text-xs text-slate-400">· 여러 부서 선택 가능</span>
          </div>
          <p className="text-xs text-slate-400">
            {isAll ? '전사 모든 사원에게 일정이 공개됩니다.' : '선택한 부서에게만 일정이 공개됩니다.'}
          </p>
          <div className="flex flex-wrap gap-2 pt-1">
            <ScopeCheckbox checked={isAll} label="전체 공개" onChange={toggleAll} />
            {departments.map((d) => (
              <ScopeCheckbox
                key={d.departmentId}
                checked={!isAll && departmentIds.includes(d.departmentId)}
                label={d.name}
                onChange={() => toggleDept(d.departmentId)}
              />
            ))}
          </div>
          {errors.departmentIds && (
            <p className="text-xs text-rose-600">{errors.departmentIds.message}</p>
          )}
        </div>

        {errors.root && (
          <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-600">{errors.root.message}</p>
        )}
      </form>

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
