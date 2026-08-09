# AI Progress Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 완료 알림 지연을 모달 폴링 주기에 맞추고, 일반 문서 처리 모달에 실제 처리 단계가 보이게 한다.

**Architecture:** 세션에서 시작한 작업 ID를 알림 컴포넌트가 직접 추적하고 2초 작업 조회를 재사용한다. 모달은 현재 문서의 서버 단계(`parsing`, `wiki_transform`, `wiki_applied`)를 세 개의 사용자용 단계로 변환한다.

**Tech Stack:** React 19, TanStack Query 5, Vitest

## Global Constraints

- 일반 문서 단계명은 `원본 문서 분석 → 위키 변경안 생성 → 위키 반영`으로 고정한다.
- 삭제 모달의 `연결된 위키 확인 → 위키 내용 정리 → 원본 파일 삭제` 흐름은 바꾸지 않는다.
- 알림은 현재 세션에서 시작한 작업에만 표시한다.

---

### Task 1: 현재 문서 단계 모델

**Files:**
- Modify: `frontend/src/features/document/aiJobProgress.js`
- Modify: `frontend/src/features/document/aiJobProgress.test.js`

- [ ] Write a failing test that maps `wiki_transform` to the second user-facing step and marks parsing as complete.
- [ ] Implement `documentStagesFor(result)` with the three approved labels.
- [ ] Verify `npm run test -- src/features/document/aiJobProgress.test.js`.

### Task 2: Immediate completion notification

**Files:**
- Modify: `frontend/src/features/document/aiJobNotifications.js`
- Modify: `frontend/src/features/document/AiJobCompletionNotifier.jsx`
- Modify: `frontend/src/features/document/aiJobNotifications.test.js`

- [ ] Write a failing test for the tracked job ID change signal.
- [ ] Make the notifier poll tracked job details at the shared 2-second interval.
- [ ] Verify notification tests.

### Task 3: General document progress modal

**Files:**
- Modify: `frontend/src/features/document/components/AiJobProgressDialog.jsx`

- [ ] Replace the oversized current-file card with a compact file header and three-stage progress panel.
- [ ] Keep scope-group disclosure and completion/background controls.
- [ ] Verify all frontend tests, lint, and build.
