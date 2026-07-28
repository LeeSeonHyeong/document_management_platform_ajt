# Postman API 명세 설계

## 목적

AJT 팀이 Postman에서 API 요청·응답 구조를 먼저 작성하고 합의하며, Notion에는 확정 내용과 진행 상태를 정리한다.

## 산출물

- `AJT-Backend-Public-API.postman_collection.json`
  - Frontend가 Spring Boot에 호출하는 `/api/v1` API
- `AJT-FastAPI-Internal-API.postman_collection.json`
  - Spring Boot가 FastAPI에 호출하는 `/internal/v1` API
- `AJT-Local.postman_environment.json`
  - 로컬 서버 주소, 액세스 토큰, 내부 API 키

## Collection 구성

- 공개 API는 인증, 사용자, 부서, 문서 카테고리, 문서, Wiki·질문, 일정, 문의 폴더로 구분한다.
- 내부 API는 문서 파싱, Wiki 변환, Wiki 수정, 일정 추출, 답변 생성 요청으로 구분한다.
- 공개 API는 Collection 수준 Bearer Token을 사용하고 인증 API만 `No Auth`로 재정의한다.
- 내부 API는 `X-Internal-API-Key`를 사용한다.

## 요청별 Docs 형식

각 Postman Request의 `description`에 다음 항목을 기록한다.

1. 설명과 사용 화면
2. Authorization
3. Path Params
4. Query Params
5. Request Body
6. 업무 정책
7. Response
8. Error

상세 설명은 사용자가 제공한 댓글 작성 API 예시와 같은 간결한 문장형으로 작성한다.

## 관리 원칙

- Postman에서 요청과 응답 구조를 먼저 작성하고 합의한다.
- Notion에는 확정된 API 목록과 Postman 링크를 정리한다.
- 별도 Swagger/OpenAPI 명세는 이번 산출물에 포함하지 않는다.
- Postman Collection은 import 즉시 요청 구조와 Docs를 확인할 수 있어야 한다.

## 개발 계약 v1.0.0

- `contractVersion`은 `1.0.0`으로 고정하며 요청 URL, HTTP 메서드, 필드명, 필드 타입과 응답 코드는 팀 합의 없이 변경하지 않는다.
- 첫 주 병렬 개발에 필요한 핵심 공개 API를 P0로 지정하고 성공 응답과 대표 오류 응답을 Postman Saved Example로 제공한다.
- Spring Boot와 FastAPI 사이의 내부 API 6개는 모두 P0이며 성공 응답과 대표 오류 응답을 제공한다.
- 오류 응답은 `timestamp`, `status`, `error`, `code`, `message`, `path`, `fieldErrors` 구조를 공통으로 사용한다.
- 날짜·시각은 RFC 3339 UTC 문자열, API의 ID는 문자열, 목록의 데이터가 없으면 빈 배열, 단일 선택값이 없으면 `null`을 사용한다.
- P0가 아닌 API도 URL과 요청 Docs는 계약에 포함하지만 Saved Example 작성은 구현 화면이 시작될 때 추가한다.

### P0 공개 흐름

- 인증: 로그인, 회원가입
- 사용자 관리: 내 정보, 사용자 목록, 가입 승인
- 부서: 부서 목록
- 문서 처리: Wiki 원본문서 업로드, AI 작업 상태
- Wiki와 질문: Wiki 공간·카테고리·목록·상세, 질문 전송과 이력
- 일정: 원본문서 업로드, 목록, 직접 생성, 상세
- 문의: 담당자 후보, 등록, 목록, 상세

## 개발 소유권

- AI 담당자는 FastAPI 내부 API 6개와 모델 출력 구조를 소유한다.
- AI 연동 백엔드 담당자는 문서·Wiki·일정 파일 처리와 질문 오케스트레이션을 소유한다.
- 공개 백엔드 담당자는 인증·사용자·부서·문의와 일반 CRUD API를 소유한다.
- 프론트엔드 3명은 인증·Wiki·챗봇, 사원 일정·문의, 관리자 화면으로 나누고 P0 Saved Example을 Mock 데이터 계약으로 사용한다.
