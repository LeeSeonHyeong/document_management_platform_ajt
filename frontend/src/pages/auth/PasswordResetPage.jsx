import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { CheckCircle2, AlertTriangle } from 'lucide-react'
import { confirmPasswordReset } from '@/api/auth'
import { passwordResetConfirmSchema } from '@/shared/validation/auth'
import { applyFieldErrors } from '@/shared/lib/fieldErrors'
import { Button, Input } from '@/components/ui'

// S0 새 비밀번호 설정. 인증번호 확인 화면(/password/find)에서 넘겨준
// email/code(라우터 state)로 진입한다. 링크 토큰 방식은 사용하지 않는다.
export default function PasswordResetPage() {
  const location = useLocation()
  const { email, code } = location.state ?? {}
  const navigate = useNavigate()
  const [done, setDone] = useState(false)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(passwordResetConfirmSchema),
    defaultValues: { newPassword: '', newPasswordConfirm: '' },
  })

  const onSubmit = async (values) => {
    try {
      await confirmPasswordReset({ email, code, newPassword: values.newPassword })
      setDone(true)
    } catch (err) {
      applyFieldErrors(err, setError, { fallbackField: 'root' })
    }
  }

  // 인증번호 확인 없이 직접 진입한 경우(새로고침 등으로 state 유실 포함).
  if (!email || !code) {
    return (
      <div className="text-center">
        <div className="mx-auto mb-4 flex size-14 items-center justify-center rounded-full bg-amber-50">
          <AlertTriangle className="size-8 text-amber-500" />
        </div>
        <h1 className="text-xl font-bold text-slate-800">인증이 필요해요</h1>
        <p className="mt-2 text-sm text-slate-500">
          인증번호 확인을 먼저 진행해 주세요. 처음부터 다시 요청할 수 있습니다.
        </p>
        <Button className="mt-6" fullWidth onClick={() => navigate('/password/find')}>
          비밀번호 찾기로 이동
        </Button>
      </div>
    )
  }

  if (done) {
    return (
      <div className="text-center">
        <div className="mx-auto mb-4 flex size-14 items-center justify-center rounded-full bg-emerald-50">
          <CheckCircle2 className="size-8 text-emerald-500" />
        </div>
        <h1 className="text-xl font-bold text-slate-800">비밀번호를 변경했어요</h1>
        <p className="mt-2 text-sm text-slate-500">새 비밀번호로 다시 로그인해 주세요.</p>
        <Button className="mt-6" fullWidth onClick={() => navigate('/login')}>
          로그인하기
        </Button>
      </div>
    )
  }

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800">새 비밀번호 설정</h1>
        <p className="mt-1 text-sm text-slate-500">안전한 새 비밀번호를 입력해 주세요.</p>
      </header>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
        <Input
          label="새 비밀번호"
          type="password"
          autoComplete="new-password"
          hint="8자 이상, 영문·숫자·특수문자 포함"
          error={errors.newPassword?.message}
          {...register('newPassword')}
        />
        <Input
          label="새 비밀번호 확인"
          type="password"
          autoComplete="new-password"
          error={errors.newPasswordConfirm?.message}
          {...register('newPasswordConfirm')}
        />

        {errors.root && (
          <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-600">
            {errors.root.message}
          </p>
        )}

        <Button type="submit" fullWidth size="lg" loading={isSubmitting}>
          비밀번호 변경
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
