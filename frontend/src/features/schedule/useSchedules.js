import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchSchedules,
  createSchedule,
  updateSchedule,
  deleteSchedule,
  approveSchedule,
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
    departmentIds: schedule.departmentIds ?? [],
    targetText: schedule.targetText,
    location: schedule.location,
    content: schedule.content,
    status: schedule.status,
    sourceGroupKey: schedule.sourceGroupKey,
    // S15P11B106-87: 낙관적 동시성 토큰. 수정 시 이 값을 expectedUpdatedAt으로 그대로 실어 보낸다.
    updatedAt: schedule.updatedAt,
    raw: schedule,
  }
}

// 기간·필터로 일정을 조회하고 캘린더 이벤트로 매핑한다.
// params: { startDate, endDate, status, visibilityType, departmentId }
export function useSchedules(params = {}) {
  return useQuery({
    queryKey: qk.schedules.list(params),
    queryFn: () => fetchSchedules(params),
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
    // S15P11B106-87: 동시성 충돌(409)이면 다른 사용자가 먼저 저장한 것이므로, 최신 일정(=최신 토큰)을 다시
    //   받아오도록 목록을 무효화한다. 사용자 안내 메시지는 각 폼에서 띄운다.
    onError: (error) => {
      if (error?.status === 409) {
        queryClient.invalidateQueries({ queryKey: qk.schedules.all })
      }
    },
  })
}

export function useDeleteSchedule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteSchedule,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.schedules.all }),
  })
}

// 초안 승인(관리자 검수). 승인 시 목록·달력 갱신.
export function useApproveSchedule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: approveSchedule,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.schedules.all }),
  })
}
