import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { loginSchema } from '@/shared/validation/auth'
import { applyFieldErrors } from '@/shared/lib/fieldErrors'
import { Button, Input } from '@/components/ui'

// S0 로그인 화면. AuthLayout(좌 브랜드 패널) 안의 우측 폼.
export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  // ProtectedRoute가 넘겨준 원래 목적지. 없으면 홈으로.
  const from = location.state?.from?.pathname ?? '/'

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } })

  const onSubmit = async (values) => {
    try {
      await login(values)
      navigate(from, { replace: true })
    } catch (err) {
      // 자격 증명 오류 등은 폼 전체(root)에 표시한다.
      applyFieldErrors(err, setError, { fallbackField: 'root' })
    }
  }

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800">로그인</h1>
        <p className="mt-1 text-sm text-slate-500">AJT에 오신 것을 환영합니다.</p>
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
        <Input
          label="비밀번호"
          type="password"
          autoComplete="current-password"
          placeholder="••••••••"
          error={errors.password?.message}
          {...register('password')}
        />

        {errors.root && (
          <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-600">
            {errors.root.message}
          </p>
        )}

        <div className="flex justify-end">
          <Link to="/password/find" className="text-sm text-primary-600 hover:text-primary-700">
            비밀번호를 잊으셨나요?
          </Link>
        </div>

        <Button type="submit" fullWidth size="lg" loading={isSubmitting}>
          로그인
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-slate-500">
        아직 계정이 없으신가요?{' '}
        <Link to="/signup" className="font-medium text-primary-600 hover:text-primary-700">
          회원가입
        </Link>
      </p>
    </div>
  )
}
