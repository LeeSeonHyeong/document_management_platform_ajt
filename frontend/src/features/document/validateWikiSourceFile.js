import Papa from 'papaparse'

const PDF_HEADER = new TextEncoder().encode('%PDF-')
const DOCX_CONTENT_TYPES = new TextEncoder().encode('[Content_Types].xml')
const DOCX_DOCUMENT = new TextEncoder().encode('word/document.xml')
const XLSX_WORKBOOK = new TextEncoder().encode('xl/workbook.xml')
const TEXT_SAMPLE_SIZE = 64 * 1024

function includesBytes(bytes, signature, limit = bytes.length) {
  const lastStart = Math.min(bytes.length, limit) - signature.length
  for (let start = 0; start <= lastStart; start += 1) {
    if (signature.every((value, index) => bytes[start + index] === value)) return true
  }
  return false
}

function isZip(bytes) {
  return (
    bytes[0] === 0x50 &&
    bytes[1] === 0x4b &&
    ((bytes[2] === 0x03 && bytes[3] === 0x04) ||
      (bytes[2] === 0x05 && bytes[3] === 0x06) ||
      (bytes[2] === 0x07 && bytes[3] === 0x08))
  )
}

function isBinary(bytes) {
  if (bytes[0] === 0x4d && bytes[1] === 0x5a) return true // Windows PE
  if (bytes[0] === 0x7f && bytes[1] === 0x45 && bytes[2] === 0x4c && bytes[3] === 0x46) return true

  let controlCount = 0
  for (const byte of bytes) {
    if (byte === 0) return true
    if (byte < 0x20 && byte !== 0x09 && byte !== 0x0a && byte !== 0x0c && byte !== 0x0d) {
      controlCount += 1
    }
  }
  return bytes.length > 0 && controlCount / bytes.length > 0.01
}

function decodeUtf8Text(bytes) {
  const sample = bytes.subarray(0, TEXT_SAMPLE_SIZE)
  if (isBinary(sample)) return null
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return null
  }
}

export function validateWikiSourceBytes(extension, bytes) {
  if (extension === 'pdf') {
    return includesBytes(bytes, PDF_HEADER, 1024)
  }

  if (extension === 'docx') {
    return isZip(bytes) && includesBytes(bytes, DOCX_CONTENT_TYPES) && includesBytes(bytes, DOCX_DOCUMENT)
  }

  if (extension === 'txt' || extension === 'md') {
    return decodeUtf8Text(bytes) !== null
  }

  return false
}

export function validateScheduleSourceBytes(extension, bytes) {
  if (extension === 'xlsx') {
    return isZip(bytes) && includesBytes(bytes, DOCX_CONTENT_TYPES) && includesBytes(bytes, XLSX_WORKBOOK)
  }
  if (extension === 'csv') {
    const text = decodeUtf8Text(bytes)
    if (text === null) return false
    const result = Papa.parse(text, { skipEmptyLines: true })
    return !result.errors.some((error) => error.type === 'Quotes')
  }
  return validateWikiSourceBytes(extension, bytes)
}

export async function validateWikiSourceFile(file, extension) {
  const bytes = new Uint8Array(await file.arrayBuffer())
  return validateWikiSourceBytes(extension, bytes)
}

export async function validateScheduleSourceFile(file, extension) {
  const bytes = new Uint8Array(await file.arrayBuffer())
  return validateScheduleSourceBytes(extension, bytes)
}
