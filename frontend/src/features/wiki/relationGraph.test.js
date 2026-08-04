import { describe, it, expect } from 'vitest'
import {
  buildEgoGraph,
  buildNeighborhoodGraph,
  truncateLabel,
  radiusOf,
  CENTER_RADIUS,
  WIKI_RADIUS,
  DOCUMENT_HALF,
} from './relationGraph'

// 좌표는 검증하지 않는다 — 배치는 d3-force 가 하고 매번 다른 값이 나온다.
// **어떤 노드와 간선이 생기는지는 결정적**이라 그 부분만 여기서 잠근다.

// `GET /api/v1/wikis/{wikiId}` 응답에서 이 그래프가 쓰는 부분만 만든다.
function wiki({ relatedWikis = [], evidenceDocuments = [] } = {}) {
  return { wikiId: '10', title: '주식 옵션 안내', relatedWikis, evidenceDocuments }
}

function related(wikiId, title) {
  return { wikiId, title }
}

function document(documentId, originalFileName) {
  return { documentId, originalFileName, downloadUrl: `/api/v1/documents/${documentId}/file` }
}

describe('truncateLabel', () => {
  it('짧은 제목은 그대로 둔다', () => {
    expect(truncateLabel('연차 규정')).toBe('연차 규정')
  })

  it('긴 제목은 말줄임표로 줄인다', () => {
    const label = truncateLabel('아주 긴 제목이라서 한 줄에 다 들어가지 않는다', 10)
    expect(label).toHaveLength(10)
    expect(label.endsWith('…')).toBe(true)
  })
})

describe('radiusOf', () => {
  it('중심이 가장 크고 원본문서는 관련 위키보다 한 단계 크다', () => {
    // 문서는 테두리·아이콘 때문에 작아 보이므로 관련 위키보다 실제 크기를 한 단계 올린다.
    expect(radiusOf({ kind: 'center' })).toBe(CENTER_RADIUS)
    expect(radiusOf({ kind: 'wiki' })).toBe(WIKI_RADIUS)
    expect(radiusOf({ kind: 'document' })).toBe(DOCUMENT_HALF)
    expect(CENTER_RADIUS).toBeGreaterThan(WIKI_RADIUS)
    expect(DOCUMENT_HALF).toBeGreaterThan(WIKI_RADIUS)
  })
})

describe('buildEgoGraph', () => {
  it('데이터가 없으면 빈 그래프를 준다', () => {
    const graph = buildEgoGraph(null)
    expect(graph.nodes).toEqual([])
    expect(graph.links).toEqual([])
    expect(graph.counts).toEqual({ relatedWikis: 0, documents: 0 })
  })

  it('첫 노드가 중심이고 고른 위키를 가리킨다', () => {
    const graph = buildEgoGraph(wiki())
    expect(graph.nodes[0]).toMatchObject({ kind: 'center', wikiId: '10', id: 'wiki-10' })
  })

  it('이웃이 없으면 중심 노드 하나만 남는다', () => {
    const graph = buildEgoGraph(wiki())
    expect(graph.nodes).toHaveLength(1)
    expect(graph.links).toHaveLength(0)
  })

  it('관련 위키와 원본문서가 각각 노드와 간선이 된다', () => {
    const graph = buildEgoGraph(
      wiki({
        relatedWikis: [related('11', '수습 기간')],
        evidenceDocuments: [document('36', '08-compensation.md')],
      }),
    )

    expect(graph.nodes.map((n) => n.kind)).toEqual(['center', 'wiki', 'document'])
    expect(graph.counts).toEqual({ relatedWikis: 1, documents: 1 })
    // 간선은 모두 중심에서 출발한다 (1홉 그래프)
    expect(graph.links.every((l) => l.source === 'wiki-10')).toBe(true)
    expect(graph.links.map((l) => l.kind)).toEqual(['wiki', 'document'])
  })

  it('원본문서 노드는 파일명과 내려받기 주소를 함께 들고 있다', () => {
    const graph = buildEgoGraph(wiki({ evidenceDocuments: [document('36', '08-compensation.md')] }))
    expect(graph.nodes.find((n) => n.kind === 'document')).toMatchObject({
      documentId: '36',
      fileName: '08-compensation.md',
      downloadUrl: '/api/v1/documents/36/file',
    })
  })

  it('같은 원본문서가 두 번 실려도 노드는 하나다', () => {
    // 중복 노드는 간선이 겹쳐 그려져 관계가 실제보다 많아 보인다.
    const graph = buildEgoGraph(
      wiki({ evidenceDocuments: [document('36', 'a.md'), document('36', 'a.md')] }),
    )
    expect(graph.nodes.filter((n) => n.kind === 'document')).toHaveLength(1)
    expect(graph.links).toHaveLength(1)
  })

  it('중심 위키가 자기 자신을 관련 위키로 들고 있어도 노드가 겹치지 않는다', () => {
    const graph = buildEgoGraph(wiki({ relatedWikis: [related('10', '주식 옵션 안내')] }))
    expect(graph.nodes).toHaveLength(1)
    expect(graph.links).toHaveLength(0)
  })

  it('노드 id 가 종류별로 갈려 위키와 문서가 충돌하지 않는다', () => {
    // wikiId 와 documentId 는 서로 다른 번호 공간이라 접두어 없이 합치면 부딪친다.
    const graph = buildEgoGraph(
      wiki({ relatedWikis: [related('7', 'A')], evidenceDocuments: [document('7', 'b.md')] }),
    )
    expect(new Set(graph.nodes.map((n) => n.id)).size).toBe(graph.nodes.length)
    expect(graph.nodes.map((n) => n.id)).toEqual(['wiki-10', 'wiki-7', 'doc-7'])
  })

  it('간선 id 는 서로 다르다', () => {
    // react-force-graph 가 같은 키를 두 번 받으면 한쪽을 잃는다.
    const graph = buildEgoGraph(
      wiki({
        relatedWikis: [related('11', 'A'), related('12', 'B')],
        evidenceDocuments: [document('40', 'a.md')],
      }),
    )
    expect(new Set(graph.links.map((l) => l.id)).size).toBe(3)
  })

  it('제목이나 파일명이 없어도 터지지 않는다', () => {
    const graph = buildEgoGraph({
      wikiId: '10',
      title: null,
      relatedWikis: [{ wikiId: '11' }],
      evidenceDocuments: [{ documentId: '40' }],
    })
    expect(graph.nodes).toHaveLength(3)
    expect(graph.nodes.every((n) => typeof n.label === 'string')).toBe(true)
  })
})

describe('buildNeighborhoodGraph', () => {
  it('이웃 상세가 없으면 1홉과 같다', () => {
    const one = buildEgoGraph(wiki({ relatedWikis: [related('11', 'A')] }))
    const two = buildNeighborhoodGraph(wiki({ relatedWikis: [related('11', 'A')] }), [])
    expect(two.nodes).toHaveLength(one.nodes.length)
    expect(two.counts.secondHop).toBe(0)
  })

  it('데이터가 없으면 빈 그래프를 준다', () => {
    const graph = buildNeighborhoodGraph(null, [])
    expect(graph.nodes).toEqual([])
    expect(graph.counts.secondHop).toBe(0)
  })

  it('깊이를 노드에 적어 크기로 읽을 수 있게 한다', () => {
    const graph = buildNeighborhoodGraph(wiki({ relatedWikis: [related('11', 'A')] }), [
      { wikiId: '11', title: 'A', relatedWikis: [related('21', 'A의 관련')], evidenceDocuments: [] },
    ])
    const depths = Object.fromEntries(graph.nodes.map((n) => [n.id, n.depth]))
    expect(depths['wiki-10']).toBe(0)
    expect(depths['wiki-11']).toBe(1)
    expect(depths['wiki-21']).toBe(2)
    expect(graph.counts.secondHop).toBe(1)
  })

  it('2홉 노드는 그 부모에서 간선이 나온다', () => {
    const graph = buildNeighborhoodGraph(wiki({ relatedWikis: [related('11', 'A')] }), [
      { wikiId: '11', title: 'A', relatedWikis: [], evidenceDocuments: [document('40', 'a.md')] },
    ])
    expect(graph.links.map((l) => `${l.source}->${l.target}`)).toEqual([
      'wiki-10->wiki-11',
      'wiki-11->doc-40',
    ])
  })

  it('두 위키가 같은 문서를 근거로 삼으면 간선이 둘 다 생긴다', () => {
    // 이 그래프에서 가장 읽을 만한 사실이다 — 노드는 하나로 합치되 간선은 둘 다 남는다.
    const graph = buildNeighborhoodGraph(
      wiki({ relatedWikis: [related('11', 'A')], evidenceDocuments: [document('40', 'a.md')] }),
      [{ wikiId: '11', title: 'A', relatedWikis: [], evidenceDocuments: [document('40', 'a.md')] }],
    )
    expect(graph.nodes.filter((n) => n.id === 'doc-40')).toHaveLength(1)
    expect(graph.links.filter((l) => l.target === 'doc-40').map((l) => l.source).sort()).toEqual([
      'wiki-10',
      'wiki-11',
    ])
  })

  it('이미 1홉에 있는 위키는 깊이를 2로 덮어쓰지 않는다', () => {
    const graph = buildNeighborhoodGraph(
      wiki({ relatedWikis: [related('11', 'A'), related('12', 'B')] }),
      [{ wikiId: '11', title: 'A', relatedWikis: [related('12', 'B')], evidenceDocuments: [] }],
    )
    expect(graph.nodes.find((n) => n.id === 'wiki-12').depth).toBe(1)
    expect(graph.counts.secondHop).toBe(0)
  })

  it('중심의 1홉이 아닌 상세가 섞여 오면 무시한다', () => {
    // 출발점 없는 간선은 떠 있는 노드를 만들어 그림이 거짓말을 한다.
    const graph = buildNeighborhoodGraph(wiki({ relatedWikis: [related('11', 'A')] }), [
      { wikiId: '99', title: '무관', relatedWikis: [related('98', 'X')], evidenceDocuments: [] },
    ])
    expect(graph.nodes.map((n) => n.id)).toEqual(['wiki-10', 'wiki-11'])
  })

  it('아직 안 온 상세(null)는 그만큼만 안 펼쳐진다', () => {
    const graph = buildNeighborhoodGraph(
      wiki({ relatedWikis: [related('11', 'A'), related('12', 'B')] }),
      [null, { wikiId: '12', title: 'B', relatedWikis: [related('22', 'C')], evidenceDocuments: [] }],
    )
    expect(graph.nodes.map((n) => n.id)).toEqual(['wiki-10', 'wiki-11', 'wiki-12', 'wiki-22'])
  })
})
