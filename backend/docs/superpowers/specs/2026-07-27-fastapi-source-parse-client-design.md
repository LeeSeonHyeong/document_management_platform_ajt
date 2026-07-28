# FastAPI 원본문서 파싱 클라이언트 설계

## 목적

Spring Boot가 Postman 개발 계약 v1.0.0의
`POST /internal/v1/source-parses`를 호출할 수 있도록 공통 AI 서버 HTTP
클라이언트 기반과 첫 번째 내부 API 연동을 구현한다.

이 작업은 Jira `S15P11B106-107`의 범위이며, AI 서버 측 대응 작업은
`S15P11B106-76`이다.

## 확정 계약

- Frontend는 FastAPI를 직접 호출하지 않는다.
- Spring Boot만 FastAPI의 `/internal/v1` API를 호출한다.
- 모든 호출에 `X-Internal-API-Key`를 포함한다.
- 요청은 `multipart/form-data`이며 다음 파트를 정확히 사용한다.
  - `requestId`: 내부 요청 추적 ID
  - `sourceType`: `wiki` 또는 `schedule`
  - `sourceId`: Wiki `documentId` 또는 일정 `sourceGroupKey`
  - `file`: 원본문서 바이너리
  - `originalFileName`: 검증된 원본 파일명
  - `mimeType`: 검증된 MIME 타입
- 성공 응답은 `requestId`, `sourceType`, `sourceId`, `parsedMarkdown`,
  `warnings`를 반환한다.
- 내부 API의 DB ID는 문자열로 송수신한다.
- FastAPI는 DB와 서비스 파일을 직접 변경하지 않는다.
- Postman Saved Example과 다른 구현이 필요하면 코드를 임의로 바꾸지 않고
  계약 변경 절차를 먼저 수행한다.

## 접근 방식

### 선택: Spring `RestClient`

현재 백엔드가 Spring Web MVC 기반이므로 동기식 `RestClient`를 사용한다.
첫 번째 연동에서 설정, 인증 헤더, multipart 구성, 오류 변환을 명시적으로
검증하기 쉽고 별도의 반응형 의존성이 필요하지 않다.

### 제외한 대안

- `WebClient`: 스트리밍이나 반응형 체인이 현재 요구사항에 없고 WebFlux
  의존성과 테스트 복잡도만 증가한다.
- 선언형 HTTP 인터페이스: 반복 코드는 줄지만 초기 공통 오류 변환과 multipart
  세부 동작이 숨겨져 첫 계약 연동을 검증하기 어렵다.

## 구성 요소

### `AiApiProperties`

`ajt.ai` 아래 설정을 타입 안전하게 바인딩한다.

- `base-url`: 기본값 `http://localhost:8000`
- `internal-api-key`: 로컬 기본값 `local-dev-key`
- `connect-timeout`: 기본값 5초
- `read-timeout`: 기본값 180초

운영 환경에서는 `AI_BASE_URL`, `AI_INTERNAL_API_KEY`,
`AI_CONNECT_TIMEOUT`, `AI_READ_TIMEOUT` 환경변수로 덮어쓴다.
180초는 일정 원본문서 파싱과 일정 추출을 한 요청에서 동기로 처리하는 기존
상한과 일치한다.

### `AiApiConfig`

타임아웃이 적용된 `ClientHttpRequestFactory`와 `RestClient`를 구성한다.
기본 URL과 `X-Internal-API-Key` 헤더는 이 설정에서 한 번만 적용하고 개별
API 메서드에서 중복하지 않는다.

### `AiClient`

도메인 코드가 HTTP 구현에 직접 의존하지 않도록 다음 계약을 제공한다.

```java
SourceParseResponse parseSource(SourceParseRequest request);
```

나머지 내부 API 5개는 각 후속 Jira 작업에서 이 인터페이스에 메서드를 추가한다.

### `RestClientAiClient`

`AiClient`의 HTTP 구현체다. Spring MVC의 `MultiValueMap<String, Object>`와
`HttpEntity<Resource>`로 여섯 개 파트를 구성하고
`POST /internal/v1/source-parses`를 호출한다. 이 방식은 WebFlux·Reactive Streams
의존성을 추가하지 않고 `RestClient`의 multipart 변환기를 사용한다.

파일 파트는 Spring `Resource`로 받되 multipart의 파일명과 Content-Type은
각각 `originalFileName`, `mimeType`을 사용한다. 저장 경로와 DB 접속 정보는
요청이나 응답에 포함하지 않는다.

### 요청·응답 모델

- `SourceType`: `WIKI`, `SCHEDULE`을 가지며 전송값은 각각 `wiki`,
  `schedule`이다.
- `SourceParseRequest`: `requestId`, `sourceType`, `sourceId`, `file`,
  `originalFileName`, `mimeType`
- `SourceParseResponse`: `requestId`, `sourceType`, `sourceId`,
  `parsedMarkdown`, `List<String> warnings`
- `AiApiErrorResponse`: 공통 오류 응답의 `timestamp`, `status`, `error`,
  `code`, `message`, `path`, `fieldErrors`

필수 값이 없거나 응답 구조가 계약과 다르면 네트워크 호출 또는 후속 처리를
계속하지 않는다.

## 오류 처리

공개 API용 `BusinessException`으로 즉시 바꾸지 않는다. 이 클라이언트는 Wiki
비동기 작업과 일정 동기 작업에서 함께 사용되므로 호출자가 작업 실패 상태와
공개 응답을 결정해야 한다.

`AiClientException`은 다음 정보를 보존한다.

- 실패 유형: `BAD_REQUEST`, `UNAUTHORIZED`, `SERVER_ERROR`,
  `UNEXPECTED_STATUS`, `CONNECTION_FAILED`, `TIMEOUT`, `INVALID_RESPONSE`
- FastAPI HTTP 상태 코드
- FastAPI 업무 오류 코드와 메시지
- 필드 오류
- 원인이 된 예외

매핑 규칙은 다음과 같다.

- `400` → `BAD_REQUEST`
- `401` → `UNAUTHORIZED`
- `500` 이상 → `SERVER_ERROR`
- 그 밖의 오류 상태 → `UNEXPECTED_STATUS`
- 연결 실패 → `CONNECTION_FAILED`
- 연결·읽기 타임아웃 → `TIMEOUT`
- 성공 상태지만 JSON이 계약과 다름 → `INVALID_RESPONSE`

FastAPI 응답 본문을 로그에 그대로 출력하지 않고, 파일 내용과 내부 API 키는
예외 메시지나 로그에 포함하지 않는다.

## 데이터 흐름

1. 호출자는 접근 권한과 파일 메타데이터를 검증한다.
2. 호출자는 `Resource`와 계약 필드로 `SourceParseRequest`를 만든다.
3. `RestClientAiClient`가 인증 헤더와 multipart 본문을 구성한다.
4. FastAPI가 Markdown과 경고 목록을 반환한다.
5. 클라이언트가 계약 응답을 역직렬화해 호출자에게 반환한다.
6. 호출자가 Wiki 비동기 작업 또는 일정 동기 처리의 다음 단계를 수행한다.

## 테스트 전략

`MockRestServiceServer`로 실제 `RestClient` 요청을 검사한다.

- 정확한 HTTP 메서드와 경로
- `X-Internal-API-Key` 헤더
- multipart Content-Type과 여섯 개 파트
- 파일명과 MIME 타입
- Postman 200 Saved Example 역직렬화
- Postman 400 Saved Example 오류 변환
- `401`, `500`, 예상하지 못한 오류 상태 변환
- 연결 실패와 타임아웃 분류
- 누락되거나 잘못된 성공 응답의 `INVALID_RESPONSE` 변환

구현 전 기존 백엔드 전체 테스트를 기준선으로 실행하고, 구현 후 신규 테스트와
전체 테스트를 모두 실행한다.

## 제외 범위

- 나머지 FastAPI 내부 API 5개
- FastAPI 파싱 파이프라인 자체
- Wiki·일정 DB 저장과 후속 작업 오케스트레이션
- 공개 Controller 또는 Frontend 변경
- Postman 계약과 버전 변경
