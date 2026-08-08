# Queued Document Deletion Implementation Plan

**Goal:** 같은 범위의 여러 원본문서 삭제를 충돌 없이 대기 작업으로 접수한다.

**Architecture:** 삭제 대상 문서만 진행 상태와 활성 작업 여부를 검사한다. 다른 문서의 `DELETING` 상태는 기존 전역 직렬 작업 실행기가 순서대로 처리하므로 삭제 접수를 막지 않는다.

## Task 1: 같은 범위 삭제 요청 접수

**Files:**
- Modify: `backend/src/test/java/com/ajt/backend/domain/document/service/DocumentManagementServiceTest.java`
- Modify: `backend/src/main/java/com/ajt/backend/domain/document/service/DocumentManagementService.java`

- [ ] 기존 범위 차단 테스트를 두 번째 삭제가 별도 `WAITING` 작업을 반환하는 실패 테스트로 바꾼다.
- [ ] `delete()`에서 다른 문서의 진행 상태를 검사하는 `ensureScopeNotProcessing(scopeKey)` 호출을 제거한다.
- [ ] 삭제 대상 자체의 `ensureNotInProgress(document)` 검증은 유지한다.
- [ ] `./gradlew test --tests com.ajt.backend.domain.document.service.DocumentManagementServiceTest`를 실행한다.
