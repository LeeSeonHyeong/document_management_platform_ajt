import apiClient from '@/api/client'

// 현재 공개 API 문서에는 비밀번호 변경 계약이 아직 없습니다.
// 팀 합의안인 PATCH /me/password를 임시 적용하며, 백엔드 계약 확정 시 이 함수만 맞춰 수정합니다.
export async function changeMyPassword(currentPassword, newPassword) {
  await apiClient.patch('/me/password', {
    currentPassword,
    newPassword,
  }, {
    // 현재 비밀번호 오류(401)는 세션 만료가 아니므로 전역 로그아웃으로 처리하지 않습니다.
    skipAuthRedirect: true,
  })
}
