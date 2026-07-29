# REST API 개발 컨벤션

## 1. 목적

이 문서는 프로젝트의 REST API를 일관된 방식으로 설계하고 구현하기 위한 기준을 정의합니다.

모든 API는 이 문서와 Postman Collection을 기준으로 개발하며, Notion에는 확정 내용과 진행 상태를 정리합니다.

문서에 정의되지 않은 상황은 개발자가 임의로 결정하지 않고 팀에 공유하여 결정합니다. 새로운 결정사항은 이 문서 또는 관련 설계 문서에 반영합니다.

---

# 2. 핵심 규칙

| 구분 | 규칙 |
| --- | --- |
| API 방식 | REST |
| 데이터 형식 | JSON |
| 통신 방식 | HTTPS |
| 기본 경로 | `/api/v1` |
| 백엔드 공개 API 경로 | `/api/v1` |
| AI 서버 내부 API 경로 | `/internal/v1` |
| 공식 API 명세 | Postman Collection v2.1 |
| URL 리소스명 | 복수 명사 |
| URL 표기법 | kebab-case |
| JSON 필드명 | camelCase |
| 상태값 및 enum | 소문자 snake_case |
| 오류 코드 | 대문자 SNAKE_CASE |
| ID 타입 | 문자열 |
| 날짜 및 시간 | RFC 3339, UTC |
| 인증 방식 | JWT Bearer Token |
| 일반 수정 | PATCH |
| 입력값 오류 | `400 Bad Request` |
| 상태 충돌 및 중복 | `409 Conflict` |
| 단일 응답 | 리소스를 직접 반환 |
| 목록 응답 | `items` 배열 |
| 빈 목록 | 빈 배열 `[]` |
| 오류 응답 | `timestamp`, `status`, `error`, `code`, `message`, `path`, `fieldErrors` |
| 요청 추적 | `X-Request-Id` 응답 헤더 |
| 업무 DB | MySQL 8.4 LTS |
| DB 시간 타입 | UTC `DATETIME(6)` |
| DB 상태 타입 | `VARCHAR(30)` + 애플리케이션 Enum |

---

# 2.1 서버 구성과 API 경계

프론트엔드는 백엔드 서버의 공개 API만 호출합니다.

```
React Frontend → Backend Server → FastAPI AI Server
```

AI 서버 API는 백엔드 서버 내부 호출 전용으로 운영합니다.

프론트엔드는 AI 서버를 직접 호출하지 않습니다.

DB 조회, DB 저장, 트랜잭션 처리, 권한 검증, 공개 범위 검증은 백엔드 서버가 담당합니다.

AI 서버는 DB를 직접 조회하거나 수정하지 않습니다.

AI 서버는 백엔드 서버가 전달한 입력 데이터만 처리하고, 문서 파싱 결과, Wiki 변환 결과, 일정 추출 결과, 답변 자료 선택 결과와 Wiki 채팅 수정 결과를 반환합니다.

백엔드 서버는 AI 서버 응답을 검증한 뒤 현재 Wiki와 DB에 즉시 반영합니다. Wiki 변환에는 관리자 사전 승인·반려 단계를 두지 않습니다.

| 구분 | 역할 |
| --- | --- |
| React Frontend | 사용자 화면, 백엔드 공개 API 호출 |
| Backend Server | 인증, 권한, DB 접근, 문서/위키/일정 상태 관리, AI 작업 오케스트레이션 |
| FastAPI AI Server | 텍스트 추출·분석, 일정 추출, Wiki와 Wiki 카테고리 생성·수정·병합·제거 결과 생성 |
| Database | 백엔드 서버를 통해서만 접근 |

---

# 3. URL과 HTTP 메서드

## 3.1 URL 작성 규칙

URL은 리소스를 나타내는 복수 명사로 작성합니다.

두 단어 이상의 리소스명은 kebab-case를 사용합니다.

```
/api/v1/users
/api/v1/orders
/api/v1/order-items
```

일반적인 생성, 조회, 수정, 삭제는 URL에 동사를 사용하지 않고 HTTP 메서드로 표현합니다.

```
GET    /api/v1/documents
POST   /api/v1/documents
GET    /api/v1/documents/{documentId}
PATCH  /api/v1/documents/{documentId}
DELETE /api/v1/documents/{documentId}
```

단, 위 URL은 일반적인 REST 표현 예시입니다. 이 프로젝트에서는 사용자 계정을 삭제하지 않고 다음 API로 `accountStatus`를 `active` 또는 `inactive`로 변경하므로 사용자 삭제 API를 정의하지 않습니다.

```text
PATCH /api/v1/users/{userId}
```

경로 파라미터 이름에는 대상 리소스명을 포함합니다.

```
{userId}
{orderId}
{orderItemId}
```

리소스 관계는 필요한 경우에만 중첩 URL로 표현합니다.

```
GET /api/v1/users/{userId}/orders
```

URL 중첩은 가능한 한 2단계를 초과하지 않습니다.

ID만으로 직접 조회할 수 있는 리소스는 독립된 URL을 사용합니다.

```
GET /api/v1/order-items/{orderItemId}
```

로그인, 취소, 승인처럼 일반적인 CRUD만으로 의미를 명확하게 표현하기 어려운 동작은 예외적으로 URL에 동사를 사용할 수 있습니다.

```
POST /api/v1/auth/login
POST /api/v1/orders/{orderId}/cancel
POST /api/v1/users/{userId}/approve
```

## 3.2 HTTP 메서드

| 목적 | 메서드 |
| --- | --- |
| 목록 조회 | GET |
| 단건 조회 | GET |
| 생성 | POST |
| 전체 교체 | PUT |
| 일부 수정 | PATCH |
| 삭제 | DELETE |

일반적인 수정은 `PATCH`를 사용합니다.

`PUT`은 리소스 전체를 교체하는 경우에만 사용합니다.

GET 요청에는 요청 본문을 사용하지 않습니다. 조회 조건은 경로 파라미터 또는 쿼리 파라미터로 전달합니다.

---

# 4. 요청 데이터 규칙

## 4.1 JSON 규칙

| 항목 | 규칙 |
| --- | --- |
| 필드명 | camelCase |
| ID | 문자열 |
| Boolean | `true`, `false` |
| 빈 목록 | `[]` |
| 값이 없는 단일 필드 | `null` |
| 상태값 및 enum | 소문자 snake_case |
| 정의되지 않은 요청 필드 | `400 Bad Request` |
| 요청 객체 | 요청 DTO 사용 |
| 응답 객체 | 응답 DTO 사용 |

데이터베이스 엔티티를 API 요청이나 응답 객체로 직접 사용하지 않습니다.

API에서 ID는 문자열로 전달합니다. 다만 정렬과 비교는 데이터베이스에 저장된 원래 ID 타입을 기준으로 수행합니다.

Postman API Docs에 정의된 응답 필드는 원칙적으로 생략하지 않습니다.

- 값이 없는 단일 필드: `null`
- 값이 없는 목록 필드: `[]`
- 응답 스키마에 없는 필드: 반환하지 않음

요청 JSON에 Postman API Docs에 정의되지 않은 필드가 포함된 경우 `400 Bad Request`를 반환합니다.

## 4.2 PATCH 요청

PATCH 요청에서는 필드 생략과 명시적인 `null`을 구분합니다.

| 요청 형태 | 처리 기준 |
| --- | --- |
| 필드 생략 | 기존 값을 변경하지 않음 |
| nullable 필드에 `null` 전달 | 기존 값을 제거함 |
| null을 허용하지 않는 필드에 `null` 전달 | `400 Bad Request` |
| 값을 전달 | 전달된 값으로 변경 |

PATCH 요청 DTO는 필드가 전달되었는지 여부를 구분할 수 있도록 구현합니다.

## 4.3 파일 업로드 요청

문서 업로드처럼 파일 전송이 필요한 API는 예외적으로 `multipart/form-data`를 사용할 수 있습니다.

파일 업로드 API도 응답은 JSON으로 반환합니다.

파일 업로드 API는 업로드 가능한 파일 형식, 개별 최대 용량, 최대 파일 개수, 요청당 총 파일 크기와 필수 메타데이터를 Postman API Docs에 정의합니다.

요청 전체의 `Content-Type`이 지원되지 않으면 `415 Unsupported Media Type`을 반환합니다.

정상적인 `multipart/form-data` 요청에 허용되지 않은 파일 확장자 또는 MIME 타입이 포함되면 `400 Bad Request`와 오류 코드 `UNSUPPORTED_FILE_TYPE`을 반환합니다.

파일 크기 초과는 `400 Bad Request`와 오류 코드 `FILE_SIZE_EXCEEDED`를 반환합니다.

파일 개수 초과는 `400 Bad Request`와 오류 코드 `FILE_COUNT_EXCEEDED`, 파일 배열의 총 크기 초과는 `400 Bad Request`와 오류 코드 `TOTAL_FILE_SIZE_EXCEEDED`를 반환합니다.

초기 허용 형식은 다음과 같습니다.

| 용도 | 허용 형식 | 추가 제한 |
| --- | --- | --- |
| Wiki 원본문서 | TXT, MD, PDF, DOCX | 파일당 20MB, 최대 20개, 총 100MB |
| 일정 원본문서 | TXT, MD, DOCX, PDF, CSV, XLSX | 파일당 20MB, 요청당 1개 |
| 일정 첨부파일 | TXT, MD, DOCX, PDF, CSV, XLSX | 파일당 20MB, 최대 20개, 총 100MB |
| 문의 첨부파일 | PNG, JPG, JPEG | 파일당 20MB, 최대 5개, 총 100MB |

---

# 5. 응답 규칙

## 5.1 성공 응답

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회 또는 수정 성공 |
| `201 Created` | 리소스 생성 성공 |
| `204 No Content` | 응답 본문이 필요 없는 성공 |

`201 Created` 응답에는 가능한 경우 생성된 리소스의 URL을 `Location` 헤더로 반환합니다.

`204 No Content` 응답에는 응답 본문을 포함하지 않습니다.

별도의 비동기 작업 처리 체계가 없는 경우 `202 Accepted`는 사용하지 않습니다.

## 5.2 단일 응답

단일 리소스는 `data` 등의 공통 필드로 감싸지 않고 직접 반환합니다.

```json
{
  "id": "123",
  "name": "홍길동"
}
```

## 5.3 일반 목록

페이지네이션이 필요하지 않은 목록은 `items` 배열로 반환합니다.

```json
{
  "items": []
}
```

## 5.4 페이지 목록

페이지네이션 목록은 다음 필드를 반환합니다.

```json
{
  "items": [],
  "page": 1,
  "size": 20,
  "totalCount": 0,
  "totalPages": 0
}
```

| 필드 | 설명 |
| --- | --- |
| `items` | 현재 페이지 데이터 |
| `page` | 현재 페이지 번호 |
| `size` | 페이지 크기 |
| `totalCount` | 전체 데이터 수 |
| `totalPages` | 전체 페이지 수 |

계속 증가할 가능성이 있는 목록은 페이지네이션을 적용합니다.

고정된 소수의 데이터만 반환하는 목록은 페이지네이션을 적용하지 않을 수 있습니다.

## 5.5 삭제 응답

삭제 대상 리소스가 존재하면 삭제 후 `204 No Content`를 반환합니다.

삭제 대상 리소스가 존재하지 않으면 `404 Not Found`를 반환합니다.

관리자가 Wiki 하나를 직접 삭제하는 공개 API는 제공하지 않습니다.

Wiki 제거는 AI의 문서 변환 또는 Wiki 채팅 수정 결과로만 수행하며, 제거가 확정되면 Wiki 파일과 관계 데이터를 하드 삭제합니다. 삭제된 Wiki의 복구, 휴지통, 비공개 전환과 과거 버전 조회 기능은 제공하지 않습니다.

따라서 다음 API는 정의하지 않습니다.

```
DELETE /api/v1/wikis/{wikiId}
```

## 5.6 비동기 작업 응답

Wiki 원본문서 파싱과 Wiki 변환은 비동기 작업으로 처리합니다. 일정 원본문서 파싱과 일정 추출은 한 요청에서 최대 180초 동안 동기로 처리하며 `ai_job`을 만들지 않습니다.

비동기 작업 생성 API는 `202 Accepted`를 반환할 수 있습니다.

`202 Accepted` 응답에는 작업 식별자와 현재 상태를 포함합니다.

```json
{
  "jobId": "job_123",
  "status": "waiting",
  "createdAt": "2026-07-20T10:30:00Z"
}
```

비동기 작업 상태는 별도 조회 API로 확인합니다.

```
GET /api/v1/ai-jobs/{jobId}
```

작업 상태 enum은 다음 값을 기본으로 사용합니다.

| 상태 | 의미 |
| --- | --- |
| `waiting` | 작업 대기 |
| `processing` | 작업 처리 중 |
| `completed` | 작업 성공 |
| `failed` | 작업 실패 |
| `cancelled` | 작업 취소 |

작업 실패 시 실패 단계와 실패 사유를 응답에 포함합니다.

실패한 작업만 재처리할 수 있으며, 이미 성공한 작업의 재처리 요청은 `409 Conflict`로 처리합니다.

Wiki 변환 작업은 전역에서 한 작업씩 직렬로 처리합니다. 동일 업로드 묶음에서도 문서를 업로드 순서대로 한 건씩 처리하고, 각 문서 결과를 완료 즉시 반영합니다.

---

# 6. 오류 처리와 요청 추적

## 6.1 실패 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `400 Bad Request` | 요청 형식, 타입, 필수값, 입력값 검증 또는 쿼리 파라미터 오류 |
| `401 Unauthorized` | 인증 정보가 없거나 유효하지 않음 |
| `403 Forbidden` | 인증되었지만 해당 작업 권한이 없음 |
| `404 Not Found` | 요청한 리소스 또는 API 경로가 존재하지 않음 |
| `405 Method Not Allowed` | 해당 URL에서 지원하지 않는 HTTP 메서드를 사용함 |
| `409 Conflict` | 중복 데이터 또는 현재 리소스 상태와 충돌 |
| `415 Unsupported Media Type` | 지원하지 않는 Content-Type으로 요청함 |
| `429 Too Many Requests` | 요청 횟수 제한 초과 |
| `500 Internal Server Error` | 처리되지 않은 서버 내부 오류 |

입력값 검증 오류는 `400 Bad Request`로 통일합니다.

`422 Unprocessable Content`는 사용하지 않습니다.

FastAPI AI 서버의 기본 validation error도 `422 Unprocessable Content`로 반환하지 않고 `400 Bad Request`와 공통 오류 응답 구조로 변환합니다.

중복 생성, 이미 완료된 상태 전환, 현재 상태에서 허용되지 않는 작업은 `409 Conflict`로 처리합니다.

권한 오류에서 자료의 존재 여부가 노출되지 않도록 다음 기준을 적용합니다.

| 상황 | 상태 코드 |
| --- | --- |
| 사원이 관리자 전용 기능을 요청하는 등 역할 자체가 부족함 | `403 Forbidden` |
| 인증된 사용자가 공개 범위 권한이 없는 문서·Wiki·일정을 ID로 조회함 | `404 Not Found` |
| 실제로 존재하지 않는 문서·Wiki·일정을 조회함 | `404 Not Found` |

권한이 없는 자료와 존재하지 않는 자료는 동일한 오류 코드와 메시지를 사용합니다.

## 6.2 오류 응답 구조

모든 API 오류는 동일한 구조로 반환합니다.

```json
{
  "timestamp": "2026-07-27T09:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "ERROR_CODE",
  "message": "오류에 대한 기본 설명입니다.",
  "path": "/api/v1/example",
  "fieldErrors": []
}
```

| 필드 | 설명 |
| --- | --- |
| `timestamp` | 오류 응답 생성 시각 |
| `status` | HTTP 상태 코드 |
| `error` | HTTP 상태 코드의 표준 설명 |
| `code` | 클라이언트와 서버가 오류를 구분하기 위한 고정 코드 |
| `message` | 오류에 대한 기본 설명 |
| `path` | 오류가 발생한 API 경로 |
| `fieldErrors` | 필드 검증 오류 목록 |

오류 코드는 대문자 SNAKE_CASE를 사용합니다.

클라이언트는 `message`가 아닌 `code`를 기준으로 오류를 처리합니다.

필드 검증 오류가 없는 경우 `fieldErrors`는 빈 배열로 반환합니다.

입력값 검증 오류의 `fieldErrors`는 다음 구조를 사용합니다.

```json
{
  "field": "email",
  "reason": "올바른 이메일 형식이 아닙니다."
}
```

## 6.3 공통 오류 처리 범위

다음 오류는 모두 공통 오류 응답 구조로 변환합니다.

- 잘못된 JSON
- 요청 필드 타입 오류
- 필수 요청값 누락
- 입력값 검증 실패
- 지원하지 않는 쿼리 파라미터
- 지원하지 않는 정렬 필드
- 인증 실패
- 권한 부족
- 존재하지 않는 API 경로
- 지원하지 않는 HTTP 메서드
- 지원하지 않는 Content-Type
- 처리되지 않은 서버 예외

애플리케이션 계층뿐 아니라 프레임워크와 보안 계층에서 발생한 오류도 동일한 형식으로 반환합니다.

`401 Unauthorized` 응답에는 다음 헤더를 포함합니다.

```
WWW-Authenticate: Bearer
```

## 6.4 요청 추적

서버는 모든 요청에 고유한 `requestId`를 부여합니다.

동일한 `requestId`를 다음 위치에 기록합니다.

- 서버 로그
- 모든 응답의 `X-Request-Id` 헤더

오류 응답 본문에는 `requestId`를 포함하지 않고 `X-Request-Id` 응답 헤더로 제공합니다.

```
X-Request-Id: 01KABCDEF123456789
```

외부에서 전달된 요청 ID를 사용할지 여부는 서버 공통 모듈 정책으로 통일합니다.

백엔드 서버가 AI 서버를 호출하는 경우 동일한 `requestId`를 AI 서버에 전달합니다.

AI 서버는 전달받은 `requestId`를 로그와 `X-Request-Id` 응답 헤더에 사용합니다.

AI 서버가 직접 `requestId`를 새로 생성해야 하는 경우에도 `X-Request-Id` 응답 헤더와 오류 응답 구조는 백엔드 서버와 동일하게 유지합니다.

## 6.5 내부 정보 노출 금지

다음 정보는 API 응답에 포함하지 않습니다.

- 서버 내부 예외 메시지
- 데이터베이스 오류 메시지
- SQL
- 스택 트레이스
- 서버 파일 경로
- 내부 클래스명
- 비밀번호 및 비밀번호 해시
- 액세스 토큰 및 리프레시 토큰
- Authorization 헤더
- 인증 쿠키

---

# 7. 날짜, 상태값, 페이지네이션

## 7.1 날짜와 시간

날짜와 시간은 RFC 3339 형식을 사용합니다.

시간이 포함된 값은 UTC로 저장하고 반환하며, UTC 표시는 `Z`를 사용합니다.

```
2026-07-20T10:30:00Z
```

날짜만 필요한 값은 `YYYY-MM-DD` 형식을 사용합니다.

```
1995-03-15
```

| 의미 | 필드명 |
| --- | --- |
| 생성 시간 | `createdAt` |
| 수정 시간 | `updatedAt` |
| 시작 시간 | `startedAt` |
| 종료 시간 | `endedAt` |
| 생년월일 | `birthDate` |

기본 시간 응답에는 밀리초를 포함하지 않습니다.

이 프로젝트는 사용자 계정 비활성화를 제외한 삭제 대상 업무 데이터를 하드 삭제하므로 공통 응답 필드로 `deletedAt`을 사용하지 않습니다.

## 7.2 상태값과 enum

상태값과 enum 값은 소문자 snake_case를 사용합니다.

```
active
payment_failed
waiting_for_approval
```

동일한 상태값은 요청 본문, 응답 본문, 경로 파라미터 및 쿼리 파라미터에서 같은 문자열을 사용합니다.

허용 가능한 값은 Postman API Docs에 enum 목록으로 정의합니다.

정의되지 않은 값이 전달되면 `400 Bad Request`를 반환합니다.

초기 API의 주요 enum은 다음 값을 사용합니다.

| 필드 | 허용값 |
| --- | --- |
| `role` | `employee`, `admin` |
| `signupStatus` | `pending`, `approved`, `rejected` |
| `accountStatus` | `active`, `inactive` |
| Wiki 공개 유형 | `all`, `department` |
| 일정 공개 유형 | `all`, `department`, `personal` |
| 문서 처리 상태 | `uploaded`, `parsing`, `processing`, `completed`, `failed`, `cancelled` |
| AI 작업 상태 | `waiting`, `processing`, `completed`, `failed`, `cancelled` |
| 일정 상태 | `draft`, `approved` |
| 질문 유형 | `wiki`, `schedule`, `mixed` |
| Wiki 채팅 발신자 | `admin`, `agent` |
| 문의 우선순위 | `high`, `normal`, `low` |
| 문의 상태 | `pending`, `done` |

Wiki에는 승인·반려·비공개 상태를 두지 않으며, 거부된 일정 초안은 `rejected` 상태로 저장하지 않고 하드 삭제합니다.

## 7.3 페이지네이션

페이지네이션은 `page`와 `size` 쿼리 파라미터를 사용합니다.

| 항목 | 값 |
| --- | --- |
| 첫 페이지 | `1` |
| 기본 페이지 크기 | `20` |
| 최대 페이지 크기 | `100` |

다음 요청은 `400 Bad Request`로 처리합니다.

- `page`가 1보다 작은 경우
- `size`가 1보다 작은 경우
- `size`가 100보다 큰 경우
- `page` 또는 `size`가 숫자가 아닌 경우

잘못된 값을 서버에서 자동으로 보정하지 않습니다.

페이지네이션을 사용하는 목록 API는 반드시 기본 정렬을 정의합니다.

별도 기준이 없는 경우 다음 정렬을 사용합니다.

1. `createdAt` 내림차순
2. `id` 내림차순

ID 보조 정렬은 API 응답의 문자열 표현이 아니라 데이터베이스에 저장된 실제 ID 타입을 기준으로 수행합니다.

## 7.4 검색, 필터 및 정렬

검색, 필터 및 정렬 조건은 쿼리 파라미터로 전달합니다.

정렬은 `sort` 파라미터를 사용합니다.

```
GET /api/v1/users?status=active
GET /api/v1/users?keyword=홍길동
GET /api/v1/users?sort=createdAt,desc
```

정렬 방향은 다음 값만 허용합니다.

- `asc`
- `desc`

하나의 요청에서는 하나의 정렬 조건만 지원합니다.

각 API는 지원하는 검색 조건, 필터 필드, 필터값 및 정렬 필드를 Postman API Docs에 정의합니다.

지원하지 않는 조건이나 값이 전달되면 `400 Bad Request`를 반환합니다.

---

# 8. 인증과 상태 전환

## 8.1 인증과 권한

인증이 필요한 API는 JWT 액세스 토큰을 사용합니다.

토큰은 `Authorization` 헤더를 통해 Bearer 방식으로 전달합니다.

```
Authorization: Bearer {accessToken}
```

JWT 액세스 토큰을 URL, 쿼리 파라미터 또는 요청 본문으로 전달하지 않습니다. 이메일 비밀번호 재설정은 URL 토큰 대신 8.2절의 6자리 인증번호 방식을 사용합니다.

| 상태 코드 | 처리 기준 |
| --- | --- |
| `401` | 토큰이 없거나 유효하지 않음 |
| `403` | 인증되었지만 해당 기능의 사용 권한이 없음 |

AI 서버 내부 API는 프론트엔드 사용자 JWT 인증 대상이 아닙니다.

AI 서버 내부 API 인증 방식은 백엔드 서버와 AI 서버 간 서버 인증 정책으로 분리합니다.

단, 사용자 역할, 부서, 공개 범위, 문서 접근 권한은 AI 서버가 아니라 백엔드 서버에서 검증합니다.

가입 상태가 `approved`가 아니거나 계정 상태가 `inactive`인 사용자는 유효한 토큰을 가지고 있어도 인증에 실패한 것으로 처리하고 `401 Unauthorized`를 반환합니다.

초기 범위에서는 액세스 토큰만 사용하며 리프레시 토큰 API는 제공하지 않습니다. 액세스 토큰 만료 후에는 다시 로그인해야 합니다.

회원가입 요청은 기본 역할 `employee`, 가입 상태 `pending`, 계정 상태 `inactive`로 저장합니다. 관리자가 승인하면 `approved`와 `active`로, 거부하면 `rejected`와 `inactive`로 전환하며 거부 행은 삭제하지 않습니다.

`pending` 또는 `approved` 이메일의 중복 가입은 `409 Conflict`를 반환합니다. `rejected` 이메일로 재신청하면 새 행을 만들지 않고 기존 회원 행의 비밀번호 해시, 이름과 부서를 갱신한 뒤 `pending`으로 전환합니다.

최초 전체 관리자와 이후 부서 관리자는 모두 같은 `admin` 역할을 사용합니다. 전체 관리자 전용 역할이나 플래그를 추가하지 않습니다. 부서 관리자는 부서 관리 API에서만 지정 또는 해제하며, `approved`·`active` 상태의 `admin`만 지정할 수 있습니다.

## 8.2 이메일 비밀번호 재설정

회원가입 시 이메일 인증은 수행하지 않습니다. 비밀번호 재설정에만 사용자의 등록 이메일을 사용합니다.

공개 API는 다음 세 개를 사용합니다.

```text
POST /api/v1/auth/password-reset-requests
POST /api/v1/auth/password-reset-verify
POST /api/v1/auth/password-resets
```

비밀번호 재설정 요청은 이메일 등록 여부와 관계없이 `200 OK`와 동일한 메시지를 반환합니다.

```json
{
  "message": "입력한 이메일이 등록되어 있다면 비밀번호 재설정 안내를 전송했습니다."
}
```

등록된 활성 계정이면 6자리 인증번호를 이메일로 전송합니다. 인증번호는 다음 규칙을 따릅니다.

- 유효시간은 5분입니다.
- 6자리 숫자이며 용도를 비밀번호 재설정으로 제한합니다.
- 틀린 인증번호를 5회 초과 시도하면 해당 인증번호를 폐기합니다.
- 비밀번호 변경에 성공하면 인증번호를 즉시 폐기합니다.
- 인증번호 원문 또는 해시를 DB에 저장하지 않고 서버 메모리에 보관합니다.
- 재설정 요청에는 IP와 이메일 기준 요청 횟수 제한을 적용하고 초과 시 `429 Too Many Requests`를 반환합니다.

프론트엔드는 이메일로 받은 인증번호를 `POST /api/v1/auth/password-reset-verify`(`email`, `code`)로 먼저 확인한 뒤, 비밀번호 수정 화면에서 `POST /api/v1/auth/password-resets`(`email`, `code`, `newPassword`)로 새 비밀번호를 전달합니다. 유효한 인증번호로 비밀번호를 변경하면 `204 No Content`를 반환하며 자동 로그인하지 않습니다.

유효하지 않거나 만료된 인증번호는 `400 Bad Request`와 `INVALID_OR_EXPIRED_RESET_CODE`를 반환합니다. 비밀번호 재설정 요청과 응답에는 이메일 등록 여부, 사용자 ID와 계정 상태를 노출하지 않습니다.

관리자가 다른 사용자의 비밀번호를 직접 변경하거나 로그인한 사용자가 기존 비밀번호로 직접 변경하는 API는 제공하지 않습니다. 비밀번호 변경은 이메일 재설정 흐름으로만 수행합니다.

## 8.3 동작형 API의 상태 전환

취소, 승인, 거절 등 상태를 변경하는 동작형 API는 허용 가능한 이전 상태와 변경 후 상태를 정의합니다.

이미 완료된 상태 전환을 다시 요청하거나 현재 상태에서 허용되지 않는 상태 전환을 요청한 경우 `409 Conflict`를 반환합니다.

상태 전환 규칙은 Postman API Docs와 Notion 또는 관련 설계 문서에 명시합니다.

일정 초안 승인 시 상태를 `draft`에서 `approved`로 변경합니다. 일정 초안을 거부하면 별도 상태로 보존하지 않고 해당 일정을 하드 삭제합니다.

가입 신청 승인·거부는 `pending` 상태에서만 허용합니다. 다른 상태에서 같은 동작을 요청하면 `409 Conflict`를 반환합니다.

## 8.4 부서 관리자와 문의 담당자

한 부서는 관리자를 최대 1명 지정할 수 있고 관리자 미지정 상태도 허용합니다. 한 관리자는 부서를 최대 1개만 담당합니다. 직원이 1명이라도 소속되어 있거나 다른 업무 데이터가 참조하는 부서는 삭제하지 않고 `409 Conflict`를 반환합니다.

문의 등록자는 현재 `department.manager_id`로 참조되는 `approved`·`active` 상태의 `admin` 중 담당자 1명을 `assigneeId`로 직접 선택합니다. 문의에는 대상 부서를 저장하지 않으며 등록 후 담당자를 변경하지 않습니다.

문의는 작성자와 지정 담당자만 조회할 수 있고, 답변 작성·수정·삭제 및 첨부 이미지 조회는 지정 담당자만 수행합니다. 담당자가 부서 관리자에서 해제되어도 기존 문의의 담당자 연결은 유지합니다. 처리되지 않은 문의가 남은 담당자의 비활성화 또는 `employee` 전환은 `409 Conflict`로 거부합니다.

문의 처리 이력, 이메일 알림, 답변 임시 저장과 담당자 변경 API는 제공하지 않습니다.

---

# 9. API 명세와 변경 관리

## 9.1 Notion과 Postman

요청·응답 구조는 `Postman Collection v2.1`에서 먼저 작성하고 합의합니다. Postman의 각 Request 설명을 API Docs 원문으로 관리하고 요청·응답 예시와 오류 조건을 함께 기록합니다.

Notion에는 확정된 API 목록, 담당자, 진행 상태와 Postman 링크를 정리합니다.

API를 추가하거나 변경할 때 다음 항목을 문서화합니다.

- API URL
- HTTP 메서드
- API 설명
- 인증 및 권한 필요 여부
- 경로 파라미터
- 쿼리 파라미터
- 요청 본문
- 성공 응답
- 실패 응답
- HTTP 상태 코드
- 필드 타입과 설명
- 필수 여부
- nullable 여부
- enum 허용값
- 기본 정렬
- 요청과 응답 예시

API의 실제 요청과 응답 구조는 최신 Postman Collection을 기준으로 판단합니다.

구현 코드와 Postman Collection이 일치하지 않으면 변경 작업이 완료되지 않은 것으로 판단합니다.

## 9.2 API 변경

다른 개발자 또는 클라이언트가 사용 중인 API는 임의로 변경하지 않습니다.

다음 변경은 사전에 공유하고 최소 1명의 코드 리뷰를 받아야 합니다.

- URL 변경
- HTTP 메서드 변경
- 요청 필드 삭제
- 응답 필드 삭제
- 필드 타입 변경
- nullable 여부 변경
- 필수 요청값 추가
- 상태 코드 변경
- 오류 코드 변경
- enum 값 변경 또는 삭제
- 기존 필드 의미 변경
- 페이지네이션 방식 변경
- 검색, 필터 또는 정렬 방식 변경

선택적인 응답 필드를 추가하는 경우에도 관련 클라이언트 개발자에게 공유합니다.

API 변경 시 Postman Collection을 먼저 수정하고 Notion에 변경 내용을 공유한 뒤 구현 코드를 함께 수정합니다.

## 9.3 API 버전

기존 클라이언트가 수정 없이 사용할 수 없는 변경은 파괴적 변경으로 간주합니다.

파괴적 변경의 대표적인 예시는 다음과 같습니다.

- 응답 필드 삭제
- 필드 타입 변경
- 필수 요청 필드 추가
- 기존 enum 값 삭제
- 기존 필드 의미 변경
- 응답 구조 변경

파괴적 변경이 필요한 경우 기존 API를 즉시 변경하지 않습니다.

새로운 API 버전 도입 여부와 기존 버전 유지 기간을 팀에서 검토한 후 변경합니다.

---

# 10. 공통 구현 기준

다음 기능은 개별 API에서 별도로 구현하지 않고 공통 모듈을 사용합니다.

- 공통 오류 응답
- 필드 검증 오류 응답
- 페이지 응답
- 전역 예외 처리
- 인증 실패 처리
- 권한 실패 처리
- 날짜 및 시간 직렬화
- 요청 ID 생성
- 요청 ID 로그 연결
- `X-Request-Id` 응답 헤더

공통 구조와 동일한 목적의 클래스를 개별 기능에 중복하여 작성하지 않습니다.

공통 구조 변경이 필요한 경우 팀 리뷰를 거쳐 수정합니다.

## 10.1 예외 규칙

페이지 응답은 원칙적으로 `totalCount`와 `totalPages`를 포함합니다.

전체 개수 계산이 성능에 큰 영향을 주는 API는 팀 협의 후 해당 필드를 제외할 수 있습니다.

이 경우 다음 조건을 충족해야 합니다.

- API 구현 전에 응답 구조를 결정합니다.
- Postman API Docs에 실제 응답 구조를 명시합니다.
- 동일한 API가 요청 상황에 따라 필드를 포함하거나 생략하지 않습니다.
- 관련 클라이언트 개발자에게 변경 내용을 공유합니다.

다음 기능은 기본적으로 적용하지 않습니다.

- `422 Unprocessable Content`
- 비동기 처리 체계 없는 `202 Accepted`
- 커서 기반 페이지네이션
- 복수 정렬 조건
- 공통 성공 응답 래퍼
- 공통 Idempotency-Key 정책

결제, 주문, 예약 등 중복 요청이 문제가 되는 기능이 추가되는 경우 멱등성 및 중복 요청 처리 규칙을 별도로 정의합니다.

## 10.2 AI 서버 내부 API 규칙

AI 서버 내부 API는 `/internal/v1` 경로를 사용합니다.

AI 서버 내부 API도 JSON 필드명, 상태값, 오류 응답, 요청 추적 규칙은 이 문서를 따릅니다.

AI 서버 내부 API는 DB ID를 문자열로 전달받고 문자열로 반환합니다.

AI 서버 내부 API는 원본 문서 파일 경로나 DB 접속 정보를 응답에 포함하지 않습니다.

AI 서버 내부 API 예시는 다음과 같습니다.

```
POST /internal/v1/source-parses
POST /internal/v1/wiki-transformations
POST /internal/v1/schedule-extractions
POST /internal/v1/wiki-edits
POST /internal/v1/answer-context-selections
POST /internal/v1/answers
```

AI 서버는 원본문서 카테고리를 분류하지 않습니다. 원본문서 카테고리는 관리자가 지정하고, AI 서버는 Wiki 구조를 최적화하기 위한 Wiki 카테고리만 생성·수정·병합·제거할 수 있습니다.

AI 서버는 DB와 서비스 파일을 직접 변경하지 않습니다.

백엔드 서버는 AI 서버의 결과를 검증하고 DB와 파일에 즉시 반영합니다. Wiki ID, 카테고리 ID, `scopeKey`, Markdown 링크, `index.md` 링크, Wiki-Wiki 관계와 Wiki-문서 관계의 정합성을 반영 전에 검사합니다. Wiki에는 사전 승인·반려·비공개 상태를 두지 않습니다. 일정 추출 결과만 `draft`로 저장하고 관리자가 일정별로 승인하거나 거부합니다.

## 10.3 챗봇 답변 출처 규칙

프론트엔드는 질문 유형을 보내지 않습니다. AI 서버가 질문 맥락을 `wiki`, `schedule`, `mixed`로 자동 판단합니다.

답변은 두 단계로 생성합니다.

1. 백엔드가 권한 내 Wiki 공간의 `index.md`와 일정 요약을 `/internal/v1/answer-context-selections`에 전달합니다.
2. AI가 종류별 최대 5개의 Wiki ID 또는 일정 ID를 관련도 순서로 반환합니다.
3. 백엔드가 ID의 존재 여부와 권한을 다시 검사하고 선택된 Wiki Markdown 또는 일정 내용만 `/internal/v1/answers`에 전달합니다.
4. AI가 최종 답변과 실제 사용한 출처 ID를 반환합니다.

Wiki 질문은 사용자가 접근할 수 있는 Wiki만 사용합니다. 원본문서는 답변 검색에 직접 사용하지 않고 Wiki의 근거 자료로만 조회합니다. 혼합 질문은 Wiki와 일정을 함께 사용할 수 있습니다.

답변 하나에는 출처가 두 개 이상 포함될 수 있으며 응답의 `sources` 배열에 사용한 Wiki 또는 일정을 각각 저장합니다.

Wiki 출처는 연결된 원본문서를 `evidenceDocuments` 하위 배열로 반환합니다. 원본문서를 Wiki와 같은 단계의 답변 출처로 반환하지 않습니다.

```json
{
  "sources": [
    {
      "sourceType": "wiki",
      "sourceId": "3001",
      "title": "연차 사용 규정",
      "evidenceDocuments": [
        {
          "documentId": "101",
          "title": "2026년 취업규칙.pdf"
        }
      ]
    }
  ]
}
```

일정 출처의 `evidenceDocuments`는 빈 배열로 반환합니다. 권한이 없는 Wiki, 일정과 원본문서는 출처 응답에서도 제외합니다.

## 10.4 DB 타입과 파일 경계

업무 DB는 MySQL 8.4 LTS를 사용합니다. PK와 FK는 `BIGINT UNSIGNED`, 시간은 UTC `DATETIME(6)`, 상태값은 `VARCHAR(30)`, 경로는 `VARCHAR(500)`, 관계·첨부파일·작업 결과는 `JSON`으로 저장합니다.

Wiki 본문, `index.md`, 원본문서, 파싱 파일과 첨부파일은 `/data/ajt`에 저장합니다. DB에는 `/data/ajt`를 제외한 상대 경로와 메타데이터만 저장합니다.

---

# 11. 코드 리뷰 체크리스트

```
[ ] URL이 복수 명사와 kebab-case로 작성되었는가?
[ ] CRUD가 적절한 HTTP 메서드로 표현되었는가?
[ ] GET 요청에 요청 본문을 사용하지 않았는가?
[ ] 요청과 응답에 엔티티를 직접 사용하지 않았는가?
[ ] JSON 필드가 camelCase로 작성되었는가?
[ ] 상태값과 enum이 소문자 snake_case로 작성되었는가?
[ ] ID가 문자열로 반환되는가?
[ ] 날짜와 시간이 RFC 3339 UTC 형식인가?
[ ] PATCH의 필드 생략과 null이 구분되는가?
[ ] 목록이 items 배열로 반환되는가?
[ ] 빈 목록이 []로 반환되는가?
[ ] 페이지 목록에 기본 정렬이 정의되어 있는가?
[ ] 적절한 HTTP 상태 코드를 사용하는가?
[ ] 오류가 공통 오류 구조로 반환되는가?
[ ] 모든 응답에 X-Request-Id 헤더가 포함되는가?
[ ] 민감 정보와 내부 예외 정보가 노출되지 않는가?
[ ] Postman Collection의 요청·응답과 구현이 일치하는가?
[ ] 파괴적 변경 여부를 확인했는가?
[ ] 프론트엔드가 백엔드 공개 API만 호출하는가?
[ ] AI 서버가 DB를 직접 조회하거나 수정하지 않는가?
[ ] FastAPI validation error가 400 공통 오류 구조로 변환되는가?
[ ] 파일 업로드 API가 multipart/form-data 예외 규칙을 따르는가?
[ ] 파일당 20MB, 최대 20개, 총 100MB 제한과 용도별 예외가 정의되어 있는가?
[ ] 비동기 작업 API가 202, jobId, 상태 조회 규칙을 따르는가?
[ ] 관리자가 Wiki를 직접 삭제하는 공개 API가 정의되어 있지 않은가?
[ ] Wiki 변환이 전역 직렬 처리되고 사전 승인 없이 문서별로 즉시 반영되는가?
[ ] 원본문서 카테고리는 관리자가, Wiki 카테고리는 AI가 관리하는가?
[ ] 권한 없는 문서·Wiki·일정 조회가 존재하지 않는 자료와 동일한 404를 반환하는가?
[ ] 일정 거부 초안이 상태로 보존되지 않고 하드 삭제되는가?
[ ] 비밀번호 재설정 요청이 이메일 존재 여부를 노출하지 않는가?
[ ] 답변 출처가 복수 sources 배열이고 원본문서가 Wiki 하위 근거로만 표시되는가?
[ ] 질문 유형을 AI가 wiki, schedule, mixed로 자동 판단하는가?
[ ] 자료 선택 단계와 답변 생성 단계 사이에서 백엔드가 ID와 권한을 다시 검증하는가?
[ ] AI 변경 반영 전에 Markdown 링크와 관계 JSON 정합성을 검증하는가?
```
