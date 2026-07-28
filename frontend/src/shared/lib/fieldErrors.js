// 백엔드 공통 오류 envelope의 fieldErrors[]를 react-hook-form에 매핑한다.
// fieldErrors 형태(계약): [{ field: 'email', message: '이미 사용 중인 이메일입니다.' }, ...]
//
// 사용 예:
//   try { await mutateAsync(values) }
//   catch (err) { applyFieldErrors(err, form.setError, { fallbackField: 'root' }) }

// 서버 필드명(snake/camel 혼용 가능)을 폼 필드명으로 정규화하는 기본 규칙.
function toCamel(field) {
  return field.replace(/_([a-z])/g, (_, c) => c.toUpperCase())
}

/**
 * @param normalizedError client.js normalizeError 결과 { status, code, message, fieldErrors }
 * @param setError react-hook-form setError
 * @param options.fieldMap 서버필드→폼필드 커스텀 매핑
 * @param options.fallbackField 매핑 실패 시 오류를 붙일 폼 필드(기본 'root')
 * @returns 매핑된 필드가 하나라도 있으면 true
 */
export function applyFieldErrors(normalizedError, setError, options = {}) {
  const { fieldMap = {}, fallbackField = 'root' } = options
  const fieldErrors = normalizedError?.fieldErrors ?? []

  if (fieldErrors.length === 0) {
    // 필드 단위 오류가 없으면 전체 메시지를 fallback 필드에 표시한다.
    if (normalizedError?.message) {
      setError(fallbackField, { type: 'server', message: normalizedError.message })
    }
    return false
  }

  let mapped = false
  for (const item of fieldErrors) {
    const target = fieldMap[item.field] ?? toCamel(item.field ?? '')
    if (!target) continue
    setError(target, { type: 'server', message: item.message })
    mapped = true
  }
  return mapped
}
