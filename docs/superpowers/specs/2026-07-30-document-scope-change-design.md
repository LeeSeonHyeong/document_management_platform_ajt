# 문서 공개 범위 변경 및 양쪽 Wiki 재처리 설계

## 목적과 범위

FR-DOC-008의 공개 범위 변경은 문서의 `scope_key`만 수정하는 작업이 아니다. 원본·파싱 파일 경로를 새 scope로 옮기고, 이전·새 scope를 각각 최신 원본문서 기준으로 다시 변환해야 한다.

이번 작업은 DB 스키마를 바꾸지 않는다. 따라서 요청 처리 단계의 파일·DB 변경만 즉시 보상하며, 비동기 AI 재처리 뒤의 장기 롤백·재시작 복구는 제공하지 않는다. 재처리 실패는 기존 `ai_job`과 `document` 실패 상태로 남긴다.

## 공개 API 계약

기존 `PATCH /api/v1/documents/{documentId}` 요청 필드는 유지한다.

- `documentCategoryId`: 새 scope에 속한 필수 카테고리 ID
- `visibilityType`, `departmentIds`: 새 `scopeKey`를 만드는 범위 값

응답의 기존 `jobId`는 호환성을 위해 유지한다. 범위가 변경된 경우, 다음의 선택 필드를 추가한다.

```json
{
  "jobId": "701",
  "reprocessJobs": [
    { "scopeKey": "D1", "jobId": "702" },
    { "scopeKey": "D2-D3", "jobId": "701" }
  ]
}
```

이는 하위 호환 추가이므로 계약 버전을 patch 올리고, Postman 생성 원본의 요청·성공 Example을 함께 갱신한다.

## 요청 처리와 파일 보상

`DocumentManagementService.update`는 다음 순서로 처리한다.

1. 관리자 권한, 문서 미처리 상태, 새 카테고리의 scope 소속을 검증한다.
2. 이전과 새 scope에 처리 중 문서가 없는지 확인한다.
3. 새 `WikiScope`를 확보한다.
4. `DocumentFileStorage`의 scope 이동 작업 단위로 원본·파싱 파일을 새 경로에 원자적으로 이동한다.
5. 같은 DB 트랜잭션에서 문서의 카테고리, `scopeKey`, 원본·파싱 경로를 함께 바꾼다.
6. 이전·새 scope에 각각 재처리 job을 만들고, 커밋 뒤 실행 큐에 넘긴다.

파일 시스템은 DB 트랜잭션에 참여하지 않는다. 파일 이동 작업 단위는 변경 전 두 경로를 기억하고, 서비스 예외 또는 DB 롤백의 `afterCompletion`에서 원래 경로로 되돌린다. 성공 커밋 뒤에는 보상 정보를 폐기한다.

## 양쪽 scope 재처리

범위가 바뀌면 두 scope 모두 `reprocessScope`로 job을 만든다.

- 이전 scope: 이동한 문서를 뺀 남은 문서를 재처리한다.
- 새 scope: 방금 이동한 문서를 포함한 모든 문서를 재처리한다.

이전 scope에 문서가 하나도 남지 않으면 FastAPI에 빈 문서 변환을 보내지 않는다. 백엔드 `WikiScopeCleanupService`가 그 scope의 Wiki 본문·목차·검색 청크·카테고리를 파일 보상과 DB 트랜잭션 안에서 제거한다.

AI 변환은 기존 내부 API와 문서별 처리 계약을 그대로 사용한다. 각 job의 AI 또는 변환 반영 실패는 해당 job과 문서를 failed로 기록하며, 범위 변경 요청을 소급 취소하지 않는다.

## 오류 처리

| 시점 | 처리 |
| --- | --- |
| 권한·카테고리·처리 중 scope 검증 실패 | 파일·DB·job 변경 없음 |
| 파일 이동 실패 | DB·job 변경 없음 |
| DB 트랜잭션 롤백 | 파일 이동을 원래 경로로 복구 |
| job 생성 실패 | 요청 단계 파일·DB를 보상하고 job을 만들지 않음 |
| 커밋 뒤 실행 제출 실패 | 파일·DB 변경은 유지하고 `WAITING` job을 남김; 기존 작업 재시작 경로로 다시 제출 |
| AI/변환 반영 실패 | 파일·문서의 새 범위는 유지, 해당 job/document에 실패 기록 |

## 테스트

- 관리자·문서 상태·카테고리 scope 검증
- 원본만 있는 문서와 원본·파싱 파일이 모두 있는 문서의 경로 이동
- 파일 이동 또는 DB 롤백 시 경로·문서 메타데이터 복구
- 이전·새 scope에 job이 각각 생성되고 커밋 뒤 실행되는지
- 빈 이전 scope의 Wiki·검색 색인 정리
- 계약 Example 및 전체 백엔드 회귀 테스트
