// 백엔드 member.role ENUM(EMPLOYEE, ADMIN)에 대응.
// 공개 API JSON은 소문자(role: "employee" | "admin")로 내려온다.
export const ROLES = Object.freeze({
  ADMIN: 'admin',
  EMPLOYEE: 'employee',
})

export const ALL_ROLES = Object.values(ROLES)
