import { Button, Modal } from '@/components/ui'
import { FileText } from 'lucide-react'

// 문서의 공개 범위를 짧은 라벨로. (전체 공개 / 부서명 / N개 부서)
function visibilityLabel(doc) {
  if (doc?.visibilityType === 'all') return '전체 공개'
  const names = (doc?.departments ?? []).map((d) => d.name)
  if (names.length === 0) return '부서 미지정'
  return names.length <= 2 ? names.join(', ') : `${names[0]} 외 ${names.length - 1}`
}

// Figma 4-4R — AI 작업 시작 확인.
// 확인하면 상위가 POST /ai-jobs 로 작업을 만들어 바로 시작한다(S15P11B106-276).
// 파일은 이미 서버에 있으므로 이 모달에서 전송하는 것은 없다 — 응답은 곧 돌아온다.
export default function AiJobStartDialog({
  open,
  onClose,
  onConfirm,
  documents = [],
  pending = false,
  uploadProgress = null,
}) {
  // uploadProgress = { loaded, total } — axios가 보고한 실제 전송 바이트.
  const percent =
    uploadProgress?.total > 0
      ? Math.min(100, Math.round((uploadProgress.loaded / uploadProgress.total) * 100))
      : null

  return (
    <Modal
      open={open}
      onClose={pending ? undefined : onClose}
      showClose={false}
      closeOnOverlay={!pending}
      size="lg"
      footer={
        <>
          <Button
            variant="outline"
            onClick={onClose}
            disabled={pending}
            className="min-w-16"
          >
            취소
          </Button>
          <Button
            variant="primary"
            onClick={onConfirm}
            disabled={pending}
            className="min-w-28 shadow-lg shadow-primary-200"
          >
            {pending ? (percent === null ? '시작 중…' : `업로드 중 ${percent}%`) : 'AI 작업 시작'}
          </Button>
        </>
      }
    >
      <div className="pt-1">
        <span className="flex size-11 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-violet-600">
          <span className="size-4 rounded-[5px] bg-white" />
        </span>

        <h2 className="mt-4 text-lg font-bold text-slate-900">
          {documents.length}개 파일의 AI 작업을 시작할까요?
        </h2>
        <p className="mt-1 text-xs leading-5 text-slate-500">
          AI가 문서를 파싱·분류하고 위키 문서와 일정을 생성합니다. 지정한 부서 소속 직원에게 공개됩니다.
        </p>

        <ul className="mt-4 max-h-52 space-y-1 overflow-y-auto rounded-xl border border-slate-200 bg-slate-50 p-3">
          {documents.map((doc) => (
            <li key={doc.documentId} className="flex items-center gap-3 rounded-lg px-2 py-2">
              <span className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-500">
                <FileText className="size-3.5" />
              </span>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-slate-800">{doc.originalFileName}</p>
                <p className="mt-0.5 truncate text-[11px] text-slate-400">
                  {doc.documentCategoryName ?? '미분류'} · {visibilityLabel(doc)}
                </p>
              </div>
            </li>
          ))}
        </ul>

        {percent !== null && (
          <div className="mt-3">
            <div className="flex items-center justify-between text-[11px] font-semibold text-slate-500">
              <span>파일 업로드 중</span>
              <span className="text-primary-600">{percent}%</span>
            </div>
            <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-100">
              <div
                className="h-full rounded-full bg-gradient-to-r from-blue-500 to-violet-600 transition-[width]"
                style={{ width: `${percent}%` }}
              />
            </div>
          </div>
        )}
      </div>
    </Modal>
  )
}
