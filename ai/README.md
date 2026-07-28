# AJT AI 서버

원본문서 파싱과 위키 편집 에이전트를 담당하는 FastAPI 서버.
Spring Boot 백엔드만 이 서버를 호출한다 (`/internal/v1`, 계약은
`docs/api/AJT-FastAPI-Internal-API.postman_collection.json`).
AI 서버는 DB와 서비스 파일에 직접 접근하지 않는다.

## 개발 환경

Python 3.12+ 와 [uv](https://docs.astral.sh/uv/)를 사용한다.

```sh
cd ai
uv sync
uv run pytest
```

## 구성

| 경로 | 내용 |
| --- | --- |
| `src/` | 서버 소스. 패키지 단위로 추가한다 |
| `tests/` | pytest 테스트 |
