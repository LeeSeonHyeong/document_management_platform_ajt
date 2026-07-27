# AJT Backend

Spring Boot 기반 AJT 백엔드 애플리케이션입니다.

## 기술 기준

- Java 21
- Spring Boot
- Gradle Wrapper
- Spring Web MVC
- Spring Security
- Spring Data JPA
- Validation
- MySQL Connector
- H2 Database for local/test
- Actuator

## 로컬 실행

```bash
./gradlew bootRun
```

Windows PowerShell:

```powershell
.\gradlew.bat bootRun
```

## 테스트

```bash
./gradlew test
```

Windows PowerShell:

```powershell
.\gradlew.bat test
```

## 기동 확인

```text
GET /api/v1/health
GET /actuator/health
```

## 브랜치

```text
feature/S15P11B106-100-backend-project-setup
```
