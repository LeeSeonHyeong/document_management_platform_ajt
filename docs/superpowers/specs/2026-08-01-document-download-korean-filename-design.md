# 원본문서 한글 다운로드 파일명 수정 설계

## 배경

원본문서 다운로드 응답은 RFC 5987 형식의 UTF-8 `filename*`과 구형 환경을 위한 ASCII `filename`을 함께 제공한다. Spring이 생성한 ASCII 파일명에서는 한글이 `_`로 치환된다.

현재 프론트엔드는 두 파라미터를 하나의 정규식으로 탐색해 응답에서 먼저 등장하는 ASCII `filename`을 선택한다. 이 값을 `<a download>`에 지정하면서 다운로드 파일명의 한글이 `_`로 저장된다.

## 변경 범위

- 백엔드 응답과 API 계약은 변경하지 않는다.
- `frontend/src/features/document/api.js`의 `Content-Disposition` 파일명 파서를 수정한다.
- UTF-8 `filename*`을 먼저 찾고 `decodeURIComponent`로 복원한다.
- `filename*`이 없을 때만 일반 `filename`을 사용한다.
- 헤더가 없거나 UTF-8 값이 잘못된 경우 파서는 `null`을 반환하고, 기존 상세 화면의 `doc.originalFileName` 폴백을 사용한다.
- 다른 다운로드 기능의 공통화나 관련 없는 리팩터링은 하지 않는다.

## 데이터 흐름

1. 프론트가 `GET /api/v1/documents/{documentId}/file`을 Blob으로 요청한다.
2. 응답 `Content-Disposition`에서 `filename*`을 우선 탐색한다.
3. 정상적인 UTF-8 값이면 디코딩한 한글 파일명을 반환한다.
4. `filename*`이 없으면 인용된 일반 `filename`을 반환한다.
5. 파싱 결과가 없으면 상세 조회 응답의 `originalFileName`을 다운로드 이름으로 사용한다.

## 오류 처리

- 잘못된 퍼센트 인코딩 때문에 `decodeURIComponent`가 예외를 던져도 다운로드 전체가 실패하지 않게 한다.
- 파싱 실패는 `null`로 처리해 기존 원본 파일명 폴백으로 이어지게 한다.

## 검증

- `filename`과 `filename*`이 모두 있으면 UTF-8 한글 파일명을 선택한다.
- `filename`만 있으면 해당 파일명을 선택한다.
- 헤더가 없거나 `filename*` 인코딩이 잘못돼도 예외 없이 폴백한다.
- 프론트 테스트와 프로덕션 빌드를 실행한다.

