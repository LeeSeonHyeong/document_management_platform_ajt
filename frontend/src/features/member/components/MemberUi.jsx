import { UserRound } from 'lucide-react'
import Badge from '@/components/ui/Badge'
import Card from '@/components/ui/Card'
import {
  ACCOUNT_STATUS,
  ACCOUNT_STATUS_LABELS,
  ROLE_LABELS,
  ROLES,
  SIGNUP_STATUS,
  SIGNUP_STATUS_LABELS,
} from '@/shared/constants/enums'
import { cn } from '@/shared/lib/cn'

const AVATAR_COLORS = [
  'bg-blue-100 text-blue-700',
  'bg-rose-100 text-rose-700',
  'bg-emerald-100 text-emerald-700',
  'bg-amber-100 text-amber-700',
  'bg-violet-100 text-violet-700',
  'bg-cyan-100 text-cyan-700',
]

export function EmployeeAvatar({ employee, large = false }) {
  const seed = Number(employee?.userId ?? 0)
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-xl font-bold',
        AVATAR_COLORS[seed % AVATAR_COLORS.length],
        large ? 'size-14 text-xl' : 'size-8 text-sm',
      )}
    >
      {employee?.name?.slice(0, 1) ?? <UserRound className="size-4" />}
    </span>
  )
}

export function RoleBadge({ role }) {
  return <Badge tone={role === ROLES.ADMIN ? 'primary' : 'neutral'}>{ROLE_LABELS[role] ?? role}</Badge>
}

export function AccountBadge({ status }) {
  return (
    <Badge tone={status === ACCOUNT_STATUS.ACTIVE ? 'success' : 'neutral'}>
      {ACCOUNT_STATUS_LABELS[status] ?? status}
    </Badge>
  )
}

export function SignupBadge({ status }) {
  const tone = {
    [SIGNUP_STATUS.PENDING]: 'warning',
    [SIGNUP_STATUS.APPROVED]: 'success',
    [SIGNUP_STATUS.REJECTED]: 'neutral',
  }[status]
  return <Badge tone={tone}>{SIGNUP_STATUS_LABELS[status] ?? status}</Badge>
}

export function StatCard({ label, value, suffix = '명', tone = 'primary', caption }) {
  const toneClass = {
    primary: 'bg-primary-50 text-primary-600',
    blue: 'bg-blue-50 text-blue-600',
    amber: 'bg-amber-50 text-amber-600',
    slate: 'bg-slate-100 text-slate-500',
  }[tone]
  return (
    <Card className="p-5">
      <div className={cn('mb-4 flex size-9 items-center justify-center rounded-xl', toneClass)}>
        <span className="size-3 rounded bg-current" />
      </div>
      <p className="text-sm text-slate-500">{label}</p>
      <p className="mt-2 text-3xl font-bold text-slate-900">
        {value}<span className="ml-1 text-sm font-medium text-slate-400">{suffix}</span>
      </p>
      {caption && <p className="mt-3 text-xs text-slate-400">{caption}</p>}
    </Card>
  )
}
