# MySQL 8.4 독립 Import SQL 설계

- 작성일: 2026-07-27
- 상태: Active
- 기준 계약: 요구사항 정의서 v2.5, `erdTable.sql`, ERDCloud 스냅샷

## 1. 목표

기존 `erdTable.sql`을 빈 MySQL 8.4 LTS 서버에 한 번 실행하여 `ajt`
데이터베이스와 확정된 16개 업무 테이블을 생성할 수 있는 독립 Import 파일로 만든다.

별도 복제 SQL 파일을 만들지 않고 `erdTable.sql`을 DB 스키마의 단일 원본으로 유지한다.

## 2. Import 계약

파일 시작 부분에 다음 데이터베이스 생성·선택 구문을 추가한다.

```sql
CREATE DATABASE IF NOT EXISTS `ajt`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `ajt`;
```

지원 실행 예시는 다음과 같다.

```bash
mysql -u <user> -p < erdTable.sql
```

MySQL 계정에는 데이터베이스 생성과 `ajt` 스키마 내 테이블·제약조건 생성 권한이
있어야 한다.

## 3. 안전 정책

- `DROP DATABASE`, `DROP TABLE`, `TRUNCATE`와 데이터 삭제문을 포함하지 않는다.
- `INSERT` 샘플 데이터와 최초 관리자 계정은 포함하지 않는다.
- 기존 테이블을 덮어쓰거나 자동 변경하지 않는다.
- 같은 이름의 테이블이 이미 있으면 MySQL 오류로 중단한다.
- `CREATE TABLE IF NOT EXISTS`를 사용하지 않는다. 일부 테이블만 생성된 상태를
  성공으로 오인하거나 후속 FK가 누락되는 상황을 방지하기 위해서다.
- MySQL DDL은 암묵적으로 커밋될 수 있으므로 전체 파일의 원자적 롤백을 보장한다고
  표현하지 않는다.

## 4. 스키마 유지 범위

기존 16개 업무 테이블, 컬럼, 타입, PK, FK, UNIQUE, CHECK와 인덱스를 변경하지 않는다.

`department.manager_id`와 `member.department_id`의 순환 관계는 다음 순서를 유지한다.

1. `department`를 관리자 FK 없이 생성한다.
2. `member`를 생성하고 `member.department_id` FK를 연결한다.
3. `ALTER TABLE department`로 `manager_id` FK를 연결한다.
4. 나머지 테이블을 현재 의존 순서대로 생성한다.

신규 테이블, 별도 파일 테이블과 상태값 고도화는 추가하지 않는다.

## 5. 자동 검사

`scripts/validate-artifact-consistency.mjs`에 다음 회귀 검사를 추가한다.

- `CREATE DATABASE IF NOT EXISTS ajt`가 존재한다.
- 문자셋은 `utf8mb4`, collation은 `utf8mb4_0900_ai_ci`다.
- `USE ajt`가 존재한다.
- `DROP DATABASE`, `DROP TABLE`, `TRUNCATE`, `DELETE FROM`이 없다.
- 업무 `CREATE TABLE` 수는 16개다.
- 기존 SQL·ERD 테이블·컬럼·타입 비교가 계속 통과한다.

검사는 SQL 문자열의 주석을 제거한 뒤 실행 구문을 기준으로 판단하여 설명 문구 때문에
오탐하지 않게 한다.

## 6. 검증

변경 후 다음 명령이 모두 성공해야 한다.

```bash
node scripts/validate-artifact-consistency.mjs
node --check scripts/validate-artifact-consistency.mjs
```

MySQL 8.4 테스트 서버와 접속 정보가 제공되는 경우에는 빈 임시 데이터베이스에 실제
Import한 뒤 다음을 추가 확인한다.

- 테이블 16개
- FK와 CHECK 제약조건 생성
- `SHOW CREATE TABLE`의 문자셋과 collation

실제 서버가 없으면 정적 계약 검증 결과와 MySQL 8.4 문법을 기준으로 전달하며, 사용자의
운영·공유 DB에는 자동으로 Import하지 않는다.

## 7. 범위 제외

- 기존 DB 또는 테이블 삭제
- 초기 데이터와 최초 관리자 계정 삽입
- Spring Boot·Flyway·Liquibase 마이그레이션 파일
- ERD 구조 변경
- MySQL 8.4 외 DB 호환성
