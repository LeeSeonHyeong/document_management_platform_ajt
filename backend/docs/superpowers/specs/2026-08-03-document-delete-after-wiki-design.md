# 원본문서 삭제 순서 뒤집기 — 위키를 걷어낸 뒤에 지운다

관련 Jira: `S15P11B106-195`(이 설계) · `S15P11B106-194`(삭제 시 Wiki 반영 400 — 원인 수정, 별건) · `S15P11B106-164`(삭제 시 근거 Wiki 걷어내기 연동, 완료) · `S15P11B106-93`(삭제 응답에 재처리 여부 추가, 완료)

## 목적

원본문서 삭제에서 **되돌릴 수 없는 일(하드 삭제)을 마지막에 둔다.**

지금은 문서 행과 파일을 먼저 지우고 위키 걷어내기를 뒤에서 비동기로 돌린다. 그래서 걷어내기가
실패하면 **원본은 사라졌는데 위키에는 그 문서를 근거로 한 내용이 남는다.** 문서 행이 없으니
재시도할 수단도 없다. 복구 불가능한 상태만 남는 것이 지금 설계의 기본 실패 모드다.

DB 스키마(`docs/db/erd.sql`)는 변경하지 않는다. `document.status`가 `VARCHAR(30)` +
애플리케이션 enum이라 상태 값 추가에 DDL이 필요 없다. 공개 계약은 §4에서 1건 변경을 제안한다.

## 1. 지금 무슨 일이 일어나는가

`DocumentManagementService.delete` (`DocumentManagementService.java:487`):

1. `readParsedMarkdownQuietly(document)` — 걷어내기에 쓸 옛 파싱 본문을 읽어 둔다
2. `documentRepository.delete(document)` + `flush()` — **문서 행이 사라진다**
3. `removeDeletedDocumentFromScope(...)` — 걷어내기 `AiJob`을 만든다
4. `registerAfterCommitFileDelete(originalPath, parsedPath)` — 커밋 후 파일 삭제 예약
5. 응답 `202 { deleted: true, reprocessRequired: true, jobId, ... }`

작업 실행은 **커밋 뒤 비동기**다 — `AsyncDocumentParseJobLauncher.launch`가
`afterCommit`에 등록한다(`AsyncDocumentParseJobLauncher.java:27`). 즉 API가 202를 돌려줄 때
이미 문서 행과 파일은 없고, 위키 걷어내기는 그때부터 시작한다.

### 실패하면 남는 것

`DocumentParseWorker.removeDocument`(`DocumentParseWorker.java:184`)가 실패하면
`AiJob.DocumentParseResult.failed(...)`만 기록되고 끝난다. 결과:

| 대상 | 상태 |
| --- | --- |
| 문서 행 | 삭제됨 (복구 불가) |
| 원본·파싱 파일 | 삭제됨 (복구 불가) |
| 위키 | **그 문서를 근거로 한 내용이 그대로 남음** |
| 재시도 | **불가** — `retry`의 `findDocument`가 404 |
| 걷어낼 옛 본문 | 실패한 작업의 계획에만 있었고 함께 사라짐 |

`S15P11B106-194`가 이 실패를 실제로 일으켰다. 삭제 요청이 `parsedMarkdown: null`을 실어
FastAPI가 전건 400을 냈고, 실서버에서 삭제한 문서들이 위 상태로 남았다.

**194를 고쳐도 이 설계 결함은 남는다.** 400은 없어지지만 AI 서버 다운·타임아웃·lint 실패 등
다른 이유로 걷어내기가 실패하면 같은 결과가 된다.

## 2. 확정 설계

**걷어내기가 성공한 뒤에 지운다.**

```
[삭제 요청]
  → document.status = DELETING          (행·파일 모두 살아 있음)
  → 걷어내기 AiJob 생성, 비동기 실행
  → 202 { deleted: false, deleting: true, jobId, ... }

[워커]
  → document_removed 변환
  ├─ 성공 → 문서 행 삭제 + 파일 삭제 → 작업 completed
  └─ 실패 → status = FAILED, failureReason 기록. 행·파일 모두 그대로 둔다
```

### 2-1. `DocumentStatus.DELETING` 추가

`DocumentStatus`에 값 하나를 더한다. **ERD 변경 없음** — `status VARCHAR(30)`에
애플리케이션 enum을 매핑하는 방식이라 값만 늘리면 된다.

`DELETING`은 처리 중으로 취급한다. `Document.isInProgress()`가 지금은
`PARSING || PROCESSING`만 보므로(`Document.java:203`) 여기에 `DELETING`을 더한다. 그러면
두 가지가 따라온다:

- `ensureNotInProgress`가 막는다 — 삭제 대기 중인 문서를 다른 관리자가 수정·교체할 수 없다
- `ensureScopeNotProcessing`이 같은 메서드를 쓰므로 **삭제 대기 문서 하나가 그 범위의 다른
  위키 작업을 막는다.** 의도한 동작이다 — 걷어내기와 다른 변환이 같은 범위를 동시에 고치면 안 된다

`retryParsing`은 `FAILED`·`CANCELLED`만 받으므로(`Document.java:167`) `DELETING` 상태에서
재처리를 누를 수 없다. 이것도 맞는 동작이다.

목록·상세 응답의 `status`로 나가 화면이 "삭제 중"을 보여줄 수 있다.

### 2-2. 워커가 삭제를 확정한다

`removeDocument`가 지금은 문서 엔티티를 읽지 않는다(행이 이미 없으니까). 이제는 읽는다:

- 변환 성공 → `documentRepository.delete(document)` + 파일 삭제
- 변환 실패 → `DELETING` → `FAILED`, 실패 사유 기록. 행·파일 유지

**기존 실패 전이를 쓸 수 없다.** `failParsing`은 `ensureParsing()`으로 `PARSING`만 받고
(`Document.java:150`), `failProcessing`은 `PROCESSING`만 받는다(`Document.java:159`).
`DELETING → FAILED` 전이를 하나 더 만든다(`failDeleting(String)`).

전이를 재사용하지 않고 새로 만드는 이유는 상태 기계를 느슨하게 하지 않기 위해서다. 세 실패
전이가 각각 어느 상태에서만 오는지 막아 두면, 잘못된 순서로 부른 코드가 조용히 통과하지 않는다.

파일 삭제는 지금처럼 커밋 후에 한다. DB 트랜잭션과 달리 롤백되지 않기 때문이다.

### 2-3. 재시도가 가능해진다

`DELETING`으로 실패한 문서는 행과 `parsed.md`가 살아 있다. 그래서 옛 파싱 본문을 다시 읽어
`document_removed` 계획으로 재시도할 수 있다.

**다만 지금 `retry`는 쓸 수 없다.** `DocumentManagementService.retry`는 무조건
`DocumentReprocessPlan.added()`로 돈다(`DocumentManagementService.java:159`). 삭제 실패의
재시도는 `removed()`여야 한다.

두 가지 방법이 있다:

| 방법 | 내용 | 평가 |
| --- | --- | --- |
| A. 관리자가 삭제를 다시 누른다 | 별도 재시도 경로 없음. `FAILED` + 파일 있음 = 평범한 문서이므로 `DELETE`를 다시 부르면 된다 | **선택.** 새 API·새 상태가 필요 없다 |
| B. `retry`가 실패 종류를 보고 계획을 고른다 | 문서에 "무엇을 하다 실패했는지"를 기록해야 한다 | 상태를 하나 더 들고 있어야 해서 비싸다 |

**A를 택한다.** 삭제가 실패한 문서는 삭제되지 않은 문서일 뿐이다. 다시 삭제를 시도하면
`readParsedMarkdownQuietly`가 살아 있는 `parsed.md`를 다시 읽어 같은 경로를 탄다.

이때 `retry`(파싱 재처리)를 눌러도 안전하다 — `added()`로 돌아 문서가 정상 상태로 복귀할 뿐,
위키에 중복이 생기지 않는다(반영이 `REQUIRES_NEW` 트랜잭션이라 실패 시 롤백된다,
`DocumentWikiTransformationTransactionService.java:26`).

## 3. 바뀌지 않는 것

- **하드 삭제 정책(DR-014)은 그대로다.** 지우는 시점만 뒤로 밀린다. 소프트 삭제가 아니다
- 걷어내기 요청의 모양(`changeType: document_removed` + `removedParsedMarkdown`)은 그대로다.
  내부 계약(`docs/api/AJT-FastAPI-Internal-API...`)은 변경하지 않는다
- 같은 범위 동시 작업 차단(`ensureScopeNotProcessing`)의 의미도 그대로다

## 4. 공개 계약 변경 제안 (1건)

`DELETE /api/v1/documents/{documentId}` 응답의 **`deleted` 의미가 바뀐다.**

지금은 "이 응답이 내려오면 항상 `true`"다(`DocumentDeleteResponse` javadoc). 순서를 뒤집으면
응답 시점에 아직 지워지지 않았으므로 `true`일 수 없다.

제안:

| 필드 | 지금 | 바뀜 |
| --- | --- | --- |
| `deleted` | 항상 `true` | 걷어낼 내용이 없어 즉시 지운 경우만 `true` |
| `status` | `waiting` \| `skipped` | `deleting` \| `skipped` |
| `reprocessRequired`, `jobId`, `scopeKey` | — | 그대로 |

`skipped`(파싱 본문이 없어 걷어낼 것이 없는 문서)는 지금처럼 즉시 삭제하고 `deleted: true`로
답한다. 그 경로는 위키를 건드리지 않으므로 순서를 미룰 이유가 없다.

**소비자**: 프론트(`DocumentDeleteDialog`). 백엔드 공개 API·프론트 담당자 확인이 필요하다.
`docs/api/README.md`의 계약 변경 절차를 따르고 `contractVersion`을 minor로 올린다 — 필드
의미 변경은 하위 호환이 아니다.

## 5. 프론트 영향

`DocumentDeleteDialog`(`frontend/src/features/document/components/DocumentDeleteDialog.jsx`)가
이미 세 단계(확인 → 진행 → 완료)로 되어 있어 구조는 그대로 쓴다. 바뀌는 것은 셋이다.

1. **단계 순서가 실제와 맞아떨어진다.** 지금 완료 화면은 "원본 파일 삭제 ✓"를 초록으로 보여주는데,
   위키 반영이 실패해도 그 표시가 그대로다. 순서를 뒤집으면 원본 삭제는 위키 정리가 끝난 뒤에만
   일어나므로 이 모순이 사라진다
2. **실패 화면이 실패처럼 보여야 한다.** 지금은 실패해도 초록 원(`bg-emerald-50 ring-emerald-100`,
   `DocumentDeleteDialog.jsx:258`)에 경고 아이콘만 얹어 성공처럼 읽힌다
3. **실패 화면에 재시도 버튼이 생긴다.** 지금은 "목록으로"밖에 없다. 순서를 뒤집으면 문서가
   살아 있으므로 "다시 삭제"를 걸 수 있다

## 6. 작업 순서

1. `DocumentStatus.DELETING` 추가, `Document.isInProgress()`에 반영, `failDeleting` 전이 추가
2. `delete`가 행을 지우는 대신 `DELETING`으로 표시하고 작업만 생성
3. `DocumentParseWorker.removeDocument`가 변환 성공 시 행·파일 삭제, 실패 시 `failDeleting` 기록
4. 계약 변경 협의 → `docs/api/` 반영, `contractVersion` minor 상향
5. 프론트 다이얼로그 3건 반영
6. 실기동 확인 — 삭제 성공 경로와 **실패 후 재삭제 경로**를 둘 다 돌려본다

## 7. 이 설계가 해결하지 못하는 것

**이미 망가진 건들은 복구되지 않는다.** `S15P11B106-194` 이전에 삭제한 문서들은 원본·행이 모두
사라진 상태이고, 위키에 남은 잔재를 되짚을 근거가 없다. 관리자가 위키를 직접 수정해야 한다.

실서버에서 몇 건이 그 상태인지 확인이 필요하다. `ai_job`에서 `document_results`의 실패 기록으로
역추적할 수 있다 — 실패한 걷어내기 작업의 `document_ids`가 그 목록이다.
