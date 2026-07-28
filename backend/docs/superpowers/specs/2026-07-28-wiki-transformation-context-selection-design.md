# Wiki 변환 2단계 문맥 선택 API 설계

## 목적

새 원본문서의 파싱 Markdown을 Wiki로 반영할 때, Spring Boot가 같은 `scopeKey`의
모든 Wiki 본문을 FastAPI에 보내지 않는다. FastAPI가 먼저 현재 `index.md`를 보고
필요한 Wiki ID를 선택하고, Spring Boot가 선택된 Wiki의 본문과 관계 정보만 다시
전달한 뒤 최종 변경안을 받는다.

이 작업은 Postman 개발 계약을 v1.2.0으로 올린다. 공개 API와 DB 테이블은 변경하지 않는다.

## 확정 흐름

```text
Spring Boot                         FastAPI
    |                                   |
    | changeType + 문서 Markdown + 목차  |
    | POST wiki-context-selections      |
    |---------------------------------->|
    |                                   | index.md로 관련 Wiki 선택
    | selected wikiIds + reason         |
    |<----------------------------------|
    |                                   |
    | scope·존재 재검증, 선택 본문 조회   |
    |                                   |
    | selectedWikis + categories        |
    | POST wiki-transformations         |
    |---------------------------------->|
    |                                   | 변경안 생성
    | category/wiki/relation/index 변경안 |
    |<----------------------------------|
```

FastAPI는 Spring Boot의 DB, 실제 파일 경로, 파일 저장소에 직접 접근하지 않는다.
Spring Boot만 내부 API를 호출하며 모든 호출에는 `X-Internal-API-Key`를 포함한다.

## 신규 내부 API

### `POST /internal/v1/wiki-context-selections`

원본문서의 추가·삭제·교체가 기존 Wiki 중 어떤 자료를 수정·병합·연결해야 하는지 1차로 선택한다.

#### Request

```json
{
  "jobId": "42",
  "documentId": "15",
  "scopeKey": "D1-D2",
  "changeType": "document_replaced",
  "parsedMarkdown": "# 취업 규칙\n본문...",
  "removedParsedMarkdown": "# 기존 취업 규칙\n이전 본문...",
  "currentIndex": "# 목차\n- [휴가 규정](pages/101.md)"
}
```

- `jobId`, `documentId`, `scopeKey`는 문자열이다.
- `changeType`은 `document_added`, `document_removed`, `document_replaced` 중 하나다.
- `parsedMarkdown`은 added·replaced의 새 문서 파싱 결과이며 removed에서는 생략한다.
- `removedParsedMarkdown`은 removed·replaced의 제거 또는 교체 전 파싱 결과다.
- `currentIndex`는 요청 `scopeKey`의 현재 `wiki/{scopeKey}/index.md` 내용이다.
- FastAPI는 실제 파일 경로와 Wiki 본문을 이 요청에서 받지 않는다.

#### Response

```json
{
  "wikiIds": ["101", "108"],
  "reason": "새 취업 규칙의 휴가·복무 항목과 관련된 현재 Wiki입니다."
}
```

- `wikiIds`는 현재 목차 링크에서 식별된 문자열 ID이며, 중복 없이 관련도 순서로 최대 5개다.
- `reason`은 선택 결과를 설명하는 짧은 문자열이다.
- Spring Boot는 응답 ID가 현재 `scopeKey`에 속하고 현재 Wiki로 존재하는지 재검증한다.
- `400` 업무 오류 코드는 `INVALID_WIKI_CONTEXT_SELECTION_REQUEST`다.
- `401`은 내부 API 키 오류, `500` 업무 오류 코드는 `WIKI_CONTEXT_SELECTION_FAILED`다.

## 변경된 변환 API

### `POST /internal/v1/wiki-transformations`

기존 `currentWikis` 필드를 제거하고, 선택 API 이후 Spring Boot가 준비한
`selectedWikis`만 전달한다. `currentIndex`와 `currentCategories`는 현재 공간의
목차·카테고리 구조를 계속 제공한다.

#### Request

```json
{
  "jobId": "42",
  "documentId": "15",
  "scopeKey": "D1-D2",
  "changeType": "document_replaced",
  "parsedMarkdown": "# 취업 규칙\n본문...",
  "removedParsedMarkdown": "# 기존 취업 규칙\n이전 본문...",
  "currentIndex": "# 목차\n- [휴가 규정](pages/101.md)",
  "currentCategories": [
    { "categoryId": "10", "name": "인사·복무" }
  ],
  "selectedWikis": [
    {
      "wikiId": "101",
      "categoryId": "10",
      "title": "휴가 규정",
      "summary": "연차와 반차 사용 기준",
      "contentMarkdown": "# 휴가 규정\n...",
      "documentRefs": ["15", "18"],
      "wikiRefs": ["108"]
    }
  ]
}
```

- `changeType`, `parsedMarkdown`, `removedParsedMarkdown`의 조합은 선택 요청과 동일하다.
- `selectedWikis`에는 선택 응답을 통과한 현재 Wiki만 포함한다.
- `wikiId`, `categoryId`, `documentRefs`, `wikiRefs`는 모두 문자열 ID다.
- `contentMarkdown`은 Spring Boot가 현재 파일에서 읽은 본문이다.
- 실제 저장 경로, DB 연결 정보, 권한 정보는 포함하지 않는다.
- 선택 결과가 빈 배열이어도 변환 API는 새 Wiki 생성 또는 목차 변경안을 만들 수 있다.

#### Response

기존 응답 필드 `summary`, `categoryChanges`, `wikiChanges`, `relationChanges`,
`indexEntries`를 유지한다. `wikiChanges[].evidence`는 선택 필드이며 문서 ID, 각주,
위치, 인용을 담는다. FastAPI는 구조화된 변경 제안만 반환하며, Spring Boot가
임시 참조를 실제 ID로 치환하고 관계 JSON·Markdown·`index.md` 링크를 검증한 뒤
반영한다.

이번 변경은 기존 변경 제안 배열의 action별 세부 객체를 확장하지 않는다. 그 객체의
생성·수정·삭제·병합 상세 형식은 Wiki 반영 구현 전에 별도 계약으로 확정한다.

## Spring Boot 반영 규칙

1. 같은 `scopeKey`의 변환 작업은 기존 전역 직렬 처리 원칙을 따른다.
2. 선택 API가 반환한 ID는 존재·scopeKey를 재검증하고, 유효하지 않은 ID는 제외한다.
3. 선택 Wiki 본문과 관계 JSON은 Spring Boot가 파일·DB에서 읽는다.
4. 최종 변경안은 작업 공간에서 작성·검증한 뒤 반영한다.
5. Wiki-Wiki 관계는 양쪽 `wiki_refs` JSON을, Wiki-원본문서 관계는 양쪽 JSON을
   같은 DB 트랜잭션에서 함께 갱신한다.
6. Markdown 링크와 `index.md` 링크가 유효하지 않거나 관계 반영이 실패하면 파일과
   DB를 모두 기존 상태로 롤백한다.
7. Wiki 변환·문맥 선택 오류의 선택 필드 `failureStage`(`context_load`, `agent_timeout`,
   `agent_error`, `lint_failed`, `assemble`)는 문서별 작업 결과 JSON에 저장한다.
8. FastAPI 내부 API 소비자는 계약에 정의되지 않은 응답 필드를 무시한다.

## 계약 산출물 변경

- `postman/postman-contract-examples.mjs`: 버전과 신규 Saved Example을 갱신한다.
- `postman/generate-postman-collections.mjs`: 신규 선택 요청과 변환 요청의
  `selectedWikis` 구조를 생성하도록 변경한다.
- 생성된 `AJT-FastAPI-Internal-API.postman_collection.json`을 재생성한다.
- `postman/README.md`와 요구사항 정의서의 내부 API 수를 7개로 갱신한다.
- `scripts/validate-artifact-consistency.mjs`가 신규 선택 API와 7개 내부 API를 검증한다.

## 제외 범위

- Spring Boot Wiki 반영 서비스와 파일 작업 공간 구현
- FastAPI의 실제 선택·변환 모델 구현
- DB 테이블·컬럼 추가 또는 변경
- FastAPI의 DB·파일 직접 접근
