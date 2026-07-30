import { useEffect, useState } from 'react'
import { AlertTriangle, Check, Circle, FileText, Info, Sparkles } from 'lucide-react'
import { Modal, Button } from '@/components/ui'

// Figma 4-7-2R ~ 4-7-3R — 삭제 확인 후 AI 삭제 처리 화면으로 전환한다.
// 실제 삭제 완료 처리는 추후 AI 작업 상태 API가 연결된 뒤 반영한다.
export default function DocumentDeleteDialog({ open, onClose, document, onBackground }) {
  const [phase, setPhase] = useState('confirm')

  // 다이얼로그가 다시 열릴 때 항상 확인 단계부터 시작한다.
  useEffect(() => {
    if (open) setPhase('confirm')
  }, [open])

  function handleDelete() {
    setPhase('processing')
  }

  if (!open) return null

  if (phase === 'processing') {
    return (
      <DeleteProgressDialog
        open
        document={document}
        onBackground={onBackground ?? onClose}
      />
    )
  }

  const departments = document?.departments ?? []
  const departmentLabel =
    document?.visibilityType === 'all'
      ? '전체 공개'
      : departments.length > 1
        ? `${departments[0]?.name} 외 ${departments.length - 1}`
        : departments[0]?.name ?? '공개 부서 미지정'
  const fileSizeMb = document?.fileSize
    ? `${(document.fileSize / (1024 * 1024)).toFixed(1)} MB`
    : '용량 미확인'
  const relatedWikiTitle = document?.relatedWikis?.[0]?.title ?? '연결된 위키 문서'

  // confirm — Figma 4-7-2R
  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      showClose={false}
      footerClassName="grid grid-cols-2 gap-3 bg-slate-50 px-6 py-4"
      footer={
        <>
          <Button variant="outline" onClick={onClose} fullWidth>
            취소
          </Button>
          <Button variant="danger" onClick={handleDelete} fullWidth>
            삭제하고 위키 반영
          </Button>
        </>
      }
    >
      <div className="pt-1">
        <span className="flex size-12 items-center justify-center rounded-2xl bg-rose-50 text-rose-500">
          <AlertTriangle className="size-6" strokeWidth={2.2} />
        </span>

        <h2 className="mt-4 text-xl font-bold text-slate-900">원본 문서를 삭제할까요?</h2>
        <p className="mt-1.5 text-sm text-slate-500">
          삭제하면 이 문서로 생성된 위키 내용도 함께 정리됩니다.
        </p>

        <div className="mt-4 flex items-center gap-3 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-white text-primary-500">
            <FileText className="size-4.5" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-bold text-slate-800">
              {document?.originalFileName ?? '선택한 원본 문서'}
            </p>
            <p className="mt-0.5 truncate text-xs text-slate-400">
              {document?.documentCategoryName ?? '미분류'} · {departmentLabel} · {fileSizeMb}
            </p>
          </div>
        </div>

        <div className="mt-3 space-y-2">
          <DeleteNotice tone="info">
            AI가 위키 문서 「{relatedWikiTitle}」에서 이 원본 기반 내용을 먼저 정리합니다.
          </DeleteNotice>
          <DeleteNotice tone="warning">
            위키 반영이 끝난 뒤 원본 파일이 삭제됩니다. 최대 1~2분 걸릴 수 있습니다.
          </DeleteNotice>
          <DeleteNotice tone="danger">삭제한 원본은 복구할 수 없습니다.</DeleteNotice>
        </div>
      </div>
    </Modal>
  )
}

function DeleteProgressDialog({ open, document, onBackground }) {
  const relatedWikiTitle = document?.relatedWikis?.[0]?.title ?? '연결된 위키 문서'

  return (
    <Modal
      open={open}
      size="lg"
      showClose={false}
      closeOnOverlay={false}
      footerClassName="justify-between bg-slate-50 px-6 py-4"
      footer={
        <>
          <p className="text-xs leading-5 text-slate-400">
            처리 중에는 취소할 수 없습니다.
            <br />
            창을 닫아도 계속 진행됩니다.
          </p>
          <Button variant="outline" onClick={onBackground}>
            백그라운드에서 계속
          </Button>
        </>
      }
    >
      <div className="flex flex-col items-center pt-2 text-center">
        <span className="flex size-14 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500 to-violet-600 text-white shadow-lg shadow-violet-200">
          <Sparkles className="size-7" fill="currentColor" />
        </span>
        <h2 className="mt-4 text-xl font-bold text-slate-900">
          위키에 삭제 내용을 반영하고 있습니다
        </h2>
        <p className="mt-1.5 text-sm text-slate-500">
          반영이 끝나면 원본 문서가 자동으로 삭제됩니다
        </p>
      </div>

      <ol className="mt-5 space-y-1 rounded-xl border border-slate-200 bg-slate-50 p-3">
        <ProgressStep
          icon={<Check className="size-3.5" />}
          iconClassName="bg-emerald-100 text-emerald-600"
          label="연결된 위키 문서 확인"
          badge={`${document?.relatedWikis?.length ?? 0}건 발견`}
          badgeClassName="bg-emerald-50 text-emerald-600"
        />
        <ProgressStep
          icon={<span className="size-2 rounded-full bg-blue-500" />}
          iconClassName="border border-blue-400 bg-blue-50"
          label="위키 내용 정리"
          badge="진행 중"
          badgeClassName="bg-blue-50 text-blue-600"
        />
        <ProgressStep
          icon={<Circle className="size-4" />}
          iconClassName="text-slate-300"
          label="원본 파일 삭제"
          labelClassName="text-slate-400"
          badge="대기"
          badgeClassName="bg-white text-slate-400"
        />
      </ol>

      <div className="mt-3 flex items-center gap-3 rounded-xl bg-violet-50 px-4 py-3 text-xs text-slate-500">
        <span className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-white text-violet-500">
          <span className="size-2.5 rounded-sm bg-violet-400" />
        </span>
        <p className="truncate">{relatedWikiTitle} · 원본 기반 내용 정리 중</p>
      </div>
    </Modal>
  )
}

function ProgressStep({
  icon,
  iconClassName,
  label,
  labelClassName = 'text-slate-700',
  badge,
  badgeClassName,
}) {
  return (
    <li className="flex items-center gap-3 rounded-lg px-1 py-2">
      <span className={`flex size-6 shrink-0 items-center justify-center rounded-full ${iconClassName}`}>
        {icon}
      </span>
      <span className={`flex-1 text-left text-sm font-semibold ${labelClassName}`}>{label}</span>
      <span className={`rounded-md px-2 py-1 text-[11px] font-semibold ${badgeClassName}`}>
        {badge}
      </span>
    </li>
  )
}

function DeleteNotice({ tone, children }) {
  const styles = {
    info: 'bg-blue-50 text-blue-700',
    warning: 'bg-amber-50 text-amber-700',
    danger: 'bg-rose-50 text-rose-700',
  }

  return (
    <div className={`flex items-start gap-2 rounded-xl px-3 py-2.5 text-xs leading-5 ${styles[tone]}`}>
      <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-white/80">
        {tone === 'danger' ? (
          <AlertTriangle className="size-3" />
        ) : (
          <Info className="size-3" />
        )}
      </span>
      <p>{children}</p>
    </div>
  )
}
