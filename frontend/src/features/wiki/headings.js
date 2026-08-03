// 목차(우측 패널)와 본문(WikiMarkdown)이 같은 헤딩 id·번호를 만들도록 공유한다.
// 두 곳이 다른 규칙을 쓰면 목차 클릭이 본문과 어긋나거나 번호가 달라지므로, 반드시 함께 쓴다.
export function headingId(text) {
  const slug = String(text ?? '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^\p{L}\p{N}-]/gu, '')
  return `wiki-h-${slug}`
}

// 마크다운에서 #~### 헤딩을 문서 순서대로 뽑는다.
// 코드블록(``` 펜스) 안의 # 는 헤딩이 아니므로 건너뛴다 — react-markdown 렌더 결과와 맞춘다.
export function extractHeadings(markdown = '') {
  const headings = []
  let inFence = false
  for (const line of String(markdown).split('\n')) {
    if (/^\s*```/.test(line)) {
      inFence = !inFence
      continue
    }
    if (inFence) continue
    const match = /^(#{1,3})\s+(.+)$/.exec(line)
    if (!match) continue
    const label = match[2].replace(/[*_`]/g, '').trim()
    headings.push({ level: match[1].length, label, id: headingId(label) })
  }
  return headings
}

// A안 계층 번호: 문서 제목 격인 최상위(레벨 1, H1)에는 번호를 매기지 않고,
// 레벨 2를 최상위 번호로 본다. 구분자는 ".". 예) H2 → "1", "2";  H3 → "1.1".
// 상위 레벨이 나오면 그보다 깊은 카운터는 초기화한다.
export function numberHeadings(headings) {
  const counters = {}
  return headings.map((heading) => {
    counters[heading.level] = (counters[heading.level] ?? 0) + 1
    Object.keys(counters).forEach((level) => {
      if (Number(level) > heading.level) delete counters[level]
    })
    const parts = []
    for (let level = 2; level <= heading.level; level += 1) {
      if (counters[level] != null) parts.push(counters[level])
    }
    return { ...heading, number: parts.join('.') }
  })
}

// 본문 헤딩 렌더 시 id → 번호를 바로 찾도록 맵으로 만든다. 번호 없는 H1은 제외한다.
export function headingNumberMap(markdown = '') {
  const map = {}
  for (const heading of numberHeadings(extractHeadings(markdown))) {
    if (heading.number) map[heading.id] = heading.number
  }
  return map
}
