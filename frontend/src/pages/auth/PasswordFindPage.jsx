import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link } from 'react-router-dom'
import { MailCheck } from 'lucide-react'
import { requestPasswordReset } from '@/api/auth'
import { passwordResetRequestSchema } from '@/shared/validation/auth'
import { Button, Input } from '@/components/ui'

// S0 비밀번호 찾기. 계정 존재 여부와 무관하게 동일 안내를 보여준다.
export default function PasswordFindPage() {
  const [sent, setSent] = useState(false)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(passwordResetRequestSchema), defaultValues: { email: '' } })

  const onSubmit = async (values) => {
    // 요청 성공 여부와 관계없이 동일 화면을 보여준다(계정 존재 노출 방지).
    try {
      await requestPasswordReset(values)
    } catch {
      // 무시: 항상 동일한 안내를 노출한다.
    }
    setSent(true)
  }

  if (sent) {
    return (
      <div className="text-center">
        <div className="mx-auto mb-4 flex size-14 items-center justify-center rounded-full bg-primary-50">
          <MailCheck className="size-8 text-primary-500" />
        </div>
        <h1 className="text-xl font-bold text-slate-800">메일을 확인해 주세요</h1>
        <p className="mt-2 text-sm text-slate-500">
          입력한 이메일이 등록되어 있다면 비밀번호 재설정 안내를 전송했습니다.
        </p>
        <Link
          to="/login"
          className="mt-6 inline-block text-sm font-medium text-primary-600 hover:text-primary-700"
        >
          로그인 화면으로 돌아가기
        </Link>
      </div>
    )
  }

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800">비밀번호 찾기</h1>
        <p className="mt-1 text-sm text-slate-500">
          가입한 이메일로 재설정 링크를 보내드립니다.
        </p>
      </header>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
        <Input
          label="이메일"
          type="email"
          autoComplete="username"
          placeholder="you@company.com"
          error={errors.email?.message}
          {...register('email')}
        />
        <Button type="submit" fullWidth size="lg" loading={isSubmitting}>
          재설정 링크 보내기
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-slate-500">
        <Link to="/login" className="font-medium text-primary-600 hover:text-primary-700">
          로그인으로 돌아가기
        </Link>
      </p>
    </div>
  )
}
