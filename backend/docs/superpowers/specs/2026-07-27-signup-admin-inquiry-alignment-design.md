# 가입 승인·관리자·문의 담당자 정합성 설계

- 작성일: 2026-07-27
- 대상: 요구사항 정의서, REST API 개발 컨벤션, MySQL DDL, ERDCloud 스냅샷, Postman Collection, 관리자 와이어프레임
- 목표: 가입 승인 데이터 보존과 문의 담당자 직접 선택 정책을 기존 최소 테이블 구조에 반영한다.

## 1. 설계 원칙

1. 가입 승인 상태와 승인 후 계정 활성 상태를 분리한다.
2. 가입 거부 데이터는 별도 이력 테이블 없이 `member` 행으로 보존한다.
3. 전체 관리자와 부서 관리자는 별도 역할로 나누지 않는다.
4. 문의는 대상 부서가 아니라 등록 시 선택한 담당자에게 직접 전달한다.
5. 문의 처리 이력, 답변 임시 저장과 알림 메일은 MVP에 추가하지 않는다.
6. 기존 16개 업무 테이블을 유지하고 신규 테이블을 만들지 않는다.

## 2. 가입 승인

### 2.1 상태 모델

`member`에 `signup_status VARCHAR(30) NOT NULL` 컬럼을 추가한다.

- `PENDING`: 가입 승인 대기
- `APPROVED`: 가입 승인 완료
- `REJECTED`: 가입 거부

기존 `account_status`는 `ACTIVE`, `INACTIVE`만 유지한다. `signup_status`는 가입 검수 상태이고 `account_status`는 승인된 계정의 로그인 활성 상태이다.

### 2.2 상태 전이

| 동작 | signup_status | account_status | 로그인 |
| --- | --- | --- | --- |
| 일반 회원가입 | `PENDING` | `INACTIVE` | 불가 |
| 가입 승인 | `APPROVED` | `ACTIVE` | 가능 |
| 가입 거부 | `REJECTED` | `INACTIVE` | 불가 |
| 거부 후 재신청 | `PENDING` | `INACTIVE` | 불가 |
| 승인 계정 비활성화 | `APPROVED` | `INACTIVE` | 불가 |

시스템 개발자가 최초 제공하는 관리자 계정은 `APPROVED`, `ACTIVE`, `ADMIN` 상태로 생성한다.

### 2.3 재신청

거부된 이메일로 다시 가입하면 새 행을 생성하지 않는다. 기존 `REJECTED` 행의 비밀번호 해시, 이름과 소속 부서를 새 요청값으로 갱신하고 `signup_status`를 `PENDING`으로 변경한다.

- `PENDING` 이메일의 중복 가입은 `409 Conflict`로 거부한다.
- `APPROVED` 이메일의 중복 가입은 `409 Conflict`로 거부한다.
- 재신청 시 이전 거부 상태는 별도 이력으로 보존하지 않고 현재 행의 상태만 변경한다.

### 2.4 승인 API

- 일반 회원가입 성공은 `202 Accepted`와 사용자 ID, `pending` 상태를 반환한다.
- 관리자는 가입 요청을 `pending`, `approved`, `rejected`로 조회한다.
- 승인·거부는 사용자 ID를 대상으로 하는 별도 명령 API로 제공한다.
- 승인 또는 거부를 두 번 처리하면 `409 Conflict`를 반환한다.

## 3. 관리자

### 3.1 역할

`member.role`은 `ADMIN`, `EMPLOYEE` 두 값만 사용한다.

- 전체 관리자: 시스템 개발자가 최초 제공한 관리자 계정을 부르는 화면 표현
- 부서 관리자: `department.manager_id`에 연결된 관리자

두 관리자 모두 DB 역할과 시스템 권한은 `ADMIN`으로 동일하다. 전체 관리자 여부를 저장하는 별도 역할, 플래그 또는 테이블은 만들지 않는다.

### 3.2 부서 관리자

- 부서 관리 화면에서만 관리자를 지정하거나 해제한다.
- `department.manager_id`는 `NULL`을 허용한다.
- 한 부서에는 관리자 한 명만 지정할 수 있다.
- 한 관리자는 동시에 한 부서의 관리자만 될 수 있다.
- 관리자 미지정 부서를 허용한다.
- 부서에 직원이 한 명이라도 있거나 다른 데이터가 참조 중이면 부서를 삭제할 수 없다.

부서 관리자 후보는 `signup_status=APPROVED`, `account_status=ACTIVE`, `role=ADMIN`인 사용자이다.

## 4. 문의 담당자

### 4.1 저장 구조

`inquiry.target_department_id`를 제거하고 `inquiry.assignee_id BIGINT UNSIGNED NOT NULL`을 추가한다.

`assignee_id`는 `member.member_id`를 참조하며 사용자 계정을 삭제하지 않으므로 `ON DELETE RESTRICT`를 사용한다.

### 4.2 등록

사용자는 문의 등록 화면에서 현재 부서 관리자로 지정된 활성 관리자 중 담당자 한 명을 직접 선택한다.

담당자 선택 가능 조건은 다음과 같다.

- `member.role=ADMIN`
- `member.signup_status=APPROVED`
- `member.account_status=ACTIVE`
- 하나 이상의 `department.manager_id`가 해당 관리자 ID를 참조

문의 등록 API는 `targetDepartmentId` 대신 `assigneeId`를 필수로 받는다. 백엔드는 등록 시점마다 위 조건을 다시 검사한다.

### 4.3 조회와 답변

- 사원은 본인이 등록한 문의만 조회한다.
- 관리자는 본인의 `assignee_id`로 지정된 문의만 조회한다.
- 문의 등록 후 담당자를 변경하는 기능은 제공하지 않는다.
- 지정된 담당자만 답변을 작성·수정·삭제할 수 있다.
- 답변 등록 시 문의 상태를 `DONE`, 답변 삭제 시 `PENDING`으로 변경한다.
- 담당자 이름과 담당 부서는 문의 상세 응답에 포함한다.

담당자 선택 후 해당 관리자의 부서 지정이 해제되어도 기존 문의의 `assignee_id`는 유지하며 해당 관리자는 기존 담당 문의에 답변할 수 있다. 미처리 담당 문의가 남아 있으면 해당 관리자의 계정 비활성화 또는 관리자 역할 해제를 `409 Conflict`로 거부한다.

### 4.4 제외 기능

다음 기능과 데이터는 추가하지 않는다.

- 문의 처리 이력
- 담당자 변경
- 답변 임시 저장
- 문의 알림 메일
- 실시간 푸시 알림

## 5. API 변경 범위

### 인증·가입

- `POST /api/v1/auth/signup`: 즉시 활성 계정 생성에서 승인 대기 요청 생성으로 변경
- `GET /api/v1/signup-requests`: 가입 요청 목록
- `POST /api/v1/signup-requests/{userId}/approve`: 가입 승인
- `POST /api/v1/signup-requests/{userId}/reject`: 가입 거부

### 부서

- `GET /api/v1/departments`: 관리자 ID·이름과 관리자 미지정 여부 포함
- `POST /api/v1/departments`: 선택 `managerId` 허용
- `PATCH /api/v1/departments/{departmentId}`: `name`, `managerId` 수정

### 문의

- `POST /api/v1/inquiries`: `targetDepartmentId` 제거, `assigneeId` 추가
- `GET /api/v1/inquiries`: 관리자 조회 범위를 담당 문의로 변경하고 `assigneeId` 필터 제공
- `GET /api/v1/inquiries/{inquiryId}`: 담당자 정보 포함
- 담당자 변경 API는 만들지 않는다.

## 6. 와이어프레임 변경 범위

- 가입 승인 대기·승인·거부 화면은 유지한다.
- 직원 관리의 `사용자` 표현을 `사원`으로 변경한다.
- 직원 수정 화면의 관리자 직접 비밀번호 변경을 제거한다.
- 내 정보 화면의 현재 비밀번호 기반 변경을 제거하고 이메일 재설정 흐름만 사용한다.
- 부서 관리 화면은 관리자 미지정 상태를 허용한다.
- 부서 삭제 안내를 소속 직원 또는 참조 데이터가 있으면 삭제할 수 없다는 문구로 변경한다.
- 문의 등록 화면에 담당자 선택을 추가한다.
- 문의 상세 화면의 담당자 변경 드롭다운, 처리 이력, 알림 메일과 답변 임시 저장을 제거한다.

## 7. 검증 기준

1. 가입 직후 로그인할 수 없고 승인 후에만 로그인할 수 있다.
2. 거부 행이 DB에 남고 같은 이메일 재신청 시 기존 행이 `PENDING`으로 바뀐다.
3. `account_status`는 `ACTIVE`, `INACTIVE` 두 값만 사용한다.
4. 전체 관리자와 부서 관리자를 구분하는 새 역할이나 테이블이 없다.
5. 관리자 미지정 부서를 저장할 수 있다.
6. 직원 또는 다른 데이터가 참조 중인 부서는 삭제할 수 없다.
7. 문의 등록 요청에 `targetDepartmentId`가 없고 `assigneeId`가 필수이다.
8. 부서 관리자로 지정되지 않은 관리자에게 문의를 등록할 수 없다.
9. 문의 담당자 변경·처리 이력·임시 저장·알림 메일 API가 없다.
10. SQL, ERDCloud JSON, 요구사항과 Postman에서 상태값과 FK가 일치한다.
