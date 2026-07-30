# Wiki 문맥 범위 확대 대응 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (또는 superpowers:subagent-driven-development) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 문맥 5개 상한이 없어져 Spring 이 위키를 전량 실어 보낼 수 있게 되면서 확대된 AI 서버 결함 3건을 막고, 배포 런타임 기준 실행 시간의 근거를 만든다.

**Architecture:** 이 계획은 `ai/` 안에서만 끝난다. Spring 창구(federation)로 가는 전환은 범위 밖이고 후속 이터레이션이다. 저장 계층 포트(`vaultfs/base.py`)와 툴 10개의 인터페이스는 건드리지 않으며, 변경은 (1) 요청 검증 (2) 검색 토큰화 (3) `lint` 검사 범위 (4) 측정 하네스 네 지점에 국한한다.

**Tech Stack:** Python 3.12, uv, pytest, SQLite FTS5, Claude Code CLI / DeepAgents 런타임

---

## 배경

- 설계 근거: `../specs/2026-07-29-wiki-context-federation-design.md`
- 문서 개정: Jira S15P11B106-139 · MR !63 (요구사항 v2.9 · 계약 v1.3.1 · ERD)
- 백엔드 결함 전달: Jira S15P11B106-73 코멘트 (트랜잭션 경계)

`FR-WIKI-002` 에서 "최대 5개 선택" 이 빠졌다. Spring 이 그 범위 위키를 전량 보낼 수 있게 되었고, `schemas.py:80` 의 `selectedWikis` 에 상한이 없어 **AI 코드 변경 없이 그대로 받는다.**

그 결과 기존 결함 3건의 영향 범위가 5장에서 전 범위로 커졌다.

| # | 결함 | 5장일 때 | 전량일 때 |
| --- | --- | --- | --- |
| D7 | 요청 크기 상한 없음 | 페이로드 작음 | 메모리·임시 디스크 소진 가능 |
| D8-검색 | 한국어 검색 실패 (`unicode61`) | 5장이라 덜 드러남 | 검색 대상이 늘었는데 못 찾는다 |
| D5 | `lint` 이 라이브 전체를 검사 | 오류 노이즈 5장분 | 전 범위 각주가 오류로 뜬다 |

D5 는 단순 노이즈가 아니다. 에이전트 지시가 "마지막에 `lint` 를 부른다"(`base.py:117`)이므로 **에이전트가 남이 만든 각주를 지우려 든다.**

### 측정 근거 (설계 문서 §2 · §9.1)

한국어 코퍼스 100장(평균 7,946자) · 질의 40개(정확어 20 + 자연어 20). Task 8 에서
`experiments/corpus-ko/` 로 승격했고 `evaluate_sqlite.py --split all` 로 재현한다.

| 색인 · 질의 | R@5 | 정확어 R@5 | 자연어 R@5 | 0건 |
| --- | --- | --- | --- | --- |
| `unicode61` AND — **현행** | 0.33 | 0.65 | 0.00 | 26/40 |
| `unicode61` OR | 0.38 | 0.65 | 0.10 | 19/40 |
| `trigram` AND | 0.30 | 0.60 | 0.00 | 28/40 |
| `trigram` OR | 0.35 | 0.60 | 0.10 | 26/40 |
| **2글자 분해 OR** | **0.60** | **0.95** | **0.25** | **7/40** |
| 2글자 분해 AND / phrase | 0.47 | 0.95 | 0.00 | 20/40 |

MySQL 8.4 `ngram` 실측에서도 정확어 0.95 / MRR 0.96 이 일치했다. 위키 변환 질의는 정확어
성격이라 이 값이 결정적이다 — 다만 그 성격 판정 자체가 아직 가정이다 (Task 9).

> **정정 (2026-07-30, Task 8).** 앞 판본의 표는 세 줄이 서로 다른 조건이었다 —
> `unicode61 0.38` 은 OR 변형, `trigram 0.50 / 24건` 은 폐기한 48개 세트, 2글자만 40개
> 세트였다. 결론은 그대로지만 차이가 보고했던 것보다 크고 `trigram` 은 더 나쁘다.
> 수치를 적을 때 **색인 방식 · 질의 방식 · 분할** 셋을 함께 적는다.

### 시간 수치의 한계

현재 모든 실행 시간이 `claude-code` CLI 측정이다. **배포는 `deepagents` 런타임**이고 그 조합은 측정하지 않았다. `--output-format json` 이라 턴별·툴별 내역도 남지 않는다.

| 항목 | 값 | 출처 |
| --- | --- | --- |
| 문서 1건 | 126~894초 | CLI · `2026-07-27-opus46-12docs` |
| 12건 순차 | 93분 | 같음 |
| 시간 ≈ 출력토큰 ÷ 55 | 12건 중 11건이 45~59 tok/s | 같음 |
| `--effort` | **미지정** — CLI 기본값 | `claude_code.py:144` |

---

## 사전 조건과 파일 지도

`ai/` 밖 파일은 수정하지 않는다. `docs/`·`backend/` 변경이 필요하면 제시하고 확인을 받는다.

측정 작업(Task 5~7)은 `claude-code` 런타임을 쓰므로 구독 과금이며 API 크레딧이 들지 않는다. GMS 게이트웨이 Anthropic 키는 소진돼 사용 불가다 (`experiments/INDEX.md`).

생성/수정 파일과 책임:

- `src/wiki_api/schemas.py`: 요청 크기 상한 검증 (Task 1)
- `src/wiki_mcp/services/chunker.py`: 색인용 2글자 분해, 토큰 추정 주석 정정 (Task 2·4)
- `src/wiki_mcp/vaultfs/local.py`: `search_chunks` 질의 전처리 (Task 2)
- `src/wiki_mcp/tools/lint.py`: 검사 범위 분리 (Task 3)
- `src/agent_runtime/claude_code.py`: `stream-json` 전환, `--effort` 전달 (Task 5)
- `experiments/corpus-ko/`: 한국어 코퍼스·질의셋 승격 (Task 8)
- `tests/`: 각 변경의 회귀 테스트

### 세션 스크래치패드에만 있는 자산

다음은 이 세션의 임시 디렉터리에만 있다. **Task 8 이전에 유실되면 재생성해야 한다.**

```
kcorpus/v2/pages/*.md      한국어 위키 100장 (평균 7,946자)
kcorpus/queries.json       질의 48개 (초기 세트)
ir_ko.py 내 EXACT·PARA     질의 40개 (정확어 20 + 자연어 20) — 이 계획이 쓰는 정본
eval2.py · ir_ko2.py       토크나이저 평가 하네스
mysql_load.py · mysql_eval.py   MySQL ngram 평가 하네스
```

---

### Task 1: 요청 크기 상한 (D7)

**Files:**
- Modify: `src/wiki_api/schemas.py`
- Modify: `tests/api/test_schemas_v11.py`

**Step 1: 실패하는 테스트를 먼저 쓴다**

- [ ] `selectedWikis` 개수가 상한을 넘으면 400 이 되는 테스트
- [ ] `selectedWikis` 총 UTF-8 바이트가 상한을 넘으면 400 이 되는 테스트
- [ ] 개별 `contentMarkdown` 이 상한을 넘으면 400 이 되는 테스트
- [ ] 상한 안이면 통과하는 테스트 (위키 200장 · 각 8KB 수준)

**Step 2: 검증을 구현한다**

- [ ] `TransformRequest` 에 `model_validator` 로 세 상한을 검사한다
- [ ] 오류는 계약이 정한 400 `INVALID_WIKI_TRANSFORMATION_REQUEST` 로 낸다. 새 상태·코드를 만들지 않는다
- [ ] `fieldErrors[].field` 는 `selectedWikis` 로 낸다
- [ ] 상한 값을 모듈 상수로 두고 근거를 주석에 남긴다 — 위키 300장 페이로드가 약 4MB, 하이드레이션 1.2초(설계 문서 §2.2)

**Step 3: 값 결정**

- [ ] 개수 상한은 두지 않거나 넉넉히 잡는다. **`FR-WIKI-002` 가 "선택 개수 상한은 두지 않는다" 로 개정됐다** — 개수로 막으면 요구사항 위반이다
- [ ] 대신 **총 바이트**로 막는다. 이것이 실제 자원 한계다
- [ ] `EditRequest` 에도 같은 규칙을 적용한다 (`currentWiki`·`evidenceDocuments`)

**Verify:**
- [ ] `uv run pytest tests/api -m "not ocr"` 통과
- [ ] 상한 초과 요청이 500 이 아니라 400 으로 나가는지 확인 (`UNEXPECTED_STATUS` 방지)

---

### Task 2: 한국어 검색 — 2글자 분해 (D8-검색)

**Files:**
- Modify: `src/wiki_mcp/services/chunker.py`
- Modify: `src/wiki_mcp/vaultfs/local.py`
- Create: `tests/mcp/test_korean_search.py`

색인과 질의 **양쪽에 같은 전처리**를 걸어야 한다. 한쪽만 하면 아무것도 안 맞는다.

**Step 1: 실패하는 테스트를 먼저 쓴다**

- [ ] 본문 `"연차를 사용하려면 승인이 필요하다"` 를 색인하고 `"연차"` 로 찾는 테스트 — 현행 구현에서 실패해야 한다
- [ ] `"이월"`·`"승인"`·`"부여"` 같은 2글자 질의 테스트
- [ ] 본문에 없는 단어(`"싸이버펑크"`)가 0건인 테스트 — 오탐 회귀 방지
- [ ] 영문 질의가 계속 동작하는 테스트 — 기존 영어 코퍼스 회귀 방지
- [ ] 헤더 경로(`header_breadcrumb`)가 결과에 그대로 실리는 테스트

**Step 2: 전처리 함수를 만든다**

- [ ] `chunker.py` 에 `search_tokens(text) -> str` 를 추가한다. NFC 정규화 → 어절 분리 → 어절마다 2글자 겹침 분해 → 공백 결합
- [ ] 1글자 어절은 그대로 둔다
- [ ] 영문·숫자는 소문자화만 하고 분해하지 않는다 — 분해하면 영어 재현율이 떨어진다
- [ ] docstring 에 측정 수치와 `trigram` 을 쓰지 않는 이유를 남긴다

**Step 3: 색인 경로에 적용한다**

- [ ] `store_chunks` 가 FTS 에 넣는 값에 전처리를 적용한다. **`document_chunks.content` 원문은 그대로 둔다** — 스니펫·각주 대조가 원문을 읽는다
- [ ] 전처리본을 어디에 둘지 결정한다. `chunks_fts` 트리거가 `document_chunks.content` 를 그대로 넣으므로 **스키마 변경이 필요하다** (`shared/schema.sql`)
- [ ] `vaultfs/rebuild.py` 로 재생성해도 같은 결과가 나오는지 확인한다

**Step 4: 질의 경로에 적용한다**

- [x] `local.py` 의 `search_chunks` 가 질의에 같은 전처리를 적용한다
- [x] ~~어절 단위로 묶는다. 어절 하나의 bigram 들은 하나의 검색 단위여야 한다~~ — **측정이
      뒤집었다.** 어절 phrase 묶음은 `P@5` 가 같고(0.12) 재현율만 0.60 → 0.57 로 떨어진다.
      bigram 이 이미 연속 부분문자열이라 순서가 뒤집힌 오탐이 생기지 않는다 — 질의 `"차연"`
      의 bigram 은 `[차연]` 이고 본문 `"연차를"` 의 색인은 `[연차, 차를]` 이라 애초에 맞지
      않는다. **평평한 OR 로 구현했다** (`chunker.search_query` 에 근거를 남겼다)
- [x] 전부 OR 로 잇는다. AND 는 자연어 질의를 죽인다 — 0건 20/40. FTS5 는 공백을 AND 로
      읽으므로 OR 를 명시하지 않으면 그 나쁜 쪽이 기본값이다
- [ ] 오탐이 문제가 되면 phrase 로 조인다 — 코퍼스가 커진 뒤 재측정해서 판단한다

**Verify:**
- [ ] `uv run pytest tests/mcp -m "not ocr"` 통과
- [ ] Task 8 의 코퍼스로 R@5 ≥ 0.55, 정확어 R@5 ≥ 0.90 확인
- [ ] 영어 코퍼스(`experiments/corpus/`) 회귀 없음 — `search` 결과가 이전과 같은 페이지를 찾는지

---

### Task 3: `lint` 검사 범위 분리 (D5)

**Files:**
- Modify: `src/wiki_mcp/tools/lint.py`
- Modify: `src/wiki_api/session.py`
- Modify: `tests/mcp/` (해당 테스트)

설계 문서 §3.5 를 따른다. 목적이 다른 둘로 나눈다.

**Step 1: 지금 무엇이 버려지는지 테스트로 고정한다**

- [ ] `session.py:64` `_LIVE_ONLY_BLOCKING_CODES` 가 `dangling-link` 하나뿐임을 검증하는 테스트
- [ ] 라이브 전용 페이지의 `missing-frontmatter`·`unresolved-citation` 이 반영을 막지 않음을 검증하는 테스트
- [ ] **`uncited-source` 는 오류로 남아야 한다** (`lint.py:279`) — 이번 문서가 어느 위키에도 반영되지 않은 경우를 잡는 유일한 검사다

**Step 2: 에이전트용 검사 범위를 좁힌다**

- [ ] 작업층 페이지는 전부 검사한다 (frontmatter · 각주 형식 · 각주 원문 대조 · 링크)
- [ ] 라이브 전용 페이지는 **내용 검사를 하지 않는다.** 내용이 바뀌지 않았으므로 새로 깨질 수 없고, 그 결과는 `session.py` 가 이미 버린다
- [ ] **`uncited-source` 는 유지한다.** 이번 입력이 반영됐는지 보는 검사다
- [ ] 삭제·병합한 주소를 가리키는 라이브 페이지는 역조회로 확인한다. **나가는 링크를 지운 경우는 대상이 아니다** — 남의 링크를 깨지 않는다

**Step 3: 서버 게이트를 구조화한다**

- [ ] `session.py` 가 `lint` 의 문자열 보고서를 정규식으로 재파싱하는 것을 없앤다 (`_ISSUE_LINE_RE`·`_FOOTNOTE_LABEL_RE`·`_LINK_TARGET_RE`)
- [ ] 반영을 막는 판정은 구조화된 결과로 받는다
- [ ] 막는 조건은 지금과 동일하게 유지한다. **이 Task 는 범위를 좁히는 것이고 게이트를 느슨하게 하는 것이 아니다**

**Verify:**
- [ ] `uv run pytest -m "not ocr"` 전체 통과
- [ ] 위키 100장 + 원본문서 1건 조건에서 에이전트가 받는 `lint` 출력에 라이브 페이지의 각주 오류가 없는지 확인
- [ ] 위키를 삭제하는 시나리오에서 그것을 가리키는 라이브 페이지가 오류로 잡히는지 확인

---

### Task 4: `chunker` 토큰 추정 주석 정정 (D6)

**Files:**
- Modify: `src/wiki_mcp/services/chunker.py`

**동작은 바꾸지 않는다.** 청크 크기를 512 실토큰으로 줄이면 자연어 R@5 가 0.60 → 0.50 으로 떨어진다는 것이 측정으로 확인됐다 (설계 문서 §2).

- [ ] `_estimate_tokens` 주석의 "which only makes chunks smaller than the target" 을 정정한다. 과소계산하면 늦게 잘려 **큰** 청크가 나온다
- [ ] 실측을 주석에 남긴다 — 한국어 실토큰 평균 3,454 vs 목표 512(6.7배), 영어 1,053(2.1배)
- [ ] 그래도 유지하는 이유를 남긴다 — 페이지 단위 dedupe 와 스니펫 240자 절단 때문에 검색 품질·프롬프트 비용에 영향이 없고, 줄이면 자연어 재현율이 떨어진다

**Verify:**
- [ ] `uv run pytest tests/parsing -m "not ocr"` 통과 (동작 무변경 확인)

---

### Task 5: 런타임 관측 — `stream-json` 전환

**Files:**
- Modify: `src/agent_runtime/claude_code.py`
- Modify: `tests/runtime/` (해당 테스트)

Task 6·7 의 결과를 해석하려면 이것이 먼저다. 지금은 총계만 남아 출력 토큰이 어디서 나오는지 모른다.

**Step 1: 테스트를 먼저 쓴다**

- [ ] `stream-json` 출력에서 턴별 이벤트를 파싱하는 테스트 (고정 샘플 입력)
- [ ] 툴 호출별 타임스탬프·토큰을 집계하는 테스트
- [ ] 파싱 실패 시에도 기존처럼 `RunResult` 를 내는 테스트 — 관측이 실행을 깨뜨리면 안 된다

**Step 2: 구현한다**

- [ ] `--output-format stream-json` 으로 바꾸고 스트림을 줄 단위로 파싱한다
- [ ] 턴별 소요시간·입출력 토큰·사고 토큰·툴 이름을 집계한다
- [ ] `RunResult` 에 그 내역을 추가한다. 기존 필드는 유지한다 (계약 응답에는 안 실린다)
- [ ] `--effort` 를 인자로 받아 전달할 수 있게 한다. 기본값은 지정하지 않는다

**Step 3: 기록에 남긴다** — **막혔다.** `backend_sim.py` 가 이 브랜치에 없다 (챗봇
브랜치에만 있고 develop 은 하네스를 안 들고 있다 — `INDEX.md` 이관 주석). 챗봇 MR 병합 후 처리한다.

- [ ] `backend_sim.py` 가 `manifest.json` 에 `effort` 를 적는다. **지금까지 모든 측정이 CLI 기본값으로 돌았고 그 값이 기록되지 않았다**
- [ ] `manifest.json` 에 CLI 판본과 설정 출처도 적는다 — 스폰된 CLI 가 운영자 `~/.claude` 를 물려받아 툴 표면이 달라진다 (`INDEX.md` 「측정을 막고 있는 것」)
- [ ] `report.json` 에 턴별·툴별 내역을 담는다. `RunResult.detail` 이 그 모양이다

**Verify:**
- [x] `uv run pytest tests/runtime -m "not ocr"` 통과 — 46건
- [x] 실기동으로 턴별 내역이 남는지 확인 — 남는다. 그 실행에서 **CLI 가 MCP 서버에 연결하지
      못하는 것**이 드러났고 (`INDEX.md` 「측정을 막고 있는 것」) 파서가 그것을 정확히
      기록했다: 서버측 집계 0, 스트림측 `Bash` 10·`Edit` 8. 총계만 보던 앞 판본에서는
      「툴 호출 0」까지만 보였다
- [x] 부수 발견 — `result` 이벤트가 없는 실행이 **성공으로 나가고 있었다.** 종료 코드 0 +
      `is_error` falsy 라서 아무것도 안 한 실행이 빈 본문의 성공이 됐다. 세션이 「변경 없음」
      200 으로 Spring 에 보내 `document_results` 에 성공으로 남는다 (NFR-AI-003 상실).
      앞 판본에도 있던 구멍이다 — 실패로 고치고 회귀 테스트를 넣었다

---

### Task 6: `--effort` 대조 측정

**Files:**
- Create: `experiments/2026-07-XX-effort-<level>/` (단계별)

**Goal:** 시간·비용과 품질의 교환비를 얻고, 지금까지의 측정이 어느 단계였는지 역추정한다.

- [ ] 문서 2건(`01-training`·`02-side-gigs`)으로 `low`·`medium`·`high` 를 각각 측정한다
- [ ] 각 실행의 `manifest.json` 에 `effort` 를 명시한다
- [ ] 지표: 시간 · 출력 토큰 · 사고 토큰 · 턴 · 툴 호출 · lint 오류 · 조각 수 · 페이지간 링크 · 각주 수
- [ ] `INDEX.md` 의 대조 규칙을 지킨다 — **같은 조건 1회 비교는 근거가 안 된다.** 시간·비용의 10~30% 차이는 판정에 쓰지 않고 구조적 지표만 1회 측정으로 읽는다
- [ ] `low` 로 구조적 지표(조각 0 · 페이지간 링크 · 각주 원문 인용)가 유지되면 그 단계를 기본값으로 제안한다

**Verify:**
- [ ] `experiments/INDEX.md` 에 결과와 판정을 기록한다
- [ ] 기본값 변경을 제안할 경우 근거를 `manifest.json` 과 함께 남긴다

---

### Task 7: `deepagents` 런타임 실행 시간

**Files:**
- Create: `experiments/2026-07-XX-deepagents-2docs/`

**배포는 `claude-code` CLI 가 아니다.** FastAPI 안에서 `claude -p` 를 subprocess 로 띄우는 것은 성립하지 않으므로 배포 형태는 `deepagents` 다 (`ai/README.md`).

- [ ] `uv sync --extra deepagents` 로 설치한다
- [ ] 같은 문서 2건을 `deepagents` 로 돌린다
- [ ] CLI 측정과 대조한다. **`INDEX.md` 규칙 — CLI 수치는 CLI 끼리만 비교한다.** 하네스마다 토큰 집계가 다르므로 시간과 구조적 지표로 비교한다
- [ ] API 키가 필요하다. GMS 게이트웨이 Anthropic 키는 소진 상태이므로 **키 확보가 선행 조건**이다

**Verify:**
- [ ] `NFR-PERF-002` 의 절대 상한을 배포 조건에 맞게 재검토할 근거가 나오는지 확인
- [ ] D1(백엔드 읽기 제한)을 조정해야 하는지 판단할 수 있는지 확인. 지금은 "그대로 두고 문제가 생기면" 으로 보류 중이다
- [ ] **관리자 대화 경로를 따로 본다.** `47b7ca8` 이 올린 `editWiki` 배선에도 같은 `read-timeout: 10m` 이 걸리는데 그것은 **동기 요청이라 사람이 화면 앞에서 기다린다.** 변환용과 수정용 제한 시간을 나눠야 하는지 판단한다 (설계 문서 §8.4 D3b)

---

### Task 8: 한국어 코퍼스·질의셋 저장소 승격

**Files:**
- Create: `experiments/corpus-ko/pages/*.md` (100장)
- Create: `experiments/corpus-ko/queries.json`
- Create: `experiments/corpus-ko/README.md`
- Modify: `experiments/INDEX.md`

`INDEX.md` 가 경고한 "한국어 코퍼스 측정은 미실시" 를 메운다. Task 2 의 회귀 테스트 기반이 되고, Spring `ngram` 구현 검증에도 같은 셋을 쓸 수 있다.

**Step 1: 신뢰도를 먼저 보강한다**

- [ ] **dev/test 를 분리한다.** test 셋은 한 번만 사용한다
- [ ] 질의·정답 라벨을 블라인드 재라벨한다. 현재는 작성자·라벨러·튜너가 동일하다
- [ ] hard negative 를 보강한다 — 유사·중복·상충 위키
- [ ] 질의셋 정본을 하나로 고정한다. **지금 48개 세트와 40개 세트가 섞여 있고 수치가 다르다** (bigram OR R@5 0.90 vs 0.60). 40개 세트(정확어 20 + 자연어 20)를 정본으로 한다

**Step 2: 승격한다**

- [ ] `README.md` 에 합성 코퍼스임을 명시한다. 실제 사내 문서가 아니다
- [ ] 페이지 크기가 실측 위키(평균 7,793자)에 맞춰졌음을 적는다
- [ ] `INDEX.md` 에 한국어 측정 결과를 추가하고 "미실시" 경고를 갱신한다
- [ ] 영어 코퍼스 수치와 **직접 비교하지 말라**는 주의를 남긴다 — 한국어 100장 중 5개 고르기 vs 영어 12건 중 5개 고르기로 난이도가 다르다

**Verify:**
- [ ] Task 2 의 회귀 테스트가 이 코퍼스를 참조하는지 확인
- [ ] `uv run python experiments.py` 가 새 항목을 표에 넣는지 확인

---

### Task 9: 실제 에이전트 검색어 수집

**Files:**
- Modify: `src/wiki_mcp/telemetry.py`
- Modify: `src/wiki_mcp/tools/search.py`

설계 문서 §2.3 의 **"변환 질의는 정확어 성격"** 이 취약한 결론이다. 실제 검색어는 원본문서 문장이 아니라 에이전트가 생성한다.

- [x] `telemetry.py` 가 툴 호출 횟수 외에 `search` 질의 문자열을 남긴다 — `record_search`
- [x] 질의를 정확어/자연어로 분류할 수 있는 형태로 기록한다. 질의 + **히트 수** + `scopeKey`
      를 줄 단위 JSON 으로. 히트 0건이 가장 중요한 신호다
- [x] 분류와 집계를 함께 넣었다 — `classify_query` · `summarise_queries`. 기준은 질의어가
      원본문서에 글자 그대로 있는지다. 어절 단위로 비교하지 않는다: 원본문서에는 조사가
      붙어 `"연차를"` 로 있고 질의는 `"연차"` 다
- [ ] 측정 실행에서 수집한 질의로 §2.3 결론을 재검증한다 — **막혔다.** CLI 가 MCP 서버에
      연결하지 못해 에이전트 실행 자체가 안 된다 (`INDEX.md` 「측정을 막고 있는 것」)
- [x] **개인정보·사내 내용이 로그에 남을 수 있다.** `--query-log` 를 준 세션에서만 켜지고
      기본은 끈다. `--tool-log`(횟수)와 별개 스위치다 — 횟수는 진단에 늘 필요하다

**Verify:**
- [x] 기존 `read_counts` 동작이 깨지지 않는지 확인 (`guards.py` 가 의존한다) — 파일을
      나눴다. `read_counts` 가 공백으로 나눠 세므로 같은 파일에 질의를 섞으면 어절마다 툴
      이름으로 세어져 `bypassed_server` 판정이 망가진다
- [x] 수집 결과로 "정확어 비율" 을 낼 수 있는지 확인 — `summarise_queries` 가
      `exactRatio` 와 `zeroHit` 을 낸다. 실제 수집은 위 막힌 항목 때문에 대기

---

## 실행 순서와 근거

```
1  Task 1  요청 크기 상한       가장 짧다. 전체 push 의 방어
2  Task 2  한국어 검색          지금 반쯤 고장. 검색 대상이 늘어 더 중요해졌다
3  Task 3  lint 범위           위키가 늘어나면 남의 각주를 고칠 확률이 올라간다
4  Task 4  chunker 주석        5분. 동작 무변경
5  Task 5  stream-json         Task 6·7 을 해석하려면 선행
6  Task 6  --effort 대조        15분 → 3분 가능성. 크레딧 0
7  Task 8  코퍼스 승격          재라벨이 필요해 시간이 걸린다
8  Task 9  검색어 수집          결론 신뢰도 보강
9  Task 7  deepagents 측정      API 키 확보가 선행 조건
```

Task 1~3 이 MR !63 때문에 급해진 것들이다. Task 4~9 는 독립적이라 순서를 바꿀 수 있다.

## 범위 밖

| 항목 | 이유 |
| --- | --- |
| Spring 조회 창구 5개 | 후속 이터레이션. 설계 문서 §11 에 미확정 항목이 정리돼 있다 |
| AI federated 어댑터 | 캐시 3상태 · lint 분리 · capability · CAS. 창구가 있어야 의미가 있다 |
| 챗봇 의미 검색 | 자연어 0.25 → 0.80. 배포 GPU 유무가 전제 |
| 백엔드 코드 수정 | `ai/` 밖. S15P11B106-73 에 전달했다 |
| D1 백엔드 읽기 제한 | "그대로 두고 문제가 생기면" 으로 보류. Task 7 결과로 재판단 |

## 완료 판정

- [ ] `uv run pytest -m "not ocr"` 전체 통과
- [ ] 위키 100장 조건에서 한국어 검색 R@5 ≥ 0.55, 정확어 R@5 ≥ 0.90
- [ ] 위키 100장 + 원본문서 1건 조건에서 에이전트 `lint` 출력에 라이브 페이지 각주 오류 없음
- [ ] 상한 초과 요청이 400 으로 나감
- [ ] `experiments/INDEX.md` 에 한국어 측정과 `--effort` 대조 결과 기록
- [ ] `manifest.json` 에 `effort` 가 기록됨
