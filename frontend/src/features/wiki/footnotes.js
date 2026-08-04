// 본문 마크다운에서 각주 정의를 읽어 `라벨 → 근거` 로 만든다.
//
// 왜 본문을 파싱하나: 각주의 인용문은 **본문 안에만** 있다. Wiki 조회 API 의
// `evidenceDocuments` 는 `documentId`·`originalFileName`·`downloadUrl` 만 주고 인용문·위치를
// 담지 않는다(구조화된 각주는 AI 서버의 `document_references` 테이블에만 있고 MySQL 에는 없다).
// 그래서 "이 문장의 근거가 원문 어디인가" 를 화면에서 보여주려면 본문에서 되읽는 수밖에 없다.
//
// 저장된 형태 (AI 가 만든 위키, 생성분 전수 확인):
//   [^1]: 08-compensation.md, Equity — "인용문 원문 그대로"
//   [^2]: document-33, 회사 분위기 — "..."          (예전 형식)
//   [^3]: 인사규정.pdf, 12쪽
//
// 즉 `파일명, 위치 — "인용문"` 이고 위치·인용문은 없을 수 있다.

// 줄 시작의 `[^라벨]:` 만 정의로 본다. 본문 중간에서 각주 뒤에 콜론이 오는 정상적인 문장을
// 정의로 오판하지 않기 위한 것이다(AI 서버의 lint 가 같은 이유로 줄 위치를 본다).
const DEFINITION = /^\[\^([^\]]+)\]:[ \t]*(.+)$/gm

// 인용문은 마지막 따옴표까지 **그리디하게** 잡는다 — 인용문 안에 따옴표가 겹칠 수 있다
// (`"not after a "probation period" or so"` 같은 형태가 실제로 있다).
// 굽은 따옴표(“ ”)와 곧은 따옴표(")를 따로 다룬다 — 한 문자 클래스로 묶으면 여는 따옴표와
// 닫는 따옴표가 뒤바뀐 짝도 통과해 엉뚱한 구간을 잡는다.
const QUOTE = /“([\s\S]*)”|"([\s\S]*)"/

/** 정의 한 줄을 `{ source, location, quote }` 로 쪼갠다. */
export function parseFootnoteDefinition(raw) {
  const text = String(raw ?? '').trim()
  if (!text) return null

  let rest = text
  let quote = null
  const quoted = QUOTE.exec(rest)
  if (quoted) {
    quote = (quoted[1] ?? quoted[2] ?? '').trim() || null
    rest = rest.slice(0, quoted.index)
  }

  // 인용문을 떼어낸 뒤 남은 꼬리의 구분자(— , -)를 정리한다.
  rest = rest.replace(/[\s,]*[—–-]\s*$/, '').trim()

  const comma = rest.indexOf(',')
  const source = (comma >= 0 ? rest.slice(0, comma) : rest).trim() || null
  const location = comma >= 0 ? rest.slice(comma + 1).trim() || null : null

  if (!source && !location && !quote) return null
  return { source, location, quote }
}

/**
 * 본문에서 각주 정의를 모은다.
 * @param {string} markdown
 * @returns {Record<string, {source: string|null, location: string|null, quote: string|null}>}
 */
export function collectFootnotes(markdown) {
  const out = {}
  const text = String(markdown ?? '')
  DEFINITION.lastIndex = 0
  let match
  while ((match = DEFINITION.exec(text)) !== null) {
    const parsed = parseFootnoteDefinition(match[2])
    if (parsed) out[match[1]] = parsed
  }
  return out
}

// react-markdown 이 각주 참조를 `href="#user-content-fn-<라벨>"` 로 만든다.
const FOOTNOTE_HREF = /#user-content-fn-(.+)$/

/** 각주 참조 링크의 href 에서 라벨을 뽑는다. 각주가 아니면 `null`. */
export function footnoteLabelFromHref(href) {
  const match = FOOTNOTE_HREF.exec(String(href ?? ''))
  return match ? decodeURIComponent(match[1]) : null
}
