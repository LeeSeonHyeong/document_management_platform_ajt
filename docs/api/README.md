# AJT Postman 개발 계약

## 계약 버전

- 현재 버전: `1.12.1`
- 기준 요구사항: `docs/requirements/요구사항정의서.md` v2.19
- 공개 API: 60개
- Spring Boot → FastAPI 내부 API: 5개
- FastAPI → Spring Boot Wiki 조회 API: 8개
- FastAPI → Spring Boot 일정 조회 API: 2개

> 세 방향 모두 `AJT-FastAPI-Internal-API` 컬렉션에 있다. 조회 API는 FastAPI가 호출자이고
> Spring Boot가 응답한다 — `baseVariable`이 `backendBaseUrl`인 요청이 그것이다.

URL, HTTP 메서드, 필드명, 필드 타입, 상태 코드와 P0 Saved Example은 프론트엔드·Spring Boot·FastAPI가 함께 사용하는 개발 계약이다. 변경이 필요하면 소비 담당자와 먼저 합의하고 `contractVersion`을 올린 뒤 컬렉션을 다시 배포한다.

## Import 순서

Postman에서 아래 파일 3개를 모두 Import한다.

1. `AJT-Backend-Public-API.postman_collection.json`
2. `AJT-FastAPI-Internal-API.postman_collection.json`
3. `AJT-Local.postman_environment.json`

`AJT Local` Environment를 선택하고 다음 값을 설정한다.

- `backendBaseUrl`: Spring Boot 로컬 주소
- `aiBaseUrl`: FastAPI 로컬 주소
- `internalApiKey`: Spring Boot와 FastAPI가 공유하는 내부 API 키
- `wikiCapability`: Wiki 조회 창구를 Postman에서 직접 호출해 볼 때만 채운다. 실제 운영에서는 Spring Boot가 변환·수정 요청마다 발급하므로 사람이 넣는 값이 아니다

Frontend는 `AJT Backend Public API`만 호출한다. `AJT FastAPI Internal API`는 Spring Boot만 호출한다.

공개 API 인증은 `AJT_ACCESS_TOKEN` HttpOnly 쿠키를 사용한다. 로그인 응답의 쿠키를 Postman Cookie Jar에 저장하고, 상태 변경 요청 전 `GET /api/v1/auth/csrf`로 받은 `XSRF-TOKEN` 값을 `csrfToken` 환경 변수와 `X-XSRF-TOKEN` 헤더로 전송한다. 로그아웃은 `POST /api/v1/auth/logout`이 인증 쿠키를 만료한다.

## Saved Example 사용법

P0 Request의 `Examples`에는 성공 응답과 대표 오류 응답이 저장되어 있다.

- 프론트엔드는 성공 Example을 화면 Mock 데이터와 응답 타입 작성 기준으로 사용한다.
- 백엔드는 요청 Docs와 성공·오류 Example을 Controller 응답 기준으로 사용한다.
- AI 서버는 내부 API 5개의 요청 본문과 Example을 Pydantic 모델 기준으로 사용한다.
- 실제 구현 응답이 Example과 다르면 구현자가 임의로 맞추지 않고 계약 변경 절차를 따른다.

## 공통 데이터 규칙

- JSON 필드명: `camelCase`
- Enum 값: 소문자 `snake_case`
- 업무 오류 코드: 대문자 `SNAKE_CASE`
- ID: JSON 문자열
- 날짜·시각: RFC 3339 UTC 문자열
- 목록 결과 없음: `[]`
- 단일 선택값 없음: `null`
- 페이지 응답: `items`, `page`, `size`, `totalCount`, `totalPages`
- FastAPI 내부 API 응답: 계약에 정의되지 않은 필드는 소비자가 무시한다. 추가 필드는 하위 호환으로 제공하며, 필수 필드의 삭제·의미 변경은 minor 이상 계약 버전 변경이 필요하다.

Wiki 변환·문맥 선택 오류 응답은 공통 오류 구조에 선택 필드 `failureStage`를 추가할 수 있다. 값은 `context_load`, `agent_timeout`, `agent_error`, `lint_failed`, `assemble`, `scope_changed`이며 Spring Boot는 이를 `ai_job.document_results` JSON의 문서별 실패 단계로 저장한다. `scope_changed`는 변환 중 같은 `scope_key`의 Wiki가 바뀌어(DR-030) 반영하지 않고 종료한 경우다.

공통 오류 응답은 다음 구조를 사용한다.

```json
{
  "timestamp": "2026-07-27T09:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_REQUEST",
  "message": "요청값을 확인해주세요.",
  "path": "/api/v1/example",
  "fieldErrors": [
    {
      "field": "title",
      "reason": "제목은 비어 있을 수 없습니다."
    }
  ]
}
```

필드 오류가 없으면 `fieldErrors`는 빈 배열로 반환한다. 권한이 없는 데이터의 존재를 숨겨야 하는 상세 조회는 요구사항에 따라 `404 Not Found`를 사용할 수 있다.

## P0 공개 API

첫 주 병렬 개발 시 다음 API부터 구현한다.

| 영역 | Method | Path |
|---|---|---|
| 인증 | POST | `/api/v1/auth/login` |
| 인증 | POST | `/api/v1/auth/signup` |
| 인증 | GET | `/api/v1/signup-departments` |
| 사용자 | GET | `/api/v1/me` |
| 사용자 | GET | `/api/v1/users` |
| 사용자 | POST | `/api/v1/signup-requests/{userId}/approve` |
| 부서 | GET | `/api/v1/departments` |
| 문서 | POST | `/api/v1/documents` |
| 문서 | GET | `/api/v1/ai-jobs/{jobId}` |
| Wiki | GET | `/api/v1/wiki-spaces` |
| Wiki | GET | `/api/v1/wiki-categories` |
| Wiki | GET | `/api/v1/wikis` |
| Wiki | GET | `/api/v1/wikis/{wikiId}` |
| 질문 | POST | `/api/v1/questions` |
| 질문 | GET | `/api/v1/questions` |
| 일정 | POST | `/api/v1/schedule-sources` |
| 일정 | GET | `/api/v1/schedules` |
| 일정 | POST | `/api/v1/schedules` |
| 일정 | GET | `/api/v1/schedules/{scheduleId}` |
| 문의 | GET | `/api/v1/inquiry-assignees` |
| 문의 | POST | `/api/v1/inquiries` |
| 문의 | GET | `/api/v1/inquiries` |
| 문의 | GET | `/api/v1/inquiries/{inquiryId}` |

FastAPI 내부 API 5개는 모두 P0다.

## 6명 역할

| 담당 | 소유 범위 |
|---|---|
| AI 서버 1명 | FastAPI 내부 API 5개, 파싱·Wiki 변환·Wiki 관리자 수정·일정 추출·답변 생성 |
| AI 연동 백엔드 1명 | 문서·Wiki·일정 파일 처리, 작업 직렬화, 질문 오케스트레이션과 내부 API 호출 |
| 공개 백엔드 1명 | 인증·사용자·부서·문의, 공개 CRUD API와 공통 오류 응답 |
| 프론트엔드 1명 | 로그인·회원가입·내 정보·사원 Wiki·챗봇 |
| 프론트엔드 1명 | 사원 일정·문의 |
| 프론트엔드 1명 | 관리자 사용자·부서·문서·Wiki·일정·문의 |

두 백엔드 담당자는 도메인 소유권과 무관하게 공통 인증·예외 형식·파일 저장 규칙을 함께 고정한다.

## 계약 변경 절차

1. 변경 제안자가 영향받는 Request와 소비 담당자를 적는다.
2. 요청·응답 Example을 먼저 수정한다.
3. 프론트엔드·공개 백엔드·AI 서버 중 영향받는 담당자가 변경을 확인한다.
4. 하위 호환 변경은 patch, 호환되지 않는 변경은 minor 이상으로 `contractVersion`을 올린다.
5. `node postman/generate-postman-collections.mjs`를 실행한다.
6. `node scripts/validate-artifact-consistency.mjs`가 통과한 컬렉션만 팀에 공유한다.

생성된 JSON을 Postman에서 직접 수정한 뒤 생성기를 실행하면 변경 내용이 사라진다. 계약 변경은 `generate-postman-collections.mjs` 또는 `postman-contract-examples.mjs`에서 수행한다.
