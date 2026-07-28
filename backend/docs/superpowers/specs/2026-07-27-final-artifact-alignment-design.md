# AJT 최종 산출물 정합성 설계

## 목표

개발 시작 전에 요구사항 명세서, MySQL ERD, Postman API 명세와 REST API 개발 컨벤션이 같은 정책과 데이터 구조를 사용하도록 맞춘다.

## 확정 기술

- 업무 데이터베이스: MySQL 8.4 LTS
- Wiki 본문과 목차: `/data/ajt` 아래 Markdown 파일
- 업무 데이터, 파일 경로, 관계, 질문·답변, 작업 상태: MySQL
- 프론트엔드는 Spring Boot 공개 API만 호출한다.
- FastAPI는 DB와 파일 저장소를 직접 변경하지 않는다.
- Redis와 벡터 DB는 MVP에서 사용하지 않는다.

## 파일 제한

- 파일 한 개당 최대 20MB
- 파일 배열 한 요청당 최대 20개
- 파일 배열 한 요청당 총 파일 크기 최대 100MB
- Wiki 원본문서 업로드: 최대 20개, 총 100MB
- 일정 원본문서 업로드: 한 번에 1개
- 일정 첨부파일: 최대 20개, 총 100MB
- 문의 첨부 이미지: 최대 5개, 총 100MB

## 질문·답변 처리

1. 프론트엔드는 질문만 Spring Boot에 전송한다.
2. Spring Boot는 사용자가 접근 가능한 Wiki 공간의 `index.md`와 일정 요약을 FastAPI에 전달한다.
3. FastAPI는 질문을 `WIKI`, `SCHEDULE`, `MIXED` 중 하나로 분류하고 필요한 Wiki ID와 일정 ID를 반환한다.
4. Spring Boot는 반환된 ID의 존재 여부와 권한을 다시 검사한다.
5. Spring Boot는 선택된 Wiki Markdown 또는 일정 내용을 FastAPI에 전달한다.
6. FastAPI는 답변과 실제 사용한 Wiki·일정 ID를 반환한다.
7. Spring Boot는 답변과 출처를 저장한다.
8. Wiki 출처의 원본문서는 `wiki.document_refs`로 조회해 하위 근거로 표시한다.

자료 선택은 종류별 최대 5개로 제한한다. 중복·존재하지 않는 ID·권한 없는 ID는 제거한다. 유효한 자료가 하나도 없으면 근거 부족으로 처리한다.

## Wiki 목차와 변경 결과

LLM은 Wiki 카테고리, 목차 순서, 제목과 요약을 결정한다. Spring Boot는 실제 Wiki ID를 발급하고 `pages/{wikiId}.md` 링크로 변환하며 링크 정합성을 검증한다.

FastAPI는 DB 명령이나 실제 저장 경로 대신 다음 구조의 변경 목록을 반환한다.

- `categoryChanges`
- `wikiChanges`
- `relationChanges`
- `indexEntries`
- `summary`

새 리소스는 임시 참조값을 사용하고 Spring Boot가 실제 ID로 치환한다. 병합은 대상 Wiki 수정, 병합 원본 Wiki 삭제와 관계 이동의 조합으로 표현한다.

## 반영과 링크 검증

문서 한 건의 변경 결과를 하나의 원자적 반영 단위로 처리한다.

1. 작업 폴더에서 변경 파일을 작성한다.
2. ID, `scopeKey`, 카테고리, Markdown 링크, Wiki-Wiki 관계와 Wiki-문서 관계를 검증한다.
3. 기존 파일을 작업 폴더의 백업으로 이동한다.
4. DB 트랜잭션 안에서 관계 JSON과 경로를 갱신하고 새 파일을 원자적으로 이동한다.
5. 성공하면 백업을 삭제한다.
6. 실패하면 DB를 롤백하고 기존 파일을 복원한다.

Wiki-Wiki 관계는 양쪽 `wiki_refs`를, Wiki-문서 관계는 `wiki.document_refs`와 `document.document_wiki_refs`를 함께 갱신한다. 삭제된 Wiki ID는 관계와 목차에서 모두 제거한다.

## 작업 큐

- Wiki 문서 변환은 전역 FIFO 직렬 큐로 처리한다.
- 현재 변환 중인 문서는 강제 중단하지 않는다.
- 중단 요청이 오면 현재 문서 반영 후 나머지 문서를 `CANCELLED`로 처리한다.
- `FAILED`, `CANCELLED` 문서만 새 작업으로 재처리한다.
- 정상 파싱 파일이 있으면 재사용하고, 파싱 실패 또는 파일 부재 시 원본부터 다시 파싱한다.
- 재처리는 실행 시점의 최신 Wiki와 `index.md`를 사용한다.
- 같은 `scopeKey`에서 문서 변환 중이면 Wiki 채팅 수정은 `409 Conflict`로 거부한다.

## 일정 문서

일정 문서 업로드와 추출은 MVP에서 동기로 처리한다.

- 한 요청에 원본문서 1개
- 처리 제한 시간 180초
- 일정이 없으면 정상 결과 `no_schedule`을 반환하고 원본·파싱 파일을 삭제한다.
- 성공하면 추출한 일정별 `DRAFT` 행을 한 트랜잭션으로 저장한다.
- 거부된 초안은 하드 삭제한다.
- 같은 원본문서에서 추출된 마지막 일정이 삭제되면 원본·파싱 파일도 삭제한다.

## 데이터 타입

- PK/FK: `BIGINT UNSIGNED`, PK는 `AUTO_INCREMENT`
- 시간: UTC `DATETIME(6)`
- 애플리케이션 상태: `VARCHAR(30)`과 애플리케이션 Enum
- 파일 크기: `BIGINT UNSIGNED`
- 파일 경로: `VARCHAR(500)`
- 본문·답변·메시지: `TEXT`
- 관계·첨부파일·작업 결과: `JSON`
- 비밀번호: `password_hash VARCHAR(255)`
- `ai_question.question_type`: 분류 전 또는 분류 실패 시 `NULL`
- 질문 유형: `WIKI`, `SCHEDULE`, `MIXED`
- 문서 상태: `UPLOADED`, `PARSING`, `PROCESSING`, `COMPLETED`, `FAILED`, `CANCELLED`

새 테이블은 추가하지 않는다.

