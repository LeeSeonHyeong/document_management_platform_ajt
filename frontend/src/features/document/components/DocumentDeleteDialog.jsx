import { useEffect, useState } from 'react'
import { AlertTriangle, Check, ChevronRight, Circle, FileText, Info, Sparkles } from 'lucide-react'
import { Modal, Button, useToast } from '@/components/ui'
import { useDeleteDocument } from '../queries'
import { useAiJobPolling } from '../hooks/useAiJobPolling'

// Figma 4-7-2R ~ 4-7-4R — 삭제 확인 → 위키 반영 진행 → 완료.
// DELETE /documents/:id 는 202로 { deleted, reprocessRequired, jobId, ... }를 준다.
// reprocessRequired=true이고 jobId가 있을 때만 그 작업을 폴링하고, 재처리할 내용이 없으면
// (reprocessRequired=false, jobId=null, status=skipped) 그대로 삭제 완료로 처리한다.
export default function DocumentDeleteDialog({
  open,
  onClose,
  document,
  onDeletePreview,
  onBackground,
  onViewWiki,
  onGoToList,
}) {
  const toast = useToast()
  // 어느 문서의 삭제 작업인지 함께 들고 있는다. 다이얼로그는 부모에 계속 마운트된 상태로
  // open만 토글되므로, 문서가 바뀌었을 때 이전 작업의 완료 화면이 잠깐 보이는 것을 막는다.
  const [deletion, setDeletion] = useState(null) // { documentId, jobId }
  const deleteMutation = useDeleteDocument()
  const jobId =
    deletion && String(deletion.documentId) === String(document?.documentId) ? deletion.jobId : null
  const { job, isFinished } = useAiJobPolling(open ? jobId : null)

  // 다이얼로그가 열리고 닫힐 때 항상 확인 단계부터 시작한다.
  useEffect(() => {
    setDeletion(null)
  }, [open, document?.documentId])

  function handleDelete() {
    const documentId = document?.documentId
    if (document?.previewOnly) {
      onDeletePreview?.(documentId)
      return
    }
    deleteMutation.mutate(documentId, {
      onSuccess: (data) => {
        // 삭제 응답은 { deleted, reprocessRequired, jobId, scopeKey, status } 형태다.
        // S15P11B106-195: deleted 는 더 이상 항상 true 가 아니다 — 걷어낼 근거가 있으면
        // Wiki 정리가 끝난 뒤에 지우므로 이 시점에는 아직 문서가 살아 있다(status=deleting).
        if (data?.reprocessRequired && data.jobId) {
          setDeletion({ documentId, jobId: data.jobId })
          return
        }
        // 걷어낼 내용이 없어 즉시 지운 경우(deleted=true, jobId=null, status=skipped).
        if (!data?.deleted) {
          toast.error('문서 삭제 응답이 올바르지 않습니다.')
          return
        }
        toast.success('문서가 삭제되었습니다.')
        onGoToList?.()
      },
      onError: (error) => {
        if (error?.status === 403) toast.error('이 문서를 삭제할 권한이 없습니다.')
        else toast.error('삭제 요청에 실패했습니다.')
      },
    })
  }

  if (!open) return null

  if (jobId && isFinished) {
    return (
      <DeleteDoneDialog
        document={document}
        failureReason={job?.status === 'completed' ? null : job?.failureReason}
        onViewWiki={onViewWiki}
        onGoToList={onGoToList ?? onBackground ?? onClose}
        // S15P11B106-195: 걷어내기가 실패하면 문서와 원본 파일이 그대로 남는다.
        // 같은 삭제 요청을 다시 보내면 재시도된다 — 별도 재시도 API가 없다.
        //
        // 옛 jobId 를 먼저 지우지 않는다. 지우면 새 jobId 가 오기 전까지 이 화면이
        // 확인 단계("삭제할까요?")로 되돌아가 깜빡인다. 끝난 작업은 폴링도 멈춰 있어
        // 그대로 들고 있어도 부담이 없다 (useAiJobPolling).
        onRetry={handleDelete}
        retrying={deleteMutation.isPending}
      />
    )
  }

  if (jobId) {
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
          <Button variant="danger" onClick={handleDelete} loading={deleteMutation.isPending} fullWidth>
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

// Figma 4-7-4R — 위키 반영까지 끝난 뒤의 완료 화면.
function DeleteDoneDialog({ document, failureReason, onViewWiki, onGoToList, onRetry, retrying }) {
  const relatedWikis = document?.relatedWikis ?? []
  const firstWiki = relatedWikis[0] ?? null
  const fileSizeMb = document?.fileSize
    ? `${(document.fileSize / (1024 * 1024)).toFixed(1)} MB`
    : '용량 미확인'

  return (
    <Modal
      open
      size="lg"
      showClose={false}
      closeOnOverlay={false}
      footerClassName="grid grid-cols-2 gap-3 bg-slate-50 px-6 py-4"
      footer={
        failureReason ? (
          <>
            <Button variant="outline" onClick={onGoToList} fullWidth>
              목록으로
            </Button>
            <Button variant="danger" onClick={onRetry} loading={retrying} fullWidth>
              다시 삭제
            </Button>
          </>
        ) : (
          <>
            <Button
              variant="outline"
              onClick={() => firstWiki && onViewWiki?.(firstWiki.wikiId)}
              disabled={!firstWiki}
              fullWidth
            >
              갱신된 위키 보기
            </Button>
            <Button variant="primary" onClick={onGoToList} fullWidth>
              목록으로
            </Button>
          </>
        )
      }
    >
      <div className="flex flex-col items-center pt-2 text-center">
        {/* 실패는 실패처럼 보여야 한다 — 예전에는 실패해도 초록 원이라 성공으로 읽혔다. */}
        <span
          className={`flex size-14 items-center justify-center rounded-full ring-4 ${
            failureReason
              ? 'bg-rose-50 text-rose-500 ring-rose-100'
              : 'bg-emerald-50 text-emerald-500 ring-emerald-100'
          }`}
        >
          {failureReason ? <AlertTriangle className="size-7" /> : <Check className="size-7" strokeWidth={2.6} />}
        </span>
        <h2 className="mt-4 text-xl font-bold text-slate-900">
          {failureReason ? '삭제 처리가 완료되지 않았습니다' : '원본 문서가 삭제되었습니다'}
        </h2>
        <p className="mt-1.5 text-sm text-slate-500">
          {failureReason ?? '위키 반영까지 정상적으로 완료되었습니다.'}
        </p>
      </div>

      <div className="mt-5 space-y-2">
        {firstWiki && (
          <button
            type="button"
            onClick={() => onViewWiki?.(firstWiki.wikiId)}
            className="focus-ring flex w-full items-center gap-3 rounded-xl bg-slate-50 px-4 py-3 text-left hover:bg-primary-50"
          >
            <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-white text-violet-500">
              <span className="size-2.5 rounded-sm bg-violet-400" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-bold text-slate-800">
                위키 문서 {relatedWikis.length}건 갱신
              </span>
              <span className="mt-0.5 block truncate text-xs text-slate-400">
                {firstWiki.title} · 원본 기반 내용 정리됨
              </span>
            </span>
            <ChevronRight className="size-4 shrink-0 text-slate-400" />
          </button>
        )}

        {/* 순서가 뒤집혔다(S15P11B106-195) — 원본은 위키 정리가 끝난 뒤에만 지워진다.
            그래서 실패했으면 원본이 아직 남아 있고, 그렇게 적어야 사실과 맞는다. */}
        <div
          className={`flex items-center gap-3 rounded-xl px-4 py-3 ${
            failureReason ? 'bg-slate-50' : 'bg-emerald-50/60'
          }`}
        >
          <span
            className={`flex size-9 shrink-0 items-center justify-center rounded-lg bg-white ${
              failureReason ? 'text-slate-400' : 'text-emerald-500'
            }`}
          >
            {failureReason ? <Circle className="size-4" /> : <Check className="size-4" strokeWidth={2.6} />}
          </span>
          <div className="min-w-0">
            <p className="text-sm font-bold text-slate-800">
              {failureReason ? '원본 파일 유지됨' : '원본 파일 삭제'}
            </p>
            <p className="mt-0.5 truncate text-xs text-slate-400">
              {document?.originalFileName ?? '원본 문서'} · {fileSizeMb}
              {failureReason && ' · 다시 삭제할 수 있습니다'}
            </p>
          </div>
        </div>
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
