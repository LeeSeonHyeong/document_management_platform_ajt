# backend — Spring Boot API 서버

공통 규칙(Git·REST API 컨벤션, 요구사항, ERD)은 루트 `../docs/`가 기준이다. 루트 `CLAUDE.md` 참조.

## 스택

- Java + Spring Boot, Gradle 빌드
- MySQL 8.4 LTS (`../docs/db/erd.sql` 기준)
- 패키지 루트: `com.ajt`

## 명령

```bash
./gradlew build      # 빌드 + 테스트
./gradlew test       # 테스트만
./gradlew bootRun    # 로컬 실행
```

## 규칙

- API는 `../docs/conventions/rest-api-convention.md`와 `../docs/api/` 계약을 따른다. 계약에 없는 API를 임의로 만들지 않는다.
- 스프링 구현(생성자 주입, JSON 직렬화, 리스트 참조 컬럼)은 `../docs/conventions/backend-spring-convention.md`를 따른다.
- 스키마 변경은 `../docs/db/erd.sql`을 먼저 갱신한다.
- 설계 이력: `docs/superpowers/plans/`, `docs/superpowers/specs/` (백엔드 전용 설계 문서)
