import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { LogOut } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import Button from '@/components/ui/Button'
import Card from '@/components/ui/Card'
import Input from '@/components/ui/Input'
import { useToast } from '@/components/ui'
import { useAuth } from '@/hooks/useAuth'
import {
  ACCOUNT_STATUS_LABELS,
  ROLE_LABELS,
} from '@/shared/constants/enums'
import { changeMyPassword } from '../api'

const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z\d]).{8,}$/

function formatDateTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  const pad = (number) => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function StatusBadge({ active, children }) {
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-xs font-semibold ${active ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-500'}`}>
      <span className="size-1.5 rounded-full bg-current" />
      {children}
    </span>
  )
}

export default function MyProfilePage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  const passwordMutation = useMutation({
    mutationFn: () => changeMyPassword(currentPassword, newPassword),
    onSuccess: () => {
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
      toast.success('비밀번호가 변경되었습니다.')
    },
    onError: (error) => toast.error(error.message ?? '비밀번호를 변경하지 못했습니다.'),
  })

  const passwordFormatValid = PASSWORD_PATTERN.test(newPassword)
  const passwordMatches = newPassword === confirmPassword
  const canChangePassword = currentPassword && passwordFormatValid && passwordMatches

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  const rows = [
    ['이름', user?.name ?? '-'],
    ['이메일 (로그인 ID)', user?.email ?? '-'],
    ['부서', user?.department?.name ?? '-'],
    ['사번', user?.employeeNo ?? '-'],
    ['역할', ROLE_LABELS[user?.role] ?? user?.role ?? '-'],
    ['계정 상태', (
      <StatusBadge key="account-status" active={user?.accountStatus === 'active'}>
        {ACCOUNT_STATUS_LABELS[user?.accountStatus] ?? user?.accountStatus ?? '-'}
      </StatusBadge>
    )],
    ['생성일시', formatDateTime(user?.createdAt)],
    ['수정일시', formatDateTime(user?.updatedAt)],
  ]

  return (
    <div className="max-w-2xl space-y-4">
      <Card className="p-6">
        <div className="border-b border-slate-100 pb-4">
          <h2 className="text-base font-bold text-slate-900">계정 정보</h2>
        </div>
          <dl className="mt-4 space-y-4">
            {rows.map(([label, value]) => (
              <div key={label} className="grid grid-cols-[155px_1fr] items-center text-sm">
                <dt className="text-slate-400">{label}</dt>
                <dd className="font-medium text-slate-700">{value}</dd>
              </div>
            ))}
          </dl>
      </Card>

      <Card className="p-6">
        <div className="border-b border-slate-100 pb-4">
          <h2 className="text-base font-bold text-slate-900">개인 비밀번호 변경</h2>
        </div>
        <div className="mt-4 space-y-4">
          <Input
            type="password"
            label="현재 비밀번호"
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.target.value)}
            placeholder="현재 비밀번호를 입력하세요"
            autoComplete="current-password"
          />
          <div className="grid gap-4 sm:grid-cols-2">
            <Input
              type="password"
              label="새 비밀번호"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
              placeholder="8자 이상 입력"
              autoComplete="new-password"
              error={newPassword && !passwordFormatValid ? '영문·숫자·특수문자를 포함해 8자 이상 입력해주세요.' : undefined}
            />
            <Input
              type="password"
              label="새 비밀번호 확인"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              placeholder="다시 한 번 입력"
              autoComplete="new-password"
              error={confirmPassword && !passwordMatches ? '새 비밀번호가 일치하지 않습니다.' : undefined}
            />
          </div>
          <p className="rounded-lg bg-primary-50 px-3 py-2 text-xs text-slate-500">● 영문·숫자·특수문자를 포함한 8자 이상이어야 합니다.</p>
        </div>
      </Card>

      <div className="flex items-center justify-between pt-1">
        <Button variant="outline" onClick={handleLogout} className="border-rose-200 text-rose-500 hover:bg-rose-50">
          <LogOut className="size-4" /> 로그아웃
        </Button>
        <Button
          disabled={!canChangePassword}
          loading={passwordMutation.isPending}
          onClick={() => passwordMutation.mutate()}
        >
          변경사항 저장
        </Button>
      </div>
    </div>
  )
}
