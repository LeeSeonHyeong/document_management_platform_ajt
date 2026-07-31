# Wiki 원본문서 업로드 연결 복구 설계

## 배경

`DocumentListPage`의 Wiki 원본문서 파일 선택은 현재 실제 업로드 API를 호출하지 않는다.
브라우저 `File` 객체와 `blob:` URL로 미리보기 문서를 만들고, AI 작업 시작 확인에서도
`sessionStorage`에 가짜 완료 데이터를 저장한다. 그 결과 화면에는 파일이 표시되지만
`POST /api/v1/documents` 요청, `document`·`ai_job` 행, 원본 파일이 생성되지 않는다.

요구사항 FR-DOC-002·004, FR-AI-002·003과 공개 API 계약은 파일·카테고리·공개 범위를
한 업로드 요청으로 전송하고, 서버가 원본 파일과 메타데이터 및 작업을 생성한 뒤
별도 관리자 승인 없이 비동기 처리를 시작하도록 확정한다.

## 목표

- Wiki 원본문서 업로드 화면을 `POST /api/v1/documents`에 다시 연결한다.
- 한 업로드 묶음의 모든 파일에 동일한 공개 범위와 카테고리를 적용한다.
- 서버가 반환한 `jobId`를 실제 진행·요약 라우트의 식별자로 사용한다.
- Wiki 원본문서 흐름에서 브라우저 미리보기 문서를 실제 완료 문서처럼 저장하지 않는다.

## 범위

### 포함

- `DocumentListPage`의 문서 파일 카드
- Wiki 원본문서 파일 선택·검증·메타데이터 선택
- 업로드 진행률과 오류 표시
- 업로드 성공 후 실제 `jobId` 기반 진행 화면 이동
- 프론트 API 연결 회귀 테스트

### 제외

- 일정 파일 업로드
- 백엔드 `POST /api/v1/documents` 계약 변경
- 별도 서버 임시 업로드 API
- 별도 AI 작업 시작 API
- FastAPI 서버 구현 또는 배포

일정 파일은 `POST /api/v1/schedule-sources`라는 별도 동기 계약을 사용하므로 후속 작업으로
분리한다. 이번 수정에서 일정 파일을 Wiki 문서 API로 전송하지 않는다.

## 사용자 흐름

1. 관리자가 문서 파일 카드에서 TXT, MD, PDF 또는 DOCX 파일을 선택한다.
2. 업로드 모달에서 공개 범위와 해당 `scopeKey`의 카테고리를 선택한다.
3. 관리자가 업로드를 누르면 프론트가 파일과 메타데이터를 multipart로 전송한다.
4. 업로드 중에는 실제 전송 바이트 기반 진행률을 표시하고 모달을 닫지 못하게 한다.
5. 서버가 `202 Accepted`와 `jobId`·`documentIds`를 반환한다.
6. 프론트는 문서 목록 캐시를 무효화하고 `jobId` 기반 진행 화면으로 이동한다.
7. AI 연동 실패는 서버가 문서·작업 상태로 기록하며, 프론트는 가짜 성공 결과를 만들지 않는다.

## 컴포넌트 변경

### `DocumentListPage`

- 문서 파일 카드 클릭 시 실제 업로드 모달을 연다.
- `createPreviewDocuments`, `addPreviewSummary`, `addPreviewSourceDocuments`를 Wiki 원본문서
  경로에서 제거한다.
- 업로드 성공 응답의 `jobId`로 `/admin/documents/jobs/{jobId}/progress`에 이동한다.
- 서버의 `UPLOADED` 문서를 로컬 완료 상태로 바꾸지 않는다.

### 업로드 모달

- 기존 `useUploadDocuments` mutation을 사용한다.
- 파일 개수 1~20개, 개별 20MB, 총 100MB, 확장자를 클라이언트에서 선검증한다.
- 공개 범위로 `scopeKey`를 계산하고 해당 범위의 카테고리만 선택하게 한다.
- 업로드 오류는 모달에 유지하여 재시도할 수 있게 한다.

### API와 Query

- 기존 `uploadDocuments`와 `useUploadDocuments`를 재사용한다.
- multipart 필드는 `files`, `documentCategoryId`, `visibilityType`, `departmentIds`로 유지한다.
- 성공 시 문서 목록 query를 무효화한다.

## 오류 처리

- 잘못된 확장자·크기·개수·총량은 요청 전에 파일별 또는 묶음 오류로 표시한다.
- 카테고리나 공개 범위가 없으면 업로드 버튼을 비활성화한다.
- HTTP 오류는 공통 API 오류 메시지를 표시하고 선택 파일과 메타데이터를 유지한다.
- AI 서버 연결 실패는 업로드 실패로 위장하지 않는다. 업로드가 커밋된 뒤의 처리 실패는
  실제 `ai_job`·`document` 상태 조회로 표시한다.

## 테스트

- 파일과 메타데이터를 선택하면 `useUploadDocuments`가 정확한 payload로 한 번 호출된다.
- 업로드 전에는 mutation이 호출되지 않는다.
- 성공 응답의 `jobId`로 실제 진행 라우트에 이동한다.
- 업로드 실패 시 미리보기 완료 데이터와 가짜 성공 알림을 만들지 않는다.
- 기존 lint, Vitest, Vite production build가 통과한다.

## 완료 기준

- 브라우저 Network에 `POST /api/v1/documents`와 `202`가 표시된다.
- MySQL `document`·`ai_job`에 행이 생성된다.
- `ajt-develop-files`의 `/data/ajt/documents` 아래에 원본 파일이 생성된다.
- 화면은 반환된 `jobId`의 실제 상태를 조회한다.
- 새로고침 후에도 서버 문서와 작업 상태를 다시 조회할 수 있다.
