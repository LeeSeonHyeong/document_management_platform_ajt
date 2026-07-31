# Wiki Document Upload Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Wiki document card's browser-only preview flow with the existing multipart upload API and navigate with the real server `jobId`.

**Architecture:** Keep the backend and public API contract unchanged. A focused upload modal owns file validation, shared scope/category selection, transfer progress, and `useUploadDocuments`; `DocumentListPage` only opens the modal and routes the successful response. Small pure helpers define validation and success-route behavior so the regression can be tested without adding a browser test framework.

**Tech Stack:** React 19, TanStack Query 5, axios, react-hook-form, zod, Vitest, Vite 8

## Global Constraints

- Wiki upload supports TXT, MD, PDF, and DOCX only.
- One request contains 1-20 files, at most 20MB each and 100MB total.
- One upload request applies one `documentCategoryId`, `visibilityType`, and `departmentIds` set to every file.
- Use the existing `POST /api/v1/documents` contract and its `202` response.
- Do not add a temporary upload endpoint or a separate AI start endpoint.
- Do not route schedule files through the Wiki document API.
- Do not store preview-only Wiki documents or summaries in `sessionStorage`.

---

### Task 1: Upload contract helpers and regression tests

**Files:**
- Create: `frontend/src/features/document/uploadFlow.js`
- Create: `frontend/src/features/document/uploadFlow.test.js`

**Interfaces:**
- Produces: `WIKI_UPLOAD_LIMIT`
- Produces: `validateWikiFile(file): string | null`
- Produces: `validateWikiUpload(files): string | null`
- Produces: `buildWikiUploadPayload(files, metadata, onUploadProgress): UploadPayload`
- Produces: `wikiUploadProgressPath(result): string`

- [ ] **Step 1: Write failing validation and routing tests**

```js
import { describe, expect, it } from 'vitest'
import {
  buildWikiUploadPayload,
  validateWikiFile,
  validateWikiUpload,
  wikiUploadProgressPath,
} from './uploadFlow'

describe('Wiki 원본문서 업로드 흐름', () => {
  it('지원 형식과 파일당 20MB 제한을 검증한다', () => {
    expect(validateWikiFile({ name: 'policy.pdf', size: 1024 })).toBeNull()
    expect(validateWikiFile({ name: 'sheet.xlsx', size: 1024 })).toContain('지원하지 않는 형식')
    expect(validateWikiFile({ name: 'large.pdf', size: 20 * 1024 * 1024 + 1 })).toContain('20.0MB')
  })

  it('최대 20건과 총 100MB를 검증한다', () => {
    expect(validateWikiUpload([])).toContain('1개 이상')
    expect(validateWikiUpload(Array.from({ length: 21 }, (_, i) => ({ name: `${i}.md`, size: 1 })))).toContain('20건')
    expect(validateWikiUpload([
      { name: 'a.pdf', size: 60 * 1024 * 1024 },
      { name: 'b.pdf', size: 41 * 1024 * 1024 },
    ])).toContain('100.0MB')
  })

  it('실제 API mutation payload를 만든다', () => {
    const files = [{ name: 'policy.pdf', size: 1024 }]
    const progress = () => {}
    expect(buildWikiUploadPayload(files, {
      documentCategoryId: '3',
      visibilityType: 'department',
      departmentIds: ['2', '1'],
    }, progress)).toEqual({
      files,
      documentCategoryId: '3',
      visibilityType: 'department',
      departmentIds: ['2', '1'],
      onUploadProgress: progress,
    })
  })

  it('서버 jobId로 실제 진행 라우트를 만든다', () => {
    expect(wikiUploadProgressPath({ jobId: '17' })).toBe('/admin/documents/jobs/17/progress')
    expect(() => wikiUploadProgressPath({})).toThrow('jobId')
  })
})
```

- [ ] **Step 2: Run the focused test and verify it fails because the module does not exist**

Run: `cd frontend && npm run test -- src/features/document/uploadFlow.test.js`

Expected: FAIL resolving `./uploadFlow`.

- [ ] **Step 3: Implement the pure helpers**

```js
export const WIKI_UPLOAD_LIMIT = {
  extensions: ['txt', 'md', 'pdf', 'docx'],
  maxFileBytes: 20 * 1024 * 1024,
  maxCount: 20,
  maxTotalBytes: 100 * 1024 * 1024,
}

export function validateWikiFile(file) {
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!extension || !WIKI_UPLOAD_LIMIT.extensions.includes(extension)) {
    return `지원하지 않는 형식입니다 (${WIKI_UPLOAD_LIMIT.extensions.join(', ')}만 허용)`
  }
  if (file.size > WIKI_UPLOAD_LIMIT.maxFileBytes) {
    return '파일당 최대 20.0MB까지 가능합니다'
  }
  return null
}

export function validateWikiUpload(files) {
  if (files.length === 0) return '파일을 1개 이상 선택하세요'
  if (files.length > WIKI_UPLOAD_LIMIT.maxCount) return '한 번에 최대 20건까지 업로드할 수 있습니다'
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0)
  if (totalBytes > WIKI_UPLOAD_LIMIT.maxTotalBytes) return '전체 용량은 최대 100.0MB까지 가능합니다'
  return null
}

export function buildWikiUploadPayload(files, metadata, onUploadProgress) {
  return { files, ...metadata, onUploadProgress }
}

export function wikiUploadProgressPath(result) {
  if (!result?.jobId) throw new Error('업로드 응답에 jobId가 없습니다.')
  return `/admin/documents/jobs/${result.jobId}/progress`
}
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `cd frontend && npm run test -- src/features/document/uploadFlow.test.js`

Expected: 4 tests passed.

- [ ] **Step 5: Commit the helper and regression tests**

```bash
git add frontend/src/features/document/uploadFlow.js frontend/src/features/document/uploadFlow.test.js
git commit -m "test(document): 원본문서 업로드 연결 회귀 검증"
```

### Task 2: Restore the real Wiki upload modal

**Files:**
- Create: `frontend/src/features/document/components/DocumentUploadModal.jsx`
- Modify: `frontend/src/features/document/uploadFlow.js`

**Interfaces:**
- Consumes: `useUploadDocuments()`
- Consumes: `useDocumentCategories(scopeKey)`
- Consumes: `buildScopeKey(visibilityType, departmentIds)`
- Consumes: Task 1 validation and payload helpers
- Produces: `DocumentUploadModal({ open, onClose, onUploaded })`

- [ ] **Step 1: Create the modal with real mutation state**

Implement a modal that:

```jsx
const uploadMutation = useUploadDocuments()
const uploading = uploadMutation.isPending

function onSubmit(values) {
  uploadMutation.mutate(
    buildWikiUploadPayload(
      validFiles.map((entry) => entry.file),
      {
        documentCategoryId: values.documentCategoryId,
        visibilityType: values.visibilityType,
        departmentIds: values.departmentIds,
      },
      handleUploadProgress,
    ),
    {
      onSuccess: (data) => {
        onUploaded?.(data)
        onClose?.()
      },
    },
  )
}
```

The modal must:

- preserve selected files and metadata on HTTP failure;
- prevent close while `uploading`;
- show individual extension/size errors;
- show aggregate count/total-size errors;
- derive `scopeKey` with `buildScopeKey`;
- reset category when `scopeKey` changes;
- select categories only from `useDocumentCategories(scopeKey)`;
- use actual axios `loaded` and `total` values for progress.

- [ ] **Step 2: Run lint and focused tests**

Run: `cd frontend && npm run lint`

Expected: exit 0.

Run: `cd frontend && npm run test -- src/features/document/uploadFlow.test.js`

Expected: 4 tests passed.

- [ ] **Step 3: Commit the modal**

```bash
git add frontend/src/features/document/components/DocumentUploadModal.jsx
git commit -m "fix(document): 실제 원본문서 업로드 모달 복구"
```

### Task 3: Wire `DocumentListPage` to the server response

**Files:**
- Modify: `frontend/src/features/document/pages/DocumentListPage.jsx`
- Test: `frontend/src/features/document/uploadFlow.test.js`

**Interfaces:**
- Consumes: `DocumentUploadModal`
- Consumes: `wikiUploadProgressPath(result)`
- Preserves: existing schedule preview behavior without sending schedule files to `/documents`

- [ ] **Step 1: Replace the Wiki card preview state with modal state**

Remove `previewUploadFiles` and the document card's `createPreviewDocuments` callback.

Add:

```jsx
const [uploadOpen, setUploadOpen] = useState(false)

<UploadCard
  tone="document"
  icon={FileText}
  title="문서 파일"
  description="일반 문서, 규정, 안내문 등 다양한 문서를 업로드하세요."
  extensions={['TXT', 'MD', 'PDF', 'DOCX']}
  onClick={() => setUploadOpen(true)}
/>
```

- [ ] **Step 2: Route the real upload response**

Add:

```jsx
<DocumentUploadModal
  open={uploadOpen}
  onClose={() => setUploadOpen(false)}
  onUploaded={(result) => {
    setUploadOpen(false)
    navigate(wikiUploadProgressPath(result))
  }}
/>
```

Do not create preview Wiki documents, summaries, or fake completed statuses in this callback.

- [ ] **Step 3: Verify source-level regression markers**

Run:

```bash
rg -n "DocumentUploadModal|wikiUploadProgressPath" frontend/src/features/document/pages/DocumentListPage.jsx
rg -n "createPreviewDocuments" frontend/src/features/document/pages/DocumentListPage.jsx
```

Expected:

- the first command finds the import and rendered modal;
- the second command only finds the schedule-preview callback/helper, not the Wiki document card.

- [ ] **Step 4: Run the complete frontend verification**

Run: `cd frontend && npm run lint`

Expected: exit 0.

Run: `cd frontend && npm run test`

Expected: all test files pass.

Run: `cd frontend && npm run build`

Expected: production build succeeds; the existing chunk-size warning is allowed.

- [ ] **Step 5: Commit the page wiring**

```bash
git add frontend/src/features/document/pages/DocumentListPage.jsx
git commit -m "fix(document): 원본문서 업로드 API 연결 복구"
```

### Task 4: Branch verification and server handoff

**Files:**
- Verify only

**Interfaces:**
- Produces: pushed branch `fix/S15P11B106-65-document-upload-wiring`
- Produces: server-test instructions for the 8090 `ajt-develop` project

- [ ] **Step 1: Inspect final branch scope**

Run:

```bash
git status --short
git diff --check origin/develop...HEAD
git diff --stat origin/develop...HEAD
git log --oneline origin/develop..HEAD
```

Expected: clean worktree; only design, plan, upload helper/tests, modal, and page wiring are changed.

- [ ] **Step 2: Run final verification again**

Run: `cd frontend && npm run lint && npm run test && npm run build`

Expected: lint exit 0, all tests pass, build succeeds.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin fix/S15P11B106-65-document-upload-wiring
```

- [ ] **Step 4: Switch and rebuild on the server**

After push, use the server runbook supplied in the handoff to:

- fetch and switch to the remote fix branch;
- build `ajt-backend:<12-char SHA>` and `ajt-frontend:<12-char SHA>`;
- recreate `ajt-develop` with `.env.develop` on port 8090;
- verify `/api/v1/health` returns 200;
- upload a Wiki document through 8090 and verify the Network POST, DB rows, and named-volume file.
