import { Check, Sparkles } from 'lucide-react'
import { Button, Modal } from '@/components/ui'
import { useAiJobsPolling } from '../hooks/useAiJobPolling'

// currentStage 값의 진행 순서.
// 중간 단계 표기가 백엔드(wiki_pending)와 목 핸들러·status.js(wiki_transform)에서 갈려 있고
// docs/api 계약 예시에는 parsing·wiki_applied만 있어 중간 값이 확정돼 있지 않다.
// 어느 한쪽을 가정하지 않도록 두 표기를 같은 단계로 취급한다.
// TODO(API): 계약에 currentStage 허용값이 명시되면 한 가지로 정리한다.
const STAGE_SEQUENCE = [['waiting'], ['parsing'], ['wiki_pending', 'wiki_transform'], ['wiki_applied']]
const LAST_STAGE_INDEX = STAGE_SEQUENCE.length - 1

// 화면에 표시하는 3단계가 STAGE_SEQUENCE의 몇 번째 단계에 해당하는지.
const STAGES = [
  { key: 'parsing', label: '파싱', index: 1 },
  { key: 'classification', label: '분류', index: 2 },
  { key: 'wiki', label: '위키 생성', index: 3 },
]

const STATUS_LABEL = {
  completed: '완료',
  processing: '진행 중',
  waiting: '대기',
}

// 문서 한 건이 어느 단계까지 왔는지. status가 확정 값이므로 그것을 우선 신뢰한다.
function stageIndexOf(result) {
  if (result.status === 'completed') return LAST_STAGE_INDEX
  if (result.status === 'waiting') return 0
  const known = STAGE_SEQUENCE.findIndex((values) => values.includes(result.currentStage))
  // 처리 중인데 단계 표기를 알 수 없으면 최소한 파싱은 시작한 것으로 본다.
  return known === -1 ? 1 : known
}

// 표시 단계 상태. 기준은 아직 진행 중인 문서 중 가장 뒤처진 단계다.
function stageStatus(target, slowestIndex) {
  if (slowestIndex > target || (target === LAST_STAGE_INDEX && slowestIndex >= target)) return 'completed'
  if (slowestIndex === target) return 'processing'
  return 'waiting'
}

// Figma 4-5R — GET /ai-jobs/:jobId 폴링 결과로 실제 진행 단계를 표시한다.
// 업로드가 공개 범위별로 여러 작업으로 쪼개질 수 있어 jobIds 여러 개를 한 모달에 합쳐 보여준다.
export default function AiJobProgressDialog({ open, jobIds, documentCount = 0, onBackground, onDone }) {
  // 닫혀 있는 동안에는 폴링하지 않는다.
  const { documentResults, progress, isFinished } = useAiJobsPolling(open ? jobIds : [])

  // 실패·취소된 문서는 더 진행되지 않으므로 단계 계산에서 제외한다.
  const pendingIndexes = documentResults
    .filter((result) => result.status !== 'failed' && result.status !== 'cancelled')
    .map(stageIndexOf)
  const slowestIndex = pendingIndexes.length
    ? Math.min(...pendingIndexes)
    : progress.completed > 0
      ? LAST_STAGE_INDEX
      : 0
  const total = documentResults.length || documentCount

  return (
    <Modal
      open={open}
      onClose={isFinished ? onDone : onBackground}
      showClose={false}
      closeOnOverlay={false}
      size="md"
      footerClassName="justify-between"
      footer={
        isFinished ? (
          <Button onClick={onDone} className="w-full">
            요약 목록 보기
          </Button>
        ) : (
          <>
            <p className="text-[11px] text-slate-400">
              창을 닫아도 처리는 계속되며, 완료되면 알림으로 알려드립니다
            </p>
            <Button variant="outline" onClick={onBackground} className="shrink-0">
              백그라운드에서 계속
            </Button>
          </>
        )
      }
    >
      <div className="pt-2 text-center">
        <span className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500 to-violet-600 text-white shadow-lg shadow-primary-200">
          <Sparkles className="size-6" fill="currentColor" />
        </span>

        <h2 className="mt-4 text-lg font-bold text-slate-900">
          {isFinished ? 'AI 문서 처리가 끝났습니다' : 'AI가 문서를 처리하고 있습니다'}
        </h2>
        <p className="mt-2 text-xs text-slate-500">
          {documentResults.length
            ? `문서 ${total}개 중 ${progress.completed}개 완료${progress.failed ? ` · ${progress.failed}개 실패` : ''}`
            : `문서 ${total}개를 파싱하고 위키 문서를 생성하는 중입니다`}
        </p>

        <div className="mt-5 space-y-1 rounded-xl border border-slate-200 bg-slate-50 p-3 text-left">
          {STAGES.map(({ key, label, index }) => {
            const status = stageStatus(index, slowestIndex)
            return (
              <div key={key} className="flex items-center gap-3 rounded-lg px-1 py-2">
                <StageIcon status={status} />
                <span className={`text-sm font-semibold ${status === 'waiting' ? 'text-slate-400' : 'text-slate-700'}`}>
                  {label}
                </span>
                <span
                  className={`ml-auto rounded-md px-2 py-1 text-[10px] font-semibold ${
                    status === 'completed'
                      ? 'bg-emerald-50 text-emerald-600'
                      : status === 'processing'
                        ? 'bg-blue-50 text-blue-600'
                        : 'border border-slate-200 bg-white text-slate-400'
                  }`}
                >
                  {STATUS_LABEL[status]}
                </span>
              </div>
            )
          })}
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
