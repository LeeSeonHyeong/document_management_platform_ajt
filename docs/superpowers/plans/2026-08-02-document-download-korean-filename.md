# 원본문서 한글 다운로드 파일명 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원본문서 다운로드 시 `Content-Disposition`의 UTF-8 `filename*`을 우선 사용해 한글 원본 파일명을 보존한다.

**Architecture:** 기존 Blob 다운로드 흐름은 유지하고 `frontend/src/features/document/api.js`의 파일명 파서를 순수 함수로 테스트한다. 파서는 RFC 5987 `filename*`을 먼저 디코딩하고, 없을 때만 일반 `filename`을 사용하며, 잘못된 UTF-8 퍼센트 인코딩은 `null`로 처리해 상세 화면의 기존 `originalFileName` 폴백으로 넘긴다.

**Tech Stack:** React 19, Vite 8, Axios, Vitest 4

## Global Constraints

- 백엔드 응답과 API 계약은 변경하지 않는다.
- 원본문서 다운로드 외 다른 기능을 리팩터링하지 않는다.
- 파싱 실패 시 다운로드 요청 자체를 실패시키지 않는다.

---

### Task 1: Content-Disposition 파일명 파서 회귀 테스트와 수정

**Files:**
- Create: `frontend/src/features/document/api.test.js`
- Modify: `frontend/src/features/document/api.js:59-69`

**Interfaces:**
- Consumes: Axios 응답 헤더 객체의 `content-disposition` 문자열
- Produces: `parseContentDispositionFileName(headers): string | null`

- [ ] **Step 1: UTF-8 우선순위와 폴백을 고정하는 실패 테스트 작성**

```js
import { describe, expect, it } from 'vitest'
import { parseContentDispositionFileName } from './api'

describe('parseContentDispositionFileName', () => {
  it('filename보다 UTF-8 filename*을 우선한다', () => {
    const headers = {
      'content-disposition':
        'attachment; filename="15_ AI ____ __ __.pdf"; filename*=UTF-8\'\'15%EA%B8%B0%20AI%20%EC%8B%A4%EC%8A%B5%ED%8A%B9%EA%B0%95%20%EC%82%AC%EC%A0%84%20%EC%84%B8%ED%8C%85.pdf',
    }

    expect(parseContentDispositionFileName(headers)).toBe('15기 AI 실습특강 사전 세팅.pdf')
  })

  it('filename*이 없으면 일반 filename을 사용한다', () => {
    expect(
      parseContentDispositionFileName({
        'content-disposition': 'attachment; filename="rules.pdf"',
      }),
    ).toBe('rules.pdf')
  })

  it('잘못된 filename* 인코딩은 null로 처리한다', () => {
    expect(
      parseContentDispositionFileName({
        'content-disposition': "attachment; filename*=UTF-8''%E0%A4%A",
      }),
    ).toBeNull()
  })

  it('Content-Disposition이 없으면 null을 반환한다', () => {
    expect(parseContentDispositionFileName({})).toBeNull()
  })
})
```

- [ ] **Step 2: 테스트를 실행해 현재 구현이 ASCII fallback을 선택하는지 확인**

Run: `npm test -- src/features/document/api.test.js`

Expected: 첫 번째 테스트가 `15_ AI ____ __ __.pdf`를 반환해 FAIL한다. 잘못된 인코딩 테스트는 `URIError` 때문에 FAIL한다.

- [ ] **Step 3: UTF-8 우선 파서를 최소 구현**

```js
export function parseContentDispositionFileName(headers) {
  const disposition = headers?.['content-disposition'] ?? ''
  const utf8Match = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition)
  if (utf8Match) {
    try {
      return decodeURIComponent(utf8Match[1].trim())
    } catch {
      return null
    }
  }

  const quotedMatch = /filename\s*=\s*"([^"]+)"/i.exec(disposition)
  if (quotedMatch) return quotedMatch[1]

  const plainMatch = /filename\s*=\s*([^;]+)/i.exec(disposition)
  return plainMatch?.[1]?.trim() || null
}
```

- [ ] **Step 4: 대상 테스트를 다시 실행해 통과 확인**

Run: `npm test -- src/features/document/api.test.js`

Expected: 4 tests PASS.

- [ ] **Step 5: 구현 파일과 테스트 파일 커밋**

```bash
git add frontend/src/features/document/api.js frontend/src/features/document/api.test.js
git commit -m "fix(document): 한글 다운로드 파일명 보존"
```

### Task 2: 프론트 전체 검증과 MR 준비

**Files:**
- Verify: `frontend/src/features/document/api.js`
- Verify: `frontend/src/features/document/api.test.js`

**Interfaces:**
- Consumes: Task 1의 파일명 파서와 회귀 테스트
- Produces: 린트·테스트·프로덕션 빌드가 통과한 MR 브랜치

- [ ] **Step 1: 프론트 전체 테스트 실행**

Run: `npm test`

Expected: 모든 Vitest 테스트 PASS.

- [ ] **Step 2: 프론트 린트 실행**

Run: `npm run lint`

Expected: 오류 없이 종료 코드 0.

- [ ] **Step 3: 프론트 프로덕션 빌드 실행**

Run: `npm run build`

Expected: Vite 빌드 성공과 종료 코드 0.

- [ ] **Step 4: 변경 범위 확인**

Run: `git diff --check develop...HEAD && git status --short`

Expected: 공백 오류가 없고 계획·설계·파서·테스트 외 변경이 없다.

- [ ] **Step 5: 원격 브랜치 푸시와 develop 대상 MR 생성**

```bash
git push -u origin codex/fix-document-download-korean-filename
```

MR 제목은 `fix(document): 원본문서 한글 다운로드 파일명 보존`, 대상 브랜치는 `develop`로 생성한다.

