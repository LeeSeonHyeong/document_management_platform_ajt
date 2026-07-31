# 원본문서 수정 시 changeType 배분 설계

관련 Jira: `S15P11B106-61`(부서 변경, 해야 할 일) · `S15P11B106-44`(파일 교체, 구현 완료·배선 누락) · `S15P11B106-142`(재처리 API, 완료)

## 목적

관리자가 원본문서 상세 화면에서 카테고리·공개 범위·원본 파일을 수정할 때, Spring Boot가
FastAPI에 어떤 `changeType`으로 무엇을 보낼지 확정한다.

현재 Spring은 **모든 재처리를 `document_added`로 보낸다.** `WikiDocumentChangeType`에
`DOCUMENT_REMOVED`·`DOCUMENT_REPLACED`가 정의되어 있으나 호출하는 코드가 없다
(`DocumentParseWorker:128`, `WikiTransformationService:73`). FastAPI는 세 값 모두
구현되어 있다(`ai/src/agent_runtime/base.py`의 `reconcile_instruction`).

그 결과 파일을 교체하거나 부서를 바꿔도 옛 내용을 근거로 쓴 위키 문단·각주가 걷히지 않고
새 내용이 덧붙는다. 각주 인용문이 실제 원본문서에 없는 문장을 가리키는 상태가 남는다.

DB 스키마와 공개 API 경로는 변경하지 않는다. 내부 계약은 변경하지 않으며, 공개 계약만
아래 §6에서 2건 변경을 제안한다.

## 1. 확정 정책

| 바뀐 것 | 처리 | AI 호출 |
| --- | --- | --- |
| 문서 카테고리만 | DB 메타데이터만 수정 | **없음** |
| 공개 범위(scopeKey) | 옛 범위에서 삭제 → 새 범위로 재업로드 | `document_removed` → `document_added` |
| 원본 파일만 | 교체 | `document_replaced` |
| 범위 + 파일 동시 | 옛 본문 삭제 → 새 파일 업로드 | `document_removed` → `document_added` |

범위 + 파일을 함께 바꿀 때 `document_replaced`를 쓰지 않는다. 옛 범위에서 걷어내고 새 범위에
넣는 것이므로 한 scope 안의 교체가 아니다.

### 카테고리를 no-op으로 두는 근거

- **FR-DOC-006** — "원본문서 카테고리는 관리자가 관리하며 Wiki 카테고리는 에이전트가 별도로
  관리한다. 카테고리는 파일 경로에 포함하지 않고 DB에만 저장한다."
- **FR-WIKI-014** — "관리자는 Wiki 카테고리를 조회만 할 수 있으며 직접 생성·수정·삭제하지
  않는다."
- **DR-019** — 관리 주체가 원본문서=관리자, Wiki=에이전트로 분리되어 있다.
- **FR-DOC-007** — AI 자동 카테고리 분류는 제공하지 않는다.
- ERD에서 `document_category`와 `wiki_category`는 별개 테이블이고 서로 FK가 없다.
  `wiki.wiki_category_id`는 `wiki_category`만 가리킨다.
- FR-DOC-008은 **공개 범위 수정**에만 재처리를 규정한다. 카테고리 수정에는 재처리 규정이 없다.
- `documentCategoryId`는 어떤 내부 API 요청에도 실리지 않는다. 재처리해도 FastAPI가 받는
  입력이 변경 전과 동일하다.
- `GET /api/v1/wikis/{wikiId}` 응답의 `category`는 위키 카테고리이고 원본문서는
  `evidenceDocuments`에 파일명·다운로드 링크만 실린다. 문서 카테고리는 위키 응답에 노출되지
  않으므로 DB만 고쳐도 화면 정합이 깨지지 않는다.

재처리하면 같은 문서를 `document_added`로 다시 밀어넣어 중복 문단·중복 페이지 위험만 남는다.

## 2. 현재 동작과의 차이

| | 지금 | 변경 후 |
| --- | --- | --- |
| 카테고리만 | 문서 1건 재처리(최대 10분) | 없음 |
| 범위 변경 | `reprocessScope` 양쪽 **전체** — 문서 N건 × (파싱+문맥선택+변환), 단일 스레드 순차 | removed 1건 + added 1건 |
| 파일 교체 | `document_added` 재처리 | `document_replaced` 재처리 |

범위 변경을 문서 1건 단위로 바꾸면 옛 범위에 남은 문서가 0건일 때 `documentIds`가 비어
`AiJob.finish()`의 `anySucceeded=false`로 job이 `FAILED`로 마감되던 허위 실패도 사라진다
(`AiJob:86`, `DocumentManagementService:332`의 리뷰 주석).

## 3. 옛 파싱 본문 보존 (선결 과제)

`document_removed`·`document_replaced` 요청은 `removedParsedMarkdown`이 필수다. 없으면
FastAPI 스키마(`TransformRequest._removed_body_is_required`)가 **400 + fieldErrors**로 막는다.

현재 옛 본문을 잃는 지점이 세 곳이다.

1. `replaceFile` — 새 원본을 같은 경로에 저장해 **원본 파일을 덮어쓴다**
   (`DocumentManagementService:171`). 재파싱으로 복원할 수 없다.
2. `DocumentParseWorker` — `parseSource` 직후 `storeParsedMarkdown`이
   `wiki/{scopeKey}/sources/{documentId}/parsed.md`에 새 본문을 덮어쓴다
   (`LocalDocumentFileStorage:52`, 경로가 문서 ID 기준으로 고정).
3. `delete`·`moveToScope` — 파일을 지우거나 옮긴 뒤 재처리를 트리거한다.

### 방침

**파일을 건드리기 전에 옛 `parsed.md`를 읽어 문자열로 들고 간다.** 별도 백업 파일을 만들지
않는다. `DocumentFileStorage.readText(parsedPath)`가 이미 있어 새 저장소 API가 필요 없다.

`parsedPath`가 `null`이거나(파싱 전 문서) 파일이 유실된 경우 옛 본문이 없다. 이때 폴백은
**변경 종류에 따라 다르다.**

- **범위 변경의 걷어내기**: 옛 범위 작업을 **아예 만들지 않는다.** `document_added`로 폴백하면
  걷어내려던 범위에 그 문서를 다시 반영하게 되어 정반대 결과가 된다. 작업을 만들지 않았음을
  경고 로그로 남기고, 응답의 `reprocessJobs`에는 새 범위 작업만 실린다.
- **파일 교체**: `document_added`로 폴백해도 같은 범위에 새 내용을 반영하는 것이어서 방향이
  어긋나지 않는다. 옛 내용을 걷어내지 못한다는 한계만 남는다. (S15P11B106-44/103 범위)

걷어내기를 건너뛴 경우 옛 범위 Wiki에 그 문서를 근거로 쓴 서술이 남을 수 있다. 파싱 전 문서는
애초에 Wiki에 반영된 적이 없어 실제 문제가 되는 것은 파싱 파일이 유실된 경우뿐이다.

크기는 문제되지 않는다. FastAPI가 `exceeds_ceiling`으로 요청 본문 상한을 이미 검사하며
(`ai/src/agent_runtime/limits.py`), 그 상한을 넘는 문서는 업로드 단계에서 거부된다.

## 4. changeType을 워커까지 전달하는 방법

워커는 지금 `AiJob`의 `documentIds`만 받아 항상 `document_added`로 처리한다. 재처리 의도를
전달할 수단이 필요하다.

### 선택: 인메모리 재처리 계획 객체

`DocumentParseJobLauncher.launch(job)`에 계획을 함께 넘기고, 워커가 문서별로 그 계획을 보고
`changeType`과 `removedParsedMarkdown`을 결정한다.

```java
// 새 파일: domain/document/service/DocumentReprocessPlan.java (예시 형태)
record DocumentReprocessPlan(
        Map<Long, WikiDocumentChangeType> changeTypeByDocumentId,
        Map<Long, String> removedParsedMarkdownByDocumentId
) {
    static DocumentReprocessPlan added() { ... }   // 업로드·재시도 기본값
}
```

- `AiJob`·`document` 테이블에 컬럼을 추가하지 않는다. ERD 변경 금지 원칙(루트 `CLAUDE.md`)을
  지키고 팀 합의 없이 스키마를 건드리지 않기 위해서다.
- 계획이 없으면 전부 `document_added`다. 업로드(`DocumentUploadService`)와
  재시도(`retry`)는 지금 동작이 그대로 유지된다.
- **한계**: 계획은 서버 재시작 시 유실된다. 다만 재처리 job 자체가 이미 인메모리
  단일 스레드 큐(`DocumentParseExecutorConfig`의 `newSingleThreadExecutor`)로 실행되어
  재시작하면 `WAITING`·`PROCESSING` job이 이어지지 않는다. 새로 생기는 한계가 아니다.

`WikiTransformationService.transformForAddedDocument`는 `changeType`과
`removedParsedMarkdown`을 인자로 받는 형태로 일반화한다. 메서드명도
`transformForDocumentChange` 등으로 바꾼다.

## 5. 변경 흐름

### 5.1 카테고리만 변경

```text
PATCH /api/v1/documents/{id}  { documentCategoryId }
  → 관리자 검증, 카테고리의 scopeKey 소속 검증
  → document.documentCategoryId 수정
  → 200 OK (재처리 없음, jobId 없음)
```

`ensureNotInProgress`는 유지한다. 처리 중 문서의 메타데이터를 바꾸면 진행 중인 변환이 보는
값과 어긋난다.

### 5.2 공개 범위 변경 — 구현 완료

```text
PATCH /api/v1/documents/{id}  { documentCategoryId, visibilityType, departmentIds }
  → 관리자·문서 상태·새 범위 카테고리 소속 검증 (기존 그대로)
  → 옛 parsed.md 본문을 문자열로 읽어 둔다        ← 신규
  → moveToScope로 원본·파싱 파일 이동 (기존 보상 로직 유지)
  → document 메타데이터 변경 (카테고리·scopeKey·경로)
  → 옛 범위 job: document_removed, 이 문서 1건, removedParsedMarkdown = 읽어 둔 본문
  → 새 범위 job: document_added,   이 문서 1건
  → 202 Accepted + reprocessJobs[{ scopeKey, jobId }] × 2
```

걷어내기 반영은 `WikiTransformationApplier.applyRemovedDocument`가 맡는다. 문서 참조를 자동으로
더하지 않고(`originDocumentId = null`), 반영 후 그 범위에 남은 Wiki의 `documentRefs`에서 빠진
문서를 지운다. AI가 응답의 근거 배열에 그 문서를 실어 보내도 이 단계에서 정리된다.

걷어내기 작업은 **문서 엔티티의 처리 상태를 건드리지 않는다.** 같은 문서를 새 범위 작업이 이어서
처리하므로, 옛 범위 작업이 상태를 함께 옮기면 두 작업이 한 문서의 상태를 두고 다툰다.

`reprocessScope` 양쪽 전체 재처리를 제거한다. 계약의 `reprocessJobs` 응답 형태는 그대로
유지되므로 프론트 영향이 없다.

옛 범위 job의 `documentIds`에는 이 문서 ID가 들어가지만 문서 행의 `scope_key`는 이미 새
범위로 바뀐 상태다. 워커가 옛 범위 위키를 대상으로 돌아야 하므로 **job의 `scopeKey`를
기준으로 변환 대상 범위를 정한다** — 문서의 현재 `scope_key`를 쓰면 옛 범위를 건드리지
못한다. `AiJob`은 이미 `scope_key`를 갖고 있다.

### 5.3 원본 파일만 교체 — 구현 완료

```text
PUT /api/v1/documents/{id}/file  { file }
  → 관리자·문서 상태 검증, 파일 검증 (기존 그대로)
  → 옛 parsed.md 본문을 문자열로 읽어 둔다        ← 신규
  → 새 원본 저장(덮어쓰기), 경로가 달라지면 옛 파일 정리 (기존 그대로)
  → job: document_replaced, 이 문서 1건, removedParsedMarkdown = 읽어 둔 본문
  → 202 Accepted + jobId
```

`DocumentManagementService`의 `TODO(계약)`이 여기서 해소된다. 재처리 범위는 (a) 이 문서 1건
증분으로 확정했다.

추가와 교체는 워커의 같은 경로(`parseDocument`)를 쓴다. 교체는 새 파일을 파싱해 보내면서 교체 전
본문도 함께 실을 뿐이다. 반영도 추가와 같은 `applyAddedDocument`를 쓴다 — 문서가 그대로 남으므로
새 근거를 `documentRefs`에 더하는 것이 맞다.

### 5.4 문서 삭제 — 구현 완료

```text
DELETE /api/v1/documents/{documentId}
  → 관리자·문서 상태 검증, 범위 처리 중 검사 (기존 그대로)
  → parsed.md 본문을 문자열로 읽어 둔다          ← 신규
  → 문서 행·원본·파싱 파일 하드 삭제 (DR-014, 기존 그대로)
  → job: document_removed, 이 문서 1건
  → 202 Accepted + jobId
```

부서 변경을 "삭제 → 걷어내기 → 재업로드"로 정한 것이 곧 삭제 정책이므로 §1의 범위 변경과 같은
경로를 쓴다. `DocumentManagementService`의 `TODO(위키)`(문서 참조 정리, 고아 Wiki 제거)가
`applyRemovedDocument`와 에이전트의 변경 목록으로 해소된다 — 근거가 전부 사라진 페이지는
에이전트가 `delete`로 지시한다(FastAPI `reconcile_instruction`).

**문서 행이 이미 지워진 뒤에 걷어내기가 실행된다.** 그래서 워커의 걷어내기 경로는 문서 엔티티를
읽지 않고 문서 ID와 계획이 실어 온 본문만 쓴다. 소프트 삭제를 도입하지 않았다 — DR-014가 하드
삭제를 규정하고 ERD도 바꾸지 않는다.

파싱 전이거나 파싱에 실패한 문서는 Wiki에 반영된 적이 없어 걷어낼 근거가 없다. 이때는 작업을
만들지 않고 응답의 `jobId`가 비는데, 계약은 `202 + jobId`를 요구한다. §6.3으로 올린다.

### 5.5 `reprocessScope` 제거

범위 변경·삭제 두 경로가 모두 단건 걷어내기로 바뀌어 범위 전체 재처리를 호출하는 곳이 없어졌다.
죽은 코드이므로 제거했다. 남은 문서가 0개일 때 빈 작업이 `FAILED`로 마감되던 허위 실패도 함께
사라졌다.

## 6. 계약 변경 제안

`docs/api/README.md`의 계약 변경 절차를 따른다. 영향받는 소비자는 프론트엔드(관리자 문서 관리
화면)다.

### 6.1 카테고리만 변경 시 응답

현재 계약은 `PATCH /api/v1/documents/{documentId}`가 항상 `202 Accepted` + `jobId`를
반환하고 "기존 범위와 새 범위 Wiki를 각각 최신 문서 기준으로 재처리합니다"라고 정한다.
카테고리만 바꿀 때 재처리가 없으면 돌려줄 `jobId`가 없다.

- **제안**: 카테고리만 변경 시 `200 OK`, 본문은 수정된 문서 정보만. `jobId`·`reprocessJobs`
  없음. 공개 범위가 함께 바뀌면 기존대로 `202 + jobId + reprocessJobs`.
- 허위 job을 만들어 202를 맞추는 방안은 채택하지 않는다. 작업 상태 조회에 아무 일도 하지
  않는 job이 남는다.
- 응답 타입이 갈리므로 **minor 이상** 버전업.

### 6.2 파일 + 메타데이터 동시 저장

관리자 화면의 교체 버튼 하나로 부서·카테고리·원본 파일을 함께 수정한다. 지금은 엔드포인트가
`PATCH /api/v1/documents/{id}`와 `PUT /api/v1/documents/{id}/file` 둘로 나뉘어 있어, 프론트가
연달아 호출하면 **첫 호출이 만든 job 때문에 두 번째가 `ensureNotInProgress`에서 409
Conflict로 막힌다.** 앞 job이 끝날 때까지 기다리면 문서당 최대 10분이다.

- **제안**: `PATCH /api/v1/documents/{documentId}`가 `file`을 optional multipart로 함께 받아
  한 요청으로 처리한다. 그러면 §1의 "범위 + 파일 동시" 조합도 job 2개로 정리된다.
- 대안(요청 분리 유지)은 프론트가 순차 호출 + job 완료 폴링을 해야 해 사용성이 나쁘다.
- 요청 형식이 바뀌므로 **minor 이상** 버전업.

### 6.3 걷어낼 근거가 없는 삭제

파싱 전이거나 파싱에 실패한 문서를 삭제하면 Wiki에 반영된 적이 없어 걷어내기 작업을 만들 이유가
없다. 그런데 계약은 `DELETE /api/v1/documents/{documentId}` 응답을 `202 + jobId`로 정한다.

- **제안**: 재처리할 것이 없으면 `200 OK` + `jobId` 없음. §6.1과 같은 성격이다.
- 현재 구현은 `jobId`를 `null`로 돌려주며 코드에 `TODO(계약)`을 남겼다.
- 응답 타입이 갈리므로 **minor 이상** 버전업.

세 건 모두 프론트엔드 담당 동의가 필요하다. 합의 전에는 §6.1·§6.2 구현을 시작하지 않는다.

## 7. 작업 순서

1. ~~**옛 parsed 본문 보존** (§3)~~ — 완료.
2. ~~**재처리 계획 전달** (§4)~~ — 완료. `DocumentReprocessPlan`.
3. ~~**공개 범위 변경** (§5.2)~~ — 완료. `S15P11B106-61`.
4. ~~**파일 교체** (§5.3)~~ — 완료. `S15P11B106-103`.
5. ~~**문서 삭제** (§5.4)~~ — 완료. `S15P11B106-142` 후속.
6. **계약 변경 합의** (§6.1~6.3) — 프론트엔드 담당과 확정. 남은 일은 이것뿐이다.

카테고리만 변경(§5.1)은 §6.1 합의 후에 구현한다. 그때까지는 기존대로 단건 재처리가 돈다.

## 8. 테스트

- 카테고리만 변경: `AiClient`가 **한 번도 호출되지 않음**을 검증한다. 200 응답과 DB 반영 확인.
- 범위 변경: 옛 범위 job이 `document_removed` + 이동 전 parsed 본문을, 새 범위 job이
  `document_added`를 보내는지 검증. 옛 범위에 문서가 0건 남는 경우 job이 `FAILED`로 마감되지
  않음을 함께 검증.
- 파일 교체: `document_replaced` + 교체 전 parsed 본문 전달 검증.
- 옛 parsed 본문이 없을 때 걷어내기 작업을 만들지 않고 새 범위 작업만 생기는지 검증.
- 기존 업로드·재시도 경로가 여전히 `document_added`임을 회귀 검증.
