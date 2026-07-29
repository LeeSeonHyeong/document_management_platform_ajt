/** The shape `graph_api.py` returns. */

export type NodeKind = 'page' | 'index' | 'source'
export type EdgeType = 'cites' | 'links_to'

export interface GraphNode {
  id: string
  kind: NodeKind
  title: string
  category: string | null
  tags: string[]
  chars: number
  citations: number
  wikiId: string | null
  sourceId: string | null
  fileName: string | null
  orphan: boolean
  // d3-force writes simulation state onto the node objects themselves.
  x?: number
  y?: number
  vx?: number
  vy?: number
  fx?: number
  fy?: number
}

export interface GraphEdge {
  source: string | GraphNode
  target: string | GraphNode
  type: EdgeType
  /** Per-footnote fields. Only `cites` edges carry them. */
  footnote: string | null
  location: string | null
  quote: string | null
  page: number | null
}

export interface GraphData {
  scope: string
  indexPath: string
  nodes: GraphNode[]
  edges: GraphEdge[]
}

/** d3 replaces string endpoints with node objects once the simulation starts. */
export function endpointId(end: string | GraphNode): string {
  return typeof end === 'object' ? end.id : end
}
