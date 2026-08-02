import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useNavigate } from 'react-router-dom'
import { requestPasswordReset, verifyPasswordResetCode } from '@/api/auth'
import { passwordResetRequestSchema, passwordResetVerifySchema } from '@/shared/validation/auth'
import { applyFieldErrors } from '@/shared/lib/fieldErrors'
import { Button, Input } from '@/components/ui'

// S0 비밀번호 찾기. 이메일 입력 → 6자리 인증번호 확인의 2단계로 진행한다.
// 계정 존재 여부와 무관하게 동일 안내를 보여준다(존재 노출 방지).
export default function PasswordFindPage() {
  const navigate = useNavigate()
  const [step, setStep] = useState('email') // 'email' | 'code'
  const [email, setEmail] = useState('')

  const emailForm = useForm({
    resolver: zodResolver(passwordResetRequestSchema),
    defaultValues: { email: '' },
  })

  const codeForm = useForm({
    resolver: zodResolver(passwordResetVerifySchema),
    defaultValues: { code: '' },
  })

  // 1단계: 이메일로 인증번호 발송 요청. 성공 여부와 무관하게 인증번호 입력 단계로 넘어간다.
  const onRequest = async (values) => {
    try {
      await requestPasswordReset(values)
    } catch {
      // 무시: 계정 존재 여부를 노출하지 않기 위해 항상 동일하게 진행한다.
    }
    setEmail(values.email)
    setStep('code')
  }

  // 2단계: 인증번호 확인. 성공하면 새 비밀번호 설정 화면으로 이동한다.
  const onVerify = async ({ code }) => {
    try {
      await verifyPasswordResetCode({ email, code })
      // 확인된 email/code를 재설정 화면으로 넘긴다(URL이 아닌 라우터 state).
      navigate('/password/reset', { state: { email, code } })
    } catch (err) {
      applyFieldErrors(err, codeForm.setError, { fallbackField: 'code' })
    }
  }

  // 인증번호 재발송.
  const onResend = async () => {
    try {
      await requestPasswordReset({ email })
    } catch {
      // 무시: 항상 동일 안내.
    }
    codeForm.reset({ code: '' })
  }

  if (step === 'code') {
    return (
      <div>
        <header className="mb-8">
          <h1 className="text-2xl font-bold text-slate-800">인증번호 입력</h1>
          <p className="mt-1 text-sm text-slate-500">
            <span className="font-medium text-slate-700">{email}</span>로 보내드린 6자리 인증번호를
            입력해 주세요. 입력한 이메일이 등록되어 있다면 인증번호가 발송됩니다.
          </p>
        </header>

        <form onSubmit={codeForm.handleSubmit(onVerify)} className="space-y-4" noValidate>
          <Input
            label="인증번호"
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            placeholder="6자리 숫자"
            hint="이메일로 받은 6자리 숫자를 입력해 주세요."
            error={codeForm.formState.errors.code?.message}
            {...codeForm.register('code')}
          />

          <Button
            type="submit"
            fullWidth
            size="lg"
            loading={codeForm.formState.isSubmitting}
          >
            인증번호 확인
          </Button>
        </form>

        <div className="mt-6 flex items-center justify-between text-sm">
          <button
            type="button"
            onClick={() => {
              setStep('email')
              codeForm.reset({ code: '' })
            }}
            className="font-medium text-slate-500 hover:text-slate-700"
          >
            이메일 다시 입력
          </button>
          <button
            type="button"
            onClick={onResend}
            className="font-medium text-primary-600 hover:text-primary-700"
          >
            인증번호 재발송
          </button>
        </div>

        <p className="mt-6 text-center text-sm text-slate-500">
          <Link to="/login" className="font-medium text-primary-600 hover:text-primary-700">
            로그인으로 돌아가기
          </Link>
        </p>
      </div>
    )
  }

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800">비밀번호 찾기</h1>
        <p className="mt-1 text-sm text-slate-500">
          가입한 이메일로 6자리 인증번호를 보내드립니다.
        </p>
      </header>

      <form onSubmit={emailForm.handleSubmit(onRequest)} className="space-y-4" noValidate>
        <Input
          label="이메일"
          type="email"
          autoComplete="username"
          placeholder="you@company.com"
          error={emailForm.formState.errors.email?.message}
          {...emailForm.register('email')}
        />
        <Button type="submit" fullWidth size="lg" loading={emailForm.formState.isSubmitting}>
          인증번호 받기
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
