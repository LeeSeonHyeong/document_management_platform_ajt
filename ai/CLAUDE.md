# ai — FastAPI AI 서버

공통 규칙(Git·REST API 컨벤션, 요구사항, ERD)은 루트 `../docs/`가 기준이다. 루트 `CLAUDE.md` 참조.

## 담당 범위

직접 수정하는 것은 **`ai/` 뿐이다.**

`backend/`, `frontend/`, `docs/`, 루트 파일은 각각 담당자가 따로 있다.
동작 확인을 위해 **읽는 것은 자유지만 수정·생성은 하지 않는다.**

사용자가 `ai/` 밖 파일 수정을 명시적으로 지시한 경우에도 바로 고치지 않는다.
어떤 파일을 왜 어떻게 바꿀지 먼저 제시하고 확인을 받는다. 확인은 그때 제시한
그 파일·그 변경에만 유효하다. 다음 파일이나 다음 턴으로 넘어가지 않는다.

확인 없이 진행해도 된다는 지시가 없으면 기본은 담당자와 소통해 그쪽에서 처리한다.

- 백엔드 요청·응답이 계약과 다르면 → 백엔드 담당자에게 알린다
- 계약(`../docs/api/`) 자체를 바꿔야 하면 → `../docs/api/README.md`의 계약 변경 절차를 따른다
- ERD·요구사항 수정이 필요하면 → 해당 담당자에게 알린다
- 계약 불일치를 `ai/` 안의 우회 구현으로 덮지 않는다. 기록하고 알린다

## 작업 수칙

- **브랜치·커밋·MR 전에 `../docs/conventions/git-convention.md`를 읽는다.** Jira 티켓 먼저, 브랜치는 그 번호로. 티켓은 백로그에서 먼저 찾는다 — 없으면 대개 기존 티켓의 일부다.
- **계약이 정본이다**: `../docs/api/AJT-FastAPI-Internal-API.postman_collection.json`. 컨벤션과 겹치면 계약 기준. 계약에 없는 상황(오류 코드 이름 등)은 임의로 정하지 않고 팀에 공유한다 — 정했다면 MR 본문에 협의 항목으로 명시한다.
- 각 엔드포인트가 낼 수 있는 상태는 계약이 정한 400·401·500뿐이다. 그 밖의 상태를 내면 Spring 분기에서 `UNEXPECTED_STATUS`로 뭉개진다.
- stage 는 `ai/` 하위 경로만 명시한다. `git add -A`, `git add .` 를 쓰지 않는다. 커밋 전 `git status` 로 `ai/` 밖 변경이 섞이지 않았는지 확인한다.
- `.env*`는 커밋하지 않는다 (`src/.env.example`만). 실제 파일은 `src/.env` — `wiki_mcp/config.py` 가 그 경로를 읽는다.

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

패키지마다 먼저 읽을 파일. 각 파일 상단 docstring 이 그 파일의 정본 설명이다.

| 파일 | 무엇을 알려주나 |
| --- | --- |
| `wiki_api/session.py` | 요청 1건의 생애 — 임시 루트 개설·하이드레이션·에이전트 실행·폐기. v1.1.0 부터 Spring 에 되묻지 않는다 |
| `wiki_api/changes.py` | 작업 층 diff → 계약 응답. **계약 모양을 아는 유일한 곳.** `pageKey` ↔ `tempWikiId` 매핑 |
| `wiki_api/deps.py` | 내부 API 키 검증과 `requestId` 재사용. 사용자 권한 검증은 여기서 하지 않는다 |
| `wiki_mcp/config.py` | `src/.env` 에서 읽는 설정 — `WORKSPACE_PATH`·`APP_URL` 뿐 |
| `wiki_mcp/local_server.py` | stdio MCP 서버. 프로세스 1개 = 스코프 1개 = 작업 1개 (구조적 스코프 격리) |
| `wiki_mcp/telemetry.py` | 서버 측 툴 호출 집계. "search 를 안 불렀다"와 "부르고 안 읽었다"를 구분하는 근거. `--query-log` 로 검색어도 남길 수 있다 — **개인정보가 실리므로 기본은 끈다** |
| `wiki_mcp/services/chunker.py` | ~512토큰 청크·~128토큰 겹침, 헤더 경로 보존. **한국어 검색은 `search_tokens`/`search_query` 짝이다** — 색인·질의 양쪽에 같은 2글자 분해를 걸어야 한다. 한쪽만 하면 아무것도 안 맞는다. 근거 수치는 그 docstring |
| `wiki_mcp/shared/schema.sql` | 파생 색인 스키마. `vaultfs/rebuild.py` 로 언제든 재생성 |
| `document_parser/normalize.py` | 줄바꿈·공백 정규화 헬퍼 |

- **AI 서버는 DB·서비스 파일에 접근하지 않는다.** 요청 본문이 실어 온 것만 처리하고 변경안을 반환한다. 저장·확정은 Spring.
- **라이브 위키는 에이전트에게 읽기 전용.** 모든 쓰기는 작업 공간(`work/{jobId}/output/`)으로 가고, 반영 전 `lint`를 통과해야 한다.
- 의존 방향은 `wiki_api → agent_runtime → wiki_mcp` 단방향. `wiki_mcp`는 위쪽을 임포트하지 않는다.
- `wiki_mcp`는 [Lucas LLM Wiki](https://github.com/lucasastorian/llmwiki)(Apache 2.0) 이식 — 수정 시 파일 헤더의 원본·변경점 기록을 유지하고, 요약은 `NOTICE`에.

## 함정

- **`mcp`라는 이름의 디렉터리·테스트 폴더를 만들지 않는다.** PyPI `mcp` 패키지를 가려서 `No module named 'mcp.server'`가 난다. 같은 이유로 `tests/__init__.py`를 지우면 안 된다 — `tests/mcp/`가 최상위 패키지가 돼 버린다.
- 직접 임포트하는 패키지는 전이 의존성이어도 `pyproject.toml`에 명시한다 (uvicorn·python-multipart에서 한 번씩 밟았다).
- 모델명은 정확한 이름을 쓴다 (`claude-opus-4-6`). `opus` 별칭은 시점에 따라 다른 모델로 해석돼 두 측정의 비교를 조용히 깨뜨린다.
- **파일이 없다고 문서 오류로 판단하기 전에 다른 브랜치를 본다.** `ai/` 작업 브랜치는 develop보다 뒤처진 게 정상이라 작업 트리의 부재는 드리프트 근거가 아니다. `git ls-tree -r --name-only origin/develop -- ai/<경로>` 로 확인한다. 없는 줄 알고 `.gitignore` 규칙을 넣어 develop 추적 파일을 무시한 사고가 있었다.
- "동작 확인"은 근거를 구분해 말한다 — 단위 테스트(인프로세스)와 실기동 HTTP, Spring 실제 클라이언트 연동은 다른 수준이다.

## 설계 이력

`docs/superpowers/plans/`, `docs/superpowers/specs/` (AI 전용 설계 문서)
