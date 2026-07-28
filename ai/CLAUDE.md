# ai — FastAPI AI 서버

공통 규칙(Git·REST API 컨벤션, 요구사항, ERD)은 루트 `../docs/`가 기준이다. 루트 `CLAUDE.md` 참조.

## 작업 수칙

- **브랜치·커밋·MR 전에 `../docs/conventions/git-convention.md`를 읽는다.** Jira 티켓 먼저, 브랜치는 그 번호로. 티켓은 백로그에서 먼저 찾는다 — 없으면 대개 기존 티켓의 일부다.
- **계약이 정본이다**: `../docs/api/AJT-FastAPI-Internal-API.postman_collection.json`. 컨벤션과 겹치면 계약 기준. 계약에 없는 상황(오류 코드 이름 등)은 임의로 정하지 않고 팀에 공유한다 — 정했다면 MR 본문에 협의 항목으로 명시한다.
- 각 엔드포인트가 낼 수 있는 상태는 계약이 정한 400·401·500뿐이다. 그 밖의 상태를 내면 Spring 분기에서 `UNEXPECTED_STATUS`로 뭉개진다.
- `.env*`는 커밋하지 않는다 (`.env.example`만).

## 스택과 명령

Python 3.12+, [uv](https://docs.astral.sh/uv/).

```bash
cd ai
uv sync
uv run pytest -m "not ocr"                  # OCR 제외 (CI 후보)
uv run pytest                               # 전체 — 로컬 Tesseract(eng) 필요
INTERNAL_API_KEY=... uv run python -m wiki_api.serve --port 8000   # 서버 기동
```

## 구조와 경계

```
Spring Boot --HTTP--> wiki_api --> agent_runtime --> (MCP) --> wiki_mcp
                        └-> document_parser
```

| 패키지 | 역할 |
| --- | --- |
| `document_parser` | 파일 → Markdown (TXT·MD·DOCX·PDF, 이미지 PDF는 OCR) |
| `wiki_mcp` | 위키 저장 계층(VaultFS)과 편집 에이전트용 MCP 툴 |
| `agent_runtime` | 에이전트 실행 — claude-code(지금)·deepagents(배포) 런타임, 시간 상한 |
| `wiki_api` | Spring이 부르는 `/internal/v1` 엔드포인트와 기동 진입점 |
| `viewer/` | 위키 참조 그래프 뷰어 (개발 도구). 데이터는 `python -m wiki_mcp.graph_api --root <저장소> --scope ALL` 로 띄운다 |
| `experiments/` | 측정 기록 — `INDEX.md` 가 수치의 정본. 실험별로 `report.json`(문서→위키 변경 매핑 포함)·`data/wiki/`(생성된 위키 전문)·`graph.json`(각주 단위 인용 그래프)이 있어 "어떤 원본에서 어떤 위키가 나왔나"를 추적할 수 있다 |

- **AI 서버는 DB·서비스 파일에 접근하지 않는다.** 요청 본문이 실어 온 것만 처리하고 변경안을 반환한다. 저장·확정은 Spring.
- **라이브 위키는 에이전트에게 읽기 전용.** 모든 쓰기는 작업 공간(`work/{jobId}/output/`)으로 가고, 반영 전 `lint`를 통과해야 한다.
- 의존 방향은 `wiki_api → agent_runtime → wiki_mcp` 단방향. `wiki_mcp`는 위쪽을 임포트하지 않는다.
- `wiki_mcp`는 [Lucas LLM Wiki](https://github.com/lucasastorian/llmwiki)(Apache 2.0) 이식 — 수정 시 파일 헤더의 원본·변경점 기록을 유지하고, 요약은 `NOTICE`에.

## 함정

- **`mcp`라는 이름의 디렉터리·테스트 폴더를 만들지 않는다.** PyPI `mcp` 패키지를 가려서 `No module named 'mcp.server'`가 난다. 같은 이유로 `tests/__init__.py`를 지우면 안 된다 — `tests/mcp/`가 최상위 패키지가 돼 버린다.
- 직접 임포트하는 패키지는 전이 의존성이어도 `pyproject.toml`에 명시한다 (uvicorn·python-multipart에서 한 번씩 밟았다).
- 모델명은 정확한 이름을 쓴다 (`claude-opus-4-6`). `opus` 별칭은 시점에 따라 다른 모델로 해석돼 두 측정의 비교를 조용히 깨뜨린다.
- "동작 확인"은 근거를 구분해 말한다 — 단위 테스트(인프로세스)와 실기동 HTTP, Spring 실제 클라이언트 연동은 다른 수준이다.

## 설계 이력

`docs/superpowers/plans/`, `docs/superpowers/specs/` (AI 전용 설계 문서)
