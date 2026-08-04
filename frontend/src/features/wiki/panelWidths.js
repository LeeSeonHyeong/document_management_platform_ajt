// 위키 화면 좌우 열의 폭. 사용자가 열 사이 여백을 끌어 조절하고, 그 값을 기억한다.
//
// 폭 계산을 컴포넌트 밖으로 뺀 이유: 끌기 중에는 1초에 수십 번 불리는 계산이고, "본문이
// 최소 폭보다 좁아지지 않는다" 같은 조건은 눈으로 확인하기 어려워 테스트가 필요하다.

export const TREE = { key: 'tree', min: 176, max: 420, default: 240 }
export const CHAT = { key: 'chat', min: 272, max: 560, default: 336 }

// 본문이 이보다 좁아지면 표가 찌그러지고 각주가 줄바꿈으로 깨진다(예전 300px 에서 겪었다).
export const ARTICLE_MIN = 420

const STORAGE_PREFIX = 'ajt.wiki.panelWidth.'

/**
 * 한 열의 폭을 허용 범위로 자른다.
 *
 * `available` 은 이 열과 본문이 나눠 쓸 수 있는 폭이다(다른 열과 간격을 뺀 값).
 * 주어지면 본문 최소 폭을 지키는 선까지 함께 줄인다 — 화면이 좁아 애초에 본문 최소 폭도
 * 안 나오는 상황에서는 열의 최소 폭을 존중한다(그때는 열이 서지 않는 폭이라 CSS 가 숨긴다).
 */
export function clampWidth(spec, width, available) {
  const value = Number.isFinite(width) ? width : spec.default
  let max = spec.max
  if (Number.isFinite(available)) {
    max = Math.min(max, available - ARTICLE_MIN)
  }
  if (max < spec.min) return spec.min
  return Math.round(Math.min(Math.max(value, spec.min), max))
}

/** 저장된 폭을 읽는다. 없거나 못 읽으면 기본값. */
export function loadWidth(spec) {
  try {
    const raw = globalThis.localStorage?.getItem(STORAGE_PREFIX + spec.key)
    if (raw == null) return spec.default
    const parsed = Number.parseInt(raw, 10)
    return clampWidth(spec, parsed)
  } catch {
    // 사파리 프라이빗 모드 등에서 localStorage 접근 자체가 던진다.
    return spec.default
  }
}

/** 폭을 저장한다. 실패해도 화면은 그대로 동작해야 하므로 조용히 넘긴다. */
export function saveWidth(spec, width) {
  try {
    globalThis.localStorage?.setItem(STORAGE_PREFIX + spec.key, String(Math.round(width)))
  } catch {
    /* 저장 못 해도 이번 세션 동안은 조절된 폭으로 쓴다 */
  }
}
