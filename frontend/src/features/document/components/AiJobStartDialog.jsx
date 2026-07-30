import { ConfirmDialog } from '@/components/ui'
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
    <ConfirmDialog
      open={open}
      onClose={onClose}
      onConfirm={onConfirm}
      title={`${documents.length}개 파일의 AI 작업을 시작할까요?`}
      confirmLabel="AI 작업 시작"
    >
      <div className="space-y-3 text-sm text-slate-600">
        <p>
          AI가 문서를 파싱·분류해 위키를 생성합니다. 문서는 업로드 순서대로 1건씩 처리되며, 문서 1건당 최대 10분이
          소요될 수 있습니다.
        </p>
        <ul className="max-h-48 space-y-1.5 overflow-y-auto rounded-lg border border-slate-100 p-2">
          {documents.map((doc) => (
            <li key={doc.documentId} className="flex items-center gap-2">
              <FileText className="size-4 shrink-0 text-slate-400" />
              <span className="min-w-0 flex-1 truncate text-slate-700">{doc.originalFileName}</span>
              <span className="shrink-0 text-xs text-slate-400">
                {doc.documentCategoryName ?? '-'} · {visibilityLabel(doc)}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </ConfirmDialog>
  )
}
