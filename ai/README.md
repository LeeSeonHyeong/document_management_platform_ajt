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
uv run pytest -m "not ocr"   # OCR 제외 전체 테스트
uv run pytest                # OCR 포함 — 로컬 Tesseract 필요
```

OCR 테스트는 시스템에 [Tesseract](https://github.com/tesseract-ocr/tesseract)와
`eng` traineddata가 설치되어 있어야 한다.

## 구성

| 경로 | 내용 |
| --- | --- |
| `src/document_parser/` | 원본문서 파싱 — TXT·MD·DOCX·PDF, PDF 텍스트 부족 시 OCR 대체 |
| `src/wiki_mcp/` | 위키 저장 계층(VaultFS)과 편집 에이전트가 쓰는 MCP 툴 |
| `src/agent_runtime/` | 에이전트를 실제로 돌리는 층. 런타임 2종과 시간 상한 |
| `src/wiki_api/` | Spring Boot 가 호출하는 내부 API (`/internal/v1`)와 기동 진입점 |
| `src/schedule_extractor/` | 일정 문서에서 일정 초안 추출 — 프롬프트·시각 변환·근거 검사 |
| `viewer/` | 위키 참조 그래프 뷰어 (개발 도구, React·Vite) |
| `experiments/` | 측정 기록 — `INDEX.md` 가 수치의 정본 |
| `tests/` | pytest 테스트 |

## 원본문서 파싱

`document_parser.parse(path)` 하나로 진입한다. 반환은 `ParseResult` —
전체 텍스트, 페이지별 결과(`native`/`ocr` 추출 방법 포함), 품질 점수,
경고·오류. 문서 구조는 Markdown으로 보존한다 (DOCX 헤딩 스타일 →
`#` 헤더). 손상 파일·미지원 형식은 예외 대신 `ParseResult.error`
(`corrupt_document`, `unsupported_file_type` 등 코드)로 반환한다.

## 위키 저장 계층과 MCP 툴

편집 에이전트는 위키 전체를 프롬프트로 받지 않는다. MCP 툴로 검색·조회하고,
쓰기는 작업 공간에만 한다.

- **저장 계층(`wiki_mcp.vaultfs`)** — 라이브 위키는 읽기 전용이고 모든 쓰기가
  `work/{jobId}/output/` 으로 간다. 읽기는 두 계층을 겹쳐 보여주므로 쓰고 다시
  읽어 고칠 수 있다. `base.py`가 포트이고, 로컬 파일 어댑터(`local.py`)와
  Spring 푸시 어댑터(`spring.py`)가 그 포트를 구현한다. 파일이 정본이고
  SQLite는 파생 색인이라 `rebuild.py`로 언제든 다시 만든다.
- **MCP 툴(`wiki_mcp.tools`)** — 조회·검색·생성·수정·병합·삭제와 기계 검증
  (`lint`). 툴은 포트만 알고 저장 구현을 모른다.
- **검색** — 문서를 청크로 나눠 FTS로 찾고, 각 청크가 속한 헤더 경로를
  함께 돌려준다.

반영 전 `lint`를 통과해야 하며, 실제 저장·확정은 Spring Boot가 한다.

`src/wiki_mcp/` 는 [Lucas LLM Wiki](https://github.com/lucasastorian/llmwiki)
(Apache 2.0)에서 이식했다. 각 파일 헤더에 원본 파일명과 변경점이 있고, 전체
변경 요약은 `NOTICE` 에 있다.

## 에이전트 런타임

`src/agent_runtime/` 은 위 MCP 서버를 붙여 에이전트를 한 번 돌리는 층이다.
런타임이 두 개이고 같은 서버·같은 `guide` 를 쓴다. 그래서 둘의 결과 차이는
프롬프트 차이가 아니라 하네스 차이다.

| 런타임 | 언제 쓰나 |
| --- | --- |
| `claude_code.py` | 지금. Claude Code CLI 를 subprocess 로 띄운다. 구독 과금이라 API 키가 없어도 된다 |
| `deep_agents.py` | 배포 형태. FastAPI 안에서 `claude -p` 를 띄우는 것은 성립하지 않는다. `uv sync --extra deepagents` 로 설치 |

- `base.py` — 두 런타임이 지키는 인터페이스와 작업별 지시문 생성
- `limits.py` — 문서 크기에 비례한 시간 상한 (NFR-PERF-002)
- `guards.py` — 에이전트가 MCP 서버를 우회해 쓰지 않았는지 사후 확인

## 내부 API

Spring Boot 만 호출한다. 계약은
`docs/api/AJT-FastAPI-Internal-API.postman_collection.json` (v1.1.0)이 정본이다.

| 엔드포인트 | 하는 일 |
| --- | --- |
| `POST /internal/v1/source-parses` | 원본문서 파일에서 Markdown 을 추출한다 |
| `POST /internal/v1/wiki-context-selections` | 새 문서와 목차만 보고 변환에 필요한 위키를 최대 5개 고른다 |
| `POST /internal/v1/wiki-transformations` | 선택된 위키를 받아 변환하고 변경안을 돌려준다 |
| `POST /internal/v1/wiki-edits` | 관리자의 채팅 수정 지시를 반영한 변경안을 돌려준다 |
| `POST /internal/v1/schedule-extractions` | 일정 문서 Markdown 에서 일정 초안을 뽑는다 |

Spring 이 2단계로 부르는 push 방식이다 — 이 서버는 Spring 을 되물어 읽지 않고, DB·파일에도
닿지 않는다. 요청마다 임시 작업 공간을 만들어 요청 본문의 위키를 라이브 계층에 채우고,
에이전트를 돌린 뒤, 작업 계층의 차이를 변경안으로 조립해 돌려주고 공간을 버린다.

인증은 `X-Internal-API-Key` 헤더다. 키가 없으면 모든 요청이 401 이다.

```sh
INTERNAL_API_KEY=... uv run python -m wiki_api.serve --port 8000
```

```sh
uv run python -m wiki_mcp.local_server --root ./data --scope ALL --job-id 9001
```

## 그래프 뷰어

변환 결과(페이지·인용·관계)를 눈으로 확인하는 개발 도구다. 라이브 계층만 보여준다 —
작업 계층은 검증 전이라 그리지 않는다.

```sh
uv run python -m wiki_mcp.graph_api --root ./data --scope ALL   # 그래프 데이터 API
cd viewer && npm install && npm run dev                          # localhost:5173
```

## 측정 기록

`experiments/INDEX.md` 가 수치의 정본이다. 실험 1개 = 디렉터리 1개
(`manifest.json` 재현 정보 · `report.json` 집계 · `notes.md` 해석). 순차 변환이
반복에서 무너지지 않는다는 근거가 여기 있다.
