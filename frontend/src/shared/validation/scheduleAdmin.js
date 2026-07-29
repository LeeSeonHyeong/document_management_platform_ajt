import { z } from 'zod'
import { SCHEDULE_VISIBILITY } from '@/shared/constants/enums'

// 관리자 수동 일정 등록 스키마. 공개 범위는 전체(all) 또는 부서(department).
// 부서 선택 시 최소 1개 부서가 필요하다.
export const adminScheduleFormSchema = z
  .object({
    title: z.string().min(1, '제목을 입력해 주세요.').max(200, '제목은 200자 이하여야 합니다.'),
    visibilityType: z.enum([SCHEDULE_VISIBILITY.ALL, SCHEDULE_VISIBILITY.DEPARTMENT], {
      message: '공개 범위를 선택해 주세요.',
    }),
    departmentIds: z.array(z.string()).default([]),
    startAt: z.string().min(1, '시작 일시를 선택해 주세요.'),
    endAt: z.string().min(1, '종료 일시를 선택해 주세요.'),
    location: z.string().max(200).optional().or(z.literal('')),
    content: z.string().optional().or(z.literal('')),
  })
  .refine((v) => new Date(v.endAt) >= new Date(v.startAt), {
    path: ['endAt'],
    message: '종료 일시는 시작 일시 이후여야 합니다.',
  })
  .refine((v) => v.visibilityType !== SCHEDULE_VISIBILITY.DEPARTMENT || v.departmentIds.length > 0, {
    path: ['departmentIds'],
    message: '부서 공개는 최소 1개 부서를 선택해야 합니다.',
  })
