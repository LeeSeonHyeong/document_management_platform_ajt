import { describe, expect, it } from 'vitest'
import ExcelJS from 'exceljs'
import { MAX_ROWS, cellText, parseSheet } from './sheet'

async function xlsxBlob(build) {
  const workbook = new ExcelJS.Workbook()
  build(workbook)
  const buffer = await workbook.xlsx.writeBuffer()
  return new Blob([buffer], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
}

describe('parseSheet — csv', () => {
  it('행·열을 표로 만든다', async () => {
    const blob = new Blob(['일정명,시작일\n전사 워크샵,2026-09-01\n'], { type: 'text/csv' })
    const sheets = await parseSheet(blob, '일정.csv')

    expect(sheets).toHaveLength(1)
    expect(sheets[0].rows).toEqual([
      ['일정명', '시작일'],
      ['전사 워크샵', '2026-09-01'],
    ])
    expect(sheets[0].totalRows).toBe(2)
  })

  it('상한을 넘는 행은 자르고 전체 건수는 남긴다', async () => {
    const rows = Array.from({ length: MAX_ROWS + 20 }, (_, index) => `행${index},값`).join('\n')
    const sheets = await parseSheet(new Blob([rows], { type: 'text/csv' }), '큰파일.csv')

    expect(sheets[0].rows).toHaveLength(MAX_ROWS)
    expect(sheets[0].totalRows).toBe(MAX_ROWS + 20)
  })
})

describe('parseSheet — xlsx', () => {
  it('시트별로 이름과 행을 돌려준다', async () => {
    const blob = await xlsxBlob((workbook) => {
      const first = workbook.addWorksheet('일정')
      first.addRow(['일정명', '담당부서'])
      first.addRow(['전사 워크샵', '경영지원'])
      workbook.addWorksheet('비고').addRow(['메모'])
    })

    const sheets = await parseSheet(blob, '일정.xlsx')

    expect(sheets.map((sheet) => sheet.name)).toEqual(['일정', '비고'])
    expect(sheets[0].rows).toEqual([
      ['일정명', '담당부서'],
      ['전사 워크샵', '경영지원'],
    ])
  })

  it('확장자가 csv 가 아니면 워크북으로 읽는다', async () => {
    const blob = await xlsxBlob((workbook) => workbook.addWorksheet('Sheet1').addRow(['값']))
    await expect(parseSheet(blob, '이름없는파일.xls')).resolves.toHaveLength(1)
  })
})

describe('cellText', () => {
  it('exceljs 의 객체형 셀 값을 문자열로 편다', () => {
    expect(cellText({ richText: [{ text: '보' }, { text: '고' }] })).toBe('보고')
    expect(cellText({ formula: 'SUM(A1:A2)', result: 300 })).toBe('300')
    expect(cellText({ text: '링크', hyperlink: 'https://ajt.com' })).toBe('링크')
    expect(cellText({ hyperlink: 'https://ajt.com' })).toBe('https://ajt.com')
    expect(cellText(new Date('2026-09-01T00:00:00Z'))).toBe('2026-09-01')
    expect(cellText(null)).toBe('')
    expect(cellText(0)).toBe('0')
  })
})
