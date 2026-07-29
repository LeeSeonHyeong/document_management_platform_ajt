/** Ported from lucas-llmwiki `web/src/components/kb/GraphViewer.tsx`.
 *
 * Kept from upstream, because each one is a problem it already solved:
 *   - a node cache, because d3-force stores `x/y/vx/vy` on the node objects; reusing
 *     the same objects is what keeps the layout still when a filter toggles
 *   - hover computes the neighbour set and fades everything else
 *   - the force tuning (charge, link distance, collide, weak centring)
 *   - labels only past a zoom threshold, so a dense graph stays readable
 *   - a monotonic fetch sequence, so a slow response cannot overwrite fresher data
 *
 * Dropped: auth token, toasts, `next/dynamic`, and the rebuild button — rebuilding
 * is `mcp/vaultfs/rebuild.py` here.
 *
 * Added: category clustering with labels (see theme.ts for why category is position
 * rather than hue), radius by citation count, an orphan ring matching lint's
 * `orphan-page`, and a tooltip on `cites` edges showing the quoted sentence. That
 * last one is only possible because our edges are per footnote and carry the quote.
 */

import * as React from 'react'
import ForceGraph2D from 'react-force-graph-2d'
import { forceCollide, forceX, forceY } from 'd3-force'

import { endpointId, type GraphData, type GraphEdge, type GraphNode } from './types'
import { colorForKind, readPalette, type Palette } from './theme'

const PAGE_RADIUS = 4
const SOURCE_RADIUS = 2.5
const INDEX_RADIUS = 6.5
const CLUSTER_RADIUS = 260
const LABEL_ZOOM = 1.1

// d3-force mutates these objects, so they must survive a re-render.
const nodeCache = new Map<string, GraphNode>()

interface Props {
  data: GraphData
  showSources: boolean
  showIndex: boolean
}

function radiusOf(node: GraphNode): number {
  if (node.kind === 'index') return INDEX_RADIUS
  if (node.kind === 'source') return SOURCE_RADIUS
  // Citations are the reason to trust a page, so a well-sourced page reads bigger.
  return PAGE_RADIUS + Math.min(Math.sqrt(node.citations) * 0.9, 5)
}

export function GraphViewer({ data, showSources, showIndex }: Props) {
  const containerRef = React.useRef<HTMLDivElement>(null)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const graphRef = React.useRef<any>(null)
  const [size, setSize] = React.useState({ width: 0, height: 0 })
  const [palette, setPalette] = React.useState<Palette>(() => readPalette())
  const [hoverNode, setHoverNode] = React.useState<GraphNode | null>(null)
  const [hoverEdge, setHoverEdge] = React.useState<GraphEdge | null>(null)
  const [cursor, setCursor] = React.useState({ x: 0, y: 0 })
  const hoverNodeRef = React.useRef<GraphNode | null>(null)
  const neighborsRef = React.useRef<Set<string> | null>(null)

  React.useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { width, height } = entry.contentRect
        if (width > 0 && height > 0) setSize({ width, height })
      }
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  // Canvas holds resolved colours, so they have to be re-read when the theme flips.
  React.useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const refresh = () => setPalette(readPalette())
    media.addEventListener('change', refresh)
    const observer = new MutationObserver(refresh)
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    return () => {
      media.removeEventListener('change', refresh)
      observer.disconnect()
    }
  }, [])

  const categories = React.useMemo(
    () => Array.from(new Set(
      data.nodes.filter((n) => n.kind === 'page' && n.category).map((n) => n.category as string),
    )).sort(),
    [data.nodes],
  )

  const graph = React.useMemo(() => {
    const visible = data.nodes.filter((n) => {
      if (n.kind === 'source') return showSources
      if (n.kind === 'index') return showIndex
      return true
    })
    const ids = new Set(visible.map((n) => n.id))

    const nodes = visible.map((n) => {
      const cached = nodeCache.get(n.id)
      if (cached) {
        // Refresh the data fields; the payload carries no x/y, so position survives.
        Object.assign(cached, n)
        return cached
      }
      const fresh = { ...n }
      nodeCache.set(n.id, fresh)
      return fresh
    })

    const links = data.edges.filter(
      (e) => ids.has(endpointId(e.source)) && ids.has(endpointId(e.target)),
    )
    return { nodes, links }
  }, [data, showSources, showIndex])

  React.useEffect(() => {
    const fg = graphRef.current
    if (!fg) return
    fg.d3Force('charge')?.strength(-850)
    fg.d3Force('link')?.distance(130).strength(0.07)
    fg.d3Force('collide', forceCollide((n: GraphNode) => radiusOf(n) + 2.5).iterations(2))

    // Category becomes position: each category is pulled toward its own point on a
    // ring, so the clusters — and whether the wiki has any — are what you see first.
    const centre = (node: GraphNode, axis: 'x' | 'y') => {
      if (node.kind !== 'page' || !node.category) return 0
      const i = categories.indexOf(node.category)
      if (i < 0 || categories.length < 2) return 0
      const angle = (i / categories.length) * Math.PI * 2
      return (axis === 'x' ? Math.cos(angle) : Math.sin(angle)) * CLUSTER_RADIUS
    }
    fg.d3Force('x', forceX((n: GraphNode) => centre(n, 'x')).strength(0.06))
    fg.d3Force('y', forceY((n: GraphNode) => centre(n, 'y')).strength(0.06))
    fg.d3ReheatSimulation()
  }, [graph, categories, size.width])

  const handleNodeHover = React.useCallback((node: GraphNode | null) => {
    hoverNodeRef.current = node
    if (node) {
      const neighbours = new Set<string>([node.id])
      for (const link of graph.links) {
        const from = endpointId(link.source)
        const to = endpointId(link.target)
        if (from === node.id) neighbours.add(to)
        if (to === node.id) neighbours.add(from)
      }
      neighborsRef.current = neighbours
    } else {
      neighborsRef.current = null
    }
    setHoverNode(node)
  }, [graph.links])

  const nodeCanvasObject = React.useCallback(
    (node: GraphNode, ctx: CanvasRenderingContext2D, scale: number) => {
      const hovering = hoverNodeRef.current
      const isHover = hovering?.id === node.id
      const faded = hovering && neighborsRef.current && !neighborsRef.current.has(node.id)
      const radius = radiusOf(node)

      ctx.globalAlpha = faded ? 0.1 : 1
      ctx.beginPath()
      ctx.arc(node.x ?? 0, node.y ?? 0, radius, 0, 2 * Math.PI)
      ctx.fillStyle = isHover ? palette.ink : colorForKind(palette, node.kind)
      ctx.fill()

      // Same judgement lint's `orphan-page` makes: nothing points here.
      if (node.orphan) {
        ctx.beginPath()
        ctx.arc(node.x ?? 0, node.y ?? 0, radius + 2.5, 0, 2 * Math.PI)
        ctx.strokeStyle = palette.orphanRing
        ctx.lineWidth = 1.2
        ctx.stroke()
      }

      if (scale > LABEL_ZOOM || isHover) {
        const fontSize = Math.max(10 / scale, 2.5)
        ctx.font = `${fontSize}px system-ui, -apple-system, sans-serif`
        ctx.textAlign = 'center'
        ctx.textBaseline = 'top'
        ctx.fillStyle = isHover ? palette.ink : palette.inkSecondary
        ctx.fillText(node.title, node.x ?? 0, (node.y ?? 0) + radius + 2)
      }
      ctx.globalAlpha = 1
    },
    [palette],
  )

  const renderClusterLabels = React.useCallback(
    (ctx: CanvasRenderingContext2D, scale: number) => {
      if (categories.length < 2 || scale > LABEL_ZOOM) return
      ctx.save()
      ctx.font = `600 ${Math.max(13 / scale, 5)}px system-ui, -apple-system, sans-serif`
      ctx.textAlign = 'center'
      ctx.fillStyle = palette.muted
      categories.forEach((category, i) => {
        const angle = (i / categories.length) * Math.PI * 2
        ctx.fillText(
          category,
          Math.cos(angle) * CLUSTER_RADIUS * 1.42,
          Math.sin(angle) * CLUSTER_RADIUS * 1.42,
        )
      })
      ctx.restore()
    },
    [categories, palette],
  )

  const linkColor = React.useCallback(
    (link: GraphEdge) => {
      const hovering = hoverNodeRef.current
      if (hovering) {
        const touches =
          endpointId(link.source) === hovering.id || endpointId(link.target) === hovering.id
        return touches ? palette.edgeHover : 'rgba(128,128,128,0.04)'
      }
      return link.type === 'cites' ? palette.edgeCite : palette.edge
    },
    [palette, hoverNode],
  )

  const tooltip = hoverEdge ?? hoverNode
  const ready = size.width > 0 && graph.nodes.length > 0

  return (
    <div
      ref={containerRef}
      className="canvas-wrap"
      onMouseMove={(e) => {
        const rect = containerRef.current?.getBoundingClientRect()
        if (rect) setCursor({ x: e.clientX - rect.left, y: e.clientY - rect.top })
      }}
    >
      {ready ? (
        <ForceGraph2D
          ref={graphRef}
          width={size.width}
          height={size.height}
          graphData={graph}
          nodeId="id"
          nodeCanvasObject={nodeCanvasObject}
          nodePointerAreaPaint={(node: GraphNode, color: string, ctx: CanvasRenderingContext2D) => {
            ctx.beginPath()
            ctx.arc(node.x ?? 0, node.y ?? 0, radiusOf(node) + 2, 0, 2 * Math.PI)
            ctx.fillStyle = color
            ctx.fill()
          }}
          onRenderFramePre={renderClusterLabels}
          onNodeHover={handleNodeHover}
          onLinkHover={(link: GraphEdge | null) => setHoverEdge(link)}
          linkColor={linkColor}
          linkWidth={(link: GraphEdge) => (link.type === 'cites' ? 0.4 : 1)}
          linkHoverPrecision={4}
          backgroundColor="transparent"
          cooldownTicks={220}
          d3AlphaDecay={0.01}
          d3VelocityDecay={0.4}
        />
      ) : (
        <p className="empty">그래프에 보여줄 노드가 없다.</p>
      )}

      {tooltip && (
        <div className="tooltip" style={{ left: cursor.x + 14, top: cursor.y - 8 }}>
          {hoverEdge ? <EdgeTooltip edge={hoverEdge} /> : <NodeTooltip node={hoverNode!} />}
        </div>
      )}
    </div>
  )
}

function NodeTooltip({ node }: { node: GraphNode }) {
  return (
    <>
      <p className="tooltip-title">{node.title}</p>
      <dl className="tooltip-meta">
        <div>
          <dt>종류</dt>
          <dd>{node.kind === 'source' ? '원본문서' : node.kind === 'index' ? '목차' : '위키 페이지'}</dd>
        </div>
        {node.category && (
          <div>
            <dt>카테고리</dt>
            <dd>{node.category}</dd>
          </div>
        )}
        {node.kind !== 'source' && (
          <div>
            <dt>각주</dt>
            <dd>{node.citations}개</dd>
          </div>
        )}
        <div>
          <dt>본문</dt>
          <dd>{node.chars.toLocaleString()}자</dd>
        </div>
        {node.wikiId && (
          <div>
            <dt>wikiId</dt>
            <dd>{node.wikiId}</dd>
          </div>
        )}
        {node.fileName && (
          <div>
            <dt>파일명</dt>
            <dd>{node.fileName}</dd>
          </div>
        )}
      </dl>
      {node.orphan && <p className="tooltip-warn">들어오는 링크·인용이 없다 (고아 페이지)</p>}
      {node.tags.length > 0 && (
        <ul className="tags">
          {node.tags.slice(0, 6).map((tag) => (
            <li key={tag}>{tag}</li>
          ))}
        </ul>
      )}
      <p className="tooltip-path">{node.id}</p>
    </>
  )
}

function EdgeTooltip({ edge }: { edge: GraphEdge }) {
  const from = endpointId(edge.source)
  const to = endpointId(edge.target)
  if (edge.type === 'links_to') {
    return (
      <>
        <p className="tooltip-title">페이지 링크</p>
        <p className="tooltip-path">
          {from} → {to}
        </p>
      </>
    )
  }
  const where = edge.location || (edge.page ? `${edge.page}쪽` : null)
  return (
    <>
      <p className="tooltip-title">
        각주 [^{edge.footnote}]{where ? ` · ${where}` : ''}
      </p>
      {/* The reason this viewer exists in this shape: the claim's source sentence,
          verbatim, without opening the document. */}
      {edge.quote && <blockquote className="quote">{edge.quote}</blockquote>}
      <p className="tooltip-path">{to}</p>
    </>
  )
}
