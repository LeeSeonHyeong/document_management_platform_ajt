import { useCallback, useMemo, useState } from 'react'
import { AiJobQueueContext } from './AiJobQueueContext'

/**
 * AI 작업 대기 목록을 화면 이동 너머로 들고 있는다 (S15P11B106-230).
 *
 * 문서 관리 페이지의 `useState` 에 두면 다른 화면으로 갔다 오는 사이 언마운트돼
 * 올린 파일이 통째로 사라졌다. 라우터 위(AppShell)에 두어 문서 관리를 벗어나도 남긴다.
 *
 * **sessionStorage 로는 담을 수 없다.** 대기 항목은 아직 서버에 없는 임시 객체이고
 * `File` 을 그대로 들고 있다(`DocumentListPage.createPreviewDocuments`). `File` 은 직렬화가
 * 안 되고 blob URL 도 무효가 된다. 그래서 새로고침까지 버티게 하려면 IndexedDB 가 필요하고,
 * 그것은 이 티켓의 범위 밖이다 — 여기서는 화면 이동만 다룬다.
 */
export default function AiJobQueueProvider({ children }) {
  const [queueDocuments, setQueueDocuments] = useState([])
  const [queueMetadata, setQueueMetadata] = useState({})

  const addDocuments = useCallback((documents) => {
    setQueueDocuments((current) => [...documents, ...current])
  }, [])

  const updateMetadata = useCallback((documentId, changes) => {
    setQueueMetadata((current) => ({
      ...current,
      [documentId]: { ...current[documentId], ...changes },
    }))
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
  }, [])

  const value = useMemo(
    () => ({ queueDocuments, queueMetadata, addDocuments, updateMetadata, removeDocuments }),
    [queueDocuments, queueMetadata, addDocuments, updateMetadata, removeDocuments],
  )

  return <AiJobQueueContext.Provider value={value}>{children}</AiJobQueueContext.Provider>
}
