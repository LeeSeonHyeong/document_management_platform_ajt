# S15P11B106 — 사내 LLM Wiki 및 일정 관리 시스템

관리자가 업로드한 원본문서를 위키 편집자 에이전트가 LLM Wiki로 변환하고, 전체·부서·개인 일정을 관리하며, 챗봇으로 권한 내 지식·일정에 질의응답하는 서비스.

## 저장소 구성

| 경로 | 내용 |
| --- | --- |
| `backend/` | Spring Boot (Gradle, Java) API 서버 |
| `frontend/` | React 19 + Vite + Tailwind 4 |
| `docs/` | 공통 문서 단일 관리 지점 (Single Source of Truth) |
| `docs/api/` | API 계약 (Postman Collection) |
| `Jenkinsfile` | CI 파이프라인 |

## 공통 문서 (docs/)

모든 분야(백엔드·프론트·AI)는 아래 문서를 기준으로 개발한다.
문서 내용은 다른 곳에 복사하지 않으며, 수정은 `docs/`에서만 한다.

- `docs/conventions/git-convention.md` — 브랜치·커밋·PR 규칙. **브랜치 생성·커밋·PR 전 반드시 읽는다.**
- `docs/conventions/rest-api-convention.md` — REST API 설계 규칙. API 추가·수정 전 읽는다.
- `docs/requirements/요구사항정의서.md` — 기능·데이터 요구사항 확정본. 기능 구현 전 해당 FR/DR 항목을 확인한다.
- `docs/db/erd.sql` — MySQL DDL. 스키마 변경 시 이 파일을 먼저 갱신한다.

## API 계약 (docs/api/)

`docs/api/` 컬렉션은 프론트·Spring Boot·FastAPI가 함께 쓰는 개발 계약이다.
계약과 다르게 구현하지 않으며, 변경은 `docs/api/README.md`의 계약 변경 절차를 따른다.
