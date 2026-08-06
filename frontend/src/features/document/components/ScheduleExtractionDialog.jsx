import { CalendarDays, FileText } from 'lucide-react'
import { Button, Modal, Spinner } from '@/components/ui'

/**
 * 일정 추출 진행 모달 (S15P11B106-287).
 *
 * 위키는 작업 진행을 모달로 보여주는데(AiJobProgressDialog) 일정만 페이지 아래 배너였다.
 * 배너는 화면을 옮기면 사라져 무엇이 처리 중인지 알 수 없었다.
 *
 * 위키 모달과 달리 진행률을 그리지 않는다 — `POST /schedule-sources`는 파싱·추출을 한 요청에서
 * 동기로 끝내므로 중간 상태를 물어볼 곳이 없다. 그래서 무엇이 처리 중인지와 어디서 결과를 볼지만
 * 알려주고, 이동해도 셸(AiJobQueueProvider)이 상태를 들고 있어 일정 관리에서 이어 볼 수 있다.
 */
export default function ScheduleExtractionDialog({ open, files = [], onClose, onOpenSchedules }) {
  const done = files.length === 0

  return (
    <Modal
      open={open}
      onClose={onClose}
      showClose={false}
      size="lg"
      footerClassName="grid grid-cols-2 gap-3 bg-slate-50 px-6 py-4"
      footer={
        <>
          <Button variant="outline" onClick={onClose} fullWidth>
            닫기
          </Button>
          <Button variant="primary" onClick={onOpenSchedules} fullWidth>
            일정 관리에서 보기
          </Button>
        </>
      }
    >
      <span className="flex size-12 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-600">
        <CalendarDays className="size-6" />
      </span>
      <h2 className="mt-4 text-xl font-bold text-slate-900">
        {done ? '일정 추출이 끝났습니다' : '일정을 추출하고 있습니다'}
      </h2>
      <p className="mt-1.5 text-sm leading-6 text-slate-500">
        {done ? (
          <>추출된 일정은 일정 관리의 승인 대기 목록에 있습니다. 승인해야 캘린더에 반영됩니다.</>
        ) : (
          <>
            문서를 읽고 일정을 뽑아내는 중입니다. 파일에 따라 몇 분 걸릴 수 있습니다. 끝나면 일정
            관리의 승인 대기 목록에 나타납니다.
          </>
        )}
      </p>

      {!done && (
        <>
          <div className="mt-4 space-y-1.5">
            {files.map((file) => (
              <div
                key={file.documentId}
                className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2"
              >
                <FileText className="size-4 shrink-0 text-emerald-600" />
                <span className="min-w-0 flex-1 truncate text-xs font-semibold text-slate-700">
                  {file.originalFileName}
                </span>
                <Spinner size="sm" />
              </div>
            ))}
          </div>
          <p className="mt-3 rounded-xl bg-slate-50 px-3 py-2.5 text-xs leading-5 text-slate-500">
            다른 메뉴로 이동해도 계속 진행됩니다. 다만 새로고침하거나 탭을 닫으면 중단됩니다 —
            브라우저가 요청을 붙잡고 있기 때문입니다.
          </p>
        </>
      )}
    </Modal>
  )
}
