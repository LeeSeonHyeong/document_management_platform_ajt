import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import ForceGraph2D from 'react-force-graph-2d'
import { forceCollide, forceX, forceY } from 'd3-force'
import { radiusOf, truncateLabel } from '../relationGraph'
import {
  EDGE,
  EDGE_ACTIVE,
  EDGE_MUTED,
  FADED,
  INK,
  INK_SECONDARY,
  AXIS_LABEL,
  DOCUMENT_SHADOW,
  NODE_HALO,
  SURFACE,
  accentOf,
  fillOf,
  nodeShape,
  showsNodeIcon,
  strokeOf,
} from '../relationGraphTheme'

// 관계 그래프의 **그리기 전담** 컴포넌트. 데이터를 가져오지 않는다 — 카드(`WikiRelationGraph`)와
// 다이얼로그(`WikiRelationGraphDialog`)가 같은 렌더러를 쓰도록 갈라 뒀다.
//
// 힘 시뮬레이션 배선은 `ai/viewer/src/GraphViewer.tsx` 를 따랐다. 그쪽이 이미 푼 문제들이다:
//   - **노드 캐시**: d3-force 가 노드 객체에 `x/y/vx/vy` 를 써 넣는다. 같은 객체를 다시 쓰는
//     것이 리렌더에도 배치가 흔들리지 않는 이유다
//   - 호버하면 이웃 집합을 구해 나머지를 흐리게 한다
//   - 힘 튜닝 (charge · link distance · collide)
//
// 다른 점 셋:
//   - **색조로 종류를 구분하지 않는다** (`relationGraphTheme` 참고). 모양과 채움으로 가른다
//   - **위치에 뜻을 싣는다** — 관련 위키는 왼쪽, 근거 문서는 오른쪽으로 당기고 축 라벨을 둔다.
//     viewer 의 카테고리 군집은 1홉 뷰에서 의미가 없다
//   - 라벨을 숨기는 줌 임계값을 두지 않는다. 노드가 애초에 적어서, 이름을 못 읽으면 볼 이유가 없다

const KIND_SPLIT = 130
const LABEL_LIMIT = 40

// 한 열 안에서 노드끼리 벌릴 세로 간격. 라벨 한 줄(11px)보다 넉넉해야 겹치지 않는다.
const ROW_GAP = 34

/** (종류, 깊이) 별로 열을 나누고 그 안에서 세로 슬롯을 배정한다. */
function assignRows(nodes) {
  const groups = new Map()
  for (const node of nodes) {
    if (node.kind === 'center') continue
    const key = `${node.kind}:${node.depth ?? 1}`
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(node)
  }
  const rows = new Map()
  for (const group of groups.values()) {
    group.forEach((node, index) => {
      rows.set(node.id, (index - (group.length - 1) / 2) * ROW_GAP)
    })
  }
  return rows
}

// 화면 맞추기. `zoomToFit` 을 쓰지 않는다 — 그쪽은 **노드 좌표만** 보고 맞춰서 노드 밖으로
// 뻗는 라벨이 잘린다(실측). 여백을 라벨 몫까지 잡고, 확대 상한을 둔다 — 노드가 두세 개일 때
// 화면을 채우면 우스꽝스럽게 커진다.
// 여백을 폭에 비례해 잡는다. 고정값(110)은 좁은 모달(672px)에서 폭의 33% 를 먹어
// 그래프가 가운데로 뭉쳤다(실측). 라벨 몫은 남기되 좁은 컨테이너에서는 줄인다.
const MAX_ZOOM = 2.2
const MIN_ZOOM = 0.3
// 여백을 가로·세로 따로 잡는다. **라벨은 좌우로만 뻗으므로** 세로에 같은 여백을 주면
// 그래프가 세로로 쪼그라든다(실측 — 카드에서 가운데 작게 뭉쳤다).
function fitPaddingX(width) {
  return Math.max(92, Math.min(130, width * 0.14))
}
const FIT_PADDING_Y = 28

// d3-force 가 이 객체들을 변형하므로 리렌더를 넘어 살아 있어야 한다.
const nodeCache = new Map()

function endpointId(end) {
  return typeof end === 'object' && end !== null ? end.id : end
}

function drawDocumentIcon(ctx, x, y, radius) {
  const size = radius * 0.75
  const left = x - size * 0.42
  const top = y - size * 0.52
  const right = x + size * 0.42
  const bottom = y + size * 0.52

  ctx.beginPath()
  ctx.moveTo(left, top)
  ctx.lineTo(right - size * 0.28, top)
  ctx.lineTo(right, top + size * 0.28)
  ctx.lineTo(right, bottom)
  ctx.lineTo(left, bottom)
  ctx.closePath()
  ctx.moveTo(right - size * 0.28, top)
  ctx.lineTo(right - size * 0.28, top + size * 0.28)
  ctx.lineTo(right, top + size * 0.28)
  ctx.moveTo(left + size * 0.18, y - size * 0.05)
  ctx.lineTo(right - size * 0.18, y - size * 0.05)
  ctx.moveTo(left + size * 0.18, y + size * 0.23)
  ctx.lineTo(right - size * 0.18, y + size * 0.23)
  ctx.stroke()
}

function drawWikiIcon(ctx, x, y, radius) {
  const size = radius * 0.72
  ctx.beginPath()
  ctx.moveTo(x, y - size * 0.42)
  ctx.quadraticCurveTo(x - size * 0.38, y - size * 0.6, x - size * 0.62, y - size * 0.38)
  ctx.lineTo(x - size * 0.62, y + size * 0.45)
  ctx.quadraticCurveTo(x - size * 0.3, y + size * 0.24, x, y + size * 0.42)
  ctx.quadraticCurveTo(x + size * 0.3, y + size * 0.24, x + size * 0.62, y + size * 0.45)
  ctx.lineTo(x + size * 0.62, y - size * 0.38)
  ctx.quadraticCurveTo(x + size * 0.38, y - size * 0.6, x, y - size * 0.42)
  ctx.moveTo(x, y - size * 0.42)
  ctx.lineTo(x, y + size * 0.42)
  ctx.stroke()
}

function fitToView(fg, nodes, width, height) {
  if (!nodes.length) return
  const xs = nodes.map((n) => n.x ?? 0)
  const ys = nodes.map((n) => n.y ?? 0)
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minY = Math.min(...ys)
  const maxY = Math.max(...ys)
  const scale = Math.min(
    (width - fitPaddingX(width) * 2) / Math.max(maxX - minX, 1),
    (height - FIT_PADDING_Y * 2) / Math.max(maxY - minY, 1),
    MAX_ZOOM,
  )
  fg.centerAt((minX + maxX) / 2, (minY + maxY) / 2, 300)
  fg.zoom(Math.max(scale, MIN_ZOOM), 300)
}

/**
 * @param {object} props
 * @param {{nodes: object[], links: object[]}} props.graph
 * @param {number} props.height
 * @param {(node: object) => boolean} [props.isInteractive]
 * @param {(node: object) => void} [props.onActivate]
 * @param {boolean} [props.showAxisLabels] 좌우 축 라벨(관련 위키 / 근거)을 그릴지
 */
export default function RelationGraphCanvas({
  graph: source,
  height,
  isInteractive = () => false,
  onActivate,
  showAxisLabels = true,
  labelMax,
}) {
  const containerRef = useRef(null)
  const graphRef = useRef(null)
  const hoverNodeRef = useRef(null)
  const neighborsRef = useRef(null)
  const fittedRef = useRef(null)
  const nodeCountRef = useRef(0)

  const [width, setWidth] = useState(0)
  const [hoverNode, setHoverNode] = useState(null)
  const [cursor, setCursor] = useState({ x: 0, y: 0 })

  useEffect(() => {
    const element = containerRef.current
    if (!element) return
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        if (entry.contentRect.width > 0) setWidth(entry.contentRect.width)
      }
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  // 캐시된 노드 객체를 다시 쓴다 (위 주석의 이유). 데이터 필드만 갱신하고 좌표는 살린다.
  const graph = useMemo(() => {
    const nodes = source.nodes.map((node) => {
      const cached = nodeCache.get(node.id)
      if (cached) {
        Object.assign(cached, node)
        return cached
      }
      const fresh = { ...node }
      nodeCache.set(node.id, fresh)
      return fresh
    })
    nodeCountRef.current = nodes.length
    return { nodes, links: source.links.map((link) => ({ ...link })) }
  }, [source])

  useEffect(() => {
    const fg = graphRef.current
    if (!fg) return
    // 라벨이 노드 옆으로 뻗으므로 간격을 넉넉히 준다. 좁으면 라벨끼리 겹쳐 뭉친다(실측).
    fg.d3Force('charge')?.strength(-520)
    fg.d3Force('link')?.distance(110).strength(0.9)
    fg.d3Force('collide', forceCollide((node) => radiusOf(node) + 18).iterations(2))
    fg.d3Force(
      'x',
      forceX((node) => {
        if (node.kind === 'center') return 0
        const step = KIND_SPLIT * Math.max(node.depth ?? 1, 1)
        return node.kind === 'document' ? step : -step
      }).strength(0.55),
    )
    const rows = assignRows(graph.nodes)
    fg.d3Force('y', forceY((node) => rows.get(node.id) ?? 0).strength(0.62))
    fg.d3ReheatSimulation()
    fittedRef.current = null
  }, [graph, width])

  const handleNodeHover = useCallback(
    (node) => {
      hoverNodeRef.current = node
      if (node) {
        const neighbours = new Set([node.id])
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
    },
    [graph.links],
  )

  const nodeCanvasObject = useCallback((node, ctx, scale) => {
    const hovering = hoverNodeRef.current
    const isHover = hovering?.id === node.id
    const faded = hovering && neighborsRef.current && !neighborsRef.current.has(node.id)
    const radius = radiusOf(node)
    const x = node.x ?? 0
    const y = node.y ?? 0
    // 2홉은 한 걸음 멀다 — 크기(radiusOf)에 더해 살짝 물려 깊이가 읽히게 한다.
    const depthAlpha = node.depth > 1 ? 0.78 : 1
    const alpha = faded ? FADED : depthAlpha

    ctx.globalAlpha = alpha

    // 종류와 관계없이 원형을 지킨다. 중심·근거의 아이콘이 위계를 설명한다.
    ctx.beginPath()
    if (nodeShape(node.kind) === 'circle') {
      ctx.arc(x, y, radius, 0, 2 * Math.PI)
    }
    ctx.save()
    if (node.kind === 'document') {
      ctx.shadowColor = DOCUMENT_SHADOW
      ctx.shadowBlur = 8 / scale
      ctx.shadowOffsetY = 1.5 / scale
    }
    ctx.fillStyle = fillOf(node.kind)
    ctx.fill()
    ctx.lineWidth = (node.kind === 'document' ? 1.6 : 1.2) / scale
    ctx.strokeStyle = strokeOf(node.kind)
    ctx.stroke()
    ctx.restore()

    if (showsNodeIcon(node.kind)) {
      ctx.save()
      ctx.strokeStyle = node.kind === 'document' ? accentOf(node.kind) : NODE_HALO
      ctx.lineWidth = (node.kind === 'document' ? 1.25 : 1.4) / scale
      ctx.lineJoin = 'round'
      ctx.lineCap = 'round'
      if (node.kind === 'document') drawDocumentIcon(ctx, x, y, radius)
      else drawWikiIcon(ctx, x, y, radius)
      ctx.restore()
    }

    // 호버·중심 강조 링. 크기를 바꾸지 않고 눈에 띄게 한다.
    if (isHover || node.kind === 'center') {
      ctx.beginPath()
      ctx.arc(x, y, radius + 3.5, 0, 2 * Math.PI)
      ctx.strokeStyle = accentOf(node.kind)
      ctx.globalAlpha = alpha * (isHover ? 0.6 : 0.32)
      ctx.lineWidth = 1.4 / scale
      ctx.stroke()
      ctx.globalAlpha = alpha
    }

    if (nodeCountRef.current <= LABEL_LIMIT || isHover || node.kind === 'center') {
      const fontSize = Math.max((node.depth > 1 ? 10 : 11) / scale, 3)
      ctx.font = `${node.kind === 'center' ? '600 ' : ''}${fontSize}px ui-sans-serif, system-ui, sans-serif`

      // **라벨을 바깥쪽으로 뻗는다.** 전부 노드 아래에 그리면 가운데가 빽빽한 이 배치에서
      // 라벨끼리 겹쳐 읽을 수 없다(실측). 종류가 좌우로 갈려 있으니 라벨도 그 방향으로 낸다.
      let tx = x
      let ty = y
      if (node.kind === 'center') {
        ctx.textAlign = 'center'
        ctx.textBaseline = 'top'
        ty = y + radius + 4
      } else if (node.kind === 'document') {
        ctx.textAlign = 'left'
        ctx.textBaseline = 'middle'
        tx = x + radius + 5
      } else {
        ctx.textAlign = 'right'
        ctx.textBaseline = 'middle'
        tx = x - radius - 5
      }

      // 글자 뒤에 배경색 테두리를 깔아 간선이 글자를 가로지르는 것을 끊는다.
      ctx.lineWidth = 3 / scale
      ctx.strokeStyle = SURFACE
      // 깊이 2 라벨은 더 짧게 줄인다. 덜 중요한 정보가 충돌 면적을 키우면 손해다 —
      // 전체 제목은 tooltip 이 보여준다.
      const limit = labelMax ?? 22
      const text = truncateLabel(node.label, node.depth > 1 ? Math.min(limit, 12) : limit)
      ctx.strokeText(text, tx, ty)
      ctx.fillStyle = isHover || node.kind === 'center' ? INK : INK_SECONDARY
      ctx.fillText(text, tx, ty)
    }

    ctx.globalAlpha = 1
  }, [labelMax])

  const linkColor = useCallback((link) => {
    const hovering = hoverNodeRef.current
    if (hovering) {
      const touches =
        endpointId(link.source) === hovering.id || endpointId(link.target) === hovering.id
      return touches ? EDGE_ACTIVE : EDGE_MUTED
    }
    return EDGE
  }, [])

  return (
    <div
      ref={containerRef}
      className="relative overflow-hidden rounded-xl"
      style={{ height, background: SURFACE }}
      onMouseMove={(event) => {
        const rect = containerRef.current?.getBoundingClientRect()
        if (rect) setCursor({ x: event.clientX - rect.left, y: event.clientY - rect.top })
      }}
    >
      {/* 축 라벨. 좌우 배치가 관계의 종류를 말한다는 것을 글자로 못 박는다 —
          라벨이 없으면 그 규칙이 읽히지 않는다. */}
      {showAxisLabels && (
        <div
          className="pointer-events-none absolute inset-x-3 top-2 flex justify-between text-[11px]"
          style={{ color: AXIS_LABEL }}
        >
        </div>
      )}

      {width > 0 && (
        <ForceGraph2D
          ref={graphRef}
          width={width}
          height={height}
          graphData={graph}
          nodeId="id"
          nodeCanvasObject={nodeCanvasObject}
          nodePointerAreaPaint={(node, color, ctx) => {
            ctx.beginPath()
            ctx.arc(node.x ?? 0, node.y ?? 0, radiusOf(node) + 4, 0, 2 * Math.PI)
            ctx.fillStyle = color
            ctx.fill()
          }}
          onNodeHover={handleNodeHover}
          onNodeClick={(node) => {
            if (isInteractive(node)) onActivate?.(node)
          }}
          linkColor={linkColor}
          linkWidth={(link) => (link.kind === 'document' ? 1 : 1.4)}
          linkLineDash={(link) => (link.kind === 'document' ? [4, 4] : null)}
          backgroundColor="transparent"
          cooldownTicks={140}
          d3AlphaDecay={0.02}
          d3VelocityDecay={0.45}
          enableNodeDrag
          onEngineStop={() => {
            // 힘 시뮬레이션은 아무 곳에나 안정된다 — 한 번 화면에 맞춰 준다.
            // **한 번만** 한다. 매번 맞추면 사용자가 확대·이동한 것을 되돌려 버린다.
            if (fittedRef.current === graph) return
            fittedRef.current = graph
            if (graphRef.current) fitToView(graphRef.current, graph.nodes, width, height)
          }}
        />
      )}

      {hoverNode && (
        <div
          className="pointer-events-none absolute z-10 max-w-64 rounded-lg px-3 py-2 text-xs shadow-lg"
          style={{ left: cursor.x + 14, top: cursor.y - 8, background: 'rgba(15,23,42,0.93)', color: NODE_HALO }}
        >
          <p className="font-semibold break-words">{hoverNode.fullLabel}</p>
          <p className="mt-0.5 text-slate-300">
            {hoverNode.kind === 'center'
              ? '지금 보는 위키'
              : hoverNode.kind === 'document'
                ? '근거 원본문서'
                : hoverNode.depth > 1
                  ? '관련 위키의 관련 위키'
                  : '관련 위키'}
          </p>
          {isInteractive(hoverNode) && (
            <p className="mt-1 text-slate-400">
              {hoverNode.kind === 'wiki' ? '누르면 이 위키로 이동합니다' : '누르면 원본문서를 엽니다'}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
