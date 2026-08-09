import { describe, expect, it } from 'vitest'
import { validateScheduleSourceBytes, validateWikiSourceBytes } from './validateWikiSourceFile'

const encode = (value) => new TextEncoder().encode(value)

describe('문서 업로드 파일 내용 검증(S15P11B106-323)', () => {
  it('PDF 헤더가 있는 PDF를 허용한다', () => {
    expect(validateWikiSourceBytes('pdf', encode('%PDF-1.7\ncontent'))).toBe(true)
  })

  it('PDF로 확장자만 바꾼 파일을 거부한다', () => {
    expect(validateWikiSourceBytes('pdf', encode('@echo off\necho hello'))).toBe(false)
  })

  it('DOCX ZIP 구조에 필수 엔트리가 있으면 허용한다', () => {
    const bytes = new Uint8Array([
      0x50,
      0x4b,
      0x03,
      0x04,
      ...encode('[Content_Types].xml word/document.xml'),
    ])
    expect(validateWikiSourceBytes('docx', bytes)).toBe(true)
  })

  it('일반 ZIP을 DOCX로 바꾼 파일은 거부한다', () => {
    const bytes = new Uint8Array([0x50, 0x4b, 0x03, 0x04, ...encode('memo.txt')])
    expect(validateWikiSourceBytes('docx', bytes)).toBe(false)
  })

  it('일반 UTF-8 TXT와 Markdown을 허용한다', () => {
    expect(validateWikiSourceBytes('txt', encode('회의 안내 문서입니다.'))).toBe(true)
    expect(validateWikiSourceBytes('md', encode('# 제목\n\n본문입니다.'))).toBe(true)
  })

  it('검사 구간 끝에서 한글 바이트가 잘려도 UTF-8 문서를 허용한다', () => {
    const text = `${'a'.repeat(64 * 1024 - 1)}한글`
    expect(validateWikiSourceBytes('txt', encode(text))).toBe(true)
  })

  it('실행 파일을 TXT로 바꾼 경우 거부한다', () => {
    expect(validateWikiSourceBytes('txt', new Uint8Array([0x4d, 0x5a, 0, 1, 2]))).toBe(false)
  })
})

describe('일정 업로드 파일 내용 검증(S15P11B106-323)', () => {
  it('XLSX ZIP 구조에 필수 엔트리가 있으면 허용한다', () => {
    const bytes = new Uint8Array([
      0x50,
      0x4b,
      0x03,
      0x04,
      ...encode('[Content_Types].xml xl/workbook.xml'),
    ])
    expect(validateScheduleSourceBytes('xlsx', bytes)).toBe(true)
  })

  it('DOCX를 XLSX로 바꾼 파일은 거부한다', () => {
    const bytes = new Uint8Array([
      0x50,
      0x4b,
      0x03,
      0x04,
      ...encode('[Content_Types].xml word/document.xml'),
    ])
    expect(validateScheduleSourceBytes('xlsx', bytes)).toBe(false)
  })

  it('UTF-8 CSV는 허용하고 바이너리 CSV는 거부한다', () => {
    expect(validateScheduleSourceBytes('csv', encode('title,date\n회의,2026-08-07'))).toBe(true)
    expect(validateScheduleSourceBytes('csv', new Uint8Array([0x4d, 0x5a, 0, 1]))).toBe(false)
  })

  it('따옴표 구조가 깨진 CSV는 거부한다', () => {
    expect(validateScheduleSourceBytes('csv', encode('title,date\n"회의,2026-08-07'))).toBe(false)
  })
})
