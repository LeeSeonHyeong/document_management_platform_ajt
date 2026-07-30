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
// 작업은 업로드 시점에 이미 생성(waiting)되어 있으므로, 확인 시 별도 생성 없이 상위 콜백만 호출한다.
export default function AiJobStartDialog({ open, onClose, onConfirm, documents = [] }) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      showClose={false}
      size="lg"
      footer={
        <>
          <Button variant="outline" onClick={onClose} className="min-w-16">
            취소
          </Button>
          <Button variant="primary" onClick={onConfirm} className="min-w-28 shadow-lg shadow-primary-200">
            AI 작업 시작
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
      </div>
    </Modal>
  )
}
