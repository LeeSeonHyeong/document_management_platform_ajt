// 미리보기용이라 전체를 다 읽지 않는다. 일정 업로드용 시트는 행이 수천 개까지 가므로
// 앞쪽 일부만 표로 만들고 나머지는 건수만 알려 준다.
export const MAX_ROWS = 200
export const MAX_COLUMNS = 30

export function cellText(value) {
  if (value == null) return ''
  if (value instanceof Date) return value.toISOString().slice(0, 10)
  // exceljs 셀 값은 원시값 외에 수식/리치텍스트/하이퍼링크 객체로도 온다.
  if (typeof value === 'object') {
    if (Array.isArray(value.richText)) return value.richText.map((run) => run.text).join('')
    if (value.text != null) return String(value.text)
    if (value.result != null) return String(value.result)
    if (value.hyperlink) return String(value.hyperlink)
    return ''
  }
  return String(value)
}

/** @returns {Promise<Array<{name: string, rows: string[][], totalRows: number}>>} */
export async function parseCsv(blob) {
  const [{ default: Papa }, { decodeText }] = await Promise.all([
    import('papaparse'),
    import('./text'),
  ])
  const { data } = Papa.parse(await decodeText(blob), { skipEmptyLines: true })
  return [
    {
      name: 'CSV',
      rows: data.slice(0, MAX_ROWS).map((row) => row.slice(0, MAX_COLUMNS).map(cellText)),
      totalRows: data.length,
    },
  ]
}

/** @returns {Promise<Array<{name: string, rows: string[][], totalRows: number}>>} */
export async function parseWorkbook(blob) {
  const { default: ExcelJS } = await import('exceljs')
  const workbook = new ExcelJS.Workbook()
  await workbook.xlsx.load(await blob.arrayBuffer())

  return workbook.worksheets.map((worksheet) => {
    const rows = []
    worksheet.eachRow({ includeEmpty: false }, (row, rowNumber) => {
      if (rowNumber > MAX_ROWS) return
      // exceljs row.values 는 1-based 라 앞의 빈 칸을 버린다.
      const values = Array.isArray(row.values) ? row.values.slice(1) : []
      rows.push(values.slice(0, MAX_COLUMNS).map(cellText))
    })
    return { name: worksheet.name, rows, totalRows: worksheet.rowCount }
  })
}

export function parseSheet(blob, fileName) {
  return fileName?.toLowerCase().endsWith('.csv') ? parseCsv(blob) : parseWorkbook(blob)
}
