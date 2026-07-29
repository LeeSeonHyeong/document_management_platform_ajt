# 백엔드 Spring 구현 규칙

Spring Boot 백엔드(`backend/`)의 구현 규칙을 정의합니다.
공통 API 규칙은 [rest-api-convention.md](rest-api-convention.md), 스키마는 [../db/erd.sql](../db/erd.sql)을 따릅니다.

---

## 1. 의존성 주입

- 스프링 빈의 의존성은 **생성자 주입**으로만 받는다.
- 필드 주입(`@Autowired` 필드)과 세터 주입을 사용하지 않는다.
- 주입 대상 필드는 `private final`로 선언한다.
- 생성자가 하나면 `@Autowired`를 생략한다.

```java
@Service
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final CurrentMemberProvider currentMemberProvider;

    public ScheduleService(
            ScheduleRepository scheduleRepository,
            CurrentMemberProvider currentMemberProvider
    ) {
        this.scheduleRepository = scheduleRepository;
        this.currentMemberProvider = currentMemberProvider;
    }
}
```

이유: 불변(final) 보장, 테스트 시 목(mock) 주입 용이, 순환 참조 조기 발견.

---

## 2. JSON 직렬화

- JSON 직렬화·역직렬화는 `com.fasterxml.jackson`을 표준으로 사용한다.
- 응답·요청 JSON 필드명은 `camelCase`로 직렬화한다.
- enum과 상태값은 소문자 `snake_case`로 직렬화한다 (예: `DRAFT` → `"draft"`).
- 엔티티를 요청·응답 객체로 직접 노출하지 않고 요청 DTO·응답 DTO를 사용한다.
- PATCH 요청의 "필드 생략 vs 명시적 `null`" 구분([rest-api-convention.md](rest-api-convention.md) §4.2)은 Jackson 기반으로 처리한다.

---

## 3. 리스트 참조 컬럼

- 부서 목록·문서 목록 등 소규모 ID 참조 목록은 별도 정규화 테이블 대신 **JSON 컬럼**으로 저장한다.
- Hibernate `@JdbcTypeCode(SqlTypes.JSON)`을 사용하고, DB 컬럼은 `JSON NOT NULL DEFAULT (JSON_ARRAY())`로 정의한다.
- 기존 예시: `wiki_scope.department_refs`, `ai_job.document_ids`, `document.document_wiki_refs`.

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "department_refs", nullable = false, columnDefinition = "json")
private List<Long> departmentRefs = List.of();
```

### 예외: 삭제를 DB가 막아야 하는 참조

참조 대상이 삭제될 때 DB가 삭제 자체를 막아야 하는 관계는 JSON 컬럼 대신 **조인 테이블과 FK**를 쓴다.
JSON 컬럼에는 FK를 걸 수 없어 참조 무결성을 애플리케이션 검사에만 의존하게 되기 때문이다.

- 해당 예시: `schedule_department` (일정 공개 부서). 부서 삭제를 `ON DELETE RESTRICT`로 막는다.
- 조인 테이블을 쓰면 지연 로딩 N+1이 생기므로 컬렉션에 `@BatchSize`를 지정한다.
- FK 위반은 500으로 나가므로, 계약이 요구하는 오류 코드가 있으면 삭제 전에 애플리케이션에서도 확인한다.
