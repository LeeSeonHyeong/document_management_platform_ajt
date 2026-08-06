import { useCallback, useEffect, useMemo, useState } from 'react'
import { AiJobQueueContext } from './AiJobQueueContext'
import {
  persistScheduleQueueDocuments,
  persistScheduleQueueMetadata,
  readScheduleQueue,
  removeScheduleQueueDocuments,
} from './scheduleQueueStorage'

/**
 * 아직 서버에 없는 대기 항목을 화면 이동 너머로 들고 있는다 (S15P11B106-230).
 *
 * 문서 관리 페이지의 `useState` 에 두면 다른 화면으로 갔다 오는 사이 언마운트돼
 * 올린 파일이 통째로 사라졌다. 라우터 위(AppShell)에 두어 문서 관리를 벗어나도 남긴다.
 *
 * **담는 것은 두 가지다** (S15P11B106-276 이후):
 * 1. 일정 파일. `POST /schedule-sources` 가 공개 범위를 필수로 받아 분류 전에 올릴 수 없어
 *    아직 `File` 을 그대로 들고 있다. 문서 파일은 고르는 즉시 업로드되므로 여기 없다.
 * 2. 서버 문서에 대한 화면 오버레이(`queueMetadata`). 공개 부서만 먼저 고른 상태는 서버에
 *    저장할 수 없다 — PATCH 가 카테고리와 공개 범위를 함께 받아 서로 맞는지 검증한다.
 *
 * 둘 다 IndexedDB 에 함께 담아 **새로고침에도 살아남는다**(`scheduleQueueStorage`). `File` 은
 * JSON 직렬화가 안 되지만 IndexedDB 는 structured clone 을 쓰므로 그대로 담긴다.
 */
export default function AiJobQueueProvider({ children }) {
  const [queueDocuments, setQueueDocuments] = useState([])
  const [queueMetadata, setQueueMetadata] = useState({})
  // 추출 중인 일정 파일. 셸에 두어 문서 관리를 떠나도 유지된다 — 일정 관리 화면이 이 값을 읽어
  // 「N개 추출 중」을 보여준다(S15P11B106-287). POST /schedule-sources 는 파싱·추출을 동기로
  // 끝내 몇 분 걸리는데, 그 진행을 서버에 물어볼 방법이 없어 브라우저가 들고 있어야 한다.
  const [extractingSchedules, setExtractingSchedules] = useState(() => [])

  // 담아둔 항목을 복원한다. 복원 중에 사용자가 새로 올린 항목을 덮지 않도록 뒤에 이어 붙인다.
  useEffect(() => {
    let cancelled = false
    readScheduleQueue().then(({ documents, metadata }) => {
      if (cancelled || documents.length === 0) return
      setQueueDocuments((current) => {
        const existing = new Set(current.map((document) => document.documentId))
        const restored = documents.filter((document) => !existing.has(document.documentId))
        return [...current, ...restored]
      })
      setQueueMetadata((current) => ({ ...metadata, ...current }))
    })
    return () => {
      cancelled = true
    }
  }, [])

  const addDocuments = useCallback((documents) => {
    setQueueDocuments((current) => [...documents, ...current])
    persistScheduleQueueDocuments(documents)
  }, [])

  const updateMetadata = useCallback((documentId, changes) => {
    setQueueMetadata((current) => {
      const next = { ...current[documentId], ...changes }
      // 서버 문서의 오버레이도 함께 담긴다. 무해하다 — 그 문서가 대기 목록에서 빠질 때
      // removeDocuments 로 정리되고, 남아도 서버 값이 우선한다.
      persistScheduleQueueMetadata(documentId, next)
      return { ...current, [documentId]: next }
    })
  }, [])

  // 목록에서 빠지는 항목의 blob URL 을 여기서 함께 거둔다. 제거 지점이 둘(개별 삭제·업로드
  // 완료)이라 호출부에 맡기면 한쪽을 빠뜨린다.
  const removeDocuments = useCallback((documentIds) => {
    const removing = new Set(documentIds)
    if (removing.size === 0) {
      return
    }
    setQueueDocuments((current) => {
      current
        .filter((document) => removing.has(document.documentId))
        .forEach((document) => {
          if (document.downloadUrl?.startsWith('blob:')) {
            URL.revokeObjectURL(document.downloadUrl)
          }
        })
      return current.filter((document) => !removing.has(document.documentId))
    })
    setQueueMetadata((current) => {
      const next = { ...current }
      removing.forEach((documentId) => delete next[documentId])
      return next
    })
    removeScheduleQueueDocuments(removing)
  }, [])

  // 추출 시작·종료. 종료는 성공·실패와 무관하게 「더 이상 진행 중이 아님」만 알린다.
  const startScheduleExtraction = useCallback((documents) => {
    setExtractingSchedules(
      documents.map((document) => ({
        documentId: document.documentId,
        originalFileName: document.originalFileName,
      })),
    )
  }, [])

  const finishScheduleExtraction = useCallback(() => setExtractingSchedules([]), [])

  const value = useMemo(
    () => ({
      queueDocuments,
      queueMetadata,
      addDocuments,
      updateMetadata,
      removeDocuments,
      extractingSchedules,
      startScheduleExtraction,
      finishScheduleExtraction,
    }),
    [
      queueDocuments,
      queueMetadata,
      addDocuments,
      updateMetadata,
      removeDocuments,
      extractingSchedules,
      startScheduleExtraction,
      finishScheduleExtraction,
    ],
  )

  return <AiJobQueueContext.Provider value={value}>{children}</AiJobQueueContext.Provider>
}
