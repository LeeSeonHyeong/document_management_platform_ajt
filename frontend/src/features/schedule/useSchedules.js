import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchSchedules,
  createSchedule,
  updateSchedule,
  deleteSchedule,
} from '@/api/schedules'
import { qk } from '@/shared/api/queryKeys'

// API 일정 → 캘린더 이벤트로 변환한다(문자열 시각을 Date로).
export function toCalendarEvent(schedule) {
  return {
    id: schedule.scheduleId,
    title: schedule.title,
    start: new Date(schedule.startAt),
    end: new Date(schedule.endAt),
    visibilityType: schedule.visibilityType,
    location: schedule.location,
    content: schedule.content,
    status: schedule.status,
    raw: schedule,
  }
}

// 기간(달 그리드 범위)으로 일정을 조회하고 캘린더 이벤트로 매핑한다.
export function useSchedules({ startDate, endDate }) {
  return useQuery({
    queryKey: qk.schedules.list({ startDate, endDate }),
    queryFn: () => fetchSchedules({ startDate, endDate }),
    select: (items) => items.map(toCalendarEvent),
  })
}

export function useCreateSchedule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createSchedule,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.schedules.all }),
  })
}

export function useUpdateSchedule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ scheduleId, ...payload }) => updateSchedule(scheduleId, payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.schedules.all }),
  })
}

export function useDeleteSchedule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteSchedule,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.schedules.all }),
  })
}
