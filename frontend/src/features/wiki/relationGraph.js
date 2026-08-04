// 위키 1건을 중심으로 한 관계 그래프의 **데이터**를 만든다. 좌표는 만들지 않는다 —
// 배치는 `react-force-graph-2d`(d3-force)가 맡는다.
//
// 데이터는 `GET /api/v1/wikis/{wikiId}` 응답 하나로 끝난다: 그 응답의
// `relatedWikis`(위키↔위키)와 `evidenceDocuments`(위키→원본문서)가 곧 1홉 이웃이다.
//
// 좌표 계산에서 갈라 둔 이유는 테스트다. 힘 시뮬레이션은 매번 다른 좌표를 내지만
// **어떤 노드와 간선이 생기는지는 결정적**이라, 그 부분만 순수 함수로 두면 검증할 수 있다.

// 노드 반지름(캔버스 좌표). 원본문서는 사각형이라 반쪽 크기로 쓴다.
// 원본 viewer 는 수백 개 노드를 그려 점이 훨씬 작지만(4·2.5), 여기는 1홉 뷰라 노드가
// 십여 개다 — 같은 크기로 두면 빈약해 보인다.
export const CENTER_RADIUS = 12
export const WIKI_RADIUS = 8
export const DOCUMENT_HALF = 9

// 2홉에서 한 걸음 멀어질 때마다 이만큼 작게 그린다. 깊이를 크기로 인코딩하면
// "여기서 몇 걸음인지"를 색이나 범례 없이 읽을 수 있다.
const DEPTH_SCALE = 0.72

/** 노드 종류·깊이별 반지름. 포인터 판정과 충돌 계산이 같은 값을 써야 한다. */
export function radiusOf(node) {
  const base =
    node.kind === 'center' ? CENTER_RADIUS : node.kind === 'document' ? DOCUMENT_HALF : WIKI_RADIUS
  return node.depth > 1 ? base * DEPTH_SCALE : base
}

// 라벨이 길면 줄인다. 전체 제목은 tooltip 이 보여준다.
const LABEL_MAX = 22

export function truncateLabel(text, max = LABEL_MAX) {
  const value = String(text ?? '').trim()
  if (value.length <= max) return value
  return `${value.slice(0, max - 1)}…`
}

/**
 * 위키 상세 응답 하나로 1홉 그래프 데이터를 만든다.
 *
 * @param {object|null|undefined} wiki `GET /api/v1/wikis/{wikiId}` 응답
 * @returns {{
 *   nodes: {id: string, kind: 'center'|'wiki'|'document', label: string, fullLabel: string,
 *            wikiId?: string, documentId?: string, fileName?: string, downloadUrl?: string}[],
 *   links: {id: string, source: string, target: string, kind: 'wiki'|'document'}[],
 *   counts: {relatedWikis: number, documents: number}
 * }}
 */
export function buildEgoGraph(wiki) {
  if (!wiki) {
    return { nodes: [], links: [], counts: { relatedWikis: 0, documents: 0 } }
  }

  const centerId = `wiki-${wiki.wikiId}`
  const center = {
    id: centerId,
    kind: 'center',
    label: truncateLabel(wiki.title),
    fullLabel: String(wiki.title ?? ''),
    wikiId: String(wiki.wikiId),
  }

  const related = (wiki.relatedWikis ?? []).map((item) => ({
    id: `wiki-${item.wikiId}`,
    kind: 'wiki',
    label: truncateLabel(item.title),
    fullLabel: String(item.title ?? ''),
    wikiId: String(item.wikiId),
  }))

  const documents = (wiki.evidenceDocuments ?? []).map((item) => ({
    id: `doc-${item.documentId}`,
    kind: 'document',
    label: truncateLabel(item.originalFileName),
    fullLabel: String(item.originalFileName ?? ''),
    documentId: String(item.documentId),
    fileName: String(item.originalFileName ?? ''),
    downloadUrl: item.downloadUrl,
  }))

  // 같은 대상이 두 번 실려 오더라도 노드는 하나여야 한다 — 중복 노드는 간선이 겹쳐
  // 그려져 관계가 실제보다 많아 보인다. 중심을 가리키는 자기참조도 여기서 걸러진다.
  const seen = new Set([centerId])
  const neighbors = []
  for (const item of [...related, ...documents]) {
    if (seen.has(item.id)) continue
    seen.add(item.id)
    neighbors.push(item)
  }

  const links = neighbors.map((node) => ({
    id: `${centerId}->${node.id}`,
    source: centerId,
    target: node.id,
    kind: node.kind === 'document' ? 'document' : 'wiki',
  }))

  return {
    nodes: [center, ...neighbors],
    links,
    counts: { relatedWikis: related.length, documents: documents.length },
  }
}


/**
 * 2홉 그래프. 중심 위키와 그 관련 위키들의 상세를 함께 받아, 관련 위키의 관련까지 펼친다.
 *
 * **목록으로는 보여줄 수 없는 것이 이것이다.** 오른쪽 레일의 「관련 위키」·「출처 원본」
 * 카드는 1홉을 이미 평평하게 나열하고 있어서, 1홉 그래프는 그 목록과 같은 정보다.
 * 한 겹 더 나가면 "이 위키가 어느 묶음에 속하나"가 비로소 보인다.
 *
 * @param {object|null|undefined} center 중심 위키 상세
 * @param {(object|null|undefined)[]} [neighborDetails] 1홉 관련 위키들의 상세 (없거나 아직
 *   안 온 것은 `null` 로 둔다 — 그만큼만 안 펼쳐진다)
 * @returns {{nodes: object[], links: object[], counts: {relatedWikis: number, documents: number,
 *   secondHop: number}}}
 */
export function buildNeighborhoodGraph(center, neighborDetails = []) {
  const first = buildEgoGraph(center)
  if (!first.nodes.length) {
    return { ...first, counts: { ...first.counts, secondHop: 0 } }
  }

  const nodes = first.nodes.map((node) => ({ ...node, depth: node.kind === 'center' ? 0 : 1 }))
  const links = [...first.links]
  const byId = new Map(nodes.map((node) => [node.id, node]))
  let secondHop = 0

  for (const detail of neighborDetails) {
    if (!detail) continue
    const parentId = `wiki-${detail.wikiId}`
    // 중심의 1홉 위키가 아닌 상세가 섞여 들어오면 무시한다 — 간선의 출발점이 없으면
    // 떠 있는 노드가 생겨 그림이 거짓말을 한다.
    if (!byId.has(parentId)) continue

    const second = buildEgoGraph(detail)
    for (const node of second.nodes) {
      if (node.kind === 'center') continue
      const existing = byId.get(node.id)
      if (!existing) {
        const fresh = { ...node, depth: 2 }
        byId.set(node.id, fresh)
        nodes.push(fresh)
        secondHop += 1
      }
      // 이미 있는 노드라도 **간선은 더한다** — 두 위키가 같은 문서를 근거로 삼는 것이
      // 이 그래프에서 가장 읽을 만한 사실이다.
      const id = `${parentId}->${node.id}`
      if (!links.some((link) => link.id === id)) {
        links.push({
          id,
          source: parentId,
          target: node.id,
          kind: node.kind === 'document' ? 'document' : 'wiki',
        })
      }
    }
  }

  return { nodes, links, counts: { ...first.counts, secondHop } }
}
