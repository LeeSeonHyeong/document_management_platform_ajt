// 관계 그래프의 색. 컴포넌트에서 갈라 둔 이유는 둘이다 — Fast Refresh(컴포넌트 파일이
// 컴포넌트 외의 것을 export 하면 갱신이 깨진다), 그리고 캔버스가 `var(--x)` 를 읽지 못해
// 값으로 들고 있어야 한다는 점.
//
// **색조로 종류를 구분하지 않는다.** 이 앱은 rose(오류)·emerald(성공)·amber(경고)를 상태
// 색으로 쓰고 있어서, 종류를 색조로 가르려면 그 어휘를 침범하게 된다 — 실제로 원본문서에
// amber 를 썼다가 「경고」로 읽히는 문제가 있었다. 그래서 브랜드 인디고 한 색조로 통일하고,
// 종류는 **모양과 채움**으로, 색은 **상태(호버·활성)** 에만 쓴다.
//
//   관련 위키   작은 채운 원          — 눌러서 갈 수 있는 것
//   원본문서    테두리 원 + 문서 아이콘 — 근거이지 이동 대상이 아니다 (자연히 뒤로 물러난다)
//   중심 위키   진한 큰 원 + 위키 아이콘 — 지금 보고 있는 것
//
// 사이드바 최상단의 blue-600을 중심으로, 그래프도 같은 블루 계열로 맞춘다.

export const CENTER_FILL = '#2563eb' // blue-600 — 사이드바 시작 색, 현재 위키의 기준점
export const WIKI_FILL = '#3b82f6' // blue-500 — 현재 위키와 같은 계열로 묶는다
export const WIKI_STROKE = '#93c5fd' // blue-300 — 흰 캔버스에서도 원이 사라지지 않게 한다
export const WIKI_ACTIVE = '#1d4ed8' // blue-700 — 호버·활성에서만 더 진하게 쓴다
export const DOCUMENT_FILL = '#ffffff' // 흰 표면 — 문서는 재질감으로만 분리한다
export const DOCUMENT_STROKE = '#3b82f6' // blue-500 — 문서 아이콘
export const DOCUMENT_SHADOW = 'rgba(37, 99, 235, 0.24)' // blue-600 기의 그림자
export const NODE_HALO = '#ffffff' // 노드를 배경에서 띄우는 테두리
export const SURFACE = '#ffffff' // 카드 배경 (사이트 카드가 흰색이다)

export const EDGE = 'rgba(100,116,139,0.30)' // slate-500 옅게 — 뒤로 물러나야 한다
export const EDGE_ACTIVE = 'rgba(79,70,229,0.80)' // 호버한 연결만 앞으로
export const EDGE_MUTED = 'rgba(148,163,184,0.10)'

export const INK = '#0f172a' // slate-900
export const INK_SECONDARY = '#475569' // slate-600
export const AXIS_LABEL = '#94a3b8' // slate-400 — 축 라벨은 배경에 가깝게

// 호버하지 않은 것을 얼마나 물릴지.
export const FADED = 0.12

/** 모든 노드는 원이다. 종류는 채움과 아이콘으로 구분한다. */
export function nodeShape() {
  return 'circle'
}

/** 위키와 근거 문서에 아이콘을 넣고, 크기·색으로 중심 위키의 위계를 만든다. */
export function showsNodeIcon(kind) {
  return kind === 'center' || kind === 'wiki' || kind === 'document'
}

/** 노드 채움. 문서는 흰 표면과 그림자로 근거라는 역할을 분리한다. */
export function fillOf(kind) {
  if (kind === 'center') return CENTER_FILL
  if (kind === 'document') return DOCUMENT_FILL
  return WIKI_FILL
}

/** 노드 테두리. 원본문서만 색 테두리를 갖고, 나머지는 배경색으로 띄운다. */
export function strokeOf(kind) {
  if (kind === 'document') return 'transparent'
  if (kind === 'wiki') return WIKI_STROKE
  return NODE_HALO
}

/** 강조 링·라벨에 쓰는 색. 원본문서는 테두리 색을 그대로 쓴다. */
export function accentOf(kind) {
  if (kind === 'center') return CENTER_FILL
  if (kind === 'document') return DOCUMENT_STROKE
  return WIKI_ACTIVE
}
