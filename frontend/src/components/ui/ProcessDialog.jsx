import { CheckCircle2, Loader2, Circle } from 'lucide-react'
import Modal from './Modal'
import { cn } from '@/shared/lib/cn'

// 처리 중 다이얼로그. 문서 삭제/재반영, AI 작업 처리 중처럼
// 단계가 있는 비동기 작업의 진행 상태를 보여준다.
// steps=[{ label, status: 'pending' | 'active' | 'done' }]
const STEP_ICON = {
  done: { icon: CheckCircle2, className: 'text-emerald-500' },
  active: { icon: Loader2, className: 'text-primary-600 animate-spin' },
  pending: { icon: Circle, className: 'text-slate-300' },
}

export default function ProcessDialog({ open, title, description, steps = [] }) {
  return (
    <Modal open={open} title={title} description={description} size="sm" closeOnOverlay={false}>
      <ol className="space-y-3">
        {steps.map((step, i) => {
          const conf = STEP_ICON[step.status] ?? STEP_ICON.pending
          const Icon = conf.icon
          return (
            <li key={i} className="flex items-center gap-3">
              <Icon className={cn('size-5 shrink-0', conf.className)} />
              <span
                className={cn(
                  'text-sm',
                  step.status === 'pending' ? 'text-slate-400' : 'text-slate-700',
                )}
              >
                {step.label}
              </span>
            </li>
          )
        })}
      </ol>
    </Modal>
  )
}
