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
- **DB 스키마(`../docs/db/erd.sql`)와 엔티티 매핑(`@Table`/`@Column`의 name·타입·제약)을 임의로 변경하지 않는다.** 코드와 ERD가 어긋나면 ERD를 정답으로 보고 엔티티를 ERD에 맞춘다. ERD 자체를 바꿔야 하면 직접 수정하지 말고 해당 코드에 `// TODO(DB): <사유>. erd.sql 변경 필요 — 팀원 합의 후 진행` 주석을 남기고 사용자에게 질문한다.
- 설계 이력: `docs/superpowers/plans/`, `docs/superpowers/specs/` (백엔드 전용 설계 문서)
