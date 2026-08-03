// MSW 모드에서 PDF 미리보기를 실제로 렌더해 보기 위한 최소 PDF 생성기.
// 목 데이터에 바이너리 파일을 커밋하지 않기 위해 런타임에 조립한다.
// (내장 폰트가 없는 최소 PDF라 본문은 ASCII만 쓴다. 한글 PDF 는 실제 백엔드 파일로 확인해야 한다.)

function assemble(objects) {
  const header = '%PDF-1.4\n'
  let body = ''
  const offsets = []

  objects.forEach((content, index) => {
    offsets.push(header.length + body.length)
    body += `${index + 1} 0 obj\n${content}\nendobj\n`
  })

  const xrefOffset = header.length + body.length
  let xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  offsets.forEach((offset) => {
    xref += `${String(offset).padStart(10, '0')} 00000 n \n`
  })

  const trailer = `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`
  return header + body + xref + trailer
}

function textStream(lines) {
  const content = `BT /F1 16 Tf 60 760 Td 20 TL\n${lines
    .map((line) => `(${line}) Tj T*`)
    .join('\n')}\nET`
  return `<< /Length ${content.length} >>\nstream\n${content}\nendstream`
}

// 2페이지짜리 문서. 페이지 넘김 UI 까지 확인할 수 있다.
export function createSamplePdfBlob(documentId) {
  const pdf = assemble([
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    textStream(['Mock PDF - page 1', `document id: ${documentId ?? '-'}`, '', 'MSW mock document.']),
    textStream(['Mock PDF - page 2', 'Use the arrows to page through.']),
  ])

  return new Blob([pdf], { type: 'application/pdf' })
}
