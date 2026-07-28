# ERD 생성기·문서 우선순위 정합성 설계

- 작성일: 2026-07-27
- 상태: 승인 대기
- 기준 계약: 요구사항 정의서 v2.5, Postman 개발 계약 v1.0.0

## 1. 목표

현재 올바른 SQL·ERD·Postman 계약을 유지하면서 다음 재발 위험을 제거한다.

1. ERDCloud 스냅샷 생성기가 `schedule.attachment_refs`를 다시 추가하거나
   `ai_question.conversation_key`를 제거하지 않게 한다.
2. 활성 설계 문서에서 일정 첨부파일과 부서 관리자 전용 문의 담당자 구정책을 제거한다.
3. 과거 설계·계획 문서는 삭제하거나 내용을 다시 쓰지 않고 `Superseded` 상태와 대체 기준을 표시한다.
4. 생성기와 활성 문서가 다시 어긋나면 기존 일관성 검사에서 실패하게 한다.

## 2. 계약 우선순위

구현 중 해석이 충돌하면 다음 순서를 적용한다.

1. `요구사항정의서_v2 1.md` v2.5
2. Postman 개발 계약 v1.0.0의 P0 Saved Examples와 생성기
3. `erdTable.sql`과 그로부터 일치가 검증된 ERDCloud 스냅샷
4. `REST API 개발 컨벤션.md`
5. 상태가 `Active`인 `backend/docs/superpowers/specs` 문서
6. 상태가 `Superseded`인 과거 설계·계획 문서

`Superseded` 문서는 합의 이력을 보존하기 위한 참고 자료이며 신규 구현 계약으로 사용하지 않는다.

## 3. ERD 생성기 정렬

`scripts/generate-erdcloud-snapshot.mjs`를 현재 SQL·요구사항에 맞춘다.

- `schedule`에서 `attachment_refs` 정의를 제거한다.
- `ai_question`에 필수 `conversation_key VARCHAR(100)`을 추가한다.
- `inquiry.assignee_id` 설명을 모든 `APPROVED`·`ACTIVE` 상태 `ADMIN` 중 직접 선택하는 정책으로 바꾼다.
- 재생성 때 의미 없는 메타데이터 변경이 발생하지 않도록 템플릿의 `createdAt`을 보존한다.
- 수정된 생성기로 `erdTable-snapshot-ajt.json`을 재생성한다.

테이블은 16개를 유지하고 다른 컬럼·관계·타입은 변경하지 않는다.

## 4. 자동 드리프트 검사

`node scripts/validate-artifact-consistency.mjs` 실행 한 번으로 다음을 함께 검사한다.

1. ERD 생성기를 임시 경로에 실행한다.
2. 임시 생성 결과와 현재 ERD 스냅샷에서 테이블·필드의 논리명, 물리명, 타입,
   null 허용 여부, 설명, 관계 대상 테이블·필드와 관계 유형을 비교한다.
3. 임시 파일은 검사 종료 시 정리한다.
4. `schedule.attachment_refs` 재등장, `ai_question.conversation_key` 누락 또는 문의 담당자 설명의 구정책이 있으면 종료 코드 1을 반환한다.

생성된 불투명 ID, 생성 시각, 작성자 메타데이터와 화면 좌표·색상은 구조 정합성
판정에서 제외한다.

## 5. 활성 문서 최신화

다음 문서는 현재 구현 계약을 설명하는 활성 문서로 간주하고 최신 정책으로 직접 수정한다.

- `REST API 개발 컨벤션.md`
- `backend/docs/superpowers/specs/2026-07-26-file-directory-structure-design.md`
- `backend/docs/superpowers/specs/2026-07-27-final-artifact-alignment-design.md`
- `backend/docs/superpowers/specs/2026-07-27-inquiry-assignee-schedule-source-alignment-design.md`
- `postman/README.md`

위 `backend/docs/superpowers/specs` 문서에는 `상태: Active`를 명시한다.

수정 원칙:

- 일정에는 별도 첨부파일이 없다.
- 일정 원본문서와 파싱 파일은 `schedule-sources/{sourceGroupKey}`에만 저장한다.
- `schedule`에는 `attachment_refs`가 없다.
- 문의 첨부 이미지만 `inquiry.attachment_refs`에 저장한다.
- 문의 담당자 후보는 부서 관리자 지정 여부와 관계없이 모든 승인·활성 관리자다.

이미 최신 정책인 문서는 불필요하게 다시 작성하지 않는다.

## 6. 과거 문서 보존

최신 정책과 충돌하는 과거 설계·계획 문서는 본문을 변경하지 않고 문서 상단에 다음 정보를 추가한다.

- 상태: `Superseded`
- 대체 기준: 요구사항 정의서 v2.5
- 후속 설계: 최신 정합성 설계 문서 경로
- 주의: 구현 계약으로 사용하지 않음

다음 문서를 `Superseded` 대상으로 확정한다.

- `backend/docs/superpowers/specs/2026-07-27-signup-admin-inquiry-alignment-design.md`
- `backend/docs/superpowers/plans/2026-07-27-signup-admin-inquiry-alignment.md`
- `backend/docs/superpowers/plans/2026-07-27-erdcloud-import-ddl.md`
- `backend/docs/superpowers/plans/2026-07-27-postman-api-spec.md`
- `backend/docs/superpowers/plans/2026-07-27-employee-wireframe-api-requirements-alignment.md`

과거 문서의 API 수, MySQL 버전과 당시 정책 문구는 역사적 내용으로 유지한다.
완료 체크가 기록된 `2026-07-27-final-artifact-alignment.md`는 완료 이력으로
유지하고 `Superseded` 대상으로 바꾸지 않는다.

## 7. 검증

변경 후 다음을 모두 통과해야 한다.

```bash
node scripts/validate-artifact-consistency.mjs
node postman/validate-postman-collections.mjs
node --check scripts/generate-erdcloud-snapshot.mjs
node --check scripts/validate-artifact-consistency.mjs
```

추가 확인:

- SQL·ERD 업무 테이블 16개
- 공개 API 53개, 내부 API 6개
- `schedule.attachment_refs` 없음
- `ai_question.conversation_key` 존재
- 활성 문서에 일정 첨부파일 정책 없음
- 활성 문서에 문의 후보를 `department.manager_id`로 제한하는 정책 없음
- 과거 충돌 문서에 `Superseded` 표시 존재

## 8. 범위 제외

- DB 테이블·API 추가
- 요구사항 v2.5 또는 Postman v1.0.0의 계약 변경
- 와이어프레임 이미지 수정
- 과거 설계·계획 문서 삭제
- 애플리케이션 소스 생성
