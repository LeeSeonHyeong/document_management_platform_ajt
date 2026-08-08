# Grouped AI Job Progress Modal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 여러 공개 범위로 분리된 AI 작업을 한 모달에서 문서·범위별 진행 상황으로 명확히 보여 준다.

**Architecture:** 작업 시작 응답의 `jobId`, `scopeKey`, `documentIds`와 대기 문서 메타데이터에서 표시용 범위 이름을 만든다. 폴링 훅은 작업별 응답을 보존하고, 모달은 전체 합계·현재 처리 문서·접을 수 있는 작업 묶음 요약을 순수 계산 함수로 렌더링한다.

**Tech Stack:** React 19, TanStack Query 5, Vitest

## Global Constraints

- 기존 `POST /ai-jobs` 응답만 사용하며 API·DB 계약은 바꾸지 않는다.
- 문서는 서버 실행 순서에 따라 한 건만 현재 처리 중으로 표시한다.
- 실패·취소 문서는 종료로 집계하고, 모든 작업 종료 시 완료 버튼을 보여 준다.

---

### Task 1: 작업 진행 표시 모델

**Files:**
- Create: `frontend/src/features/document/aiJobProgress.js`
- Create: `frontend/src/features/document/aiJobProgress.test.js`

- [x] **Step 1: Write the failing test**

```js
expect(buildAiJobProgress(jobs, jobRefs)).toMatchObject({
  current: { originalFileName: '개발 규칙.pdf', stageLabel: '위키 반영 중' },
  groups: [{ label: '개발부', completed: 1, total: 2, state: 'processing' }],
})
```

- [x] **Step 2: Run test to verify it fails**

Run: `npm run test -- src/features/document/aiJobProgress.test.js`

- [x] **Step 3: Write minimal implementation**

```js
export function buildAiJobProgress(jobs, jobRefs) {
  // jobId로 범위 라벨을 연결하고, 종료되지 않은 첫 문서를 current로 반환한다.
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `npm run test -- src/features/document/aiJobProgress.test.js`

### Task 2: 모달과 시작 화면 연결

**Files:**
- Modify: `frontend/src/features/document/hooks/useAiJobPolling.js`
- Modify: `frontend/src/features/document/components/AiJobProgressDialog.jsx`
- Modify: `frontend/src/features/document/pages/DocumentListPage.jsx`

- [x] **Step 1: Preserve per-job polling responses**
- [x] **Step 2: Pass scope labels from the creation response to the modal**
- [x] **Step 3: Render total, current document, and collapsed group summaries**
- [x] **Step 4: Run frontend tests, lint, and build**
