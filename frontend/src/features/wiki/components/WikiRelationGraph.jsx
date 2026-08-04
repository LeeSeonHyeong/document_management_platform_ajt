import { useCallback, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FileText, Maximize2 } from 'lucide-react'
import { useWiki } from '../queries'
import { buildEgoGraph } from '../relationGraph'
import { DOCUMENT_FILL, WIKI_FILL, WIKI_STROKE } from '../relationGraphTheme'
import RelationGraphCanvas from './RelationGraphCanvas'
import WikiRelationGraphDialog from './WikiRelationGraphDialog'

// 위키 1건의 관계를 카드 하나로 보여준다. 데이터는 `GET /api/v1/wikis/{wikiId}` **한 번**이다 —
// 그 응답의 `relatedWikis`·`evidenceDocuments` 가 곧 1홉 이웃이라 별도 API 가 필요 없다.
//
// 카드 문법은 사이트의 오른쪽 레일 카드를 따랐다(흰 배경 · slate-200 테두리 · rounded-2xl,
// 「제목 + 오른쪽 개수」 헤더).
//
// **「관련의 관련까지 보기」가 2홉을 연다.** 1홉만 보여주면 오른쪽 레일의 「관련 위키」·
// 「출처 원본」 목록과 같은 정보라 그래프일 이유가 없다. 관련 위키의 관련까지 한 겹 더 가는
// 것이 목록으로는 못 하는 일이고, 그 조회는 다이얼로그를 열 때만 나간다
// (`useWikiNeighborhood`).

function Legend({ label, value, shape }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-slate-500">
      <svg width="10" height="10" aria-hidden="true">
        <circle
          cx="5"
          cy="5"
          r="4.2"
          fill={shape === 'document' ? DOCUMENT_FILL : WIKI_FILL}
          stroke={shape === 'document' ? 'transparent' : WIKI_STROKE}
          strokeWidth="1.3"
        />
      </svg>
      {shape === 'document' && <FileText className="-ml-4 size-2.5 text-primary-500" aria-hidden="true" />}
      {label}
      <span className="font-semibold text-slate-700">{value}</span>
    </span>
  )
}

/**
 * @param {object} props
 * @param {string|number} props.wikiId 중심에 둘 위키 ID
 * @param {(document: {documentId: string, fileName: string, downloadUrl?: string}) => void} [props.onDocumentClick]
 *   원본문서 노드를 눌렀을 때. 없으면 원본문서는 눌러도 아무 일도 하지 않는다
 *   (내려받기·미리보기는 화면마다 달라 이 컴포넌트가 정하지 않는다).
 * @param {number} [props.height] 카드 안 캔버스 높이
 * @param {string} [props.className]
 */
export default function WikiRelationGraph({
  wikiId,
  onDocumentClick,
  height = 300,
  className = '',
}) {
  const navigate = useNavigate()
  const { data: wiki, isError } = useWiki(wikiId)
  const [expanded, setExpanded] = useState(false)

  const graph = useMemo(() => buildEgoGraph(wiki), [wiki])

  const isInteractive = useCallback(
    (node) => {
      if (node.kind === 'wiki') return true
      return node.kind === 'document' && Boolean(onDocumentClick)
    },
    [onDocumentClick],
  )

  const activate = useCallback(
    (node) => {
      if (node.kind === 'wiki') {
        navigate(`/wiki/${node.wikiId}`)
        return
      }
      onDocumentClick?.({
        documentId: node.documentId,
        fileName: node.fileName,
        downloadUrl: node.downloadUrl,
      })
    },
    [navigate, onDocumentClick],
  )

  // **데이터가 있으면 그린다.** `isError` 만 보고 물러나면, 캐시에 멀쩡한 데이터가 있는데도
  // 백그라운드 재조회가 한 번 실패한 것(예: 토큰 만료)만으로 그래프가 사라진다 —
  // react-query 는 데이터를 남긴 채 status 를 error 로 바꾼다. 실제로 그렇게 사라졌다.
  if (!wiki) {
    return (
      <section
        className={`rounded-2xl border border-slate-200 bg-white p-5 ${className}`}
        aria-busy={!isError}
      >
        <h2 className="text-sm font-semibold text-slate-900">관계</h2>
        <p className="mt-6 mb-6 text-center text-sm text-slate-400">
          {isError ? '관계를 불러올 수 없습니다.' : '관계를 불러오는 중입니다…'}
        </p>
      </section>
    )
  }

  const { counts } = graph
  const isolated = counts.relatedWikis === 0 && counts.documents === 0

  return (
    <section className={`rounded-2xl border border-slate-200 bg-white p-5 ${className}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-900">관계</h2>
        <div className="flex items-center gap-3">
          <Legend label="관련 위키" value={counts.relatedWikis} shape="wiki" />
          <Legend label="근거 문서" value={counts.documents} shape="document" />
        </div>
      </div>

      {isolated ? (
        <p className="mt-4 rounded-xl bg-slate-50 px-4 py-8 text-center text-sm text-slate-500">
          연결된 관련 위키나 근거 문서가 없습니다.
        </p>
      ) : (
        <>
          <div className="mt-4">
            <RelationGraphCanvas
              graph={graph}
              height={height}
              isInteractive={isInteractive}
              onActivate={activate}
            />
          </div>

          <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs text-slate-400">
              노드에 올리면 그 연결만 남습니다. 끌어서 옮기고 휠로 확대할 수 있습니다.
            </p>
            <button
              type="button"
              onClick={() => setExpanded(true)}
              aria-label="관련의 관련까지 보기"
              title="관련의 관련까지 보기"
              className="focus-ring inline-flex size-8 shrink-0 items-center justify-center rounded-lg border border-slate-200 text-primary-600 transition-colors hover:bg-primary-50"
            >
              <Maximize2 className="size-4" />
            </button>
          </div>
        </>
      )}

      <WikiRelationGraphDialog
        open={expanded}
        onClose={() => setExpanded(false)}
        wikiId={wikiId}
        onDocumentClick={onDocumentClick}
      />
    </section>
  )
}
