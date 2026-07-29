import apiClient from './client'

// GET /api/v1/schedules
// 기간·상태·공개범위·부서로 조회한다. 관리자는 status=draft로 검수 대기 초안도 조회 가능.
// params 예: { startDate, endDate, status, visibilityType, departmentId }
export async function fetchSchedules(params = {}) {
  const { data } = await apiClient.get('/schedules', { params })
  return data.items ?? []
}

// POST /api/v1/schedules/:id/approve — 초안 1건 승인(APPROVED로 전환)
export async function approveSchedule(scheduleId) {
  const { data } = await apiClient.post(`/schedules/${scheduleId}/approve`)
  return data
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
