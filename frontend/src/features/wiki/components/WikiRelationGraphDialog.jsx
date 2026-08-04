import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Modal } from '@/components/ui'
import { useWikiNeighborhood } from '../useWikiNeighborhood'
import RelationGraphCanvas from './RelationGraphCanvas'

// 2홉 관계 그래프. **이 화면이 그래프를 정당화한다** — 오른쪽 레일의 「관련 위키」·「출처 원본」
// 카드가 1홉을 이미 목록으로 보여주므로, 1홉 그래프는 그 목록과 같은 정보다. 관련 위키의
// 관련까지 한 겹 더 가면 "이 위키가 어느 묶음에 속하나", "어느 문서를 여러 위키가 함께
// 근거로 삼나" 가 보인다. 목록으로는 못 하는 일이다.
//
// 조회는 **열 때만** 나간다 (`useWikiNeighborhood` 의 `enabled`). 관련 위키가 n개면 요청도
// n개라, 위키 상세를 볼 때마다 상시로 나가면 낭비다.

export default function WikiRelationGraphDialog({ open, onClose, wikiId, onDocumentClick }) {
  const navigate = useNavigate()
  const { graph, center, isError, expanding, expandedCount, totalToExpand } = useWikiNeighborhood(
    wikiId,
    open,
  )

  const isInteractive = useCallback(
    (node) => {
      if (node.kind === 'center') return false
      if (node.kind === 'wiki') return true
      return Boolean(onDocumentClick)
    },
    [onDocumentClick],
  )

  const activate = useCallback(
    (node) => {
      if (node.kind === 'wiki') {
        onClose?.()
        navigate(`/wiki/${node.wikiId}`)
        return
      }
      onDocumentClick?.({
        documentId: node.documentId,
        fileName: node.fileName,
        downloadUrl: node.downloadUrl,
      })
    },
    [navigate, onClose, onDocumentClick],
  )

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="xl"
      title="관계 — 관련의 관련까지"
      description={
        center
          ? `${center.title} 을 중심으로 관련 위키와 그 관련까지 펼쳤습니다.`
          : '관계를 불러오는 중입니다…'
      }
    >
      {/* **중심 데이터가 있으면 그린다.** `isError` 만 보고 물러나면 캐시에 멀쩡한 데이터가
          있는데도 백그라운드 재조회 한 번 실패(예: 토큰 만료)로 화면이 비어 버린다 —
          카드에서 이미 겪은 것과 같은 실수라 두 곳의 판단을 같게 맞춘다. */}
      {!center ? (
        <p className="py-12 text-center text-sm text-slate-400">
          {isError ? '관계를 불러올 수 없습니다.' : '관계를 불러오는 중입니다…'}
        </p>
      ) : (
        <>
          <RelationGraphCanvas
            graph={graph}
            height={440}
            // 좁은 모달(672px)에 세 열이 들어가므로 라벨을 짧게 쓴다 — 전체 제목은 tooltip 이 준다.
            labelMax={14}
            isInteractive={isInteractive}
            onActivate={activate}
          />

          <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-xs">
            <p className="text-slate-400">
              작은 노드가 한 걸음 더 먼 것입니다. 여러 위키가 같은 문서로 이어지면 그 문서를 함께
              근거로 삼는다는 뜻입니다.
            </p>
            {/* 펼치는 중임을 숨기지 않는다 — n개 요청이라 시간이 걸릴 수 있고, 그때 그래프가
                덜 자란 상태로 보이는 이유를 말해 줘야 한다. */}
            {expanding && totalToExpand > 0 && (
              <p className="shrink-0 text-slate-400" aria-live="polite">
                관련 위키를 펼치는 중 {expandedCount}/{totalToExpand}
              </p>
            )}
            {!expanding && graph.counts?.secondHop === 0 && totalToExpand > 0 && (
              <p className="shrink-0 text-slate-400">한 걸음 더 나갈 관계가 없습니다</p>
            )}
          </div>
        </>
      )}
    </Modal>
  )
}
