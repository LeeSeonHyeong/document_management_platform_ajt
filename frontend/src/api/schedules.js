import apiClient from './client'

// GET /api/v1/schedules?startDate=&endDate=
// 권한 내 전체·부서 일정 + 본인 개인 일정을 기간으로 조회한다.
export async function fetchSchedules({ startDate, endDate } = {}) {
  const { data } = await apiClient.get('/schedules', { params: { startDate, endDate } })
  return data.items ?? []
}

// POST /api/v1/schedules — 수동·개인 일정 생성
export async function createSchedule(payload) {
  const { data } = await apiClient.post('/schedules', payload)
  return data
}

// PATCH /api/v1/schedules/:id
export async function updateSchedule(scheduleId, payload) {
  const { data } = await apiClient.patch(`/schedules/${scheduleId}`, payload)
  return data
}

// DELETE /api/v1/schedules/:id — 하드 삭제(초안 거부 포함)
export async function deleteSchedule(scheduleId) {
  await apiClient.delete(`/schedules/${scheduleId}`)
}
