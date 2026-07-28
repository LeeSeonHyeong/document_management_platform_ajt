import { z } from 'zod'
import { emailSchema, passwordSchema, nameSchema } from './common'

// 인증 화면(S0~S0-8)에서 사용하는 폼 스키마. 화면 브랜치에서 재사용한다.

export const loginSchema = z.object({
  email: emailSchema,
  // 로그인은 형식 재검증 대신 입력 여부만 확인한다(정책은 서버가 최종 판단).
  password: z.string().min(1, '비밀번호를 입력해 주세요.'),
})

export const signupSchema = z
  .object({
    name: nameSchema,
    email: emailSchema,
    departmentId: z.string().min(1, '소속 부서를 선택해 주세요.'),
    password: passwordSchema,
    passwordConfirm: z.string().min(1, '비밀번호 확인을 입력해 주세요.'),
  })
  .refine((v) => v.password === v.passwordConfirm, {
    path: ['passwordConfirm'],
    message: '비밀번호가 일치하지 않습니다.',
  })

export const passwordResetRequestSchema = z.object({
  email: emailSchema,
})

export const passwordResetConfirmSchema = z
  .object({
    newPassword: passwordSchema,
    newPasswordConfirm: z.string().min(1, '비밀번호 확인을 입력해 주세요.'),
  })
  .refine((v) => v.newPassword === v.newPasswordConfirm, {
    path: ['newPasswordConfirm'],
    message: '비밀번호가 일치하지 않습니다.',
  })
