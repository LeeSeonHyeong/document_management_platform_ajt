import { Check, Sparkles } from 'lucide-react'
import { Button, Modal } from '@/components/ui'

const STAGES = [
  { key: 'parsing', label: '파싱', status: 'completed' },
  { key: 'classification', label: '분류', status: 'processing' },
  { key: 'wiki', label: '위키 생성', status: 'waiting' },
]

const STATUS_LABEL = {
  completed: '완료',
  processing: '진행 중',
  waiting: '대기',
}

// Figma 4-5R — 실제 AI 연결 전 화면 검증용 정적 진행 팝업.
export default function AiJobProgressDialog({ open, documentCount = 0, onBackground }) {
  return (
    <Modal
      open={open}
      onClose={onBackground}
      showClose={false}
      closeOnOverlay={false}
      size="md"
      footerClassName="justify-between"
      footer={
        <>
          <p className="text-[11px] text-slate-400">
            창을 닫아도 처리는 계속되며, 완료되면 알림으로 알려드립니다
          </p>
          <Button variant="outline" onClick={onBackground} className="shrink-0">
            백그라운드에서 작업하기
          </Button>
        </>
      }
    >
      <div className="pt-2 text-center">
        <span className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500 to-violet-600 text-white shadow-lg shadow-primary-200">
          <Sparkles className="size-6" fill="currentColor" />
        </span>

        <h2 className="mt-4 text-lg font-bold text-slate-900">AI가 문서를 처리하고 있습니다</h2>
        <p className="mt-2 text-xs text-slate-500">
          문서 {documentCount}개를 파싱하고 위키 문서를 생성하는 중입니다
        </p>

        <div className="mt-5 space-y-1 rounded-xl border border-slate-200 bg-slate-50 p-3 text-left">
          {STAGES.map((stage) => (
            <div key={stage.key} className="flex items-center gap-3 rounded-lg px-1 py-2">
              <StageIcon status={stage.status} />
              <span className={`text-sm font-semibold ${stage.status === 'waiting' ? 'text-slate-400' : 'text-slate-700'}`}>
                {stage.label}
              </span>
              <span
                className={`ml-auto rounded-md px-2 py-1 text-[10px] font-semibold ${
                  stage.status === 'completed'
                    ? 'bg-emerald-50 text-emerald-600'
                    : stage.status === 'processing'
                      ? 'bg-blue-50 text-blue-600'
                      : 'border border-slate-200 bg-white text-slate-400'
                }`}
              >
                {STATUS_LABEL[stage.status]}
              </span>
            </div>
          ))}
        </div>
      </div>
    </Modal>
  )
}

function StageIcon({ status }) {
  if (status === 'completed') {
    return (
      <span className="flex size-5 items-center justify-center rounded-full bg-emerald-100 text-emerald-600">
        <Check className="size-3" strokeWidth={3} />
      </span>
    )
  }

  return (
    <span
      className={`flex size-5 items-center justify-center rounded-full border ${
        status === 'processing' ? 'border-blue-500 bg-blue-50' : 'border-slate-200 bg-white'
      }`}
    >
      {status === 'processing' && <span className="size-2 rounded-full bg-blue-500" />}
    </span>
  )
}
