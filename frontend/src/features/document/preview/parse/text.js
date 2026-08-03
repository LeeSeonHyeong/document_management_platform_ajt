export const MAX_TEXT_CHARS = 200_000

// 사내 문서는 메모장에서 저장한 CP949(EUC-KR) txt 가 섞여 온다.
// UTF-8 로 먼저 엄격 디코드하고, 실패하면 EUC-KR 로 다시 시도한다.
export async function decodeText(blob) {
  const buffer = await blob.arrayBuffer()
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buffer)
  } catch {
    return new TextDecoder('euc-kr').decode(buffer)
  }
}

export async function readPreviewText(blob) {
  return (await decodeText(blob)).slice(0, MAX_TEXT_CHARS)
}
