import { useState } from 'react'
import { ChevronDown, Sparkles } from 'lucide-react'
import { Button, Modal } from '@/components/ui'
import { useAiJobsPolling } from '../hooks/useAiJobPolling'
import { buildAiJobProgress } from '../aiJobProgress'

// Figma 4-5R — GET /ai-jobs/:jobId 폴링 결과로 실제 진행 단계를 표시한다.
// 업로드가 공개 범위별로 여러 작업으로 쪼개질 수 있어 jobIds 여러 개를 한 모달에 합쳐 보여준다.
export default function AiJobProgressDialog({ open, jobIds, jobRefs = [], documentCount = 0, onBackground, onDone }) {
  const [showGroups, setShowGroups] = useState(false)
  // 닫혀 있는 동안에는 폴링하지 않는다.
  const { jobs, documentResults, progress, isFinished } = useAiJobsPolling(open ? jobIds : [])
  const { current, groups } = buildAiJobProgress(jobs, jobRefs)
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

        <div className="mt-5 rounded-xl border border-slate-200 bg-slate-50 p-4 text-left">
          <p className="text-[11px] font-semibold tracking-wide text-slate-400">현재 처리 중</p>
          {current ? (
            <>
              <p className="mt-1 truncate text-sm font-bold text-slate-800">{current.originalFileName ?? '이름 없는 문서'}</p>
              <p className="mt-1 text-xs text-slate-500">{current.scopeLabel} · {current.stageLabel}</p>
            </>
          ) : (
            <p className="mt-1 text-sm font-semibold text-slate-600">{isFinished ? '모든 문서 처리가 끝났습니다.' : '다음 문서를 준비하고 있습니다.'}</p>
          )}
        </div>

        {groups.length > 1 && (
          <div className="mt-3 overflow-hidden rounded-xl border border-slate-200 text-left">
            <button
              type="button"
              onClick={() => setShowGroups((value) => !value)}
              className="flex w-full items-center justify-between px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50"
            >
              범위별 진행 보기 ({groups.length})
              <ChevronDown className={`size-4 transition-transform ${showGroups ? 'rotate-180' : ''}`} />
            </button>
            {showGroups && (
              <div className="space-y-2 border-t border-slate-100 px-4 py-3">
                {groups.map((group) => (
                  <div key={group.jobId} className="flex items-center justify-between gap-3 text-xs">
                    <span className="truncate font-medium text-slate-600">{group.label}</span>
                    <span className={group.state === 'processing' ? 'font-semibold text-blue-600' : group.failed ? 'font-semibold text-rose-600' : 'font-semibold text-emerald-600'}>
                      {group.completed}/{group.total} 완료{group.failed ? ` · ${group.failed} 실패` : ''}{group.pending ? ' · 진행 중' : ''}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </Modal>
  )
}
