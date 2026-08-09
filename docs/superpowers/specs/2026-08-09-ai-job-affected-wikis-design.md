# AI 작업별 생성·변경 Wiki 이력 설계

## 목적

관리자 AI 작업 요약은 문서와 현재 연결된 Wiki가 아니라, 해당 문서를 반영하면서 실제로 생성하거나 변경한 Wiki를 보여 준다. 기존 Wiki에 내용이 추가된 경우도 요약의 Wiki 칸이 비지 않아야 한다.

## 선택한 방식

`DocumentWikiTransformationTransactionService`가 이미 반환하는 `affectedWikiIds`를 작업 결과에 보존한다. 반영 직후 Wiki 제목을 함께 스냅샷으로 만들고, `AiJob.document_results` JSON의 문서별 결과에 저장한다.

- AI 서버는 변경하지 않는다. AI 서버는 변경안을 만들고, 실제 Wiki 반영 결과는 백엔드가 가진다.
- DB 스키마는 변경하지 않는다. 기존 JSON 컬럼의 하위 호환 필드 추가다.
- 공개 `GET /api/v1/ai-jobs/{jobId}` 응답의 문서 결과에 `affectedWikis` 배열을 추가한다. 각 원소는 `wikiId`, `title`이다.
- 프론트 요약은 `affectedWikis`를 우선 사용한다. 이 필드가 없는 과거 작업만 문서 상세의 `relatedWikis`를 보조값으로 사용한다.
- 여러 Wiki가 영향받으면 첫 Wiki 제목을 링크로 보이고 `외 N건`을 덧붙인다. 삭제로 더는 열 수 없는 Wiki는 제목만 표시하고 링크를 만들지 않는다.

## 데이터 흐름

1. 백엔드가 AI 서버의 변경안을 실제 Wiki에 적용한다.
2. 적용 결과의 영향 Wiki ID와 반영 시점 제목을 문서별 `DocumentParseResult`에 기록한다.
3. AI 작업 조회 API가 기록된 배열을 그대로 응답한다.
4. 요약 목록이 그 배열을 사용해 생성·변경된 Wiki를 표시하고, 존재하는 Wiki는 상세 페이지로 연결한다.

## 예외와 호환성

- 실패한 문서는 `affectedWikis: []`다.
- 과거 JSON에는 필드가 없을 수 있으므로 서버는 빈 배열로 응답한다. 프론트는 과거 이력의 표시 연속성을 위해서만 `relatedWikis`를 대체값으로 쓴다.
- 문서 삭제 작업은 삭제된 Wiki를 링크할 수 없으므로, 이 작업의 요약 대상에서는 제외한다. 이 설계 범위는 문서 업로드·교체·재처리의 생성·변경 Wiki 표시다.

## 검증

- 백엔드: 기존 Wiki 변경 결과가 `affectedWikis`로 조회되는 단위 테스트, 새 Wiki 다건 순서 보존 테스트, 실패 결과 빈 배열 테스트.
- 프론트: 결과 배열 우선 선택, 과거 작업 fallback, Wiki 링크와 다건 표기 테스트.
- 계약: Postman 생성 원본을 갱신하고 컬렉션 생성·일관성 검증을 수행한다.
