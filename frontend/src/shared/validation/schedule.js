import { z } from 'zod'

// 일정 추가/수정 폼 스키마. 시작·종료는 datetime-local 문자열(yyyy-MM-ddTHH:mm).
export const scheduleFormSchema = z
  .object({
    title: z.string().min(1, '제목을 입력해 주세요.').max(200, '제목은 200자 이하여야 합니다.'),
    startAt: z.string().min(1, '시작 일시를 선택해 주세요.'),
    endAt: z.string().min(1, '종료 일시를 선택해 주세요.'),
    location: z.string().max(200).optional().or(z.literal('')),
    content: z.string().optional().or(z.literal('')),
  })
  .refine((v) => new Date(v.endAt) >= new Date(v.startAt), {
    path: ['endAt'],
    message: '종료 일시는 시작 일시 이후여야 합니다.',
  })
