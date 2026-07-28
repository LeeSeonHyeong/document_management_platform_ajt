import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useNavigate } from 'react-router-dom'
import { CheckCircle2 } from 'lucide-react'
import { signup } from '@/api/auth'
import { signupSchema } from '@/shared/validation/auth'
import { applyFieldErrors } from '@/shared/lib/fieldErrors'
import { useSignupDepartments } from '@/features/auth/useSignupDepartments'
import { Button, Input, Select } from '@/components/ui'

// S0 회원가입 화면. 관리자 승인 전까지 pending 상태로 신청된다.
export default function SignupPage() {
  const navigate = useNavigate()
  const { data: departments = [] } = useSignupDepartments()
  const [submitted, setSubmitted] = useState(false)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(signupSchema),
    defaultValues: { name: '', email: '', departmentId: '', password: '', passwordConfirm: '' },
  })

  const onSubmit = async (values) => {
    try {
      await signup(values)
      setSubmitted(true)
    } catch (err) {
      applyFieldErrors(err, setError, { fallbackField: 'root' })
    }
  }

  // 신청 완료 상태(관리자 승인 대기 안내).
  if (submitted) {
    return (
      <div className="text-center">
        <div className="mx-auto mb-4 flex size-14 items-center justify-center rounded-full bg-emerald-50">
          <CheckCircle2 className="size-8 text-emerald-500" />
        </div>
        <h1 className="text-xl font-bold text-slate-800">가입 신청이 접수됐어요</h1>
        <p className="mt-2 text-sm text-slate-500">
          관리자 승인 후 로그인할 수 있습니다. 승인까지 잠시 기다려 주세요.
        </p>
        <Button className="mt-6" fullWidth onClick={() => navigate('/login')}>
          로그인 화면으로
        </Button>
      </div>
    )
  }

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800">회원가입</h1>
        <p className="mt-1 text-sm text-slate-500">몇 분이면 팀에 합류합니다.</p>
      </header>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
        <Input label="이름" placeholder="홍길동" error={errors.name?.message} {...register('name')} />
        <Input
          label="이메일"
          type="email"
          autoComplete="username"
          placeholder="you@company.com"
          error={errors.email?.message}
          {...register('email')}
        />
        <Select
          label="소속 부서"
          placeholder="부서를 선택하세요"
          options={departments.map((d) => ({ value: d.departmentId, label: d.name }))}
          error={errors.departmentId?.message}
          {...register('departmentId')}
        />
        <Input
          label="비밀번호"
          type="password"
          autoComplete="new-password"
          hint="8자 이상, 영문·숫자·특수문자 포함"
          error={errors.password?.message}
          {...register('password')}
        />
        <Input
          label="비밀번호 확인"
          type="password"
          autoComplete="new-password"
          error={errors.passwordConfirm?.message}
          {...register('passwordConfirm')}
        />

        {errors.root && (
          <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-600">
            {errors.root.message}
          </p>
        )}

        <Button type="submit" fullWidth size="lg" loading={isSubmitting}>
          가입 신청
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-slate-500">
        이미 계정이 있으신가요?{' '}
        <Link to="/login" className="font-medium text-primary-600 hover:text-primary-700">
          로그인
        </Link>
      </p>
    </div>
  )
}
