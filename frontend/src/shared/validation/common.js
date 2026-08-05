import { z } from 'zod'
import { MAX_FILE_SIZE_BYTES } from '@/shared/constants/enums'

// 여러 화면에서 공유하는 기본 검증 규칙. 화면별 스키마는 이 조각을 조합해 만든다.

export const emailSchema = z
  .string()
  .min(1, '이메일을 입력해 주세요.')
  .email('이메일 형식이 올바르지 않습니다.')

// 비밀번호 정책: 8자 이상 15자 이하, 대문자·소문자·숫자·특수문자 각각 포함.
export const passwordSchema = z
  .string()
  .min(8, '비밀번호는 8자 이상이어야 합니다.')
  .max(15, '비밀번호는 15자 이하여야 합니다.')
  .regex(/[a-z]/, '소문자를 포함해야 합니다.')
  .regex(/[A-Z]/, '대문자를 포함해야 합니다.')
  .regex(/[0-9]/, '숫자를 포함해야 합니다.')
  .regex(/[^A-Za-z0-9]/, '특수문자를 포함해야 합니다.')

// 이름 정책: 50자 이하, 한글·영문만(특수문자·이모지 불가). 공백은 단어 사이 한 칸만(영문 이름 대응).
export const nameSchema = z
  .string()
  .min(1, '이름을 입력해 주세요.')
  .max(50, '이름은 최대 50자입니다.')
  .regex(
    /^[가-힣a-zA-Z]+( [가-힣a-zA-Z]+)*$/,
    '이름은 한글·영문만 쓸 수 있고, 공백은 단어 사이 한 칸만 허용됩니다.',
  )

// 파일 확장자 소문자 추출.
export function fileExtension(fileName) {
  const dot = fileName.lastIndexOf('.')
  return dot === -1 ? '' : fileName.slice(dot + 1).toLowerCase()
}

// 허용 확장자·최대 크기로 File 목록을 검증하는 zod refine 헬퍼를 만든다.
// allowedExtensions: FILE_ACCEPT.* 배열
export function createFileListSchema(allowedExtensions, { maxCount, required = false } = {}) {
  let schema = z
    .array(z.instanceof(File))
    .refine(
      (files) => files.every((f) => allowedExtensions.includes(fileExtension(f.name))),
      `허용되지 않는 형식입니다. (${allowedExtensions.join(', ')})`,
    )
    .refine(
      (files) => files.every((f) => f.size <= MAX_FILE_SIZE_BYTES),
      '파일당 최대 20MB까지 업로드할 수 있습니다.',
    )

  if (typeof maxCount === 'number') {
    schema = schema.refine((files) => files.length <= maxCount, `최대 ${maxCount}개까지 첨부할 수 있습니다.`)
  }
  if (required) {
    schema = schema.refine((files) => files.length > 0, '파일을 첨부해 주세요.')
  }
  return schema
}
