# ai — FastAPI AI 서버

공통 규칙(Git·REST API 컨벤션, 요구사항, ERD)은 루트 `../docs/`가 기준이다. 루트 `CLAUDE.md` 참조.

문서는 두 곳으로 나뉜다. **여러 분야가 함께 보는 것은 `../docs/`**(공통 문서, 수정은 담당자),
**`ai/` 안에서만 알면 되는 구현·설계는 `ai/docs/`**(AI 관할). 이 파일에서 `../docs/` 접두어가
붙은 경로는 공통 문서, 접두어 없는 `docs/`는 `ai/docs/`를 뜻한다.

## 담당 범위

**손대는 곳이 두 군데다** (2026-07-31 확대):

| 어디 | 무엇 |
| --- | --- |
| `ai/` 전부 | 자유롭게 수정한다 |
| 백엔드의 **내부 경로와 AI 클라이언트** | 제시하고 확인받은 뒤 수정한다 (아래) |

내부 경로와 AI 클라이언트란 **AI가 부르는 코드와 AI를 부르는 코드**다:

- `backend/src/main/java/com/ajt/backend/domain/*/api/internal/` — `/internal/**` 컨트롤러
- 그 컨트롤러가 쓰는 `Internal*Service` (예: `InternalScheduleQueryService`·`InternalWikiQueryService`)
- `backend/src/main/java/com/ajt/backend/global/ai/` — 호출 클라이언트·오류 매핑·허가값 발급
- 위 파일들의 테스트 (`backend/src/test/**` 의 대응 파일)

**공개 API 서비스는 내 것이 아니다.** `QuestionAskService` 처럼 `/api/v1/**` 를 처리하는
코드는 챗봇 흐름이어도 백엔드 담당자에게 알린다 — 그쪽에 사용자 인증·트랜잭션·저장이 얽혀
있고, 같은 파일을 둘이 고치면 MR 이 충돌한다.

`frontend/`, `../docs/`, 루트 파일, 그 밖의 백엔드는 담당자가 따로 있다.
동작 확인을 위해 **읽는 것은 자유지만 수정·생성은 하지 않는다.**

### 확인 절차 — 범위 안이어도 먼저 제시한다

`ai/` 밖은 **전부** 바로 고치지 않는다. 내부 경로·AI 클라이언트도 그렇다.
어떤 파일을 왜 어떻게 바꿀지 먼저 제시하고 확인을 받는다. 확인은 그때 제시한
그 파일·그 변경에만 유효하다. 다음 파일이나 다음 턴으로 넘어가지 않는다.

확인 없이 진행해도 된다는 지시가 없으면 기본은 담당자와 소통해 그쪽에서 처리한다.

- 백엔드 요청·응답이 계약과 다르면 → **내부 경로면 내가 고칠 수 있다** (제시 후). 공개 API 쪽이면 담당자에게 알린다
- 계약(`../docs/api/`) 자체를 바꿔야 하면 → `../docs/api/README.md`의 계약 변경 절차를 따른다
- ERD·요구사항 수정이 필요하면 → 해당 담당자에게 알린다
- 계약 불일치를 `ai/` 안의 우회 구현으로 덮지 않는다. 기록하고 알린다

### 백엔드를 고칠 때의 브랜치·티켓

- **커밋과 브랜치를 분야별로 나눈다.** 백엔드 변경과 `ai/` 변경을 한 커밋에 섞지 않는다. stage 경로도 각각 명시한다
- 티켓은 **기존 것을 먼저 찾는다.** 없으면 새로 만든다
- **버려진 티켓을 가져와 내용을 갈아 쓰는 것은 `ai/` 쪽만 한다.** 백엔드 티켓은 그러지 않는다 — 남의 작업 기록이라 무엇이 왜 폐기됐는지 내가 판단할 수 없다
- 백엔드 코드는 **`sh gradlew test` 로 기준선을 먼저 잡고** 고친다. 내가 늘린 실패가 있는지 그 차이로만 본다

## 작업 수칙

- **브랜치·커밋·MR 전에 `../docs/conventions/git-convention.md`를 읽는다.** 원격이 GitLab 이라 MR 이라고 쓰지만, 컨벤션 문서의 "Pull Request"와 같은 것이다. Jira 티켓 먼저, 브랜치는 그 번호로. 티켓은 백로그에서 먼저 찾는다 — 없으면 대개 기존 티켓의 일부다.
- **계약이 정본이다**: `../docs/api/AJT-FastAPI-Internal-API.postman_collection.json`. 컨벤션과 겹치면 계약 기준. 계약에 없는 상황(오류 코드 이름 등)은 임의로 정하지 않고 팀에 공유한다 — 정했다면 MR 본문에 협의 항목으로 명시한다.
- 각 엔드포인트가 낼 수 있는 상태는 계약이 정한 400·401·500뿐이다. 그 밖의 상태를 내면 Spring 분기에서 `UNEXPECTED_STATUS`로 뭉개진다.
- stage 는 **고친 경로를 하나하나 명시한다.** `git add -A`, `git add .` 를 쓰지 않는다. 커밋 전 `git status` 로 담당 범위 밖 변경(프론트·문서·공개 API 서비스)이 섞이지 않았는지 확인한다. 백엔드와 `ai/` 는 커밋을 나눈다.
- `.env*`는 커밋하지 않는다 (`src/.env.example`만). 실제 파일은 `src/.env` — `src/wiki_mcp/config.py` 가 그 경로를 읽는다.

## 스택과 명령

Python 3.12+, [uv](https://docs.astral.sh/uv/).

```bash
cd ai
uv sync
cp src/.env.example src/.env                # 최초 1회. 없으면 APP_URL 이 기본값으로 돈다
uv run pytest -m "not ocr"                  # OCR 제외 (CI 후보)
uv run pytest                               # 전체 — `ocr` 표시 테스트만 로컬 Tesseract(eng) 필요
uv sync --extra deepagents                                         # 배포 런타임 설치 (기본값)
INTERNAL_API_KEY=... uv run python -m wiki_api.serve --port 8000   # 서버 기동 (deepagents, 기본)
AI_RUNTIME=claude-code INTERNAL_API_KEY=... uv run python -m wiki_api.serve --port 8000  # 로컬 claude-code (위키 엔드포인트는 안 된다)
```

`uv` 가 없으면 `curl -LsSf https://astral.sh/uv/install.sh | sh` 로 설치한다 (`~/.local/bin`).

## 구조와 경계

```
Spring Boot --HTTP--> wiki_api --> agent_runtime --> (MCP) --> wiki_mcp
                        ├-> document_parser
                        └-> schedule_extractor
```

| 패키지 | 역할 |
| --- | --- |
| `document_parser` | 파일 → Markdown (TXT·MD·DOCX·PDF). 이미지 PDF는 OCR — 실서버는 GMS 비전 모델(`vision_ocr.py`, `AI_MODEL_FAST`), 미설정 시 로컬 Tesseract 폴백 |
| `wiki_mcp` | 위키 저장 계층(VaultFS)과 편집 에이전트용 MCP 툴 |
| `agent_runtime` | 에이전트 실행 — claude-code(로컬 전용)·deepagents(기본값, 배포) 런타임, 시간 상한. push 경로가 사라져(S15P11B106-175) `claude-code` 로는 위키 엔드포인트를 하나도 못 쓴다(`session.py._assert_runtime_can_use_the_gateway`) — 그래서 기본값이 `deepagents` 다. `claude-code` 는 `AI_RUNTIME=claude-code` 로 명시했을 때만 뜨고, 그때도 챗봇(`/answers`)·파싱(`/source-parses`)은 된다 |
| `wiki_api` | Spring이 부르는 `/internal/v1` 엔드포인트와 기동 진입점 |
| `schedule_extractor` | 일정 문서 Markdown → 일정 초안. 상태 없는 단발 LLM 호출. 시각 변환·연도 추론은 코드가 한다 |
| `viewer/` | 위키 참조 그래프 뷰어 (개발 도구). 데이터는 `uv run python -m wiki_mcp.graph_api --root <저장소> --scope ALL` 로 띄운다 |
| `experiments/` | 측정 기록 — `INDEX.md` 가 수치의 정본. 실험별로 `report.json`(문서→위키 변경 매핑 포함)·`data/wiki/`(생성된 위키 전문)·`graph.json`(각주 단위 인용 그래프)이 있어 "어떤 원본에서 어떤 위키가 나왔나"를 추적할 수 있다 |

패키지마다 먼저 읽을 파일. 각 파일 상단 docstring 이 그 파일의 정본 설명이다.
**소스는 src 레이아웃이다** — 위 표의 패키지는 모두 `ai/src/` 아래에 있고, 아래 경로도 `ai/` 기준이다.

| 파일 | 무엇을 알려주나 |
| --- | --- |
| `src/wiki_api/session.py` | 요청 1건의 생애 — 임시 루트 개설·하이드레이션·에이전트 실행·폐기. 하이드레이션은 Spring Wiki 조회 API(`FederatedVaultFS`)에서 라이브 위키를 읽는다 (S15P11B106-175) |
| `src/wiki_api/changes.py` | 작업 층 diff → 계약 응답. **계약 모양을 아는 유일한 곳.** `pageKey` ↔ `tempWikiId` 매핑 |
| `src/wiki_api/deps.py` | 내부 API 키 검증과 `requestId` 재사용. 사용자 권한 검증은 여기서 하지 않는다 |
| `src/wiki_api/errors.py` | 계약이 허용한 상태(400·401·500)로 좁히는 곳. FastAPI 의 422 를 400 으로 바꾼다 |
| `src/wiki_mcp/config.py` | `src/.env` 에서 읽는 설정 — `APP_URL` 뿐이다 (`tools/helpers.py`). 저장소 루트는 프로세스마다 달라야 하므로 환경변수가 아니라 CLI `--root` 로 준다 |
| `src/wiki_mcp/local_server.py` | stdio MCP 서버. 프로세스 1개 = 스코프 1개 = 작업 1개 (구조적 스코프 격리) |
| `src/wiki_mcp/telemetry.py` | 서버 측 툴 호출 집계. "search 를 안 불렀다"와 "부르고 안 읽었다"를 구분하는 근거. `--query-log` 로 검색어도 남길 수 있다 — **개인정보가 실리므로 기본은 끈다** |
| `src/wiki_mcp/services/chunker.py` | ~512토큰 청크·~128토큰 겹침, 헤더 경로 보존. **한국어 검색은 `search_tokens`/`search_query` 짝이다** — 색인·질의 양쪽에 같은 2글자 분해를 걸어야 한다. 한쪽만 하면 아무것도 안 맞는다. 근거 수치는 그 docstring |
| `src/wiki_mcp/shared/schema.sql` | 파생 색인 스키마. `src/wiki_mcp/vaultfs/rebuild.py` 로 언제든 재생성 |
| `src/document_parser/normalize.py` | 줄바꿈·공백 정규화 헬퍼 |

- **AI 서버는 DB·서비스 파일에 접근하지 않는다.** 라이브 위키는 Spring Wiki 조회 API로만 읽고(목차·카테고리·본문·근거 문서 포함) 변경안을 반환한다. 저장·확정은 Spring.
- **라이브 위키는 에이전트에게 읽기 전용.** 모든 쓰기는 작업 공간(`work/{jobId}/output/`)으로 가고, 반영 전 `lint`를 통과해야 한다.
- 의존 방향은 `wiki_api → agent_runtime → wiki_mcp` 단방향. `wiki_mcp`는 위쪽을 임포트하지 않는다.
  `schedule_extractor`는 `wiki_mcp`·`agent_runtime`을 임포트하지 않는다 — 상태가 없어 저장 계층이 필요 없다.
- `wiki_mcp`는 [Lucas LLM Wiki](https://github.com/lucasastorian/llmwiki)(Apache 2.0) 이식 — 수정 시 파일 헤더의 원본·변경점 기록을 유지하고, 요약은 `NOTICE`에.

## 함정

- **`mcp`라는 이름의 디렉터리·테스트 폴더를 만들지 않는다.** PyPI `mcp` 패키지를 가려서 `No module named 'mcp.server'`가 난다. 같은 이유로 `tests/__init__.py`를 지우면 안 된다 — `tests/mcp/`가 최상위 패키지가 돼 버린다.
- 직접 임포트하는 패키지는 전이 의존성이어도 `pyproject.toml`에 명시한다 (uvicorn·python-multipart에서 한 번씩 밟았다).
- 모델명은 정확한 이름을 쓴다 (`claude-opus-4-6`). `opus` 별칭은 시점에 따라 다른 모델로 해석돼 두 측정의 비교를 조용히 깨뜨린다.
- **파일이 없다고 문서 오류로 판단하기 전에 다른 브랜치를 본다.** `ai/` 작업 브랜치는 develop보다 뒤처진 게 정상이라 작업 트리의 부재는 드리프트 근거가 아니다. `git ls-tree -r --name-only origin/develop -- ai/<경로>` 로 확인한다. 없는 줄 알고 `.gitignore` 규칙을 넣어 develop 추적 파일을 무시한 사고가 있었다.
- "동작 확인"은 근거를 구분해 말한다 — 단위 테스트(인프로세스)와 실기동 HTTP, Spring 실제 클라이언트 연동은 다른 수준이다.

## 설계 이력

`ai/docs/superpowers/plans/`, `ai/docs/superpowers/specs/` (AI 전용 설계 문서 — 공통 `../docs/` 아니다)
