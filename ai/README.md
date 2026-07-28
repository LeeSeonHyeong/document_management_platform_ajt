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

```sh
uv run python -m wiki_mcp.local_server --root ./data --scope ALL --job-id 9001
```
