import { useMemo } from 'react'
import { useQueries } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import { fetchWiki } from './api'
import { useWiki } from './queries'
import { buildNeighborhoodGraph } from './relationGraph'

// 2홉 관계 그래프용 조회. 중심 위키 하나로는 1홉밖에 안 나오므로, 관련 위키 각각의 상세를
// 더 받아 한 겹 펼친다.
//
// **`enabled` 를 꺼 두면 아무 조회도 하지 않는다.** 관련 위키가 n개면 요청도 n개라
// 다이얼로그를 **열 때만** 켠다 — 위키 상세를 볼 때마다 상시로 나가면 낭비다.
//
// 조회 키를 `qk.wikis.detail` 로 쓰는 것이 중요하다 — `useWiki` 와 같은 키라서 캐시를
// 공유한다. 그래프에서 관련 위키를 눌러 그 위키로 이동하면 상세가 이미 캐시에 있다.
//
// 새 파일에 둔 이유: `queries.js` 는 여러 화면이 함께 쓰는 기존 파일이라 건드리지 않는다.

/**
 * @param {string|number|undefined} wikiId 중심 위키
 * @param {boolean} enabled 켤지 (다이얼로그가 열려 있을 때만 true)
 */
export function useWikiNeighborhood(wikiId, enabled) {
  const center = useWiki(enabled ? wikiId : undefined)

  const relatedIds = useMemo(
    () => (center.data?.relatedWikis ?? []).map((item) => String(item.wikiId)),
    [center.data],
  )

  const neighbors = useQueries({
    queries: relatedIds.map((id) => ({
      queryKey: qk.wikis.detail(id),
      queryFn: () => fetchWiki(id),
      enabled: Boolean(enabled),
    })),
  })

  // 아직 안 온 것은 `null` 로 넘긴다 — `buildNeighborhoodGraph` 가 그만큼만 덜 펼치므로,
  // 전부 도착하기를 기다리지 않고 오는 대로 그래프가 자란다.
  const details = neighbors.map((query) => query.data ?? null)
  const graph = useMemo(
    () => buildNeighborhoodGraph(center.data, details),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [center.data, details.map((d) => d?.wikiId ?? '').join(',')],
  )

  return {
    graph,
    center: center.data,
    isLoading: center.isPending,
    isError: center.isError,
    // 펼쳐지는 중인지 — 다이얼로그가 "관련 위키를 펼치는 중" 을 보여줄 수 있다.
    expanding: neighbors.some((query) => query.isPending),
    expandedCount: details.filter(Boolean).length,
    totalToExpand: relatedIds.length,
  }
}
